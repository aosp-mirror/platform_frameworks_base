/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.testutils;


import static android.util.ExceptionUtils.appendCause;
import static android.util.ExceptionUtils.propagate;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.ArrayMap;

import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/**
 * A test {@link Handler} that stores incoming {@link Message}s and {@link Runnable callbacks}
 * in a {@link PriorityQueue} based on time, to be manually processed later in a correct order
 * either all together with {@link #flush}, or only those due at the current time with
 * {@link #timeAdvance}.
 *
 * For the latter use case this also supports providing a custom clock (in a format of a
 * milliseconds-returning {@link LongSupplier}), that will be used for storing the messages'
 * timestamps to be posted at, and checked against during {@link #timeAdvance}.
 *
 * This allows to test code that uses {@link Handler}'s delayed invocation capabilities, such as
 * {@link Handler#sendMessageDelayed} or {@link Handler#postDelayed} without resorting to
 * synchronously {@link Thread#sleep}ing in your test.
 *
 * @see OffsettableClock for a useful custom clock implementation to use with this handler
 */
public class TestHandler extends Handler {
    private static final LongSupplier DEFAULT_CLOCK = SystemClock::uptimeMillis;

    private final PriorityQueue<MsgInfo> mMessages = new PriorityQueue<>();
    /**
     * Map of: {@code message id -> count of such messages currently pending }
     */
    // Boxing is ok here - both msg ids and their pending counts tend to be well below 128
    private final Map<Integer, Integer> mPendingMsgTypeCounts = new ArrayMap<>();
    private final LongSupplier mClock;
    private int  mMessageCount = 0;

    public TestHandler(Callback callback) {
        this(callback, DEFAULT_CLOCK);
    }

    public TestHandler(Callback callback, LongSupplier clock) {
        this(Looper.getMainLooper(), callback, clock);
    }

    public TestHandler(Looper looper, Callback callback, LongSupplier clock) {
        super(looper, callback);
        mClock = clock;
    }

    @Override
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        ++mMessageCount;
        mPendingMsgTypeCounts.put(msg.what,
                mPendingMsgTypeCounts.getOrDefault(msg.what, 0) + 1);

        // uptimeMillis is an absolute time obtained as SystemClock.uptimeMillis() + offsetMillis
        // if custom clock is given, recalculate the time with regards to it
        if (mClock != DEFAULT_CLOCK) {
            uptimeMillis = uptimeMillis - SystemClock.uptimeMillis() + mClock.getAsLong();
        }

        // post a dummy queue entry to keep track of message removal
        return super.sendMessageAtTime(msg, Long.MAX_VALUE)
                && mMessages.add(new MsgInfo(Message.obtain(msg), uptimeMillis, mMessageCount));
    }

    /** @see TestHandler */
    public void timeAdvance() {
        long now = mClock.getAsLong();
        while (!mMessages.isEmpty() && mMessages.peek().sendTime <= now) {
            dispatch(mMessages.poll());
        }
    }

    /**
     * Dispatch all messages in order
     *
     * @see TestHandler
     */
    public void flush() {
        MsgInfo msg;
        while ((msg = mMessages.poll()) != null) {
            dispatch(msg);
        }
    }

    /**
     * Deletes all messages in queue.
     */
    public void clear() {
        mMessages.clear();
    }

    public PriorityQueue<MsgInfo> getPendingMessages() {
        return new PriorityQueue<>(mMessages);
    }

    /**
     * Optionally-overridable to allow deciphering message types
     *
     * @see android.util.DebugUtils#valueToString - a handy utility to use when overriding this
     */
    protected String messageToString(Message message) {
        return message.toString();
    }

    private void dispatch(MsgInfo msg) {
        int msgId = msg.message.what;

        if (!hasMessages(msgId)) {
            // Handler.removeMessages(msgId) must have been called
            return;
        }

        try {
            Integer pendingMsgCount = mPendingMsgTypeCounts.getOrDefault(msgId, 0);
            if (pendingMsgCount <= 1) {
                removeMessages(msgId);
            }
            mPendingMsgTypeCounts.put(msgId, pendingMsgCount - 1);

            dispatchMessage(msg.message);
        } catch (Throwable t) {
            // Append stack trace of this message being posted as a cause for a helpful
            // test error message
            throw propagate(appendCause(t, msg.postPoint));
        } finally {
            msg.message.recycle();
        }
    }

    public class MsgInfo implements Comparable<MsgInfo> {
        public final Message message;
        public final long sendTime;
        public final int mMessageOrder;
        public final RuntimeException postPoint;

        private MsgInfo(Message message, long sendTime, int messageOrder) {
            this.message = message;
            this.sendTime = sendTime;
            this.postPoint = new RuntimeException("Message originated from here:");
            mMessageOrder = messageOrder;
        }

        @Override
        public int compareTo(MsgInfo o) {
            final int result = Long.compare(sendTime, o.sendTime);
            return result != 0 ? result : Integer.compare(mMessageOrder, o.mMessageOrder);
        }

        @Override
        public String toString() {
            return "MsgInfo{" +
                    "message =" + messageToString(message)
                    + ", sendTime =" + sendTime
                    + ", mMessageOrder =" + mMessageOrder
                    + '}';
        }
    }
}
