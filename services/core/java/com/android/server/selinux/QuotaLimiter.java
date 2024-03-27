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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;

import java.time.Duration;
import java.time.Instant;

/**
 * A QuotaLimiter allows to define a maximum number of Atom pushes within a specific time window.
 *
 * <p>The limiter divides the time line in windows of a fixed size. Every time a new permit is
 * requested, the limiter checks whether the previous request was in the same time window as the
 * current one. If the two windows are the same, it grants a permit only if the number of permits
 * granted within the window does not exceed the quota. If the two windows are different, it resets
 * the quota.
 */
public class QuotaLimiter {

    private final Clock mClock;
    private final Duration mWindowSize;

    private int mMaxPermits;
    private long mCurrentWindow;
    private int mPermitsGranted;

    @VisibleForTesting
    QuotaLimiter(Clock clock, Duration windowSize, int maxPermits) {
        mClock = clock;
        mWindowSize = windowSize;
        mMaxPermits = maxPermits;
    }

    public QuotaLimiter(Duration windowSize, int maxPermits) {
        this(Clock.SYSTEM_CLOCK, windowSize, maxPermits);
    }

    public QuotaLimiter(int maxPermitsPerDay) {
        this(Clock.SYSTEM_CLOCK, Duration.ofDays(1), maxPermitsPerDay);
    }

    /**
     * Acquires a permit if there is one available in the current time window.
     *
     * @return true if a permit was acquired.
     */
    boolean acquire() {
        long nowWindow =
                Duration.between(Instant.EPOCH, Instant.ofEpochMilli(mClock.currentTimeMillis()))
                        .dividedBy(mWindowSize);
        if (nowWindow > mCurrentWindow) {
            mCurrentWindow = nowWindow;
            mPermitsGranted = 0;
        }

        if (mPermitsGranted < mMaxPermits) {
            mPermitsGranted++;
            return true;
        }

        return false;
    }

    public void setMaxPermits(int maxPermits) {
        this.mMaxPermits = maxPermits;
    }
}
