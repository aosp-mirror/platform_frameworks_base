/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.frameworks.perftests.am.util;

import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * TimeReceiver will listen for any messages containing a timestamp by starting a BroadcastReceiver
 * which listens for Intents with the SendTime.ACTION_SEND_TIME action.
 */
public class TimeReceiver {
    private static final String TAG = TimeReceiver.class.getSimpleName();
    private static final long DEFAULT_RECEIVE_TIME_TIMEOUT_MILLIS = 10000L;

    private BlockingQueue<ReceivedMessage> mQueue = new LinkedBlockingQueue<>();

    private static class ReceivedMessage {
        private final String mReceivedMessageType;
        private final long mReceivedTimeNs;

        public ReceivedMessage(String receivedMessageType, long receivedTimeNs) {
            mReceivedMessageType = receivedMessageType;
            mReceivedTimeNs = receivedTimeNs;
        }
    }

    public void addTimeForTypeToQueue(String type, long timeNs) {
        if (type == null) {
            throw new IllegalArgumentException("type is null when adding time to queue");
        }
        if (timeNs < 0) {
            throw new RuntimeException(
                    "time is negative/non-existant (" + timeNs + ") when adding time to queue");
        }
        mQueue.add(new ReceivedMessage(type, timeNs));
    }

    public Bundle createReceiveTimeExtraBinder() {
        Bundle extras = new Bundle();
        extras.putBinder(Constants.EXTRA_RECEIVER_CALLBACK, new ITimeReceiverCallback.Stub() {
            @Override
            public void sendTime(String type, long timeNs) throws RemoteException {
                addTimeForTypeToQueue(type, timeNs);
            }
        });
        return extras;
    }

    public long getReceivedTimeNs(String type) {
        return getReceivedTimeNs(type, DEFAULT_RECEIVE_TIME_TIMEOUT_MILLIS);
    }

    /**
     * Returns a received timestamp with the given type tag. Will throw away any messages with a
     * different type tag. If it times out, a RuntimeException is thrown.
     */
    public long getReceivedTimeNs(String type, long timeoutMs) {
        ReceivedMessage message;
        long endTimeNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        do {
            long curTimeNs = System.nanoTime();
            if (curTimeNs > endTimeNs) {
                throw new RuntimeException("Timed out when listening for a time: " + type);
            }
            try {
                Log.i(TAG, "waiting for message " + type);
                message = mQueue.poll(endTimeNs - curTimeNs, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (message == null) {
                throw new RuntimeException("Timed out when listening for a time: " + type);
            }
            Log.i(TAG, "got message " + message.mReceivedMessageType);
            if (!type.equals(message.mReceivedMessageType)) {
                Log.i(TAG, String.format("Expected type \"%s\", got \"%s\" (%d), skipping", type,
                        message.mReceivedMessageType, message.mReceivedTimeNs));
            }
        } while (!type.equals(message.mReceivedMessageType));
        return message.mReceivedTimeNs;
    }

    /**
     * Clears the message queue.
     */
    public void clear() {
        mQueue.clear();
    }
}
