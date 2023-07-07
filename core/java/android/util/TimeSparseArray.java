/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.util;

/**
 * An array that indexes by a long timestamp, representing milliseconds since the epoch.
 * @param <E> The type of values this container maps to a timestamp.
 *
 * {@hide}
 */
public class TimeSparseArray<E> extends LongSparseArray<E> {
    private static final String TAG = TimeSparseArray.class.getSimpleName();

    private boolean mWtfReported;

    /**
     * Finds the index of the first element whose timestamp is greater or equal to
     * the given time.
     *
     * @param time The timestamp for which to search the array.
     * @return The smallest {@code index} for which {@code (keyAt(index) >= timeStamp)} is
     * {@code true}, or {@link #size() size} if no such {@code index} exists.
     */
    public int closestIndexOnOrAfter(long time) {
        final int size = size();
        int result = size;
        int lo = 0;
        int hi = size - 1;
        while (lo <= hi) {
            final int mid = lo + ((hi - lo) / 2);
            final long key = keyAt(mid);

            if (time > key) {
                lo = mid + 1;
            } else if (time < key) {
                hi = mid - 1;
                result = mid;
            } else {
                return mid;
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p> This container can store only one value for each timestamp. And so ideally, the caller
     * should ensure that there are no collisions. Reporting a {@link Slog#wtf(String, String)}
     * if that happens, as that will lead to the previous value being overwritten.
     */
    @Override
    public void put(long key, E value) {
        if (indexOfKey(key) >= 0) {
            if (!mWtfReported) {
                Slog.wtf(TAG, "Overwriting value " + get(key) + " by " + value);
                mWtfReported = true;
            }
        }
        super.put(key, value);
    }

    /**
     * Finds the index of the first element whose timestamp is less than or equal to
     * the given time.
     *
     * @param time The timestamp for which to search the array.
     * @return The largest {@code index} for which {@code (keyAt(index) <= timeStamp)} is
     * {@code true}, or -1 if no such {@code index} exists.
     */
    public int closestIndexOnOrBefore(long time) {
        final int index = closestIndexOnOrAfter(time);

        if (index < size() && keyAt(index) == time) {
            return index;
        }
        return index - 1;
    }
}
