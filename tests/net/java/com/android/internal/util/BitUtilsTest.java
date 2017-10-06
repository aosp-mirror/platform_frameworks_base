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

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static com.android.internal.util.BitUtils.*;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BitUtilsTest {

    @Test
    public void testUnsignedByteWideningConversions() {
        byte b0 = 0;
        byte b1 = 1;
        byte bm1 = -1;
        assertEquals(0, uint8(b0));
        assertEquals(1, uint8(b1));
        assertEquals(127, uint8(Byte.MAX_VALUE));
        assertEquals(128, uint8(Byte.MIN_VALUE));
        assertEquals(255, uint8(bm1));
        assertEquals(255, uint8((byte)255));
    }

    @Test
    public void testUnsignedShortWideningConversions() {
        short s0 = 0;
        short s1 = 1;
        short sm1 = -1;
        assertEquals(0, uint16(s0));
        assertEquals(1, uint16(s1));
        assertEquals(32767, uint16(Short.MAX_VALUE));
        assertEquals(32768, uint16(Short.MIN_VALUE));
        assertEquals(65535, uint16(sm1));
        assertEquals(65535, uint16((short)65535));
    }

    @Test
    public void testUnsignedShortComposition() {
        byte b0 = 0;
        byte b1 = 1;
        byte b2 = 2;
        byte b10 = 10;
        byte b16 = 16;
        byte b128 = -128;
        byte b224 = -32;
        byte b255 = -1;
        assertEquals(0x0000, uint16(b0, b0));
        assertEquals(0xffff, uint16(b255, b255));
        assertEquals(0x0a01, uint16(b10, b1));
        assertEquals(0x8002, uint16(b128, b2));
        assertEquals(0x01ff, uint16(b1, b255));
        assertEquals(0x80ff, uint16(b128, b255));
        assertEquals(0xe010, uint16(b224, b16));
    }

    @Test
    public void testUnsignedIntWideningConversions() {
        assertEquals(0, uint32(0));
        assertEquals(1, uint32(1));
        assertEquals(2147483647L, uint32(Integer.MAX_VALUE));
        assertEquals(2147483648L, uint32(Integer.MIN_VALUE));
        assertEquals(4294967295L, uint32(-1));
        assertEquals(4294967295L, uint32((int)4294967295L));
    }

    @Test
    public void testBytesToInt() {
        assertEquals(0x00000000, bytesToBEInt(bytes(0, 0, 0, 0)));
        assertEquals(0xffffffff, bytesToBEInt(bytes(255, 255, 255, 255)));
        assertEquals(0x0a000001, bytesToBEInt(bytes(10, 0, 0, 1)));
        assertEquals(0x0a000002, bytesToBEInt(bytes(10, 0, 0, 2)));
        assertEquals(0x0a001fff, bytesToBEInt(bytes(10, 0, 31, 255)));
        assertEquals(0xe0000001, bytesToBEInt(bytes(224, 0, 0, 1)));

        assertEquals(0x00000000, bytesToLEInt(bytes(0, 0, 0, 0)));
        assertEquals(0x01020304, bytesToLEInt(bytes(4, 3, 2, 1)));
        assertEquals(0xffff0000, bytesToLEInt(bytes(0, 0, 255, 255)));
    }

    @Test
    public void testUnsignedGetters() {
      ByteBuffer b = ByteBuffer.allocate(4);
      b.putInt(0xffff);

      assertEquals(0x0, getUint8(b, 0));
      assertEquals(0x0, getUint8(b, 1));
      assertEquals(0xff, getUint8(b, 2));
      assertEquals(0xff, getUint8(b, 3));

      assertEquals(0x0, getUint16(b, 0));
      assertEquals(0xffff, getUint16(b, 2));

      b.rewind();
      b.putInt(0xffffffff);
      assertEquals(0xffffffffL, getUint32(b, 0));
    }

    static byte[] bytes(int b1, int b2, int b3, int b4) {
        return new byte[] {b(b1), b(b2), b(b3), b(b4)};
    }

    static byte b(int i) {
        return (byte) i;
    }
}
