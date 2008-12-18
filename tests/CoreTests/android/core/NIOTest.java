/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import junit.framework.TestCase;

import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests for some buffers from the java.nio package.
 */
public class NIOTest extends TestCase {

    void checkBuffer(Buffer b) {
        assertTrue(0 <= b.position());
        assertTrue(b.position() <= b.limit());
        assertTrue(b.limit() <= b.capacity());
    }

    @SmallTest
    public void testNIO() throws Exception {
        ByteBuffer b;

        // Test byte array-based buffer
        b = ByteBuffer.allocate(12);
        byteBufferTest(b);

        // Test native heap-allocated buffer
        b = ByteBuffer.allocateDirect(12);
        byteBufferTest(b);

        // Test short array-based buffer
        short[] shortArray = new short[8];
        ShortBuffer sb = ShortBuffer.wrap(shortArray);
        shortBufferTest(sb);

        // Test int array-based buffer
        int[] intArray = new int[8];
        IntBuffer ib = IntBuffer.wrap(intArray);
        intBufferTest(ib);

        // Test float array-based buffer
        float[] floatArray = new float[8];
        FloatBuffer fb = FloatBuffer.wrap(floatArray);
        floatBufferTest(fb);
    }

    private void byteBufferTest(ByteBuffer b) {
        checkBuffer(b);

        // Bounds checks
        try {
            b.put(-1, (byte) 0);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        try {
            b.put(b.limit(), (byte) 0);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // IndexOutOfBoundsException: offset < 0
        try {
            byte[] data = new byte[8];
            b.position(0);
            b.put(data, -1, 2);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // IndexOutOfBoundsException: length > array.length - offset
        try {
            byte[] data = new byte[8];
            b.position(0);
            b.put(data, 1, 8);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // BufferOverflowException: length > remaining()
        try {
            byte[] data = new byte[8];
            b.position(b.limit() - 2);
            b.put(data, 0, 3);
            fail("expected exception not thrown");
        } catch (BufferOverflowException e) {
            // expected
        }

        // Fill buffer with bytes A0 A1 A2 A3 ...
        b.position(0);
        for (int i = 0; i < b.capacity(); i++) {
            b.put((byte) (0xA0 + i));
        }
        try {
            b.put((byte) 0xFF);
            fail("expected exception not thrown");
        } catch (BufferOverflowException e) {
            // expected
        }

        b.position(0);
        assertEquals((byte) 0xA7, b.get(7));
        try {
            b.get(12);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }
        try {
            b.get(-10);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        b.position(0);
        b.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((byte) 0xA0, b.get());
        assertEquals((byte) 0xA1, b.get());
        assertEquals((byte) 0xA2, b.get());
        assertEquals((byte) 0xA3, b.get());
        assertEquals((byte) 0xA4, b.get());
        assertEquals((byte) 0xA5, b.get());
        assertEquals((byte) 0xA6, b.get());
        assertEquals((byte) 0xA7, b.get());
        assertEquals((byte) 0xA8, b.get());
        assertEquals((byte) 0xA9, b.get());
        assertEquals((byte) 0xAA, b.get());
        assertEquals((byte) 0xAB, b.get());
        try {
            b.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        b.position(0);
        b.order(ByteOrder.BIG_ENDIAN);
        assertEquals((byte) 0xA0, b.get());
        assertEquals((byte) 0xA1, b.get());
        assertEquals((byte) 0xA2, b.get());
        assertEquals((byte) 0xA3, b.get());
        assertEquals((byte) 0xA4, b.get());
        assertEquals((byte) 0xA5, b.get());
        assertEquals((byte) 0xA6, b.get());
        assertEquals((byte) 0xA7, b.get());
        assertEquals((byte) 0xA8, b.get());
        assertEquals((byte) 0xA9, b.get());
        assertEquals((byte) 0xAA, b.get());
        assertEquals((byte) 0xAB, b.get());
        try {
            b.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        b.position(6);
        b.limit(10);
        assertEquals((byte) 0xA6, b.get());

        // Check sliced buffer
        b.position(6);

        ByteBuffer bb = b.slice();
        checkBuffer(bb);

        assertEquals(0, bb.position());
        assertEquals(4, bb.limit());
        assertEquals(4, bb.capacity());

        assertEquals((byte) 0xA6, bb.get());
        assertEquals((byte) 0xA7, bb.get());
        assertEquals((byte) 0xA8, bb.get());
        assertEquals((byte) 0xA9, bb.get());
        try {
            bb.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        // Reset position and limit
        b.position(0);
        b.limit(b.capacity());

        // Check 'getShort'
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.position(0);
        assertEquals((short) 0xA1A0, b.getShort());
        assertEquals((short) 0xA3A2, b.getShort());
        assertEquals((short) 0xA5A4, b.getShort());
        assertEquals((short) 0xA7A6, b.getShort());
        assertEquals((short) 0xA9A8, b.getShort());
        assertEquals((short) 0xABAA, b.getShort());
        try {
            bb.getShort();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        b.order(ByteOrder.BIG_ENDIAN);
        b.position(0);
        assertEquals((short) 0xA0A1, b.getShort());
        assertEquals((short) 0xA2A3, b.getShort());
        assertEquals((short) 0xA4A5, b.getShort());
        assertEquals((short) 0xA6A7, b.getShort());
        assertEquals((short) 0xA8A9, b.getShort());
        assertEquals((short) 0xAAAB, b.getShort());
        try {
            bb.getShort();
           fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        // Check 'getInt'
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.position(0);
        assertEquals(0xA3A2A1A0, b.getInt());
        assertEquals(0xA7A6A5A4, b.getInt());
        assertEquals(0xABAAA9A8, b.getInt());
        try {
            bb.getInt();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        b.order(ByteOrder.BIG_ENDIAN);
        b.position(0);
        assertEquals(0xA0A1A2A3, b.getInt());
        assertEquals(0xA4A5A6A7, b.getInt());
        assertEquals(0xA8A9AAAB, b.getInt());
        try {
            bb.getInt();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        // Check 'getFloat'
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.position(0);
        assertEquals(0xA3A2A1A0, Float.floatToIntBits(b.getFloat()));
        assertEquals(0xA7A6A5A4, Float.floatToIntBits(b.getFloat()));
        assertEquals(0xABAAA9A8, Float.floatToIntBits(b.getFloat()));
        try {
            b.getFloat();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        b.order(ByteOrder.BIG_ENDIAN);
        b.position(0);
        assertEquals(0xA0A1A2A3, Float.floatToIntBits(b.getFloat()));
        assertEquals(0xA4A5A6A7, Float.floatToIntBits(b.getFloat()));
        assertEquals(0xA8A9AAAB, Float.floatToIntBits(b.getFloat()));
        try {
            b.getFloat();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        // Check 'getDouble(int position)'
        b.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0xA7A6A5A4A3A2A1A0L, Double.doubleToLongBits(b.getDouble(0)));
        assertEquals(0xA8A7A6A5A4A3A2A1L, Double.doubleToLongBits(b.getDouble(1)));
        try {
            b.getDouble(-1);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }
        try {
            b.getDouble(5);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        b.order(ByteOrder.BIG_ENDIAN);
        assertEquals(0xA0A1A2A3A4A5A6A7L, Double.doubleToLongBits(b.getDouble(0)));
        assertEquals(0xA1A2A3A4A5A6A7A8L, Double.doubleToLongBits(b.getDouble(1)));
        try {
            b.getDouble(-1);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }
        try {
            b.getDouble(5);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // Slice and check 'getInt'
        b.position(1);
        b.limit(5);
        b.order(ByteOrder.LITTLE_ENDIAN);
        bb = b.slice();
        assertEquals(4, bb.capacity());
        assertEquals(0xA4A3A2A1, bb.getInt(0));

        bb.order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer sb = bb.asShortBuffer();

        checkBuffer(sb);
        assertEquals(2, sb.capacity());
        assertEquals((short) 0xA2A1, sb.get());
        assertEquals((short) 0xA4A3, sb.get());

        bb.order(ByteOrder.BIG_ENDIAN);
        sb = bb.asShortBuffer();

        checkBuffer(sb);
        assertEquals(2, sb.capacity());
        assertEquals((short) 0xA1A2, sb.get());
        assertEquals((short) 0xA3A4, sb.get());

        bb.order(ByteOrder.LITTLE_ENDIAN);
        IntBuffer ib = bb.asIntBuffer();

        checkBuffer(ib);
        assertEquals(1, ib.capacity());
        assertEquals(0xA4A3A2A1, ib.get());

        bb.order(ByteOrder.BIG_ENDIAN);
        ib = bb.asIntBuffer();

        checkBuffer(ib);
        assertEquals(1, ib.capacity());
        assertEquals(0xA1A2A3A4, ib.get());

        bb.order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = bb.asFloatBuffer();

        checkBuffer(fb);
        assertEquals(1, fb.capacity());
        assertEquals(0xA4A3A2A1, Float.floatToIntBits(fb.get()));

        bb.order(ByteOrder.BIG_ENDIAN);
        fb = bb.asFloatBuffer();

        checkBuffer(fb);
        assertEquals(1, fb.capacity());
        assertEquals(0xA1A2A3A4, Float.floatToIntBits(fb.get()));
    }

    private void shortBufferTest(ShortBuffer sb) {
        checkBuffer(sb);

        try {
            sb.put(-1, (short) 0);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        try {
            sb.put(sb.limit(), (short) 0);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // IndexOutOfBoundsException: offset < 0
        try {
            short[] data = new short[8];
            sb.position(0);
            sb.put(data, -1, 2);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // IndexOutOfBoundsException: length > array.length - offset
        try {
            short[] data = new short[8];
            sb.position(0);
            sb.put(data, 1, 8);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // BufferOverflowException: length > remaining()
        try {
            short[] data = new short[8];
            sb.position(sb.limit() - 2);
            sb.put(data, 0, 3);
            fail("expected exception not thrown");
        } catch (BufferOverflowException e) {
            // expected
        }

        short[] data = {0, 10, 20, 30, 40, 50, 60, 70};
        sb.position(0);
        sb.put(data);

        try {
            sb.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        sb.position(0);
        assertEquals((short) 0, sb.get());
        assertEquals((short) 10, sb.get());
        assertEquals((short) 20, sb.get());
        assertEquals((short) 30, sb.get());
        assertEquals((short) 40, sb.get());
        assertEquals((short) 50, sb.get());
        assertEquals((short) 60, sb.get());
        assertEquals((short) 70, sb.get());
        try {
            sb.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }
        sb.position(1);
        sb.put((short) 11);
        assertEquals((short) 11, sb.get(1));

        short[] ss1 = {33, 44, 55, 66};
        sb.position(3);
        sb.put(ss1);
        sb.position(0);
        assertEquals((short) 0, sb.get());
        assertEquals((short) 11, sb.get());
        assertEquals((short) 20, sb.get());
        assertEquals((short) 33, sb.get());
        assertEquals((short) 44, sb.get());
        assertEquals((short) 55, sb.get());
        assertEquals((short) 66, sb.get());
        assertEquals((short) 70, sb.get());

        short[] ss2 = {10, 22, 30};
        sb.position(2);
        sb.put(ss2, 1, 1);
        sb.position(0);
        assertEquals((short) 0, sb.get());
        assertEquals((short) 11, sb.get());
        assertEquals((short) 22, sb.get());
        assertEquals((short) 33, sb.get());
        assertEquals((short) 44, sb.get());
        assertEquals((short) 55, sb.get());
        assertEquals((short) 66, sb.get());
        assertEquals((short) 70, sb.get());
    }

    private void intBufferTest(IntBuffer ib) {
        checkBuffer(ib);

        try {
            ib.put(-1, (int) 0);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        try {
            ib.put(ib.limit(), (int) 0);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // IndexOutOfBoundsException: offset < 0
        try {
            int[] data = new int[8];
            ib.position(0);
            ib.put(data, -1, 2);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // IndexOutOfBoundsException: length > array.length - offset
        try {
            int[] data = new int[8];
            ib.position(0);
            ib.put(data, 1, 8);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // BufferOverflowException: length > remaining()
        try {
            int[] data = new int[8];
            ib.position(ib.limit() - 2);
            ib.put(data, 0, 3);
            fail("expected exception not thrown");
        } catch (BufferOverflowException e) {
            // expected
        }

        int[] data = {0, 10, 20, 30, 40, 50, 60, 70};
        ib.position(0);
        ib.put(data);

        try {
            ib.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        ib.position(0);
        assertEquals((int) 0, ib.get());
        assertEquals((int) 10, ib.get());
        assertEquals((int) 20, ib.get());
        assertEquals((int) 30, ib.get());
        assertEquals((int) 40, ib.get());
        assertEquals((int) 50, ib.get());
        assertEquals((int) 60, ib.get());
        assertEquals((int) 70, ib.get());
        try {
            ib.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }
        ib.position(1);
        ib.put((int) 11);
        assertEquals((int) 11, ib.get(1));

        int[] ss1 = {33, 44, 55, 66};
        ib.position(3);
        ib.put(ss1);
        ib.position(0);
        assertEquals((int) 0, ib.get());
        assertEquals((int) 11, ib.get());
        assertEquals((int) 20, ib.get());
        assertEquals((int) 33, ib.get());
        assertEquals((int) 44, ib.get());
        assertEquals((int) 55, ib.get());
        assertEquals((int) 66, ib.get());
        assertEquals((int) 70, ib.get());

        int[] ss2 = {10, 22, 30};
        ib.position(2);
        ib.put(ss2, 1, 1);
        ib.position(0);
        assertEquals((int) 0, ib.get());
        assertEquals((int) 11, ib.get());
        assertEquals((int) 22, ib.get());
        assertEquals((int) 33, ib.get());
        assertEquals((int) 44, ib.get());
        assertEquals((int) 55, ib.get());
        assertEquals((int) 66, ib.get());
        assertEquals((int) 70, ib.get());
    }

    void floatBufferTest(FloatBuffer fb) {
        checkBuffer(fb);

        try {
            fb.put(-1, (float) 0);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        try {
            fb.put(fb.limit(), (float) 0);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // IndexOutOfBoundsException: offset < 0
        try {
            float[] data = new float[8];
            fb.position(0);
            fb.put(data, -1, 2);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // IndexOutOfBoundsException: length > array.length - offset
        try {
            float[] data = new float[8];
            fb.position(0);
            fb.put(data, 1, 8);
            fail("expected exception not thrown");
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        // BufferOverflowException: length > remaining()
        try {
            float[] data = new float[8];
            fb.position(fb.limit() - 2);
            fb.put(data, 0, 3);
            fail("expected exception not thrown");
        } catch (BufferOverflowException e) {
            // expected
        }

        float[] data = {0, 10, 20, 30, 40, 50, 60, 70};
        fb.position(0);
        fb.put(data);

        try {
            fb.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }

        fb.position(0);
        assertEquals((float) 0, fb.get());
        assertEquals((float) 10, fb.get());
        assertEquals((float) 20, fb.get());
        assertEquals((float) 30, fb.get());
        assertEquals((float) 40, fb.get());
        assertEquals((float) 50, fb.get());
        assertEquals((float) 60, fb.get());
        assertEquals((float) 70, fb.get());
        try {
            fb.get();
            fail("expected exception not thrown");
        } catch (BufferUnderflowException e) {
            // expected
        }
        fb.position(1);
        fb.put((float) 11);
        assertEquals((float) 11, fb.get(1));

        float[] ss1 = {33, 44, 55, 66};
        fb.position(3);
        fb.put(ss1);
        fb.position(0);
        assertEquals((float) 0, fb.get());
        assertEquals((float) 11, fb.get());
        assertEquals((float) 20, fb.get());
        assertEquals((float) 33, fb.get());
        assertEquals((float) 44, fb.get());
        assertEquals((float) 55, fb.get());
        assertEquals((float) 66, fb.get());
        assertEquals((float) 70, fb.get());

        float[] ss2 = {10, 22, 30};
        fb.position(2);
        fb.put(ss2, 1, 1);
        fb.position(0);
        assertEquals((float) 0, fb.get());
        assertEquals((float) 11, fb.get());
        assertEquals((float) 22, fb.get());
        assertEquals((float) 33, fb.get());
        assertEquals((float) 44, fb.get());
        assertEquals((float) 55, fb.get());
        assertEquals((float) 66, fb.get());
        assertEquals((float) 70, fb.get());
    }
}
