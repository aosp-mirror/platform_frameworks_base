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

package com.android.internal.util;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * An implementation of the quick selection algorithm as described in
 * http://en.wikipedia.org/wiki/Quickselect.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class QuickSelect {
    private static <T> int selectImpl(@NonNull List<T> list, int left, int right, int k,
            @NonNull Comparator<? super T> comparator) {
        while (true) {
            if (left == right) {
                return left;
            }
            final int pivotIndex = partition(list, left, right, (left + right) >> 1, comparator);
            if (k == pivotIndex) {
                return k;
            } else if (k < pivotIndex) {
                right = pivotIndex - 1;
            } else {
                left = pivotIndex + 1;
            }
        }
    }

    private static int selectImpl(@NonNull int[] array, int left, int right, int k) {
        while (true) {
            if (left == right) {
                return left;
            }
            final int pivotIndex = partition(array, left, right, (left + right) >> 1);
            if (k == pivotIndex) {
                return k;
            } else if (k < pivotIndex) {
                right = pivotIndex - 1;
            } else {
                left = pivotIndex + 1;
            }
        }
    }

    private static int selectImpl(@NonNull long[] array, int left, int right, int k) {
        while (true) {
            if (left == right) {
                return left;
            }
            final int pivotIndex = partition(array, left, right, (left + right) >> 1);
            if (k == pivotIndex) {
                return k;
            } else if (k < pivotIndex) {
                right = pivotIndex - 1;
            } else {
                left = pivotIndex + 1;
            }
        }
    }

    private static <T> int selectImpl(@NonNull T[] array, int left, int right, int k,
            @NonNull Comparator<? super T> comparator) {
        while (true) {
            if (left == right) {
                return left;
            }
            final int pivotIndex = partition(array, left, right, (left + right) >> 1, comparator);
            if (k == pivotIndex) {
                return k;
            } else if (k < pivotIndex) {
                right = pivotIndex - 1;
            } else {
                left = pivotIndex + 1;
            }
        }
    }

    private static <T> int partition(@NonNull List<T> list, int left, int right, int pivotIndex,
            @NonNull Comparator<? super T> comparator) {
        final T pivotValue = list.get(pivotIndex);
        swap(list, right, pivotIndex);
        int storeIndex = left;
        for (int i = left; i < right; i++) {
            if (comparator.compare(list.get(i), pivotValue) < 0) {
                swap(list, storeIndex, i);
                storeIndex++;
            }
        }
        swap(list, right, storeIndex);
        return storeIndex;
    }

    private static int partition(@NonNull int[] array, int left, int right, int pivotIndex) {
        final int pivotValue = array[pivotIndex];
        swap(array, right, pivotIndex);
        int storeIndex = left;
        for (int i = left; i < right; i++) {
            if (array[i] < pivotValue) {
                swap(array, storeIndex, i);
                storeIndex++;
            }
        }
        swap(array, right, storeIndex);
        return storeIndex;
    }

    private static int partition(@NonNull long[] array, int left, int right, int pivotIndex) {
        final long pivotValue = array[pivotIndex];
        swap(array, right, pivotIndex);
        int storeIndex = left;
        for (int i = left; i < right; i++) {
            if (array[i] < pivotValue) {
                swap(array, storeIndex, i);
                storeIndex++;
            }
        }
        swap(array, right, storeIndex);
        return storeIndex;
    }

    private static <T> int partition(@NonNull T[] array, int left, int right, int pivotIndex,
            @NonNull Comparator<? super T> comparator) {
        final T pivotValue = array[pivotIndex];
        swap(array, right, pivotIndex);
        int storeIndex = left;
        for (int i = left; i < right; i++) {
            if (comparator.compare(array[i], pivotValue) < 0) {
                swap(array, storeIndex, i);
                storeIndex++;
            }
        }
        swap(array, right, storeIndex);
        return storeIndex;
    }

    private static <T> void swap(@NonNull List<T> list, int left, int right) {
        final T tmp = list.get(left);
        list.set(left, list.get(right));
        list.set(right, tmp);
    }

    private static void swap(@NonNull int[] array, int left, int right) {
        final int tmp = array[left];
        array[left] = array[right];
        array[right] = tmp;
    }

    private static void swap(@NonNull long[] array, int left, int right) {
        final long tmp = array[left];
        array[left] = array[right];
        array[right] = tmp;
    }

    private static <T> void swap(@NonNull T[] array, int left, int right) {
        final T tmp = array[left];
        array[left] = array[right];
        array[right] = tmp;
    }

    /**
     * Return the kth(0-based) smallest element from the given unsorted list.
     *
     * @param list The input list, it <b>will</b> be modified by the algorithm here.
     * @param start The start offset of the list, inclusive.
     * @param length The length of the sub list to be searched in.
     * @param k The 0-based index.
     * @param comparator The comparator which knows how to compare the elements in the list.
     * @return The kth smallest element from the given list,
     *         or IllegalArgumentException will be thrown if not found.
     */
    @Nullable
    public static <T> T select(@NonNull List<T> list, int start, int length, int k,
            @NonNull Comparator<? super T> comparator) {
        if (list == null || start < 0 || length <= 0 || list.size() < start + length
                || k < 0 || length <= k) {
            throw new IllegalArgumentException();
        }
        return list.get(selectImpl(list, start, start + length - 1, k + start, comparator));
    }

    /**
     * Return the kth(0-based) smallest element from the given unsorted array.
     *
     * @param array The input array, it <b>will</b> be modified by the algorithm here.
     * @param start The start offset of the array, inclusive.
     * @param length The length of the sub array to be searched in.
     * @param k The 0-based index to search for.
     * @return The kth smallest element from the given array,
     *         or IllegalArgumentException will be thrown if not found.
     */
    public static int select(@NonNull int[] array, int start, int length, int k) {
        if (array == null || start < 0 || length <= 0 || array.length < start + length
                || k < 0 || length <= k) {
            throw new IllegalArgumentException();
        }
        return array[selectImpl(array, start, start + length - 1, k + start)];
    }

    /**
     * Return the kth(0-based) smallest element from the given unsorted array.
     *
     * @param array The input array, it <b>will</b> be modified by the algorithm here.
     * @param start The start offset of the array, inclusive.
     * @param length The length of the sub array to be searched in.
     * @param k The 0-based index to search for.
     * @return The kth smallest element from the given array,
     *         or IllegalArgumentException will be thrown if not found.
     */
    public static long select(@NonNull long[] array, int start, int length, int k) {
        if (array == null || start < 0 || length <= 0 || array.length < start + length
                || k < 0 || length <= k) {
            throw new IllegalArgumentException();
        }
        return array[selectImpl(array, start, start + length - 1, k + start)];
    }

    /**
     * Return the kth(0-based) smallest element from the given unsorted array.
     *
     * @param array The input array, it <b>will</b> be modified by the algorithm here.
     * @param start The start offset of the array, inclusive.
     * @param length The length of the sub array to be searched in.
     * @param k The 0-based index to search for.
     * @param comparator The comparator which knows how to compare the elements in the list.
     * @return The kth smallest element from the given array,
     *         or IllegalArgumentException will be thrown if not found.
     */
    public static <T> T select(@NonNull T[] array, int start, int length, int k,
            @NonNull Comparator<? super T> comparator) {
        if (array == null || start < 0 || length <= 0 || array.length < start + length
                || k < 0 || length <= k) {
            throw new IllegalArgumentException();
        }
        return array[selectImpl(array, start, start + length - 1, k + start, comparator)];
    }
}
