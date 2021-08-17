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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;

/**
 * Provides a one-way communication mechanism using a Parcelable as a payload, via broadcasts.
 *
 * TODO: Move it to compatibility-device-util-axt.
 */
public final class BroadcastMessenger {
    private static final String TAG = "BroadcastMessenger";

    private static final String ACTION_MESSAGE =
            "com.android.compatibility.common.util.BroadcastMessenger.ACTION_MESSAGE";
    private static final String EXTRA_MESSAGE =
            "com.android.compatibility.common.util.BroadcastMessenger.EXTRA_MESSAGE";

    /** Send a message to the {@link Receiver} in a given package. */
    public static <T extends Parcelable> void send(@NonNull Context context,
            @NonNull String receiverPackage,
            @NonNull T message) {
        final Intent i = new Intent(ACTION_MESSAGE);
        i.putExtra(EXTRA_MESSAGE, message);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.setPackage(receiverPackage);

        Log.d(TAG, "Sending: " + message);
        context.sendBroadcast(i);
    }

    /**
     * Receive messages from the test app.
     */
    public static final class Receiver<T extends Parcelable> implements AutoCloseable {
        private final Context mContext;
        @GuardedBy("mMessages")
        private final ArrayList<T> mMessages = new ArrayList<>();
        private boolean mRegistered;

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_MESSAGE.equals(intent.getAction())) {
                    throw new RuntimeException("Unknown message received: " + intent);
                }
                final T message = intent.getParcelableExtra(EXTRA_MESSAGE);
                Log.d(TAG, "Received: " + message);
                synchronized (mMessages) {
                    mMessages.add(message);
                    mMessages.notifyAll();
                }
            }
        };

        /**
         * Constructor.
         */
        public Receiver(@NonNull Context context) {
            mContext = context;

            final IntentFilter fi = new IntentFilter(ACTION_MESSAGE);
            context.registerReceiver(mReceiver, fi);
            mRegistered = true;
        }

        @Override
        public void close() {
            if (mRegistered) {
                mContext.unregisterReceiver(mReceiver);
                mRegistered = false;
            }
        }

        /**
         * Receive the next message with a 60 second timeout.
         */
        @NonNull
        public T waitForNextMessage() throws Exception {
            return waitForNextMessage(60_000);
        }

        /**
         * Receive the next message.
         */
        @NonNull
        public T waitForNextMessage(long timeoutMillis) throws Exception {
            synchronized (mMessages) {
                final long timeout = System.currentTimeMillis() + timeoutMillis;
                while (mMessages.size() == 0) {
                    final long wait = timeout - System.currentTimeMillis();
                    if (wait <= 0) {
                        throw new RuntimeException("Timeout waiting for the next message");
                    }
                    mMessages.wait(wait);
                }
                return mMessages.remove(0);
            }
        }
    }
}
