/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.integrity.utils;

public class TestUtils {

    public static String getBits(long component, int numOfBits) {
        return String.format("%" + numOfBits + "s", Long.toBinaryString(component))
                .replace(' ', '0');
    }

    public static String getValueBits(String value) {
        StringBuilder stringBuilder = new StringBuilder();
        for (byte valueByte : value.getBytes()) {
            stringBuilder.append(getBits(valueByte, /* numOfBits= */ 8));
        }
        return stringBuilder.toString();
    }

    public static byte[] getBytes(String bits) {
        int bitStringSize = bits.length();
        int numOfBytes = bitStringSize / 8;
        if (bitStringSize % 8 != 0) {
            numOfBytes++;
        }
        byte[] bytes = new byte[numOfBytes];
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) == '1') {
                bytes[i / 8] |= 1 << (7 - (i % 8));
            }
        }
        return bytes;
    }
}
