/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.remoting.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.ChannelEventListener;
import org.apache.rocketmq.remoting.InvokeCallback;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.RemotingServer;
import org.apache.rocketmq.remoting.common.Pair;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.remoting.common.TlsMode;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.remoting.exception.RemotingTooMuchRequestException;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

/**
 * 代表了netty网络通信服务器
 */
public class NettyRemotingServer extends NettyRemotingAbstract implements RemotingServer {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(RemotingHelper.ROCKETMQ_REMOTING);

    // netty握手的handler
    private static final String HANDSHAKE_HANDLER_NAME = "handshakeHandler";
    // netty ssl加密通信handler
    private static final String TLS_HANDLER_NAME = "sslHandler";
    // 文件数据编码器
    private static final String FILE_REGION_ENCODER_NAME = "fileRegionEncoder";

    // netty网络服务器
    private final ServerBootstrap serverBootstrap;
    // 对于netty server来说，必须有两个线程池，第一个是监听连接，第二个是处理每个连接读写io请求的
    // 必须是有两个event loop group
    private final EventLoopGroup eventLoopGroupSelector;
    private final EventLoopGroup eventLoopGroupBoss;
    // nettyServer核心配置
    private final NettyServerConfig nettyServerConfig;

    // 公共使用的线程池
    private final ExecutorService publicExecutor;
    // 网络连接事件监听器
    private final ChannelEventListener channelEventListener;
    // 定时器组件
    private final Timer timer = new Timer("ServerHouseKeepingService", true);
    // netty里面的事件处理的线程池
    private DefaultEventExecutorGroup defaultEventExecutorGroup;

    // netty网络服务器监听的端口号
    private int port = 0;

    // sharable handlers
    private HandshakeHandler handshakeHandler;
    // 网络通信编码器
    private NettyEncoder encoder;
    // 网络连接管理组件
    private NettyConnectManageHandler connectionManageHandler;
    // netty 消息处理组件
    private NettyServerHandler serverHandler;

    public NettyRemotingServer(final NettyServerConfig nettyServerConfig) {
        this(nettyServerConfig, null);
    }

    public NettyRemotingServer(final NettyServerConfig nettyServerConfig,
        final ChannelEventListener channelEventListener) {
        // remoting server一个是接受rpc调用请求，可以通过它发起rpc请求--同步/异步/oneway
        // oneway semaphore是否是跟我们的netty server oneway调用请求是有关系的
        // 搞出来两个semaphore信号量。猜想：可能说是用来限制通过remoting server调用oneway和async rpc的调用数量
        super(nettyServerConfig.getServerOnewaySemaphoreValue(), nettyServerConfig.getServerAsyncSemaphoreValue());
        // netty server
        this.serverBootstrap = new ServerBootstrap();
        this.nettyServerConfig = nettyServerConfig;
        // 网络连接事件的监听器
        this.channelEventListener = channelEventListener;
        // netty服务器配置里，callback处理线程数量，如果默认是0，会重置成默认四个
        // 最终会作为公共线程池里面的线程数量
        int publicThreadNums = nettyServerConfig.getServerCallbackExecutorThreads();
        if (publicThreadNums <= 0) {
            publicThreadNums = 4;
        }
        // 构建公共线程池
        this.publicExecutor = Executors.newFixedThreadPool(publicThreadNums, new ThreadFactory() {
            private AtomicInteger threadIndex = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "NettyServerPublicExecutor_" + this.threadIndex.incrementAndGet());
            }
        });
        // 如果要开启epoll，一般Linux才会用到
        if (useEpoll()) {
            // boss event loop group。一般是负责连接监听的，一般来说他一个线程就够了
            // 一个线程用一个selector多路复用组件，监听serverSocketChannel看是否有人发起连接请求就可以了
            // 如果说有人发起连接就把物理的网络连接建立好，然后绑定一系列的handler pipeline
            this.eventLoopGroupBoss = new EpollEventLoopGroup(1, new ThreadFactory() {
                private AtomicInteger threadIndex = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("NettyEPOLLBoss_%d", this.threadIndex.incrementAndGet()));
                }
            });
            // event loop group selector，这个里面会设置对应的io线程数量，默认是3个
            this.eventLoopGroupSelector = new EpollEventLoopGroup(nettyServerConfig.getServerSelectorThreads(), new ThreadFactory() {
                private AtomicInteger threadIndex = new AtomicInteger(0);
                private int threadTotal = nettyServerConfig.getServerSelectorThreads();

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("NettyServerEPOLLSelector_%d_%d", threadTotal, this.threadIndex.incrementAndGet()));
                }
            });
        } else {
            this.eventLoopGroupBoss = new NioEventLoopGroup(1, new ThreadFactory() {
                private AtomicInteger threadIndex = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("NettyNIOBoss_%d", this.threadIndex.incrementAndGet()));
                }
            });

            this.eventLoopGroupSelector = new NioEventLoopGroup(nettyServerConfig.getServerSelectorThreads(), new ThreadFactory() {
                private AtomicInteger threadIndex = new AtomicInteger(0);
                private int threadTotal = nettyServerConfig.getServerSelectorThreads();

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("NettyServerNIOSelector_%d_%d", threadTotal, this.threadIndex.incrementAndGet()));
                }
            });
        }
        // 加载ssl加密通信的上下文
        loadSslContext();
    }

    public void loadSslContext() {
        // 先去获取默认的ssl/tls加密通信的模式
        TlsMode tlsMode = TlsSystemConfig.tlsMode;
        log.info("Server is running in TLS {} mode", tlsMode.getName());

        // 默认情况下是permissive，ssl/tls加密通信是可选的，可以搞加密通信也可以不搞
        // 只要你别手动禁用ssl/tls加密通信就可以了
        if (tlsMode != TlsMode.DISABLED) {
            try {
                sslContext = TlsHelper.buildSslContext(false);
                log.info("SSLContext created for server");
            } catch (CertificateException e) {
                log.error("Failed to create SSLContext for server", e);
            } catch (IOException e) {
                log.error("Failed to create SSLContext for server", e);
            }
        }
    }

    private boolean useEpoll() {
        return RemotingUtil.isLinuxPlatform()  // 必须是linux系统
            && nettyServerConfig.isUseEpollNativeSelector() // 默认是false
            && Epoll.isAvailable();
    }

    @Override
    public void start() {
        // 先创建一个默认事件处理线程池。netty事件处理线程池
        this.defaultEventExecutorGroup = new DefaultEventExecutorGroup(
            nettyServerConfig.getServerWorkerThreads(), // 线程数量8个
            new ThreadFactory() {

                private AtomicInteger threadIndex = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "NettyServerCodecThread_" + this.threadIndex.incrementAndGet());
                }
            });

        // 准备消息处理handler
        prepareSharableHandlers();

        // 真正的创建netty server
        ServerBootstrap childHandler =
            this.serverBootstrap.group(this.eventLoopGroupBoss, this.eventLoopGroupSelector)
                .channel(useEpoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    // tcp 三次握手的accept队列的长度
                    // 而accept队列的长度是指在服务器端用于存放已完成三次握手的连接请求的队列的长度。
                    //具体来说，在服务器端调用accept()函数时，内核会为每个已完成三次握手的连接请求创建一个对应的套接字，并将这些套接字放入accept队列中。当服务器调用accept()函数时，会从accept队列中取出一个已完成连接的套接字进行处理。
                    //accept队列的长度是通过操作系统的参数来设置的，通常可以在系统级别或者套接字级别进行设置。如果accept队列已满，那么新的连接请求将会被拒绝或者忽略。
                .option(ChannelOption.SO_BACKLOG, nettyServerConfig.getServerSocketBacklog())
                .option(ChannelOption.SO_REUSEADDR, true)  // server socket channel unbind端口监听了以后，还处于延迟unbind状态，重新启动允许我们可以立马监听这个端口
                .option(ChannelOption.SO_KEEPALIVE, false) // 是否自动发送探测包探测网络连接是否还存货
                .childOption(ChannelOption.TCP_NODELAY, true) // nodelay， 禁止打包传输，避免网络通信有延迟
                .localAddress(new InetSocketAddress(this.nettyServerConfig.getListenPort()))
                    // 网络连接建立好后，添加网络事件处理组件链条
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline()
                                // 首先是三次握手处理组件
                            .addLast(defaultEventExecutorGroup, HANDSHAKE_HANDLER_NAME, handshakeHandler)
                                // 其次是编码器、解码器、网络连接空闲处理器、网络连接管理器、消息处理器
                            .addLast(defaultEventExecutorGroup,
                                encoder,
                                new NettyDecoder(),
                                new IdleStateHandler(0, 0, nettyServerConfig.getServerChannelMaxIdleTimeSeconds()),
                                connectionManageHandler,
                                serverHandler
                            );
                    }
                });
        // 网络服务器收发消息缓存区的带下
        if (nettyServerConfig.getServerSocketSndBufSize() > 0) {
            log.info("server set SO_SNDBUF to {}", nettyServerConfig.getServerSocketSndBufSize());
            childHandler.childOption(ChannelOption.SO_SNDBUF, nettyServerConfig.getServerSocketSndBufSize());
        }
        if (nettyServerConfig.getServerSocketRcvBufSize() > 0) {
            log.info("server set SO_RCVBUF to {}", nettyServerConfig.getServerSocketRcvBufSize());
            childHandler.childOption(ChannelOption.SO_RCVBUF, nettyServerConfig.getServerSocketRcvBufSize());
        }
        // 数据输出缓存区的高低水位标记（通常用于控制缓存区的使用和管理。高低水位设置是指在缓存区中设定两个阈值，当缓存区中的数据量达到或者超过高水位时，会触发某些操作；当数据量低于低水位时，也会触发另外一些操作）
        if (nettyServerConfig.getWriteBufferLowWaterMark() > 0 && nettyServerConfig.getWriteBufferHighWaterMark() > 0) {
            log.info("server set netty WRITE_BUFFER_WATER_MARK to {},{}",
                    nettyServerConfig.getWriteBufferLowWaterMark(), nettyServerConfig.getWriteBufferHighWaterMark());
            childHandler.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                    nettyServerConfig.getWriteBufferLowWaterMark(), nettyServerConfig.getWriteBufferHighWaterMark()));
        }

        // 是否开启内存池分配机制
        if (nettyServerConfig.isServerPooledByteBufAllocatorEnable()) {
            childHandler.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        }

        try {
            // 对netty server让他监听指定的端口号，以及同步等待这个server启动和监听结果
            ChannelFuture sync = this.serverBootstrap.bind().sync();
            // 拿到结果，可以获取到当前对本地进行端口监听的真正的端口
            InetSocketAddress addr = (InetSocketAddress) sync.channel().localAddress();
            this.port = addr.getPort();
        } catch (InterruptedException e1) {
            throw new RuntimeException("this.serverBootstrap.bind().sync() InterruptedException", e1);
        }

        // 此时还会把netty网络异常事件处理线程做一个启动
        if (this.channelEventListener != null) {
            this.nettyEventExecutor.start();
        }

        // 启动一个定时调度任务。每隔1s去扫描发送出去的请求是否超时了
        this.timer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                try {
                    NettyRemotingServer.this.scanResponseTable();
                } catch (Throwable e) {
                    log.error("scanResponseTable exception", e);
                }
            }
        }, 1000 * 3, 1000);
    }

    @Override
    public void shutdown() {
        try {
            if (this.timer != null) {
                this.timer.cancel();
            }

            this.eventLoopGroupBoss.shutdownGracefully();

            this.eventLoopGroupSelector.shutdownGracefully();

            if (this.nettyEventExecutor != null) {
                this.nettyEventExecutor.shutdown();
            }

            if (this.defaultEventExecutorGroup != null) {
                this.defaultEventExecutorGroup.shutdownGracefully();
            }
        } catch (Exception e) {
            log.error("NettyRemotingServer shutdown exception, ", e);
        }

        if (this.publicExecutor != null) {
            try {
                this.publicExecutor.shutdown();
            } catch (Exception e) {
                log.error("NettyRemotingServer shutdown exception, ", e);
            }
        }
    }

    @Override
    public void registerRPCHook(RPCHook rpcHook) {
        if (rpcHook != null && !rpcHooks.contains(rpcHook)) {
            rpcHooks.add(rpcHook);
        }
    }

    @Override
    public void registerProcessor(int requestCode, NettyRequestProcessor processor, ExecutorService executor) {
        ExecutorService executorThis = executor;
        if (null == executor) {
            executorThis = this.publicExecutor;
        }

        Pair<NettyRequestProcessor, ExecutorService> pair = new Pair<NettyRequestProcessor, ExecutorService>(processor, executorThis);
        this.processorTable.put(requestCode, pair);
    }

    @Override
    public void registerDefaultProcessor(NettyRequestProcessor processor, ExecutorService executor) {
        this.defaultRequestProcessor = new Pair<NettyRequestProcessor, ExecutorService>(processor, executor);
    }

    @Override
    public int localListenPort() {
        return this.port;
    }

    @Override
    public Pair<NettyRequestProcessor, ExecutorService> getProcessorPair(int requestCode) {
        return processorTable.get(requestCode);
    }

    @Override
    public RemotingCommand invokeSync(final Channel channel, final RemotingCommand request, final long timeoutMillis)
        throws InterruptedException, RemotingSendRequestException, RemotingTimeoutException {
        return this.invokeSyncImpl(channel, request, timeoutMillis);
    }

    // 发送同步请求
    @Override
    public void invokeAsync(Channel channel, RemotingCommand request, long timeoutMillis, InvokeCallback invokeCallback)
        throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        this.invokeAsyncImpl(channel, request, timeoutMillis, invokeCallback);
    }

    @Override
    public void invokeOneway(Channel channel, RemotingCommand request, long timeoutMillis) throws InterruptedException,
        RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        this.invokeOnewayImpl(channel, request, timeoutMillis);
    }

    @Override
    public ChannelEventListener getChannelEventListener() {
        return channelEventListener;
    }


    @Override
    public ExecutorService getCallbackExecutor() {
        return this.publicExecutor;
    }

    private void prepareSharableHandlers() {
        // 握手用的handler
        handshakeHandler = new HandshakeHandler(TlsSystemConfig.tlsMode);
        encoder = new NettyEncoder();
        connectionManageHandler = new NettyConnectManageHandler();
        serverHandler = new NettyServerHandler();
    }

    /**
     * 握手处理组件，处理输入的数据
     * 当物理连接建立了以后，人家可以传输请求数据过来，如果是RocketMQ客户端发送的请求一定会经过NettyEncode的处理。请求读取出来字节数据，一定要先经过我的握手handler
     */
    @ChannelHandler.Sharable
    class HandshakeHandler extends SimpleChannelInboundHandler<ByteBuf> {
        // ssl/tls加密通信模式
        private final TlsMode tlsMode;
        // 握手消息开头的魔术，magic code
        private static final byte HANDSHAKE_MAGIC_CODE = 0x16;

        HandshakeHandler(TlsMode tlsMode) {
            this.tlsMode = tlsMode;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {

            // mark the current position so that we can peek the first byte to determine if the content is starting with
            // TLS handshake
            // 先标记
            msg.markReaderIndex();
            // 先读取一个byte，第一个字节一定是magic code
            byte b = msg.getByte(0);

            // 先根据消息第一个字节来判断一下，这个消息是不是一个握手的消息
            if (b == HANDSHAKE_MAGIC_CODE) {
                // 在判断一下当前的tls的加密通信模式
                switch (tlsMode) {
                    // 如果说是禁用加密通信，此时直接把连接关闭
                    case DISABLED:
                        ctx.close();
                        log.warn("Clients intend to establish an SSL connection while this server is running in SSL disabled mode");
                        break;
                    case PERMISSIVE:
                    case ENFORCING:
                        if (null != sslContext) {
                            // 如果说这个客户端开启了加密通信，netty服务端对加密通信是可选或者强制
                            // 此时才会针对这个客户端的连接加入两个加密通信有关的handler
                            // 会在handshakeHandler后面加入一个sslHandler，还会加入一个fileRegionEncoder
                            ctx.pipeline()
                                .addAfter(defaultEventExecutorGroup, HANDSHAKE_HANDLER_NAME, TLS_HANDLER_NAME, sslContext.newHandler(ctx.channel().alloc()))
                                .addAfter(defaultEventExecutorGroup, TLS_HANDLER_NAME, FILE_REGION_ENCODER_NAME, new FileRegionEncoder());
                            log.info("Handlers prepended to channel pipeline to establish SSL connection");
                        } else {
                            ctx.close();
                            log.error("Trying to establish an SSL connection but sslContext is null");
                        }
                        break;

                    default:
                        log.warn("Unknown TLS mode");
                        break;
                }
            } else if (tlsMode == TlsMode.ENFORCING) { // 如果连接建立了之后，你发送过来的第一条消息的第一个字节不是加密通信handshake，说明是一个普通的连接，但是服务端开启了强制启用加密通信，此时连接关闭
                ctx.close();
                log.warn("Clients intend to establish an insecure connection while this server is running in SSL enforcing mode");
            }

            // reset the reader index so that handshake negotiation may proceed as normal.
            msg.resetReaderIndex();

            try {
                // Remove this handler
                // 从handler链条里把当前的这个handler移除掉
                // 一个连接建立了之后，这个handshake handler仅仅用一次就可以了，他就是针对第一条消息的第一个字节做一个判断，如果是加密通信，就加入两个handler就可以了
                ctx.pipeline().remove(this);
            } catch (NoSuchElementException e) {
                log.error("Error while removing HandshakeHandler", e);
            }

            // Hand over this message to the next .
            // 把消息往后激活read事件就可以了
            ctx.fireChannelRead(msg.retain());
        }
    }

    @ChannelHandler.Sharable
    class NettyServerHandler extends SimpleChannelInboundHandler<RemotingCommand> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RemotingCommand msg) throws Exception {
            processMessageReceived(ctx, msg);
        }
    }

    @ChannelHandler.Sharable
    class NettyConnectManageHandler extends ChannelDuplexHandler {
        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY SERVER PIPELINE: channelRegistered {}", remoteAddress);
            super.channelRegistered(ctx);
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY SERVER PIPELINE: channelUnregistered, the channel[{}]", remoteAddress);
            super.channelUnregistered(ctx);
        }

        /**
         * 连接建立以及激活后
         * @param ctx
         * @throws Exception
         */
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY SERVER PIPELINE: channelActive, the channel[{}]", remoteAddress);
            super.channelActive(ctx);

            if (NettyRemotingServer.this.channelEventListener != null) {
                // 发送一个连接建立事件
                NettyRemotingServer.this.putNettyEvent(new NettyEvent(NettyEventType.CONNECT, remoteAddress, ctx.channel()));
            }
        }

        /**
         * 连接断开
         * @param ctx
         * @throws Exception
         */
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY SERVER PIPELINE: channelInactive, the channel[{}]", remoteAddress);
            super.channelInactive(ctx);

            if (NettyRemotingServer.this.channelEventListener != null) {
                NettyRemotingServer.this.putNettyEvent(new NettyEvent(NettyEventType.CLOSE, remoteAddress, ctx.channel()));
            }
        }

        /**
         * 连接空闲
         * @param ctx
         * @param evt
         * @throws Exception
         */
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            // 接收到连接空闲事件
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent event = (IdleStateEvent) evt;
                if (event.state().equals(IdleState.ALL_IDLE)) {
                    // 那个链接空闲了
                    final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
                    log.warn("NETTY SERVER PIPELINE: IDLE exception [{}]", remoteAddress);
                    // 关闭链接
                    RemotingUtil.closeChannel(ctx.channel());
                    if (NettyRemotingServer.this.channelEventListener != null) {
                        // 发送一个链接空闲事件，BrokerHousekeepingService.onChannelIdle
                        // 路由管理器中对连接进行处理
                        NettyRemotingServer.this
                            .putNettyEvent(new NettyEvent(NettyEventType.IDLE, remoteAddress, ctx.channel()));
                    }
                }
            }

            ctx.fireUserEventTriggered(evt);
        }

        /**
         * 连接异常
         * @param ctx
         * @param cause
         * @throws Exception
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.warn("NETTY SERVER PIPELINE: exceptionCaught {}", remoteAddress);
            log.warn("NETTY SERVER PIPELINE: exceptionCaught exception.", cause);

            if (NettyRemotingServer.this.channelEventListener != null) {
                NettyRemotingServer.this.putNettyEvent(new NettyEvent(NettyEventType.EXCEPTION, remoteAddress, ctx.channel()));
            }

            RemotingUtil.closeChannel(ctx.channel());
        }
    }
}
