/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.tools.layoutlib.java;

import java.nio.CharBuffer;
import java.nio.charset.Charset;

/**
 * Defines the same class as the java.nio.charset.Charsets which was added in
 * Dalvik VM. This hack, provides a replacement for that class which can't be
 * loaded in the standard JVM since it's in the java package and standard JVM
 * doesn't have it. An implementation of the native methods in the original
 * class has been added.
 * <p/>
 * Extracted from API level 18, file:
 * platform/libcore/luni/src/main/java/java/nio/charset/Charsets
 */
public final class Charsets {
    /**
     * A cheap and type-safe constant for the ISO-8859-1 Charset.
     */
    public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    /**
     * A cheap and type-safe constant for the US-ASCII Charset.
     */
    public static final Charset US_ASCII = Charset.forName("US-ASCII");

    /**
     * A cheap and type-safe constant for the UTF-8 Charset.
     */
    public static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * Returns a new byte array containing the bytes corresponding to the given characters,
     * encoded in US-ASCII. Unrepresentable characters are replaced by (byte) '?'.
     */
    public static byte[] toAsciiBytes(char[] chars, int offset, int length) {
        CharBuffer cb = CharBuffer.allocate(length);
        cb.put(chars, offset, length);
        return US_ASCII.encode(cb).array();
    }

    /**
     * Returns a new byte array containing the bytes corresponding to the given characters,
     * encoded in ISO-8859-1. Unrepresentable characters are replaced by (byte) '?'.
     */
    public static byte[] toIsoLatin1Bytes(char[] chars, int offset, int length) {
        CharBuffer cb = CharBuffer.allocate(length);
        cb.put(chars, offset, length);
        return ISO_8859_1.encode(cb).array();
    }

    /**
     * Returns a new byte array containing the bytes corresponding to the given characters,
     * encoded in UTF-8. All characters are representable in UTF-8.
     */
    public static byte[] toUtf8Bytes(char[] chars, int offset, int length) {
        CharBuffer cb = CharBuffer.allocate(length);
        cb.put(chars, offset, length);
        return UTF_8.encode(cb).array();
    }

    /**
     * Returns a new byte array containing the bytes corresponding to the given characters,
     * encoded in UTF-16BE. All characters are representable in UTF-16BE.
     */
    public static byte[] toBigEndianUtf16Bytes(char[] chars, int offset, int length) {
        byte[] result = new byte[length * 2];
        int end = offset + length;
        int resultIndex = 0;
        for (int i = offset; i < end; ++i) {
            char ch = chars[i];
            result[resultIndex++] = (byte) (ch >> 8);
            result[resultIndex++] = (byte) ch;
        }
        return result;
    }

    /**
     * Decodes the given US-ASCII bytes into the given char[]. Equivalent to but faster than:
     *
     * for (int i = 0; i < count; ++i) {
     *     char ch = (char) (data[start++] & 0xff);
     *     value[i] = (ch <= 0x7f) ? ch : REPLACEMENT_CHAR;
     * }
     */
    public static void asciiBytesToChars(byte[] bytes, int offset, int length, char[] chars) {
        if (bytes == null || chars == null) {
            return;
        }
        final char REPLACEMENT_CHAR = (char)0xffd;
        int start = offset;
        for (int i = 0; i < length; ++i) {
            char ch = (char) (bytes[start++] & 0xff);
            chars[i] = (ch <= 0x7f) ? ch : REPLACEMENT_CHAR;
        }
    }

    /**
     * Decodes the given ISO-8859-1 bytes into the given char[]. Equivalent to but faster than:
     *
     * for (int i = 0; i < count; ++i) {
     *     value[i] = (char) (data[start++] & 0xff);
     * }
     */
    public static void isoLatin1BytesToChars(byte[] bytes, int offset, int length, char[] chars) {
        if (bytes == null || chars == null) {
            return;
        }
        int start = offset;
        for (int i = 0; i < length; ++i) {
            chars[i] = (char) (bytes[start++] & 0xff);
        }
    }

    private Charsets() {
    }
}
