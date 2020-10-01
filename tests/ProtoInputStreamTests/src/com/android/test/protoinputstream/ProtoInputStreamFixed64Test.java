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

package com.android.test.protoinputstream;

import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoStream;
import android.util.proto.WireTypeMismatchException;

import com.android.test.protoinputstream.nano.Test;

import com.google.protobuf.nano.MessageNano;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ProtoInputStreamFixed64Test extends TestCase {

    public void testRead() throws IOException {
        testRead(0);
        testRead(1);
        testRead(5);
    }

    private void testRead(int chunkSize) throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_FIXED64;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);
        final long fieldId4 = fieldFlags | ((long) 4 & 0x0ffffffffL);
        final long fieldId5 = fieldFlags | ((long) 5 & 0x0ffffffffL);
        final long fieldId6 = fieldFlags | ((long) 6 & 0x0ffffffffL);
        final long fieldId7 = fieldFlags | ((long) 7 & 0x0ffffffffL);
        final long fieldId8 = fieldFlags | ((long) 8 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 0 - default value, not written
                // 2 -> 1
                (byte) 0x11,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 6 -> 1
                (byte) 0x41,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 3 -> -1
                (byte) 0x19,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // 4 -> Integer.MIN_VALUE
                (byte) 0x21,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // 5 -> Integer.MAX_VALUE
                (byte) 0x29,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 6 -> Long.MIN_VALUE
                (byte) 0x31,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                // 7 -> Long.MAX_VALUE
                (byte) 0x39,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);
        long[] results = new long[7];
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId1:
                    fail("Should never reach this");
                    break;
                case (int) fieldId2:
                    results[1] = pi.readLong(fieldId2);
                    break;
                case (int) fieldId3:
                    results[2] = pi.readLong(fieldId3);
                    break;
                case (int) fieldId4:
                    results[3] = pi.readLong(fieldId4);
                    break;
                case (int) fieldId5:
                    results[4] = pi.readLong(fieldId5);
                    break;
                case (int) fieldId6:
                    results[5] = pi.readLong(fieldId6);
                    break;
                case (int) fieldId7:
                    results[6] = pi.readLong(fieldId7);
                    break;
                case (int) fieldId8:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertEquals(0, results[0]);
        assertEquals(1, results[1]);
        assertEquals(-1, results[2]);
        assertEquals(Integer.MIN_VALUE, results[3]);
        assertEquals(Integer.MAX_VALUE, results[4]);
        assertEquals(Long.MIN_VALUE, results[5]);
        assertEquals(Long.MAX_VALUE, results[6]);
    }

    /**
     * Test that reading with ProtoInputStream matches, and can read the output of standard proto.
     */
    public void testReadCompat() throws Exception {
        testReadCompat(0);
        testReadCompat(1);
        testReadCompat(-1);
        testReadCompat(Integer.MIN_VALUE);
        testReadCompat(Integer.MAX_VALUE);
        testReadCompat(Long.MIN_VALUE);
        testReadCompat(Long.MAX_VALUE);
    }

    /**
     * Implementation of testReadCompat with a given value.
     */
    private void testReadCompat(long val) throws Exception {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_FIXED64;
        final long fieldId = fieldFlags | ((long) 100 & 0x0ffffffffL);

        final Test.All all = new Test.All();
        all.fixed64Field = val;

        final byte[] proto = MessageNano.toByteArray(all);

        final ProtoInputStream pi = new ProtoInputStream(proto);
        final Test.All readback = Test.All.parseFrom(proto);

        long result = 0; // start off with default value
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId:
                    result = pi.readLong(fieldId);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }

        assertEquals(readback.fixed64Field, result);
    }

    public void testRepeated() throws IOException {
        testRepeated(0);
        testRepeated(1);
        testRepeated(5);
    }

    private void testRepeated(int chunkSize) throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_REPEATED | ProtoStream.FIELD_TYPE_FIXED64;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);
        final long fieldId4 = fieldFlags | ((long) 4 & 0x0ffffffffL);
        final long fieldId5 = fieldFlags | ((long) 5 & 0x0ffffffffL);
        final long fieldId6 = fieldFlags | ((long) 6 & 0x0ffffffffL);
        final long fieldId7 = fieldFlags | ((long) 7 & 0x0ffffffffL);
        final long fieldId8 = fieldFlags | ((long) 8 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 0 - default value, written when repeated
                (byte) 0x09,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 2 -> 1
                (byte) 0x11,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 3 -> -1
                (byte) 0x19,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // 4 -> Integer.MIN_VALUE
                (byte) 0x21,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // 5 -> Integer.MAX_VALUE
                (byte) 0x29,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 6 -> Long.MIN_VALUE
                (byte) 0x31,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                // 7 -> Long.MAX_VALUE
                (byte) 0x39,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,

                // 8 -> 1
                (byte) 0x41,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,

                // 1 -> 0 - default value, written when repeated
                (byte) 0x09,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 2 -> 1
                (byte) 0x11,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 3 -> -1
                (byte) 0x19,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // 4 -> Integer.MIN_VALUE
                (byte) 0x21,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // 5 -> Integer.MAX_VALUE
                (byte) 0x29,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 6 -> Long.MIN_VALUE
                (byte) 0x31,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                // 7 -> Long.MAX_VALUE
                (byte) 0x39,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);
        long[][] results = new long[7][2];
        int[] indices = new int[7];
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {

            switch (pi.getFieldNumber()) {
                case (int) fieldId1:
                    results[0][indices[0]++] = pi.readLong(fieldId1);
                    break;
                case (int) fieldId2:
                    results[1][indices[1]++] = pi.readLong(fieldId2);
                    break;
                case (int) fieldId3:
                    results[2][indices[2]++] = pi.readLong(fieldId3);
                    break;
                case (int) fieldId4:
                    results[3][indices[3]++] = pi.readLong(fieldId4);
                    break;
                case (int) fieldId5:
                    results[4][indices[4]++] = pi.readLong(fieldId5);
                    break;
                case (int) fieldId6:
                    results[5][indices[5]++] = pi.readLong(fieldId6);
                    break;
                case (int) fieldId7:
                    results[6][indices[6]++] = pi.readLong(fieldId7);
                    break;
                case (int) fieldId8:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertEquals(0, results[0][0]);
        assertEquals(0, results[0][1]);
        assertEquals(1, results[1][0]);
        assertEquals(1, results[1][1]);
        assertEquals(-1, results[2][0]);
        assertEquals(-1, results[2][1]);
        assertEquals(Integer.MIN_VALUE, results[3][0]);
        assertEquals(Integer.MIN_VALUE, results[3][1]);
        assertEquals(Integer.MAX_VALUE, results[4][0]);
        assertEquals(Integer.MAX_VALUE, results[4][1]);
        assertEquals(Long.MIN_VALUE, results[5][0]);
        assertEquals(Long.MIN_VALUE, results[5][1]);
        assertEquals(Long.MAX_VALUE, results[6][0]);
        assertEquals(Long.MAX_VALUE, results[6][1]);
    }

    /**
     * Test that reading with ProtoInputStream matches, and can read the output of standard proto.
     */
    public void testRepeatedCompat() throws Exception {
        testRepeatedCompat(new long[0]);
        testRepeatedCompat(new long[]{0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE});
    }

    /**
     * Implementation of testRepeatedCompat with a given value.
     */
    private void testRepeatedCompat(long[] val) throws Exception {
        final long fieldFlags = ProtoStream.FIELD_COUNT_REPEATED | ProtoStream.FIELD_TYPE_FIXED64;
        final long fieldId = fieldFlags | ((long) 101 & 0x0ffffffffL);

        final Test.All all = new Test.All();
        all.fixed64FieldRepeated = val;

        final byte[] proto = MessageNano.toByteArray(all);

        final ProtoInputStream pi = new ProtoInputStream(proto);
        final Test.All readback = Test.All.parseFrom(proto);

        long[] result = new long[val.length];
        int index = 0;
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId:
                    result[index++] = pi.readLong(fieldId);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }

        assertEquals(readback.fixed64FieldRepeated.length, result.length);
        for (int i = 0; i < result.length; i++) {
            assertEquals(readback.fixed64FieldRepeated[i], result[i]);
        }
    }

    public void testPacked() throws IOException {
        testPacked(0);
        testPacked(1);
        testPacked(5);
    }

    private void testPacked(int chunkSize) throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_PACKED | ProtoStream.FIELD_TYPE_FIXED64;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);
        final long fieldId4 = fieldFlags | ((long) 4 & 0x0ffffffffL);
        final long fieldId5 = fieldFlags | ((long) 5 & 0x0ffffffffL);
        final long fieldId6 = fieldFlags | ((long) 6 & 0x0ffffffffL);
        final long fieldId7 = fieldFlags | ((long) 7 & 0x0ffffffffL);
        final long fieldId8 = fieldFlags | ((long) 8 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 0 - default value, written when repeated
                (byte) 0x0a,
                (byte) 0x10,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 2 -> 1
                (byte) 0x12,
                (byte) 0x10,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 8 -> 1
                (byte) 0x42,
                (byte) 0x10,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 3 -> -1
                (byte) 0x1a,
                (byte) 0x10,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // 4 -> Integer.MIN_VALUE
                (byte) 0x22,
                (byte) 0x10,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // 5 -> Integer.MAX_VALUE
                (byte) 0x2a,
                (byte) 0x10,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 6 -> Long.MIN_VALUE
                (byte) 0x32,
                (byte) 0x10,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x80,
                // 7 -> Long.MAX_VALUE
                (byte) 0x3a,
                (byte) 0x10,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);
        long[][] results = new long[7][2];
        int[] indices = new int[7];
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {

            switch (pi.getFieldNumber()) {
                case (int) fieldId1:
                    results[0][indices[0]++] = pi.readLong(fieldId1);
                    break;
                case (int) fieldId2:
                    results[1][indices[1]++] = pi.readLong(fieldId2);
                    break;
                case (int) fieldId3:
                    results[2][indices[2]++] = pi.readLong(fieldId3);
                    break;
                case (int) fieldId4:
                    results[3][indices[3]++] = pi.readLong(fieldId4);
                    break;
                case (int) fieldId5:
                    results[4][indices[4]++] = pi.readLong(fieldId5);
                    break;
                case (int) fieldId6:
                    results[5][indices[5]++] = pi.readLong(fieldId6);
                    break;
                case (int) fieldId7:
                    results[6][indices[6]++] = pi.readLong(fieldId7);
                    break;
                case (int) fieldId8:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertEquals(0, results[0][0]);
        assertEquals(0, results[0][1]);
        assertEquals(1, results[1][0]);
        assertEquals(1, results[1][1]);
        assertEquals(-1, results[2][0]);
        assertEquals(-1, results[2][1]);
        assertEquals(Integer.MIN_VALUE, results[3][0]);
        assertEquals(Integer.MIN_VALUE, results[3][1]);
        assertEquals(Integer.MAX_VALUE, results[4][0]);
        assertEquals(Integer.MAX_VALUE, results[4][1]);
        assertEquals(Long.MIN_VALUE, results[5][0]);
        assertEquals(Long.MIN_VALUE, results[5][1]);
        assertEquals(Long.MAX_VALUE, results[6][0]);
        assertEquals(Long.MAX_VALUE, results[6][1]);
    }

    /**
     * Test that reading with ProtoInputStream matches, and can read the output of standard proto.
     */
    public void testPackedCompat() throws Exception {
        testPackedCompat(new long[0]);
        testPackedCompat(new long[]{0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE});
    }

    /**
     * Implementation of testRepeatedCompat with a given value.
     */
    private void testPackedCompat(long[] val) throws Exception {
        final long fieldFlags = ProtoStream.FIELD_COUNT_REPEATED | ProtoStream.FIELD_TYPE_FIXED64;
        final long fieldId = fieldFlags | ((long) 102 & 0x0ffffffffL);

        final Test.All all = new Test.All();
        all.fixed64FieldPacked = val;

        final byte[] proto = MessageNano.toByteArray(all);

        final ProtoInputStream pi = new ProtoInputStream(proto);
        final Test.All readback = Test.All.parseFrom(proto);

        long[] result = new long[val.length];
        int index = 0;
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId:
                    result[index++] = pi.readLong(fieldId);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }

        assertEquals(readback.fixed64FieldPacked.length, result.length);
        for (int i = 0; i < result.length; i++) {
            assertEquals(readback.fixed64FieldPacked[i], result[i]);
        }
    }

    /**
     * Test that using the wrong read method throws an exception
     */
    public void testBadReadType() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_FIXED64;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 1
                (byte) 0x09,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        };

        ProtoInputStream pi = new ProtoInputStream(protobuf);
        pi.nextField();
        try {
            pi.readFloat(fieldId1);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.nextField();
        try {
            pi.readDouble(fieldId1);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.nextField();
        try {
            pi.readInt(fieldId1);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.nextField();
        try {
            pi.readBoolean(fieldId1);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.nextField();
        try {
            pi.readBytes(fieldId1);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.nextField();
        try {
            pi.readString(fieldId1);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }
    }

    /**
     * Test that unexpected wrong wire types will throw an exception
     */
    public void testBadWireType() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_FIXED64;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);
        final long fieldId6 = fieldFlags | ((long) 6 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 : varint -> 1
                (byte) 0x08,
                (byte) 0x01,
                // 2 : fixed64 -> 0x1
                (byte) 0x11,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 3 : length delimited -> { 1 }
                (byte) 0x1a,
                (byte) 0x08,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // 6 : fixed32
                (byte) 0x35,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream);

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            try {
                switch (pi.getFieldNumber()) {
                    case (int) fieldId1:
                        pi.readLong(fieldId1);
                        fail("Should have thrown a WireTypeMismatchException");
                        break;
                    case (int) fieldId2:
                        pi.readLong(fieldId2);
                        // don't fail, fixed64 is ok
                        break;
                    case (int) fieldId3:
                        pi.readLong(fieldId3);
                        // don't fail, length delimited is ok (represents packed fixed64)
                        break;
                    case (int) fieldId6:
                        pi.readLong(fieldId6);
                        fail("Should have thrown a WireTypeMismatchException");
                        break;
                    default:
                        fail("Unexpected field id " + pi.getFieldNumber());
                }
            } catch (WireTypeMismatchException wtme) {
                // good
            }
        }
        stream.close();
    }
}
