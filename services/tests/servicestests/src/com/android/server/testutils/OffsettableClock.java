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

import android.os.SystemClock;

import java.util.function.LongSupplier;

/**
 * A time supplier (in the format of a {@code long} as the amount of milliseconds) similar
 * to {@link SystemClock#uptimeMillis()}, but with the ability to {@link #fastForward}
 * and {@link #rewind}
 *
 * Implements {@link LongSupplier} to be interchangeable with {@code SystemClock::uptimeMillis}
 *
 * Can be provided to {@link TestHandler} to "mock time" for the delayed execution testing
 *
 * @see OffsettableClock.Stopped for a version of this clock that does not advance on its own
 */
public class OffsettableClock implements LongSupplier {
    private long mOffset = 0L;

    /**
     * @return Current time in milliseconds, according to this clock
     */
    public long now() {
        return realNow() + mOffset;
    }

    /**
     * Can be overriden with a constant for a clock that stands still, and is only ever moved
     * manually
     */
    public long realNow() {
        return SystemClock.uptimeMillis();
    }

    public void fastForward(long timeMs) {
        mOffset += timeMs;
    }
    public void rewind(long timeMs) {
        fastForward(-timeMs);
    }
    public void reset() {
        mOffset = 0;
    }

    /** @deprecated Only present for {@link LongSupplier} contract */
    @Override
    @Deprecated
    public long getAsLong() {
        return now();
    }

    /**
     * An {@link OffsettableClock} that does not advance with real time, and can only be
     * advanced manually via {@link #fastForward}
     */
    public static class Stopped extends OffsettableClock {
        @Override
        public long realNow() {
            return 0L;
        }
    }
}
