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

package com.android.server.location.contexthub;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

/**
 * A class that manages a timer used to keep track of how much time is left before a
 * {@link ContextHubClientBroker} has its authorization state changed with a nanoapp from
 * DENIED_GRACE_PERIOD to DENIED. Much of this implementation is copied from
 * {@link android.os.CountDownTimer} while adding the ability to specify the provided looper.
 *
 * @hide
 */
public class AuthStateDenialTimer {
    private static final long TIMEOUT_MS = SECONDS.toMillis(60);

    private final ContextHubClientBroker mClient;
    private final long mNanoAppId;
    private final Handler mHandler;

    /**
     * Indicates when the timer should stop in the future.
     */
    private long mStopTimeInFuture;

    /**
     * boolean representing if the timer was cancelled
     */
    private boolean mCancelled = false;

    public AuthStateDenialTimer(ContextHubClientBroker client, long nanoAppId, Looper looper) {
        mClient = client;
        mNanoAppId = nanoAppId;
        mHandler = new CountDownHandler(looper);
    }

    /**
     * Cancel the countdown.
     */
    public synchronized void cancel() {
        mCancelled = true;
        mHandler.removeMessages(MSG);
    }

    /**
     * Start the countdown.
     */
    public synchronized void start() {
        mCancelled = false;
        mStopTimeInFuture = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        mHandler.sendMessage(mHandler.obtainMessage(MSG));
    }

    /**
     * Called when the timer has expired.
     */
    public void onFinish() {
        mClient.handleAuthStateTimerExpiry(mNanoAppId);
    }

    // Message type used to trigger the timer.
    private static final int MSG = 1;

    private class CountDownHandler extends Handler {

        CountDownHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (AuthStateDenialTimer.this) {
                if (mCancelled) {
                    return;
                }
                final long millisLeft = mStopTimeInFuture - SystemClock.elapsedRealtime();
                if (millisLeft <= 0) {
                    onFinish();
                } else {
                    sendMessageDelayed(obtainMessage(MSG), millisLeft);
                }
            }
        }
    };
}
