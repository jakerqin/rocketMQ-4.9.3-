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
package org.apache.rocketmq.namesrv;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.common.Configuration;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.namesrv.kvconfig.KVConfigManager;
import org.apache.rocketmq.namesrv.processor.ClusterTestRequestProcessor;
import org.apache.rocketmq.namesrv.processor.DefaultRequestProcessor;
import org.apache.rocketmq.namesrv.routeinfo.BrokerHousekeepingService;
import org.apache.rocketmq.namesrv.routeinfo.RouteInfoManager;
import org.apache.rocketmq.remoting.RemotingServer;
import org.apache.rocketmq.remoting.common.TlsMode;
import org.apache.rocketmq.remoting.netty.NettyRemotingServer;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.netty.TlsSystemConfig;
import org.apache.rocketmq.srvutil.FileWatchService;


public class NamesrvController {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

    // namesrv的核心配置
    private final NamesrvConfig namesrvConfig;
    // namesrv进行通信netty的核心配置
    private final NettyServerConfig nettyServerConfig;

    // 支持单线程定时调度的线程池
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl(
        "NSScheduledThread"));
    // kv配置管理组件
    private final KVConfigManager kvConfigManager;
    // 路由管理组件
    private final RouteInfoManager routeInfoManager;

    // 远程网络通信服务器，跟broker、producer、consumer进行网络通信
    // 内部基于netty启动一个网络服务器，同时可以注册一些请求处理组件，可以通过它
    // 对client/broker发起一个rpc调用，同步、异步、oneway
    private RemotingServer remotingServer;

    // houseKeeping--酒店旅游行业的术语，类似于酒店房间管理，内务管理
    // 对broker进行管理的组件
    // nameserver跟broker之间的网络事件监听的组件
    private BrokerHousekeepingService brokerHousekeepingService;

    // 网络通信线程池
    private ExecutorService remotingExecutor;
    // rocketmq里面通用配置
    private Configuration configuration;
    // 观察文件变动的组件
    private FileWatchService fileWatchService;

    public NamesrvController(NamesrvConfig namesrvConfig, NettyServerConfig nettyServerConfig) {
        this.namesrvConfig = namesrvConfig;
        this.nettyServerConfig = nettyServerConfig;
        this.kvConfigManager = new KVConfigManager(this);
        this.routeInfoManager = new RouteInfoManager();
        this.brokerHousekeepingService = new BrokerHousekeepingService(this);
        // configuration会管理namesrv、netty相关的配置
        this.configuration = new Configuration(
            log,
            this.namesrvConfig, this.nettyServerConfig
        );
        // 设置存储路径
        this.configuration.setStorePathFromConfig(this.namesrvConfig, "configStorePath");
    }

    public boolean initialize() {
        // nameserver一旦启动，就会让KVConfigManager把磁盘里的数据加载到内存里来
        // 可以往里面写入kv配置数据，它会写入内存，同步写入到磁盘里去，只不过读写锁做一个控制
        // 读写锁的使用不太好, 刷磁盘这个动作，直接搞成读锁，草率了
        this.kvConfigManager.load();
        // 初始化和创建一个基于netty的远程通信服务器
        this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.brokerHousekeepingService);
        // 构建一个网络通信线程池，是用的worker线程数量
        this.remotingExecutor =
            Executors.newFixedThreadPool(nettyServerConfig.getServerWorkerThreads(), new ThreadFactoryImpl("RemotingExecutorThread_"));

        // 注册各种请求处理的组件
        this.registerProcessor();
        // 启动定时调度的任务。第一次延迟5s，每隔10s扫描不活跃的broker
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                NamesrvController.this.routeInfoManager.scanNotActiveBroker();
            }
        }, 5, 10, TimeUnit.SECONDS);

        // 延迟1分钟，每隔10分钟打印所有的kv的配置
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                NamesrvController.this.kvConfigManager.printAllPeriodically();
            }
        }, 1, 10, TimeUnit.MINUTES);

        // 如果说没有禁用ssl/tls加密通信模式
        if (TlsSystemConfig.tlsMode != TlsMode.DISABLED) {
            // Register a listener to reload SslContext
            try {
                // 搞一个文件监听的服务
                // 核心是要去监听ssl/tls加密通信对应的证书、秘钥、信任证书路径里面的变化
                // 如果说文件变化，会走进下面的监听器中
                fileWatchService = new FileWatchService(
                    new String[] {
                        TlsSystemConfig.tlsServerCertPath,
                        TlsSystemConfig.tlsServerKeyPath,
                        TlsSystemConfig.tlsServerTrustCertPath
                    },
                    new FileWatchService.Listener() {
                        boolean certChanged, keyChanged = false;
                        @Override
                        public void onChanged(String path) {
                            if (path.equals(TlsSystemConfig.tlsServerTrustCertPath)) {
                                log.info("The trust certificate changed, reload the ssl context");
                                // 重新加载
                                reloadServerSslContext();
                            }
                            if (path.equals(TlsSystemConfig.tlsServerCertPath)) {
                                certChanged = true;
                            }
                            if (path.equals(TlsSystemConfig.tlsServerKeyPath)) {
                                keyChanged = true;
                            }
                            if (certChanged && keyChanged) {
                                log.info("The certificate and private key changed, reload the ssl context");
                                certChanged = keyChanged = false;
                                reloadServerSslContext();
                            }
                        }
                        private void reloadServerSslContext() {
                            ((NettyRemotingServer) remotingServer).loadSslContext();
                        }
                    });
            } catch (Exception e) {
                log.warn("FileWatchService created error, can't load the certificate dynamically");
            }
        }

        return true;
    }

    private void registerProcessor() {
        // 默认是false
        if (namesrvConfig.isClusterTest()) {

            this.remotingServer.registerDefaultProcessor(new ClusterTestRequestProcessor(this, namesrvConfig.getProductEnvName()),
                this.remotingExecutor);
        } else {
            // 正常走这里。
            // 找到nettyRemotingServer注册一个默认的请求处理组件
            // 在处理别人给我发送过来的请求的时候，是用一个默认的请求处理组件就可以了。绑定的线程池是remotingExecutor
            this.remotingServer.registerDefaultProcessor(new DefaultRequestProcessor(this), this.remotingExecutor);
        }
    }

    public void start() throws Exception {
        // 网络服务器的启动
        this.remotingServer.start();

        if (this.fileWatchService != null) {
            this.fileWatchService.start();
        }
    }

    public void shutdown() {
        this.remotingServer.shutdown();
        this.remotingExecutor.shutdown();
        this.scheduledExecutorService.shutdown();

        if (this.fileWatchService != null) {
            this.fileWatchService.shutdown();
        }
    }

    public NamesrvConfig getNamesrvConfig() {
        return namesrvConfig;
    }

    public NettyServerConfig getNettyServerConfig() {
        return nettyServerConfig;
    }

    public KVConfigManager getKvConfigManager() {
        return kvConfigManager;
    }

    public RouteInfoManager getRouteInfoManager() {
        return routeInfoManager;
    }

    public RemotingServer getRemotingServer() {
        return remotingServer;
    }

    public void setRemotingServer(RemotingServer remotingServer) {
        this.remotingServer = remotingServer;
    }

    public Configuration getConfiguration() {
        return configuration;
    }
}
