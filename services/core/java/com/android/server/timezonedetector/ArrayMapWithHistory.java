/*
 * Copyright 2019 The Android Open Source Project
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
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

/**
 * A partial decorator for {@link ArrayMap} that records historic values for each mapping for
 * debugging later with {@link #dump(IndentingPrintWriter)}.
 *
 * <p>This class is only intended for use in {@link TimeZoneDetectorStrategy} and
 * {@link com.android.server.timedetector.TimeDetectorStrategy} so only provides the parts of the
 * {@link ArrayMap} API needed. If it is ever extended to include deletion methods like
 * {@link ArrayMap#remove(Object)} some thought would need to be given to the correct
 * {@link ArrayMap#containsKey(Object)} behavior for the history. Like {@link ArrayMap}, it is not
 * thread-safe.
 *
 * @param <K> the type of the key
 * @param <V> the type of the value
 */
public final class ArrayMapWithHistory<K, V> {
    private static final String TAG = "ArrayMapWithHistory";

    /** The size the linked list against each value is allowed to grow to. */
    private final int mMaxHistorySize;

    @Nullable
    private ArrayMap<K, ReferenceWithHistory<V>> mMap;

    /**
     * Creates an instance that records, at most, the specified number of values against each key.
     */
    public ArrayMapWithHistory(@IntRange(from = 1) int maxHistorySize) {
        if (maxHistorySize < 1) {
            throw new IllegalArgumentException("maxHistorySize < 1: " + maxHistorySize);
        }
        mMaxHistorySize = maxHistorySize;
    }

    /**
     * See {@link ArrayMap#put(K, V)}.
     */
    @Nullable
    public V put(@Nullable K key, @Nullable V value) {
        if (mMap == null) {
            mMap = new ArrayMap<>();
        }

        ReferenceWithHistory<V> valueHolder = mMap.get(key);
        if (valueHolder == null) {
            valueHolder = new ReferenceWithHistory<>(mMaxHistorySize);
            mMap.put(key, valueHolder);
        } else if (valueHolder.getHistoryCount() == 0) {
            Log.w(TAG, "History for \"" + key + "\" was unexpectedly empty");
        }

        return valueHolder.set(value);
    }

    /**
     * See {@link ArrayMap#get(Object)}.
     */
    @Nullable
    public V get(@Nullable Object key) {
        if (mMap == null) {
            return null;
        }

        ReferenceWithHistory<V> valueHolder = mMap.get(key);
        if (valueHolder == null) {
            return null;
        } else if (valueHolder.getHistoryCount() == 0) {
            Log.w(TAG, "History for \"" + key + "\" was unexpectedly empty");
        }
        return valueHolder.get();
    }

    /**
     * See {@link ArrayMap#size()}.
     */
    public int size() {
        return mMap == null ? 0 : mMap.size();
    }

    /**
     * See {@link ArrayMap#keyAt(int)}.
     */
    @Nullable
    public K keyAt(int index) {
        if (mMap == null) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return mMap.keyAt(index);
    }

    /**
     * See {@link ArrayMap#valueAt(int)}.
     */
    @Nullable
    public V valueAt(int index) {
        if (mMap == null) {
            throw new ArrayIndexOutOfBoundsException(index);
        }

        ReferenceWithHistory<V> valueHolder = mMap.valueAt(index);
        if (valueHolder == null || valueHolder.getHistoryCount() == 0) {
            Log.w(TAG, "valueAt(" + index + ") was unexpectedly null or empty");
            return null;
        }
        return valueHolder.get();
    }

    /**
     * Dumps the content of the map, including historic values, using the supplied writer.
     */
    public void dump(@NonNull IndentingPrintWriter ipw) {
        if (mMap == null) {
            ipw.println("{Empty}");
        } else {
            for (int i = 0; i < mMap.size(); i++) {
                ipw.println("key idx: " + i + "=" + mMap.keyAt(i));
                ReferenceWithHistory<V> value = mMap.valueAt(i);
                ipw.println("val idx: " + i + "=" +  value);
                ipw.increaseIndent();

                ipw.println("Historic values=[");
                ipw.increaseIndent();
                value.dump(ipw);
                ipw.decreaseIndent();
                ipw.println("]");

                ipw.decreaseIndent();
            }
        }
        ipw.flush();
    }

    /**
     * Internal method intended for tests that returns the number of historic values associated with
     * the supplied key currently. If there is no mapping for the key then {@code 0} is returned.
     */
    @VisibleForTesting
    public int getHistoryCountForKeyForTests(@Nullable K key) {
        if (mMap == null) {
            return 0;
        }

        ReferenceWithHistory<V> valueHolder = mMap.get(key);
        if (valueHolder == null) {
            return 0;
        } else if (valueHolder.getHistoryCount() == 0) {
            Log.w(TAG, "getValuesSizeForKeyForTests(\"" + key + "\") was unexpectedly empty");
            return 0;
        } else {
            return valueHolder.getHistoryCount();
        }
    }

    @Override
    public String toString() {
        return "ArrayMapWithHistory{"
                + "mHistorySize=" + mMaxHistorySize
                + ", mMap=" + mMap
                + '}';
    }
}
