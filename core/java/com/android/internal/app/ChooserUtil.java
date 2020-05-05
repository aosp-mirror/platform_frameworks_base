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

package com.android.internal.app;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility method for common computation operations for Share sheet.
 */
public class ChooserUtil {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * Hashes the given input based on MD5 algorithm.
     *
     * @return a string representation of the hash computation.
     */
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(input.getBytes(UTF_8));
            return convertBytesToHexString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Converts byte array input into an hex string. */
    private static String convertBytesToHexString(byte[] input) {
        char[] chars = new char[input.length * 2];
        for (int i = 0; i < input.length; i++) {
            byte b = input[i];
            chars[i * 2] = Character.forDigit((b >> 4) & 0xF, 16 /* radix */);
            chars[i * 2 + 1] = Character.forDigit(b & 0xF, 16 /* radix */);
        }
        return new String(chars);
    }

    private ChooserUtil() {}
}
