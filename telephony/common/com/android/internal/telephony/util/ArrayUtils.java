/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.internal.telephony.util;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/** Utility methods for array operations. */
public final class ArrayUtils {
    private ArrayUtils() { /* cannot be instantiated */ }

    /**
     * Adds value to given array if not already present, providing set-like behavior.
     *
     * @param kind    The class of the array elements.
     * @param array   The array to append to.
     * @param element The array element to append.
     * @return The array containing the appended element.
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public static <T> T[] appendElement(Class<T> kind, @Nullable T[] array, T element) {
        return appendElement(kind, array, element, false);
    }

    /**
     * Adds value to given array.
     *
     * @param kind            The class of the array elements.
     * @param array           The array to append to.
     * @param element         The array element to append.
     * @param allowDuplicates Whether to allow duplicated elements in array.
     * @return The array containing the appended element.
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public static <T> T[] appendElement(Class<T> kind, @Nullable T[] array, T element,
            boolean allowDuplicates) {
        final T[] result;
        final int end;
        if (array != null) {
            if (!allowDuplicates && contains(array, element)) return array;
            end = array.length;
            result = (T[]) Array.newInstance(kind, end + 1);
            System.arraycopy(array, 0, result, 0, end);
        } else {
            end = 0;
            result = (T[]) Array.newInstance(kind, 1);
        }
        result[end] = element;
        return result;
    }

    /**
     * Combine multiple arrays into a single array.
     *
     * @param kind   The class of the array elements
     * @param arrays The arrays to combine
     * @param <T>    The class of the array elements (inferred from kind).
     * @return A single array containing all the elements of the parameter arrays.
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public static <T> T[] concatElements(Class<T> kind, @Nullable T[]... arrays) {
        if (arrays == null || arrays.length == 0) {
            return createEmptyArray(kind);
        }

        int totalLength = 0;
        for (T[] item : arrays) {
            if (item == null) {
                continue;
            }

            totalLength += item.length;
        }

        // Optimization for entirely empty arrays.
        if (totalLength == 0) {
            return createEmptyArray(kind);
        }

        final T[] all = (T[]) Array.newInstance(kind, totalLength);
        int pos = 0;
        for (T[] item : arrays) {
            if (item == null || item.length == 0) {
                continue;
            }
            System.arraycopy(item, 0, all, pos, item.length);
            pos += item.length;
        }
        return all;
    }

    private static @NonNull <T> T[] createEmptyArray(Class<T> kind) {
        if (kind == String.class) {
            return (T[]) EmptyArray.STRING;
        } else if (kind == Object.class) {
            return (T[]) EmptyArray.OBJECT;
        }

        return (T[]) Array.newInstance(kind, 0);
    }

    private static final class EmptyArray {
        private EmptyArray() {}

        public static final Object[] OBJECT = new Object[0];
        public static final String[] STRING = new String[0];
    }

    /**
     * Checks if {@code value} is in {@code array}.
     */
    public static boolean contains(@Nullable char[] array, char value) {
        if (array == null) return false;
        for (char element : array) {
            if (element == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if {@code value} is in {@code array}.
     */
    public static <T> boolean contains(@Nullable Collection<T> cur, T val) {
        return (cur != null) ? cur.contains(val) : false;
    }

    /**
     * Checks if {@code value} is in {@code array}.
     */
    public static boolean contains(@Nullable int[] array, int value) {
        if (array == null) return false;
        for (int element : array) {
            if (element == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if {@code value} is in {@code array}.
     */
    public static boolean contains(@Nullable long[] array, long value) {
        if (array == null) return false;
        for (long element : array) {
            if (element == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if {@code value} is in {@code array}.
     */
    public static <T> boolean contains(@Nullable T[] array, T value) {
        return indexOf(array, value) != -1;
    }

    /**
     * Return first index of {@code value} in {@code array}, or {@code -1} if
     * not found.
     */
    public static <T> int indexOf(@Nullable T[] array, T value) {
        if (array == null) return -1;
        for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], value)) return i;
        }
        return -1;
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static boolean isEmpty(@Nullable Collection<?> array) {
        return array == null || array.isEmpty();
    }

    /**
     * Checks if given map is null or has zero elements.
     */
    public static boolean isEmpty(@Nullable Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static <T> boolean isEmpty(@Nullable T[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static boolean isEmpty(@Nullable int[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static boolean isEmpty(@Nullable long[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static boolean isEmpty(@Nullable byte[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static boolean isEmpty(@Nullable boolean[] array) {
        return array == null || array.length == 0;
    }
}
