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

/**
 * A buffer that supports adding new values and truncating old ones.
 */
public class RollingBuffer {

    private static final int INITIAL_SIZE = 50;

    private int mSize;
    private int mCount;
    private int mStart;
    private int mEnd;

    private long[] mTimes; // Milliseconds
    private float[] mValues;

    public RollingBuffer() {
        mSize = INITIAL_SIZE;
        mTimes = new long[INITIAL_SIZE];
        mValues = new float[INITIAL_SIZE];
        clear();
    }

    /**
     * Add a value at a given time.
     *
     * @param time
     *      The time (in milliseconds).
     * @param value
     *      The value.
     */
    public void add(long time, float value) {
        if (mCount >= mSize) {
            expandBuffer();
        }
        mTimes[mEnd] = time;
        mValues[mEnd] = value;
        mEnd = (mEnd + 1) % mSize;
        mCount++;
    }

    /**
     * Get the size of the buffer.
     *
     * @return The size of the buffer.
     */
    public int size() {
        return mCount;
    }

    /**
     * Return whether the buffer is empty or not.
     *
     * @return Whether the buffer is empty or not.
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Get a time.
     *
     * @param index
     *      The index of the time.
     *
     * @return The time.
     */
    public long getTime(int index) {
        return mTimes[offsetOf(index)];
    }

    /**
     * Get a value.
     *
     * @param index
     *      The index of the value.
     *
     * @return The value.
     */
    public float getValue(int index) {
        return mValues[offsetOf(index)];
    }

    /**
     * Truncate old values.
     *
     * @param minTime
     *      The minimum time (all values older than this time are truncated).
     */
    public void truncate(long minTime) {
        if (isEmpty() || getTime(0) >= minTime) {
            return;
        }
        final int index = getLatestIndexBefore(minTime);
        mStart = offsetOf(index);
        mCount -= index;
        // Remove everything that happened before mTimes[index], but set the index-th value time to
        // minTime rather than dropping it, as that would've been the value between the minTime and
        // mTimes[index+1].
        //
        // -*---*---|---*---*- => xxxxxxxxx|*--*---*- rather than xxxxxxxxx|???*---*-
        //      ^       ^                   ^  ^                               ^
        //      i      i+1                  i i+1                             i+1
        mTimes[mStart] = minTime;
    }

    /**
     * Clears the buffer.
     */
    public void clear() {
        mCount = 0;
        mStart = 0;
        mEnd = 0;
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
            final int index = offsetOf(i);
            sb.append(mValues[index] + " @ " + mTimes[index]);
            if (i + 1 != mCount) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private int offsetOf(int index) {
        if (index < 0 || index >= mCount) {
            throw new ArrayIndexOutOfBoundsException("invalid index: " + index + ", mCount= "
                    + mCount);
        }
        return (mStart + index) % mSize;
    }

    private void expandBuffer() {
        final int size = mSize * 2;
        long[] times = new long[size];
        float[] values = new float[size];
        System.arraycopy(mTimes, mStart, times, 0, mCount - mStart);
        System.arraycopy(mTimes, 0, times, mCount - mStart, mStart);
        System.arraycopy(mValues, mStart, values, 0, mCount - mStart);
        System.arraycopy(mValues, 0, values, mCount - mStart, mStart);
        mSize = size;
        mStart = 0;
        mEnd = mCount;
        mTimes = times;
        mValues = values;
    }

    private int getLatestIndexBefore(long time) {
        for (int i = 1; i < mCount; i++) {
            if (mTimes[offsetOf(i)] > time) {
                return i - 1;
            }
        }
        return mCount - 1;
    }

}
