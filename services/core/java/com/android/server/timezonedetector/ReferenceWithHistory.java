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

import com.android.internal.util.IndentingPrintWriter;

import java.util.ArrayDeque;

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

    private static final Object NULL_MARKER = "{null marker}";

    /** The maximum number of references to store. */
    private final int mMaxHistorySize;

    /**
     * The history storage. Note that ArrayDeque doesn't support {@code null} so this stores Object
     * and not V. Use {@link #packNullIfRequired(Object)} and {@link #unpackNullIfRequired(Object)}
     * to convert to / from the storage object.
     */
    @Nullable
    private ArrayDeque<Object> mValues;

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
        Object value = mValues.getFirst();
        return unpackNullIfRequired(value);
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

        Object nullSafeValue = packNullIfRequired(newValue);
        mValues.addFirst(nullSafeValue);
        return previous;
    }

    /**
     * Dumps the content of the reference, including historic values, using the supplied writer.
     */
    public void dump(@NonNull IndentingPrintWriter ipw) {
        if (mValues == null) {
            ipw.println("{Empty}");
        } else {
            int i = 0;
            for (Object value : mValues) {
                ipw.println(i + ": " + unpackNullIfRequired(value));
                i++;
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

    /**
     * Turns a non-nullable Object into a nullable value. See also
     * {@link #packNullIfRequired(Object)}.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private V unpackNullIfRequired(@NonNull Object value) {
        return value == NULL_MARKER ? null : (V) value;
    }

    /**
     * Turns a nullable value into a non-nullable Object. See also
     * {@link #unpackNullIfRequired(Object)}.
     */
    @NonNull
    private Object packNullIfRequired(@Nullable V value) {
        return value == null ? NULL_MARKER : value;
    }
}
