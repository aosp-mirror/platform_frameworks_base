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

package android.util;

import android.annotation.NonNull;

/**
 * Empty array is immutable. Use a shared empty array to avoid allocation.
 *
 * @hide
 */
public final class EmptyArray {
    private EmptyArray() {}

    public static final @NonNull boolean[] BOOLEAN = new boolean[0];
    public static final @NonNull byte[] BYTE = new byte[0];
    public static final @NonNull char[] CHAR = new char[0];
    public static final @NonNull double[] DOUBLE = new double[0];
    public static final @NonNull float[] FLOAT = new float[0];
    public static final @NonNull int[] INT = new int[0];
    public static final @NonNull long[] LONG = new long[0];
    public static final @NonNull Object[] OBJECT = new Object[0];
    public static final @NonNull String[] STRING = new String[0];
}
