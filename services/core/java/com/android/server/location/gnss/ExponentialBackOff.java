/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

/**
 * A simple implementation of exponential backoff.
 */
class ExponentialBackOff {
    private static final int MULTIPLIER = 2;
    private final long mInitIntervalMillis;
    private final long mMaxIntervalMillis;
    private long mCurrentIntervalMillis;

    ExponentialBackOff(long initIntervalMillis, long maxIntervalMillis) {
        mInitIntervalMillis = initIntervalMillis;
        mMaxIntervalMillis = maxIntervalMillis;

        mCurrentIntervalMillis = mInitIntervalMillis / MULTIPLIER;
    }

    long nextBackoffMillis() {
        if (mCurrentIntervalMillis > mMaxIntervalMillis) {
            return mMaxIntervalMillis;
        }

        mCurrentIntervalMillis *= MULTIPLIER;
        return mCurrentIntervalMillis;
    }

    void reset() {
        mCurrentIntervalMillis = mInitIntervalMillis / MULTIPLIER;
    }
}

