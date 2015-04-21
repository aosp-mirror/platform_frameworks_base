/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.bluetoothmidiservice;

/**
 * Convert MIDI over BTLE timestamps to system nanotime.
 */
public class MidiBtleTimeTracker {

    public final static long NANOS_PER_MILLI = 1000000L;

    private final static long RANGE_MILLIS = 0x2000; // per MIDI / BTLE standard
    private final static long RANGE_NANOS = RANGE_MILLIS * NANOS_PER_MILLI;

    private int mWindowMillis = 20; // typical max connection interval
    private long mWindowNanos = mWindowMillis * NANOS_PER_MILLI;

    private int mPreviousTimestamp; // Used to calculate deltas.
    private long mPreviousNow;
    // Our model of the peripherals millisecond clock.
    private long mPeripheralTimeMillis;
    // Host time that corresponds to time=0 on the peripheral.
    private long mBaseHostTimeNanos;
    private long mPreviousResult; // To prevent retrograde timestamp

    public MidiBtleTimeTracker(long now) {
        mPeripheralTimeMillis = 0;
        mBaseHostTimeNanos = now;
        mPreviousNow = now;
    }

    /**
     * @param timestamp
     *            13-bit millis in range of 0 to 8191
     * @param now
     *            current time in nanoseconds
     * @return nanoseconds corresponding to the timestamp
     */
    public long convertTimestampToNanotime(int timestamp, long now) {
        long deltaMillis = timestamp - mPreviousTimestamp;
        // will be negative when timestamp wraps
        if (deltaMillis < 0) {
            deltaMillis += RANGE_MILLIS;
        }
        mPeripheralTimeMillis += deltaMillis;

        // If we have not been called for a long time then
        // make sure we have not wrapped multiple times.
        if ((now - mPreviousNow) > (RANGE_NANOS / 2)) {
            // Handle missed wraps.
            long minimumTimeNanos = (now - mBaseHostTimeNanos)
                    - (RANGE_NANOS / 2);
            long minimumTimeMillis = minimumTimeNanos / NANOS_PER_MILLI;
            while (mPeripheralTimeMillis < minimumTimeMillis) {
                mPeripheralTimeMillis += RANGE_MILLIS;
            }
        }

        // Convert peripheral time millis to host time nanos.
        long timestampHostNanos = (mPeripheralTimeMillis * NANOS_PER_MILLI)
                + mBaseHostTimeNanos;

        // The event cannot be in the future. So move window if we hit that.
        if (timestampHostNanos > now) {
            mPeripheralTimeMillis = 0;
            mBaseHostTimeNanos = now;
            timestampHostNanos = now;
        } else {
            // Timestamp should not be older than our window time.
            long windowBottom = now - mWindowNanos;
            if (timestampHostNanos < windowBottom) {
                mPeripheralTimeMillis = 0;
                mBaseHostTimeNanos = windowBottom;
                timestampHostNanos = windowBottom;
            }
        }
        // prevent retrograde timestamp
        if (timestampHostNanos < mPreviousResult) {
            timestampHostNanos = mPreviousResult;
        }
        mPreviousResult = timestampHostNanos;
        mPreviousTimestamp = timestamp;
        mPreviousNow = now;
        return timestampHostNanos;
    }

    public int getWindowMillis() {
        return mWindowMillis;
    }

    public void setWindowMillis(int window) {
        this.mWindowMillis = window;
    }

}
