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

package com.android.internal.telephony;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;

/** The implementation of exponential backoff with jitter applied. */
public class ExponentialBackoff {
    private int mRetryCounter;
    private long mStartDelayMs;
    private long mMaximumDelayMs;
    private long mCurrentDelayMs;
    private int mMultiplier;
    private Runnable mRunnable;
    private Handler mHandler;

    public ExponentialBackoff(
            long initialDelayMs,
            long maximumDelayMs,
            int multiplier,
            @NonNull Looper looper,
            @NonNull Runnable runnable) {
        this(initialDelayMs, maximumDelayMs, multiplier, new Handler(looper), runnable);
    }

    public ExponentialBackoff(
            long initialDelayMs,
            long maximumDelayMs,
            int multiplier,
            @NonNull Handler handler,
            @NonNull Runnable runnable) {
        mRetryCounter = 0;
        mStartDelayMs = initialDelayMs;
        mMaximumDelayMs = maximumDelayMs;
        mMultiplier = multiplier;
        mHandler = handler;
        mRunnable = runnable;
    }

    /** Starts the backoff, the runnable will be executed after {@link #mStartDelayMs}. */
    public void start() {
        mRetryCounter = 0;
        mCurrentDelayMs = mStartDelayMs;
        mHandler.removeCallbacks(mRunnable);
        mHandler.postDelayed(mRunnable, mCurrentDelayMs);
    }

    /** Stops the backoff, all pending messages will be removed from the message queue. */
    public void stop() {
        mRetryCounter = 0;
        mHandler.removeCallbacks(mRunnable);
    }

    /** Should call when the retry action has failed and we want to retry after a longer delay. */
    public void notifyFailed() {
        mRetryCounter++;
        long temp = Math.min(
                mMaximumDelayMs, (long) (mStartDelayMs * Math.pow(mMultiplier, mRetryCounter)));
        mCurrentDelayMs = (long) (((1 + Math.random()) / 2) * temp);
        mHandler.removeCallbacks(mRunnable);
        mHandler.postDelayed(mRunnable, mCurrentDelayMs);
    }

    /** Returns the delay for the most recently posted message. */
    public long getCurrentDelay() {
        return mCurrentDelayMs;
    }
}
