/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.role.util;

import android.annotation.Nullable;

import java.util.Objects;

/**
 * Array utilities.
 */
public final class ArrayUtils {
    private ArrayUtils() {}

    /**
     * @see java.util.List#contains(Object)
     */
    public static <T> boolean contains(@Nullable T[] array, T value) {
        return indexOf(array, value) != -1;
    }

    /**
     * Get the first element of an array, or {@code null} if none.
     *
     * @param array the array
     * @param <T> the type of the elements of the array
     * @return first element of an array, or {@code null} if none
     */
    public static <T> T firstOrNull(@Nullable T[] array) {
        return !isEmpty(array) ? array[0] : null;
    }

    /**
     * @see java.util.List#indexOf(Object)
     */
    public static <T> int indexOf(@Nullable T[] array, T value) {
        if (array == null) {
            return -1;
        }
        final int length = array.length;
        for (int i = 0; i < length; i++) {
            final T element = array[i];
            if (Objects.equals(element, value)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @see java.util.List#isEmpty()
     */
    public static <T> boolean isEmpty(@Nullable T[] array) {
        return array == null || array.length == 0;
    }
}
