/*
 * Copyright (C) 2006 The Android Open Source Project
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

import java.lang.reflect.Array;

// XXX these should be changed to reflect the actual memory allocator we use.
// it looks like right now objects want to be powers of 2 minus 8
// and the array size eats another 4 bytes

/**
 * ArrayUtils contains some methods that you can call to find out
 * the most efficient increments by which to grow arrays.
 */
public class ArrayUtils
{
    private static Object[] EMPTY = new Object[0];
    private static final int CACHE_SIZE = 73;
    private static Object[] sCache = new Object[CACHE_SIZE];

    private ArrayUtils() { /* cannot be instantiated */ }

    public static int idealByteArraySize(int need) {
        for (int i = 4; i < 32; i++)
            if (need <= (1 << i) - 12)
                return (1 << i) - 12;

        return need;
    }

    public static int idealBooleanArraySize(int need) {
        return idealByteArraySize(need);
    }

    public static int idealShortArraySize(int need) {
        return idealByteArraySize(need * 2) / 2;
    }

    public static int idealCharArraySize(int need) {
        return idealByteArraySize(need * 2) / 2;
    }

    public static int idealIntArraySize(int need) {
        return idealByteArraySize(need * 4) / 4;
    }

    public static int idealFloatArraySize(int need) {
        return idealByteArraySize(need * 4) / 4;
    }

    public static int idealObjectArraySize(int need) {
        return idealByteArraySize(need * 4) / 4;
    }

    public static int idealLongArraySize(int need) {
        return idealByteArraySize(need * 8) / 8;
    }

    /**
     * Checks if the beginnings of two byte arrays are equal.
     *
     * @param array1 the first byte array
     * @param array2 the second byte array
     * @param length the number of bytes to check
     * @return true if they're equal, false otherwise
     */
    public static boolean equals(byte[] array1, byte[] array2, int length) {
        if (array1 == array2) {
            return true;
        }
        if (array1 == null || array2 == null || array1.length < length || array2.length < length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (array1[i] != array2[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns an empty array of the specified type.  The intent is that
     * it will return the same empty array every time to avoid reallocation,
     * although this is not guaranteed.
     */
    public static <T> T[] emptyArray(Class<T> kind) {
        if (kind == Object.class) {
            return (T[]) EMPTY;
        }

        int bucket = ((System.identityHashCode(kind) / 8) & 0x7FFFFFFF) % CACHE_SIZE;
        Object cache = sCache[bucket];

        if (cache == null || cache.getClass().getComponentType() != kind) {
            cache = Array.newInstance(kind, 0);
            sCache[bucket] = cache;

            // Log.e("cache", "new empty " + kind.getName() + " at " + bucket);
        }

        return (T[]) cache;
    }

    /**
     * Checks that value is present as at least one of the elements of the array.
     * @param array the array to check in
     * @param value the value to check for
     * @return true if the value is present in the array
     */
    public static <T> boolean contains(T[] array, T value) {
        for (T element : array) {
            if (element == null) {
                if (value == null) return true;
            } else {
                if (value != null && element.equals(value)) return true;
            }
        }
        return false;
    }

    public static boolean contains(int[] array, int value) {
        for (int element : array) {
            if (element == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends an element to a copy of the array and returns the copy.
     * @param array The original array, or null to represent an empty array.
     * @param element The element to add.
     * @return A new array that contains all of the elements of the original array
     * with the specified element added at the end.
     */
    @SuppressWarnings("unchecked")
    public static <T> T[] appendElement(Class<T> kind, T[] array, T element) {
        final T[] result;
        final int end;
        if (array != null) {
            end = array.length;
            result = (T[])Array.newInstance(kind, end + 1);
            System.arraycopy(array, 0, result, 0, end);
        } else {
            end = 0;
            result = (T[])Array.newInstance(kind, 1);
        }
        result[end] = element;
        return result;
    }

    /**
     * Removes an element from a copy of the array and returns the copy.
     * If the element is not present, then the original array is returned unmodified.
     * @param array The original array, or null to represent an empty array.
     * @param element The element to remove.
     * @return A new array that contains all of the elements of the original array
     * except the first copy of the specified element removed.  If the specified element
     * was not present, then returns the original array.  Returns null if the result
     * would be an empty array.
     */
    @SuppressWarnings("unchecked")
    public static <T> T[] removeElement(Class<T> kind, T[] array, T element) {
        if (array != null) {
            final int length = array.length;
            for (int i = 0; i < length; i++) {
                if (array[i] == element) {
                    if (length == 1) {
                        return null;
                    }
                    T[] result = (T[])Array.newInstance(kind, length - 1);
                    System.arraycopy(array, 0, result, 0, i);
                    System.arraycopy(array, i + 1, result, i, length - i - 1);
                    return result;
                }
            }
        }
        return array;
    }
}
