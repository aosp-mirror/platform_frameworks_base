/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Copied over from frameworks/base/core/java/com/android/internal/util/ArrayUtils.java
 *
 * @hide
 */
public class ArrayUtils {
    private ArrayUtils() { /* cannot be instantiated */ }
    public static final File[] EMPTY_FILE = new File[0];


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

    /** @hide */
    public static @NonNull File[] defeatNullable(@Nullable File[] val) {
        return (val != null) ? val : EMPTY_FILE;
    }

    /**
     * Checks if given array is null or has zero elements.
     */
    public static boolean isEmpty(@Nullable int[] array) {
        return array == null || array.length == 0;
    }

    /**
     * True if the byte array is null or has length 0.
     */
    public static boolean isEmpty(@Nullable byte[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Converts from List of bytes to byte array
     * @param list
     * @return byte[]
     */
    public static byte[] toPrimitive(List<byte[]> list) {
        if (list.size() == 0) {
            return new byte[0];
        }
        int byteLen = list.get(0).length;
        byte[] array = new byte[list.size() * byteLen];
        for (int i = 0; i < list.size(); i++) {
            for (int j = 0; j < list.get(i).length; j++) {
                array[i * byteLen + j] = list.get(i)[j];
            }
        }
        return array;
    }

    /**
     * Adds value to given array if not already present, providing set-like
     * behavior.
     */
    public static @NonNull int[] appendInt(@Nullable int[] cur, int val) {
        return appendInt(cur, val, false);
    }

    /**
     * Adds value to given array.
     */
    public static @NonNull int[] appendInt(@Nullable int[] cur, int val,
            boolean allowDuplicates) {
        if (cur == null) {
            return new int[] { val };
        }
        final int n = cur.length;
        if (!allowDuplicates) {
            for (int i = 0; i < n; i++) {
                if (cur[i] == val) {
                    return cur;
                }
            }
        }
        int[] ret = new int[n + 1];
        System.arraycopy(cur, 0, ret, 0, n);
        ret[n] = val;
        return ret;
    }
}
