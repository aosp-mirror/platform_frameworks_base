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

package com.android.server.timezonedetector;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.os.TimestampedValue;
import android.util.IndentingPrintWriter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * A class that behaves like the following definition, except it stores the history of values set
 * that can be dumped for debugging with {@link #dump(IndentingPrintWriter)}.
 *
 * <pre>{@code
 *     private static class Ref<V> {
 *         private V mValue;
 *
 *         public V get() {
 *             return mValue;
 *         }
 *
 *         public V set(V value) {
 *             V previous = mValue;
 *             mValue = value;
 *             return previous;
 *         }
 *     }
 * }</pre>
 *
 * <p>This class is not thread-safe.
 *
 * @param <V> the type of the value
 */
public final class ReferenceWithHistory<V> {

    /** The maximum number of references to store. */
    private final int mMaxHistorySize;

    /** The number of times {@link #set(Object)} has been called. */
    private int mSetCount;

    /** The history storage. */
    @Nullable
    private ArrayDeque<TimestampedValue<V>> mValues;

    /**
     * Creates an instance that records, at most, the specified number of values.
     */
    public ReferenceWithHistory(@IntRange(from = 1) int maxHistorySize) {
        if (maxHistorySize < 1) {
            throw new IllegalArgumentException("maxHistorySize < 1: " + maxHistorySize);
        }
        this.mMaxHistorySize = maxHistorySize;
    }

    /** Returns the current value, or {@code null} if it has never been set. */
    @Nullable
    public V get() {
        if (mValues == null || mValues.isEmpty()) {
            return null;
        }
        TimestampedValue<V> valueHolder = mValues.getFirst();
        return valueHolder.getValue();
    }

    /**
     * Sets the current value. Returns the previous value, which can be {@code null} if the
     * reference has never been set, or if the reference has been set to {@code null}.
     */
    @Nullable
    public V set(@Nullable V newValue) {
        if (mValues == null) {
            mValues = new ArrayDeque<>(mMaxHistorySize);
        }

        if (mValues.size() >= mMaxHistorySize) {
            mValues.removeLast();
        }

        V previous = get();

        TimestampedValue<V> valueHolder =
                new TimestampedValue<>(SystemClock.elapsedRealtime(), newValue);
        mValues.addFirst(valueHolder);
        mSetCount++;
        return previous;
    }

    /**
     * Dumps the content of the reference, including historic values, using the supplied writer.
     */
    public void dump(@NonNull IndentingPrintWriter ipw) {
        if (mValues == null) {
            ipw.println("{Empty}");
        } else {
            int i = mSetCount - mValues.size();
            Iterator<TimestampedValue<V>> reverseIterator = mValues.descendingIterator();
            while (reverseIterator.hasNext()) {
                TimestampedValue<V> valueHolder = reverseIterator.next();
                ipw.print(i++);
                ipw.print("@");
                ipw.print(Duration.ofMillis(valueHolder.getReferenceTimeMillis()).toString());
                ipw.print(": ");
                ipw.println(valueHolder.getValue());
            }
        }
        ipw.flush();
    }

    /**
     * Returns the number of historic entries stored currently.
     */
    public int getHistoryCount() {
        return mValues == null ? 0 : mValues.size();
    }

    @Override
    public String toString() {
        return String.valueOf(get());
    }
}
