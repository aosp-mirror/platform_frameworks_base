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
import android.text.TextUtils;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * A utility class for handling unsigned integers and unsigned arithmetics, as well as syntactic
 * sugar methods for {@link ByteBuffer}. Useful for networking and packet manipulations.
 * {@hide}
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class BitUtils {
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
            return Objects.equals(a, b);
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
        while (val != 0) {
            if ((val & 1) == 1) result[index++] = bitPos;
            val = val >>> 1;
            bitPos++;
        }
        return result;
    }

    public static long packBits(int[] bits) {
        long packed = 0;
        for (int b : bits) {
            packed |= (1L << b);
        }
        return packed;
    }

    public static int uint8(byte b) {
        return b & 0xff;
    }

    public static int uint16(short s) {
        return s & 0xffff;
    }

    public static int uint16(byte hi, byte lo) {
        return ((hi & 0xff) << 8) | (lo & 0xff);
    }

    public static long uint32(int i) {
        return i & 0xffffffffL;
    }

    public static int bytesToBEInt(byte[] bytes) {
        return (uint8(bytes[0]) << 24)
                + (uint8(bytes[1]) << 16)
                + (uint8(bytes[2]) << 8)
                + (uint8(bytes[3]));
    }

    public static int bytesToLEInt(byte[] bytes) {
        return Integer.reverseBytes(bytesToBEInt(bytes));
    }

    public static int getUint8(ByteBuffer buffer, int position) {
        return uint8(buffer.get(position));
    }

    public static int getUint16(ByteBuffer buffer, int position) {
        return uint16(buffer.getShort(position));
    }

    public static long getUint32(ByteBuffer buffer, int position) {
        return uint32(buffer.getInt(position));
    }

    public static void put(ByteBuffer buffer, int position, byte[] bytes) {
        final int original = buffer.position();
        buffer.position(position);
        buffer.put(bytes);
        buffer.position(original);
    }

    public static boolean isBitSet(long flags, int bitIndex) {
        return (flags & bitAt(bitIndex)) != 0;
    }

    public static long bitAt(int bitIndex) {
        return 1L << bitIndex;
    }

    public static String flagsToString(int flags, IntFunction<String> getFlagName) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        while (flags != 0) {
            final int flag = 1 << Integer.numberOfTrailingZeros(flags);
            flags &= ~flag;
            if (count > 0) builder.append(", ");
            builder.append(getFlagName.apply(flag));
            count++;
        }
        TextUtils.wrap(builder, "[", "]");
        return builder.toString();
    }

    /**
     * Converts long to byte array
     */
    public static byte[] toBytes(long l) {
        return ByteBuffer.allocate(8).putLong(l).array();
    }

    /**
     * 0b01000 -> 0b01111
     */
    public static int flagsUpTo(int lastFlag) {
        return lastFlag <= 0 ? 0 : lastFlag | flagsUpTo(lastFlag >> 1);
    }

    /**
     * 0b00010, 0b01000 -> 0b01110
     */
    public static int flagsWithin(int firstFlag, int lastFlag) {
        return (flagsUpTo(lastFlag) & ~flagsUpTo(firstFlag)) | firstFlag;
    }
}
