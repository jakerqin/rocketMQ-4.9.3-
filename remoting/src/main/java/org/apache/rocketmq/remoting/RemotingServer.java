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
package org.apache.rocketmq.remoting;

import io.netty.channel.Channel;
import java.util.concurrent.ExecutorService;
import org.apache.rocketmq.remoting.common.Pair;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.remoting.exception.RemotingTooMuchRequestException;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

public interface RemotingServer extends RemotingService {

    /**
     * 针对某个请求，注册一个netty请求处理组件，请求处理的时候可以基于线程池异步来处理
     * @param requestCode 请求编号
     * @param processor 请求处理组件
     * @param executor 请求处理线程池
     */
    void registerProcessor(final int requestCode, final NettyRequestProcessor processor,
        final ExecutorService executor);

    // 对所有请求注册一个默认的请求处理组件
    void registerDefaultProcessor(final NettyRequestProcessor processor, final ExecutorService executor);

    // 对本地机器监听的端口号
    int localListenPort();

    // 查询你的某个请求进行处理的请求处理组件和线程池组件
    Pair<NettyRequestProcessor, ExecutorService> getProcessorPair(final int requestCode);

    // 同步rpc调用，针对跟某个broker/client 之间的长连接（channel）。发起一次同步rpc调用，给一个rpc请求
    // 设置一下rpc调用的超时时间
    RemotingCommand invokeSync(final Channel channel, final RemotingCommand request,
        final long timeoutMillis) throws InterruptedException, RemotingSendRequestException,
        RemotingTimeoutException;

    // 同上，针对某个broker/client长连接发起一次异步化的调用
    // 请求发送出去以后就可以返回，但是你可以注册一个invoke callback，响应回调接口
    // 当响应回来了以后，就可以回调你的callback就可以了
    void invokeAsync(final Channel channel, final RemotingCommand request, final long timeoutMillis,
        final InvokeCallback invokeCallback) throws InterruptedException,
        RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException;

    // 发起一个oneway，仅仅发送请求，不关心他的响应
    void invokeOneway(final Channel channel, final RemotingCommand request, final long timeoutMillis)
        throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException,
        RemotingSendRequestException;

}
