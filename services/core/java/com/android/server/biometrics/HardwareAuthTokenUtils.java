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

package com.android.server.biometrics;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

import android.hardware.keymaster.HardwareAuthToken;
import android.hardware.keymaster.Timestamp;

import java.nio.ByteOrder;

/**
 * Utilities for converting between old and new HardwareAuthToken types. See
 * {@link HardwareAuthToken}.
 */
public class HardwareAuthTokenUtils {
    public static byte[] toByteArray(HardwareAuthToken hat) {
        final byte[] array = new byte[69];

        // Version, first byte. Used in hw_auth_token.h but not HardwareAuthToken
        array[0] = 0;

        // Challenge, 1:8.
        writeLong(hat.challenge, array, 1 /* offset */);

        // UserId, 9:16.
        writeLong(hat.userId, array, 9 /* offset */);

        // AuthenticatorId, 17:24.
        writeLong(hat.authenticatorId, array, 17 /* offset */);

        // AuthenticatorType, 25:28.
        writeInt(flipIfNativelyLittle(hat.authenticatorType), array, 25 /* offset */);

        // Timestamp, 29:36.
        writeLong(flipIfNativelyLittle(hat.timestamp.milliSeconds), array, 29 /* offset */);

        // MAC, 37:69. Byte array.
        System.arraycopy(hat.mac, 0 /* srcPos */, array, 37 /* destPos */, hat.mac.length);

        return array;
    }

    public static HardwareAuthToken toHardwareAuthToken(byte[] array) {
        final HardwareAuthToken hardwareAuthToken = new HardwareAuthToken();

        // First byte is version, which doesn't not exist in HardwareAuthToken anymore
        // Next 8 bytes is the challenge.
        hardwareAuthToken.challenge = getLong(array, 1 /* offset */);

        // Next 8 bytes is the userId
        hardwareAuthToken.userId = getLong(array, 9 /* offset */);

        // Next 8 bytes is the authenticatorId.
        hardwareAuthToken.authenticatorId = getLong(array, 17 /* offset */);

        // Next 4 bytes is the authenticatorType.
        hardwareAuthToken.authenticatorType = flipIfNativelyLittle(getInt(array, 25 /* offset */));

        // Next 8 bytes is the timestamp.
        final Timestamp timestamp = new Timestamp();
        timestamp.milliSeconds = flipIfNativelyLittle(getLong(array, 29 /* offset */));
        hardwareAuthToken.timestamp = timestamp;

        // Last 32 bytes is the mac, 37:69
        hardwareAuthToken.mac = new byte[32];
        System.arraycopy(array, 37 /* srcPos */,
                hardwareAuthToken.mac,
                0 /* destPos */,
                32 /* length */);

        return hardwareAuthToken;
    }

    private static long flipIfNativelyLittle(long l) {
        if (LITTLE_ENDIAN == ByteOrder.nativeOrder()) {
            return Long.reverseBytes(l);
        }
        return l;
    }

    private static int flipIfNativelyLittle(int i) {
        if (LITTLE_ENDIAN == ByteOrder.nativeOrder()) {
            return Integer.reverseBytes(i);
        }
        return i;
    }

    private static void writeLong(long l, byte[] dest, int offset) {
        dest[offset + 0] = (byte) l;
        dest[offset + 1] = (byte) (l >> 8);
        dest[offset + 2] = (byte) (l >> 16);
        dest[offset + 3] = (byte) (l >> 24);
        dest[offset + 4] = (byte) (l >> 32);
        dest[offset + 5] = (byte) (l >> 40);
        dest[offset + 6] = (byte) (l >> 48);
        dest[offset + 7] = (byte) (l >> 56);
    }

    private static void writeInt(int i, byte[] dest, int offset) {
        dest[offset + 0] = (byte) i;
        dest[offset + 1] = (byte) (i >> 8);
        dest[offset + 2] = (byte) (i >> 16);
        dest[offset + 3] = (byte) (i >> 24);
    }

    private static long getLong(byte[] array, int offset) {
        long result = 0;
        // Lowest bit is LSB
        for (int i = 0; i < 8; i++) {
            result += (long) ((array[i + offset] & 0xffL) << (8 * i));
        }
        return result;
    }

    private static int getInt(byte[] array, int offset) {
        int result = 0;
        // Lowest bit is LSB
        for (int i = 0; i < 4; i++) {
            result += (int) (((int) array[i + offset] & 0xff) << (8 * i));
        }
        return result;
    }
}
