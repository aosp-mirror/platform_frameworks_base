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

import android.util.ArraySet;

import dalvik.system.VMRuntime;

import libcore.util.EmptyArray;

import java.lang.reflect.Array;
import java.util.ArrayList;

/**
 * ArrayUtils contains some methods that you can call to find out
 * the most efficient increments by which to grow arrays.
 */
public class ArrayUtils
{
    private static final int CACHE_SIZE = 73;
    private static Object[] sCache = new Object[CACHE_SIZE];

    private ArrayUtils() { /* cannot be instantiated */ }

    public static byte[] newUnpaddedByteArray(int minLen) {
        return (byte[])VMRuntime.getRuntime().newUnpaddedArray(byte.class, minLen);
    }

    public static char[] newUnpaddedCharArray(int minLen) {
        return (char[])VMRuntime.getRuntime().newUnpaddedArray(char.class, minLen);
    }

    public static int[] newUnpaddedIntArray(int minLen) {
        return (int[])VMRuntime.getRuntime().newUnpaddedArray(int.class, minLen);
    }

    public static boolean[] newUnpaddedBooleanArray(int minLen) {
        return (boolean[])VMRuntime.getRuntime().newUnpaddedArray(boolean.class, minLen);
    }

    public static long[] newUnpaddedLongArray(int minLen) {
        return (long[])VMRuntime.getRuntime().newUnpaddedArray(long.class, minLen);
    }

    public static float[] newUnpaddedFloatArray(int minLen) {
        return (float[])VMRuntime.getRuntime().newUnpaddedArray(float.class, minLen);
    }

    public static Object[] newUnpaddedObjectArray(int minLen) {
        return (Object[])VMRuntime.getRuntime().newUnpaddedArray(Object.class, minLen);
    }

    @SuppressWarnings("unchecked")
    public static <T> T[] newUnpaddedArray(Class<T> clazz, int minLen) {
        return (T[])VMRuntime.getRuntime().newUnpaddedArray(clazz, minLen);
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
        if (length < 0) {
            throw new IllegalArgumentException();
        }

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
    @SuppressWarnings("unchecked")
    public static <T> T[] emptyArray(Class<T> kind) {
        if (kind == Object.class) {
            return (T[]) EmptyArray.OBJECT;
        }

        int bucket = (kind.hashCode() & 0x7FFFFFFF) % CACHE_SIZE;
        Object cache = sCache[bucket];

        if (cache == null || cache.getClass().getComponentType() != kind) {
            cache = Array.newInstance(kind, 0);
            sCache[bucket] = cache;

            // Log.e("cache", "new empty " + kind.getName() + " at " + bucket);
        }

        return (T[]) cache;
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static <T> boolean isEmpty(T[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Checks that value is present as at least one of the elements of the array.
     * @param array the array to check in
     * @param value the value to check for
     * @return true if the value is present in the array
     */
    public static <T> boolean contains(T[] array, T value) {
        return indexOf(array, value) != -1;
    }

    /**
     * Return first index of {@code value} in {@code array}, or {@code -1} if
     * not found.
     */
    public static <T> int indexOf(T[] array, T value) {
        if (array == null) return -1;
        for (int i = 0; i < array.length; i++) {
            if (array[i] == null) {
                if (value == null) return i;
            } else {
                if (value != null && array[i].equals(value)) return i;
            }
        }
        return -1;
    }

    /**
     * Test if all {@code check} items are contained in {@code array}.
     */
    public static <T> boolean containsAll(T[] array, T[] check) {
        for (T checkItem : check) {
            if (!contains(array, checkItem)) {
                return false;
            }
        }
        return true;
    }

    public static boolean contains(int[] array, int value) {
        if (array == null) return false;
        for (int element : array) {
            if (element == value) {
                return true;
            }
        }
        return false;
    }

    public static boolean contains(long[] array, long value) {
        if (array == null) return false;
        for (long element : array) {
            if (element == value) {
                return true;
            }
        }
        return false;
    }

    public static long total(long[] array) {
        long total = 0;
        for (long value : array) {
            total += value;
        }
        return total;
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

    /**
     * Appends a new value to a copy of the array and returns the copy.  If
     * the value is already present, the original array is returned
     * @param cur The original array, or null to represent an empty array.
     * @param val The value to add.
     * @return A new array that contains all of the values of the original array
     * with the new value added, or the original array.
     */
    public static int[] appendInt(int[] cur, int val) {
        if (cur == null) {
            return new int[] { val };
        }
        final int N = cur.length;
        for (int i = 0; i < N; i++) {
            if (cur[i] == val) {
                return cur;
            }
        }
        int[] ret = new int[N + 1];
        System.arraycopy(cur, 0, ret, 0, N);
        ret[N] = val;
        return ret;
    }

    public static int[] removeInt(int[] cur, int val) {
        if (cur == null) {
            return null;
        }
        final int N = cur.length;
        for (int i = 0; i < N; i++) {
            if (cur[i] == val) {
                int[] ret = new int[N - 1];
                if (i > 0) {
                    System.arraycopy(cur, 0, ret, 0, i);
                }
                if (i < (N - 1)) {
                    System.arraycopy(cur, i + 1, ret, i, N - i - 1);
                }
                return ret;
            }
        }
        return cur;
    }

    /**
     * Appends a new value to a copy of the array and returns the copy.  If
     * the value is already present, the original array is returned
     * @param cur The original array, or null to represent an empty array.
     * @param val The value to add.
     * @return A new array that contains all of the values of the original array
     * with the new value added, or the original array.
     */
    public static long[] appendLong(long[] cur, long val) {
        if (cur == null) {
            return new long[] { val };
        }
        final int N = cur.length;
        for (int i = 0; i < N; i++) {
            if (cur[i] == val) {
                return cur;
            }
        }
        long[] ret = new long[N + 1];
        System.arraycopy(cur, 0, ret, 0, N);
        ret[N] = val;
        return ret;
    }

    public static long[] removeLong(long[] cur, long val) {
        if (cur == null) {
            return null;
        }
        final int N = cur.length;
        for (int i = 0; i < N; i++) {
            if (cur[i] == val) {
                long[] ret = new long[N - 1];
                if (i > 0) {
                    System.arraycopy(cur, 0, ret, 0, i);
                }
                if (i < (N - 1)) {
                    System.arraycopy(cur, i + 1, ret, i, N - i - 1);
                }
                return ret;
            }
        }
        return cur;
    }

    public static long[] cloneOrNull(long[] array) {
        return (array != null) ? array.clone() : null;
    }

    public static <T> ArraySet<T> add(ArraySet<T> cur, T val) {
        if (cur == null) {
            cur = new ArraySet<>();
        }
        cur.add(val);
        return cur;
    }

    public static <T> ArraySet<T> remove(ArraySet<T> cur, T val) {
        if (cur == null) {
            return null;
        }
        cur.remove(val);
        if (cur.isEmpty()) {
            return null;
        } else {
            return cur;
        }
    }

    public static <T> boolean contains(ArraySet<T> cur, T val) {
        return (cur != null) ? cur.contains(val) : false;
    }

    public static <T> ArrayList<T> add(ArrayList<T> cur, T val) {
        if (cur == null) {
            cur = new ArrayList<>();
        }
        cur.add(val);
        return cur;
    }

    public static <T> ArrayList<T> remove(ArrayList<T> cur, T val) {
        if (cur == null) {
            return null;
        }
        cur.remove(val);
        if (cur.isEmpty()) {
            return null;
        } else {
            return cur;
        }
    }

    public static <T> boolean contains(ArrayList<T> cur, T val) {
        return (cur != null) ? cur.contains(val) : false;
    }
}
