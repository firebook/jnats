/*******************************************************************************
 * Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 * materials are made available under the terms of the MIT License (MIT) which accompanies this
 * distribution, and is available at http://opensource.org/licenses/MIT
 *******************************************************************************/

package io.nats.client;

import static io.nats.client.Constants.ERR_BAD_SUBSCRIPTION;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

abstract class SubscriptionImpl implements Subscription {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * Default maximum pending/undelivered messages on a subscription.
     * 
     * <p>This property is defined as String {@value #DEFAULT_MAX_PENDING_MSGS}
     */
    static final int DEFAULT_MAX_PENDING_MSGS = 65536;
    /**
     * Default maximum pending/undelivered payload bytes on a subscription.
     * 
     * <p>This property is defined as String {@value #DEFAULT_MAX_PENDING_BYTES}
     */
    static final int DEFAULT_MAX_PENDING_BYTES = 65536 * 1024;

    final Lock mu = new ReentrantLock();

    long sid; // int64 in Go

    // Subject that represents this subscription. This can be different
    // than the received subject inside a Msg if this is a wildcard.
    String subject = null;

    // Optional queue group name. If present, all subscriptions with the
    // same name will form a distributed queue, and each message will
    // only be processed by one member of the group.
    String queue;

    // Number of messages delivered on this subscription
    long msgs;
    long delivered; // uint64
    long bytes; // uint64
    // int pendingMax; // uint64 in Go, int here due to underlying data structure
    long max; // AutoUnsubscribe max

    protected boolean closed;
    protected boolean connClosed;

    // slow consumer flag
    boolean sc;

    ConnectionImpl conn = null;
    BlockingQueue<Message> mch;
    Condition pCond;

    // Pending stats, async subscriptions, high-speed etc.
    int pMsgs;
    int pBytes;
    int pMsgsMax; // highest number of pending msgs
    int pBytesMax; // highest number of pending bytes
    int pMsgsLimit = 65536;
    int pBytesLimit = pMsgsLimit * 1024;
    int dropped;

    SubscriptionImpl(ConnectionImpl conn, String subject, String queue) {
        this(conn, subject, queue, DEFAULT_MAX_PENDING_MSGS, DEFAULT_MAX_PENDING_BYTES);
    }

    SubscriptionImpl(ConnectionImpl conn, String subject, String queue, int pendingMsgsLimit,
            int pendingBytesLimit) {
        this.conn = conn;
        this.subject = subject;
        this.queue = queue;
        setPendingMsgsLimit(pendingMsgsLimit);
        setPendingBytesLimit(pendingBytesLimit);
        this.mch = new LinkedBlockingQueue<Message>();
        pCond = mu.newCondition();
    }

    void closeChannel() {
        mu.lock();
        try {
            if (mch != null) {
                mch.clear();
                mch = null;
            }
        } finally {
            mu.unlock();
        }
    }

    @Override
    public String getSubject() {
        return subject;
    }

    public String getQueue() {
        // if (queue==null)
        // return "";
        return queue;
    }

    public BlockingQueue<Message> getChannel() {
        return this.mch;
    }

    public void setChannel(BlockingQueue<Message> ch) {
        this.mch = ch;
    }

    boolean isClosed() {
        // Internal only and assumes lock is held
        return closed;
    }

    public boolean isValid() {
        mu.lock();
        boolean valid = (conn != null);
        mu.unlock();
        return valid;
    }

    @Override
    public void unsubscribe() throws IOException {
        unsubscribe(false);
    }

    public void unsubscribe(boolean ignoreInvalid) throws IOException {
        ConnectionImpl conn;
        mu.lock();
        conn = this.conn;
        mu.unlock();
        if (conn == null) {
            if (!ignoreInvalid) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
        } else {
            conn.unsubscribe(this, 0);
        }
    }


    @Override
    public void autoUnsubscribe(int max) throws IOException {
        ConnectionImpl conn;
        mu.lock();
        conn = this.conn;
        mu.unlock();
        if (conn == null) {
            throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
        }
        conn.unsubscribe(this, max);
    }

    @Override
    public void close() {
        try {
            unsubscribe(true);
        } catch (Exception e) {
            // Just log and ignore. This is for AutoCloseable.
            logger.debug("Exception while calling unsubscribe from AutoCloseable.close()", e);
        }
    }

    protected long getSid() {

        return sid;
    }

    protected void setSid(long id) {
        this.sid = id;
    }


    @Override
    public int getDropped() {
        int rv = 0;
        mu.lock();
        try {
            if (conn == null) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
            rv = dropped;
        } finally {
            mu.unlock();
        }
        return rv;
    }

    @Override
    public int getPendingMsgsMax() {
        int rv = 0;
        mu.lock();
        try {
            if (conn == null) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
            rv = this.pMsgsMax;
        } finally {
            mu.unlock();
        }

        return rv;
    }

    @Override
    public long getPendingBytesMax() {
        int rv = 0;
        mu.lock();
        try {
            if (conn == null) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
            rv = this.pBytesMax;
        } finally {
            mu.unlock();
        }

        return rv;
    }

    @Override
    public void setPendingLimits(int msgs, int bytes) {
        setPendingMsgsLimit(msgs);
        setPendingBytesLimit(bytes);
    }

    void setPendingMsgsLimit(int pendingMsgsLimit) {
        mu.lock();
        try {
            pMsgsLimit = pendingMsgsLimit;
            if (pendingMsgsLimit == 0) {
                throw new IllegalArgumentException("nats: pending message limit cannot be zero");
            }
        } finally {
            mu.unlock();
        }
    }

    void setPendingBytesLimit(int pendingBytesLimit) {
        mu.lock();
        try {
            pBytesLimit = pendingBytesLimit;
            if (pendingBytesLimit == 0) {
                throw new IllegalArgumentException("nats: pending message limit cannot be zero");
            }
        } finally {
            mu.unlock();
        }
    }

    void setPendingMsgsMax(int max) {
        mu.lock();
        try {
            if (conn == null) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
            pMsgsMax = (max <= 0) ? 0 : max;
        } finally {
            mu.unlock();
        }
    }

    void setPendingBytesMax(int max) {
        mu.lock();
        try {
            if (conn == null) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
            pBytesMax = (max <= 0) ? 0 : max;
        } finally {
            mu.unlock();
        }
    }

    @Override
    public void clearMaxPending() {
        setPendingMsgsMax(0);
        setPendingBytesMax(0);
    }

    protected Connection getConnection() {
        return (Connection) this.conn;
    }

    protected void setConnection(ConnectionImpl conn) {
        this.conn = conn;
    }

    @Override
    public long getDelivered() {
        long rv = 0L;
        mu.lock();
        try {
            if (conn == null) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
            rv = delivered;
        } finally {
            mu.unlock();
        }
        return rv;
    }

    @Override
    public int getPendingBytes() {
        int rv = 0;
        mu.lock();
        try {
            if (conn == null) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
            rv = pBytes;
        } finally {
            mu.unlock();
        }
        return rv;
    }

    @Override
    public int getPendingBytesLimit() {
        int rv = 0;
        mu.lock();
        rv = pBytesLimit;
        mu.unlock();
        return rv;
    }

    @Override
    public int getPendingMsgs() {
        int rv = 0;
        mu.lock();
        try {
            if (conn == null) {
                throw new IllegalStateException(ERR_BAD_SUBSCRIPTION);
            }
            rv = pMsgs;
        } finally {
            mu.unlock();
        }
        return rv;
    }

    @Override
    public int getPendingMsgsLimit() {
        int rv = 0;
        mu.lock();
        rv = pMsgsLimit;
        mu.unlock();
        return rv;
    }

    @Override
    public int getQueuedMessageCount() {
        return getPendingMsgs();
    }

    public String toString() {
        String str = String.format(
                "{subject=%s, queue=%s, sid=%d, max=%d, delivered=%d, pendingMsgsLimit=%d, "
                        + "pendingBytesLimit=%d, maxPendingMsgs=%d, maxPendingBytes=%d, valid=%b}",
                getSubject(), getQueue() == null ? "null" : getQueue(), getSid(), getMax(),
                delivered, getPendingMsgsLimit(), getPendingBytesLimit(), getPendingMsgsMax(),
                getPendingBytesMax(), isValid());
        return str;
    }

    protected void setSlowConsumer(boolean sc) {
        this.sc = sc;
    }

    protected boolean isSlowConsumer() {
        return this.sc;
    }

    protected void setMax(long max) {
        this.max = max;
    }

    protected long getMax() {
        return max;
    }

    void lock() {
        mu.lock();
    }

    void unlock() {
        mu.unlock();
    }

    void printStats() {
        System.err.println("delivered: " + delivered);
        System.err.println("dropped: " + dropped);
        System.err.println("pMsgs: " + pMsgs);
        System.err.println("pMsgsLimit: " + pMsgsLimit);
        System.err.println("pMsgsMax: " + pMsgsMax);
        System.err.println("pBytes: " + pBytes);
        System.err.println("pBytesLimit: " + pBytesLimit);
        System.err.println("pBytesMax: " + pBytesMax);
        System.err.println();
    }
}
