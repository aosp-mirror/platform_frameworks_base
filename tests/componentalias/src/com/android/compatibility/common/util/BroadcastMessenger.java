/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.compatibility.common.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Provides a one-way communication mechanism using a Parcelable as a payload, via broadcasts.
 *
 * Use {@link #send(Context, String, Parcelable)} to send a message.
 * USe {@link Receiver} to receive a message.
 *
 * Pick a unique "suffix" for your test, and use it with both the sender and receiver, in order
 * to avoid "cross-talks" between different tests. (if they ever run at the same time.)
 *
 * TODO: Move it to compatibility-device-util-axt.
 */
public final class BroadcastMessenger {
    private static final String TAG = "BroadcastMessenger";

    private static final String ACTION_MESSAGE =
            "com.android.compatibility.common.util.BroadcastMessenger.ACTION_MESSAGE_";
    private static final String ACTION_PING =
            "com.android.compatibility.common.util.BroadcastMessenger.ACTION_PING_";
    private static final String EXTRA_MESSAGE =
            "com.android.compatibility.common.util.BroadcastMessenger.EXTRA_MESSAGE";

    /**
     * We need to drop messages that were sent before the receiver was created. We keep
     * track of the message send time in this extra.
     */
    private static final String EXTRA_SENT_TIME =
            "com.android.compatibility.common.util.BroadcastMessenger.EXTRA_SENT_TIME";

    private static long getCurrentTime() {
        return SystemClock.uptimeMillis();
    }

    private static void sendBroadcast(@NonNull Intent i, @NonNull Context context,
            @NonNull String broadcastSuffix, @Nullable String receiverPackage) {
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.setPackage(receiverPackage);
        i.putExtra(EXTRA_SENT_TIME, getCurrentTime());

        context.sendBroadcast(i);
    }

    /** Send a message to the {@link Receiver} expecting a given "suffix". */
    public static <T extends Parcelable> void send(@NonNull Context context,
            @NonNull String broadcastSuffix, @NonNull T message) {
        final Intent i = new Intent(ACTION_MESSAGE + Objects.requireNonNull(broadcastSuffix));
        i.putExtra(EXTRA_MESSAGE, Objects.requireNonNull(message));

        Log.i(TAG, "Sending: " + message);
        sendBroadcast(i, context, broadcastSuffix, /*receiverPackage=*/ null);
    }

    private static void sendPing(@NonNull Context context,@NonNull String broadcastSuffix,
            @NonNull String receiverPackage) {
        final Intent i = new Intent(ACTION_PING + Objects.requireNonNull(broadcastSuffix));

        Log.i(TAG, "Sending a ping");
        sendBroadcast(i, context, broadcastSuffix, receiverPackage);
    }

    /**
     * Receive messages sent with {@link #send}. Note it'll ignore all the messages that were
     * sent before instantiated.
     */
    public static final class Receiver<T extends Parcelable> implements AutoCloseable {
        private final Context mContext;
        private final String mBroadcastSuffix;
        private final HandlerThread mReceiverThread = new HandlerThread(TAG);

        // @GuardedBy("mMessages")
        private final ArrayList<T> mMessages = new ArrayList<>();
        private final long mCreatedTime = getCurrentTime();
        private boolean mRegistered;

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Log.d(TAG, "Received intent: " + intent);
                if (intent.getAction().equals(ACTION_MESSAGE + mBroadcastSuffix)
                        || intent.getAction().equals(ACTION_PING + mBroadcastSuffix)) {
                    // OK
                } else {
                    throw new RuntimeException("Unknown broadcast received: " + intent);
                }
                if (intent.getLongExtra(EXTRA_SENT_TIME, 0) < mCreatedTime) {
                    Log.i(TAG, "Dropping stale broadcast: " + intent);
                    return;
                }

                // Note for a PING, the message will be null.
                final T message = intent.getParcelableExtra(EXTRA_MESSAGE);
                if (message != null) {
                    Log.i(TAG, "Received: " + message);
                }

                synchronized (mMessages) {
                    mMessages.add(message);
                    mMessages.notifyAll();
                }
            }
        };

        /**
         * Constructor.
         */
        public Receiver(@NonNull Context context, @NonNull String broadcastSuffix) {
            mContext = context;
            mBroadcastSuffix = Objects.requireNonNull(broadcastSuffix);

            mReceiverThread.start();

            final IntentFilter fi = new IntentFilter(ACTION_MESSAGE + mBroadcastSuffix);
            fi.addAction(ACTION_PING + mBroadcastSuffix);

            context.registerReceiver(mReceiver, fi, /* permission=*/ null,
                    mReceiverThread.getThreadHandler(), Context.RECEIVER_EXPORTED);
            mRegistered = true;
        }

        @Override
        public void close() {
            if (mRegistered) {
                mContext.unregisterReceiver(mReceiver);
                mReceiverThread.quit();
                mRegistered = false;
            }
        }

        /**
         * Receive the next message with a 60 second timeout.
         */
        @NonNull
        public T waitForNextMessage() {
            return waitForNextMessage(60_000);
        }

        /**
         * Receive the next message.
         */
        @NonNull
        public T waitForNextMessage(long timeoutMillis) {
            synchronized (mMessages) {
                final long timeout = System.currentTimeMillis() + timeoutMillis;
                while (mMessages.size() == 0) {
                    final long wait = timeout - System.currentTimeMillis();
                    if (wait <= 0) {
                        throw new RuntimeException("Timeout waiting for the next message");
                    }
                    try {
                        mMessages.wait(wait);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                return mMessages.remove(0);
            }
        }

        /**
         * Ensure that no further messages have been received.
         *
         * Call it before {@link #close()}.
         */
        public void ensureNoMoreMessages() {
            // Send a ping to myself.
            sendPing(mContext, mBroadcastSuffix, mContext.getPackageName());

            final T m = waitForNextMessage();
            if (m == null) {
                return; // Okay. Ping will deliver a null message.
            }
            throw new RuntimeException("No more messages expected, but received: " + m);
        }
    }
}
