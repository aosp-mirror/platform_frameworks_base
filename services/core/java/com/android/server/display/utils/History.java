/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.utils;

import java.time.Clock;

/**
 * A fixed-size buffer that keeps the most recent values and their times.
 *
 * This class is used for logging and debugging purposes only, so there's no way to retrieve the
 * history other than toString(), and a non-monotonic clock is good enough.
 */
public class History {

    private int mSize;
    private int mCount;
    private int mStart;
    private int mEnd;

    private long[] mTimes;
    private float[] mValues;

    private Clock mClock;

    /**
     * @param size
     *      The maximum number of values kept.
     */
    public History(int size) {
        this(size, Clock.systemUTC());
    }

    /**
     * @param size
     *    The maximum number of values kept.
     * @param clock
     *    The clock used.
     */
    public History(int size, Clock clock) {
        mSize = size;
        mCount = 0;
        mStart = 0;
        mEnd = 0;
        mTimes = new long[size];
        mValues = new float[size];
        mClock = clock;
    }

    /**
     * Add a value.
     *
     * @param value
     *      The value.
     */
    public void add(float value) {
        mTimes[mEnd] = mClock.millis();
        mValues[mEnd] = value;
        if (mCount < mSize) {
            mCount++;
        } else {
            mStart = (mStart + 1) % mSize;
        }
        mEnd = (mEnd + 1) % mSize;
    }

    /**
     * Convert the buffer to string.
     *
     * @return The buffer as string.
     */
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("[");
        for (int i = 0; i < mCount; i++) {
            final int index = (mStart + i) % mSize;
            final long time = mTimes[index];
            final float value = mValues[index];
            sb.append(value + " @ " + time);
            if (i + 1 != mCount) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

}
