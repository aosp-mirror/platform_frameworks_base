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

package android.util;

import android.annotation.Nullable;

/**
 * Copied over from frameworks/base/core/java/com/android/internal/util/ArrayUtils.java
 *
 * @hide
 */
public class ArrayUtils {
    private ArrayUtils() { /* cannot be instantiated */ }

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
}
