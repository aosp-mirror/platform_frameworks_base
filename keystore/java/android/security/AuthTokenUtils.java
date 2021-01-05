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

package android.security;

import android.annotation.NonNull;
import android.hardware.security.keymint.HardwareAuthToken;
import android.hardware.security.secureclock.Timestamp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @hide This Utils class provides method(s) for AuthToken conversion.
 */
public class AuthTokenUtils {

    private AuthTokenUtils(){
    }

    /**
     * Build a HardwareAuthToken from a byte array
     * @param array byte array representing an auth token
     * @return HardwareAuthToken representation of an auth token
     */
    public static @NonNull HardwareAuthToken toHardwareAuthToken(@NonNull byte[] array) {
        final HardwareAuthToken hardwareAuthToken = new HardwareAuthToken();

        // First byte is version, which does not exist in HardwareAuthToken anymore
        // Next 8 bytes is the challenge.
        hardwareAuthToken.challenge =
                ByteBuffer.wrap(array, 1, 8).order(ByteOrder.nativeOrder()).getLong();

        // Next 8 bytes is the userId
        hardwareAuthToken.userId =
                ByteBuffer.wrap(array, 9, 8).order(ByteOrder.nativeOrder()).getLong();

        // Next 8 bytes is the authenticatorId.
        hardwareAuthToken.authenticatorId =
                ByteBuffer.wrap(array, 17, 8).order(ByteOrder.nativeOrder()).getLong();

        // while the other fields are in machine byte order, authenticatorType and timestamp
        // are in network byte order.
        // Next 4 bytes is the authenticatorType.
        hardwareAuthToken.authenticatorType =
                ByteBuffer.wrap(array, 25, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        // Next 8 bytes is the timestamp.
        final Timestamp timestamp = new Timestamp();
        timestamp.milliSeconds =
                ByteBuffer.wrap(array, 29, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        hardwareAuthToken.timestamp = timestamp;

        // Last 32 bytes is the mac, 37:69
        hardwareAuthToken.mac = new byte[32];
        System.arraycopy(array, 37 /* srcPos */,
                hardwareAuthToken.mac,
                0 /* destPos */,
                32 /* length */);

        return hardwareAuthToken;
    }
}
