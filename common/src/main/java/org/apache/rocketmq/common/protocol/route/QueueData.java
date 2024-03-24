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

/**
 * $Id: QueueData.java 1835 2013-05-16 02:00:50Z vintagewang@apache.org $
 */
package org.apache.rocketmq.common.protocol.route;

public class QueueData implements Comparable<QueueData> {
    // 每个queue都属于一个数据分区，一定是在一个broker组里
    private String brokerName;
    // read queue是用于消费数据的路由的
    private int readQueueNums;
    // write queue是用于写入数据的路由的
    // 在这个broker里，我的topic有4个write queue，还有4个read queue
    // 随机的从4个write queue里获取到一个queue来写入数据，在消费的时候，从4个read queue随机的挑选一个，来读取数据
    // 如果你是4个write queue，2个read queue，数据会均匀的写入到4个write queue里去，读数据的时候仅仅会读里面的2个queue里的数据
    // 如果是4个write queue，8个read queue，数据会均匀的写入到4个write queue里去，但是消费的时候随机从8个read queue里读取，其中有个read queue是没有数据的

    // 区分读写队列作用是帮助我们对topic的queues进行扩容和缩容，8个write queue + 8个read queue
    // 4个write queue -> 写入数据仅仅会进入这4个write queue里去
    // 8个read queue，读取数据，有4个queue持续消费到最新的数据，另外4个queue不会写入新数据，但是会把他已有的数据全部消费完毕
    // 把8个read queue -> 4个read queue
    private int writeQueueNums;
    // 针对这些queue的权限（有读权限还是有写权限还是都有）
    private int perm;
    // 标识是否为系统层面的topic
    private int topicSysFlag;

    public int getReadQueueNums() {
        return readQueueNums;
    }

    public void setReadQueueNums(int readQueueNums) {
        this.readQueueNums = readQueueNums;
    }

    public int getWriteQueueNums() {
        return writeQueueNums;
    }

    public void setWriteQueueNums(int writeQueueNums) {
        this.writeQueueNums = writeQueueNums;
    }

    public int getPerm() {
        return perm;
    }

    public void setPerm(int perm) {
        this.perm = perm;
    }

    public int getTopicSysFlag() {
        return topicSysFlag;
    }

    public void setTopicSysFlag(int topicSysFlag) {
        this.topicSysFlag = topicSysFlag;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((brokerName == null) ? 0 : brokerName.hashCode());
        result = prime * result + perm;
        result = prime * result + readQueueNums;
        result = prime * result + writeQueueNums;
        result = prime * result + topicSysFlag;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        QueueData other = (QueueData) obj;
        if (brokerName == null) {
            if (other.brokerName != null)
                return false;
        } else if (!brokerName.equals(other.brokerName))
            return false;
        if (perm != other.perm)
            return false;
        if (readQueueNums != other.readQueueNums)
            return false;
        if (writeQueueNums != other.writeQueueNums)
            return false;
        if (topicSysFlag != other.topicSysFlag)
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "QueueData [brokerName=" + brokerName + ", readQueueNums=" + readQueueNums
            + ", writeQueueNums=" + writeQueueNums + ", perm=" + perm + ", topicSysFlag=" + topicSysFlag
            + "]";
    }

    @Override
    public int compareTo(QueueData o) {
        return this.brokerName.compareTo(o.getBrokerName());
    }

    public String getBrokerName() {
        return brokerName;
    }

    public void setBrokerName(String brokerName) {
        this.brokerName = brokerName;
    }
}
