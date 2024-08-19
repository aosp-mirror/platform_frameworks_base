/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static com.android.internal.util.HexDump.hexStringToByteArray;
import static com.android.internal.util.HexDump.toHexString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
public final class HexDumpTest {
    @Test
    public void testBytesToHexString() {
        assertEquals("abcdef", HexDump.toHexString(
                new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef }, false));
        assertEquals("ABCDEF", HexDump.toHexString(
                new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef }, true));
    }

    @Test
    public void testNullByteArray() {
        assertThrows(
                NullPointerException.class,
                () -> HexDump.toHexString(null));
    }

    @Test
    public void testBytesToHexString_allByteValues() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 256);
        }

        StringBuilder sb = new StringBuilder();
        for (char firstChar : "0123456789ABCDEF".toCharArray()) {
            for (char secondChar : "0123456789ABCDEF".toCharArray()) {
                sb.append(firstChar).append(secondChar);
            }
        }
        String expected = sb.toString();

        assertEquals(expected, HexDump.toHexString(bytes));
    }

    @Test
    public void testRoundTrip_fromBytes() {
        Random deterministicRandom = new Random(31337); // arbitrary but deterministic
        for (int length = 0; length < 100; length++) {
            byte[] bytes = new byte[length];
            deterministicRandom.nextBytes(bytes);
            byte[] reconstruction = hexStringToByteArray(toHexString(bytes));

            assertBytesEqual(bytes, reconstruction);
        }
    }

    @Test
    public void testRoundTrip_fromString() {
        String hexString = "0123456789ABCDEF72f9a3438934c378d34f32a8b932";
        for (int length = 0; length < hexString.length(); length += 2) {
            String original = hexString.substring(0, length);
            String reconstruction = toHexString(hexStringToByteArray(original));
            assertEquals(original.toUpperCase(), reconstruction);
        }
    }

    @Test
    public void testToHexString_offsetLength() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < 16; i++) {
            bytes[i] = (byte) i;
            bytes[16 + i] = (byte) (16 * i);
        }
        String expected = "000102030405060708090A0B0C0D0E0F00102030405060708090A0B0C0D0E0F0";
        for (int offset = 0; offset < bytes.length; offset++) {
            for (int len = 0; len < (bytes.length - offset); len++) {

                byte[] subBytes = new byte[len];
                System.arraycopy(bytes, offset, subBytes, 0, len);

                String actual = toHexString(bytes, offset, len);
                assertEquals(expected.substring(2 * offset, 2 * offset + 2 * len), actual);
                assertEquals(toHexString(subBytes), actual);
            }
        }
    }

    @Test
    public void testToHexString_case() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < 16; i++) {
            bytes[i] = (byte) i;
            bytes[16 + i] = (byte) (16 * i);
        }

        String expected = "000102030405060708090A0B0C0D0E0F00102030405060708090A0B0C0D0E0F0";

        assertEquals(expected.toUpperCase(), toHexString(bytes, true));
        assertEquals(expected.toLowerCase(), toHexString(bytes, false));

        // default is uppercase
        assertEquals(expected.toUpperCase(), toHexString(bytes));
    }

    @Test
    public void testHexStringToByteArray_empty() {
        assertBytesEqual(new byte[0], HexDump.hexStringToByteArray(""));
    }

    @Test
    public void testHexStringToByteArray_null() {
        assertThrows(
                NullPointerException.class,
                () -> HexDump.hexStringToByteArray((String) null));
    }

    @Test
    public void testHexStringToByteArray_invalidCharacters() {
        // IllegalArgumentException would probably have been better than RuntimeException, but it
        // might be too late to change now.
        assertThrows(
                RuntimeException.class,
                () -> HexDump.hexStringToByteArray("GG"));
        assertThrows(
                RuntimeException.class,
                () -> HexDump.hexStringToByteArray("\0\0"));
        assertThrows(
                RuntimeException.class,
                () -> HexDump.hexStringToByteArray("abcdefgh"));
    }

    @Test
    public void testHexStringToByteArray_oddLength() {
        // IllegalArgumentException would probably have been better than
        // StringIndexOutOfBoundsException, but it might be too late to change now.
        assertThrows(
                StringIndexOutOfBoundsException.class,
                () -> HexDump.hexStringToByteArray("A"));
        assertThrows(
                StringIndexOutOfBoundsException.class,
                () -> HexDump.hexStringToByteArray("123"));
        assertThrows(
                StringIndexOutOfBoundsException.class,
                () -> HexDump.hexStringToByteArray("ABCDE"));
    }

    private static void assertBytesEqual(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail("Expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
        }
    }

    private static void assertThrows(Class<? extends RuntimeException> clazz, Runnable runnable) {
        try {
            runnable.run();
            fail();
        } catch (RuntimeException expected) {
            assertEquals(toStrackTrace(expected), clazz, expected.getClass());
        }
    }

    private static String toStrackTrace(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

}
