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

import io.netty.channel.Channel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.remoting.InvokeCallback;
import org.apache.rocketmq.remoting.common.SemaphoreReleaseOnlyOnce;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

public class ResponseFuture {
    // 请求id
    private final int opaque;
    // 请求发送出去的网络连接
    private final Channel processChannel;
    // 请求等待响应的超时时间
    private final long timeoutMillis;
    // async异步调用响应回来以后给我一个invoke回调接口
    private final InvokeCallback invokeCallback;
    private final long beginTimestamp = System.currentTimeMillis();
    // 用于进行并发控制的countDownLatch
    private final CountDownLatch countDownLatch = new CountDownLatch(1);

    // 支持仅仅释放一次的semaphore组件的封装
    private final SemaphoreReleaseOnlyOnce once;

    // 支持仅仅执行一次callback调用的CAS组件
    private final AtomicBoolean executeCallbackOnlyOnce = new AtomicBoolean(false);
    // 发起的远程调用的请求命令对应的响应
    private volatile RemotingCommand responseCommand;
    // 请求是否发送成功
    private volatile boolean sendRequestOK = true;
    // 这次PRC调用，如果遇到了异常
    private volatile Throwable cause;

    public ResponseFuture(
            Channel channel, // 通过那个网络连接的请求
            int opaque,   // 请求的ID是什么
            long timeoutMillis,   // 请求响应超时时间
            InvokeCallback invokeCallback, // 异步rpc请求，请求响应时来回调谁
            // 仅仅就支持释放一次的semaphore的组件
        SemaphoreReleaseOnlyOnce once) {
        this.opaque = opaque;
        this.processChannel = channel;
        this.timeoutMillis = timeoutMillis;
        this.invokeCallback = invokeCallback;
        this.once = once;
    }

    // 如果说响应回来了以后，执行invoke回调
    public void executeInvokeCallback() {
        if (invokeCallback != null) {
            // 通过cas来确定只能回调一次
            if (this.executeCallbackOnlyOnce.compareAndSet(false, true)) {
                invokeCallback.operationComplete(this);
            }
        }
    }

    // 可以基于仅仅支持释放一次的semaphore做一次释放
    public void release() {
        if (this.once != null) {
            this.once.release();
        }
    }

    // 当前时间 - 请求发起的开始时间，请求发起之后到现在截止的时间差，是否超过了请求超时时间
    // 判断当前请求是否超时了
    public boolean isTimeout() {
        long diff = System.currentTimeMillis() - this.beginTimestamp;
        return diff > this.timeoutMillis;
    }

    // 等待请求响应回来，等待超时时间，countDownLatch来等待，如果说响应回来了
    // 要对countDownLatch进行countDown()操作
    // await等待操作就会返回
    public RemotingCommand waitResponse(final long timeoutMillis) throws InterruptedException {
        // 在这里通过countDownLatch来等待，谁去对那1个数字去进行一次countDown，只有有人countDown了以后
        // 从1数字变为0数字，等待指定超时时间
        this.countDownLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        return this.responseCommand;
    }

    // 如果说请求的响应回来了，响应command设置到这里。countDownLatch减1
    public void putResponse(final RemotingCommand responseCommand) {
        this.responseCommand = responseCommand;
        this.countDownLatch.countDown();
    }

    public long getBeginTimestamp() {
        return beginTimestamp;
    }

    public boolean isSendRequestOK() {
        return sendRequestOK;
    }

    public void setSendRequestOK(boolean sendRequestOK) {
        this.sendRequestOK = sendRequestOK;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public InvokeCallback getInvokeCallback() {
        return invokeCallback;
    }

    public Throwable getCause() {
        return cause;
    }

    public void setCause(Throwable cause) {
        this.cause = cause;
    }

    public RemotingCommand getResponseCommand() {
        return responseCommand;
    }

    public void setResponseCommand(RemotingCommand responseCommand) {
        this.responseCommand = responseCommand;
    }

    public int getOpaque() {
        return opaque;
    }

    public Channel getProcessChannel() {
        return processChannel;
    }

    @Override
    public String toString() {
        return "ResponseFuture [responseCommand=" + responseCommand
            + ", sendRequestOK=" + sendRequestOK
            + ", cause=" + cause
            + ", opaque=" + opaque
            + ", processChannel=" + processChannel
            + ", timeoutMillis=" + timeoutMillis
            + ", invokeCallback=" + invokeCallback
            + ", beginTimestamp=" + beginTimestamp
            + ", countDownLatch=" + countDownLatch + "]";
    }
}
