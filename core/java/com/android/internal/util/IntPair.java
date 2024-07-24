/*
 * Copyright (C) 2017 The Android Open Source Project
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

/**
 * Utilities for treating a {@code long} as a pair of {@code int}s
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class IntPair {
    private IntPair() {}

    public static long of(int first, int second) {
        return (((long)first) << 32) | ((long)second & 0xffffffffL);
    }

    public static int first(long intPair) {
        return (int)(intPair >> 32);
    }

    public static int second(long intPair) {
        return (int)intPair;
    }
}
