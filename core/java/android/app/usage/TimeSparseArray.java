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

package android.app.usage;

import android.util.LongSparseArray;

/**
 * An array that indexes by a long timestamp, representing milliseconds since the epoch.
 *
 * {@hide}
 */
public class TimeSparseArray<E> extends LongSparseArray<E> {
    public TimeSparseArray() {
        super();
    }

    public TimeSparseArray(int initialCapacity) {
        super(initialCapacity);
    }

    /**
     * Finds the index of the first element whose timestamp is greater or equal to
     * the given time.
     *
     * @param time The timestamp for which to search the array.
     * @return The index of the matched element, or -1 if no such match exists.
     */
    public int closestIndexOnOrAfter(long time) {
        // This is essentially a binary search, except that if no match is found
        // the closest index is returned.
        final int size = size();
        int lo = 0;
        int hi = size - 1;
        int mid = -1;
        long key = -1;
        while (lo <= hi) {
            mid = lo + ((hi - lo) / 2);
            key = keyAt(mid);

            if (time > key) {
                lo = mid + 1;
            } else if (time < key) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }

        if (time < key) {
            return mid;
        } else if (time > key && lo < size) {
            return lo;
        } else {
            return -1;
        }
    }

    /**
     * Finds the index of the first element whose timestamp is less than or equal to
     * the given time.
     *
     * @param time The timestamp for which to search the array.
     * @return The index of the matched element, or -1 if no such match exists.
     */
    public int closestIndexOnOrBefore(long time) {
        final int index = closestIndexOnOrAfter(time);
        if (index < 0) {
            // Everything is larger, so we use the last element, or -1 if the list is empty.
            return size() - 1;
        }

        if (keyAt(index) == time) {
            return index;
        }
        return index - 1;
    }
}
