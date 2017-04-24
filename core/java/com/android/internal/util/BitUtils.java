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

import android.annotation.Nullable;

import libcore.util.Objects;

import java.util.Arrays;
import java.util.UUID;

public class BitUtils {
    private BitUtils() {}

    public static boolean maskedEquals(long a, long b, long mask) {
        return (a & mask) == (b & mask);
    }

    public static boolean maskedEquals(byte a, byte b, byte mask) {
        return (a & mask) == (b & mask);
    }

    public static boolean maskedEquals(byte[] a, byte[] b, @Nullable byte[] mask) {
        if (a == null || b == null) return a == b;
        Preconditions.checkArgument(a.length == b.length, "Inputs must be of same size");
        if (mask == null) return Arrays.equals(a, b);
        Preconditions.checkArgument(a.length == mask.length, "Mask must be of same size as inputs");
        for (int i = 0; i < mask.length; i++) {
            if (!maskedEquals(a[i], b[i], mask[i])) return false;
        }
        return true;
    }

    public static boolean maskedEquals(UUID a, UUID b, @Nullable UUID mask) {
        if (mask == null) {
            return Objects.equal(a, b);
        }
        return maskedEquals(a.getLeastSignificantBits(), b.getLeastSignificantBits(),
                    mask.getLeastSignificantBits())
                && maskedEquals(a.getMostSignificantBits(), b.getMostSignificantBits(),
                    mask.getMostSignificantBits());
    }

    public static int[] unpackBits(long val) {
        int size = Long.bitCount(val);
        int[] result = new int[size];
        int index = 0;
        int bitPos = 0;
        while (val > 0) {
            if ((val & 1) == 1) result[index++] = bitPos;
            val = val >> 1;
            bitPos++;
        }
        return result;
    }

    public static long packBits(int[] bits) {
        long packed = 0;
        for (int b : bits) {
            packed |= (1 << b);
        }
        return packed;
    }
}
