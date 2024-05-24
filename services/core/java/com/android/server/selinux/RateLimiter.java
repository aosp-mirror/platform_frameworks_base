/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.selinux;

import android.os.SystemClock;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Rate limiter to ensure Atoms are pushed only within the allowed QPS window. This class is not
 * thread-safe.
 *
 * <p>The rate limiter is smoothed, meaning that a rate limiter allowing X permits per second (or X
 * QPS) will grant permits at a ratio of one every 1/X seconds.
 */
public final class RateLimiter {

    private Instant mNextPermit = Instant.EPOCH;

    private final Clock mClock;
    private final Duration mWindow;

    @VisibleForTesting
    RateLimiter(Clock clock, Duration window) {
        mClock = clock;
        // Truncating because the system clock does not support units smaller than milliseconds.
        mWindow = window;
    }

    /**
     * Create a rate limiter generating one permit every {@code window} of time, using the {@link
     * Clock.SYSTEM_CLOCK}.
     */
    public RateLimiter(Duration window) {
        this(Clock.SYSTEM_CLOCK, window);
    }

    /**
     * Acquire a permit if allowed by the rate limiter. If not, wait until a permit becomes
     * available.
     */
    public void acquire() {
        Instant now = Instant.ofEpochMilli(mClock.currentTimeMillis());

        if (mNextPermit.isAfter(now)) { // Sleep until we can acquire.
            SystemClock.sleep(ChronoUnit.MILLIS.between(now, mNextPermit));
            mNextPermit = mNextPermit.plus(mWindow);
        } else {
            mNextPermit = now.plus(mWindow);
        }
    }

    /**
     * Try to acquire a permit if allowed by the rate limiter. Non-blocking.
     *
     * @return true if a permit was acquired. Otherwise, return false.
     */
    public boolean tryAcquire() {
        final Instant now = Instant.ofEpochMilli(mClock.currentTimeMillis());

        if (mNextPermit.isAfter(now)) {
            return false;
        }
        mNextPermit = now.plus(mWindow);
        return true;
    }
}
