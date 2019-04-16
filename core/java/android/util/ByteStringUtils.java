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

package android.util;

import libcore.util.HexEncoding;

/**
 * A utility class for common byte array to hex string operations and vise versa.
 *
 * @hide
 */
public final class ByteStringUtils {

    private ByteStringUtils() {
    /* hide constructor */
    }

    /**
     * Returns the hex encoded string representation of bytes.
     * @param bytes Byte array to encode.
     * @return Hex encoded string representation of bytes.
     */
    public static String toHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 2 != 0) {
            return null;
        }

        return HexEncoding.encodeToString(bytes, true /* upperCase */);
    }

    /**
     * Returns the decoded byte array representation of str.
     * @param str Hex encoded string to decode.
     * @return Decoded byte array representation of str.
     */
    public static byte[] fromHexToByteArray(String str) {
        if (str == null || str.length() == 0 || str.length() % 2 != 0) {
            return null;
        }

        return HexEncoding.decode(str, false /* allowSingleChar */);
    }
}
