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
import java.util.Arrays;

public class ProtoInputStreamBytesTest extends TestCase {

    public void testRead() throws IOException {
        testRead(0);
        testRead(1);
        testRead(5);
    }

    private void testRead(int chunkSize) throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BYTES;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);
        final long fieldId4 = fieldFlags | ((long) 4 & 0x0ffffffffL);
        final long fieldId5 = fieldFlags | ((long) 5 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> null - default value, written when repeated
                (byte) 0x0a,
                (byte) 0x00,
                // 2 -> { } - default value, written when repeated
                (byte) 0x12,
                (byte) 0x00,
                // 5 -> { 0, 1, 2, 3, 4 }
                (byte) 0x2a,
                (byte) 0x05,
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04,
                // 3 -> { 0, 1, 2, 3, 4, 5 }
                (byte) 0x1a,
                (byte) 0x06,
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05,
                // 4 -> { (byte)0xff, (byte)0xfe, (byte)0xfd, (byte)0xfc }
                (byte) 0x22,
                (byte) 0x04,
                (byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);
        byte[][] results = new byte[4][];
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {

            switch (pi.getFieldNumber()) {
                case (int) fieldId1:
                    results[0] = pi.readBytes(fieldId1);
                    break;
                case (int) fieldId2:
                    results[1] = pi.readBytes(fieldId2);
                    break;
                case (int) fieldId3:
                    results[2] = pi.readBytes(fieldId3);
                    break;
                case (int) fieldId4:
                    results[3] = pi.readBytes(fieldId4);
                    break;
                case (int) fieldId5:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertTrue("Expected: []  Actual: " + Arrays.toString(results[0]),
                Arrays.equals(new byte[]{}, results[0]));
        assertTrue("Expected: []  Actual: " + Arrays.toString(results[1]),
                Arrays.equals(new byte[]{}, results[1]));
        assertTrue("Expected: [0, 1, 2, 3, 4, 5]  Actual: " + Arrays.toString(results[2]),
                Arrays.equals(new byte[]{0, 1, 2, 3, 4, 5}, results[2]));
        assertTrue("Expected: [-1, -2, -3, -4]  Actual: " + Arrays.toString(results[3]),
                Arrays.equals(new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc},
                        results[3]));
    }


    /**
     * Test that reading with ProtoInputStream matches, and can read the output of standard proto.
     */
    public void testReadCompat() throws Exception {
        testReadCompat(new byte[0]);
        testReadCompat(new byte[]{1, 2, 3, 4});
        testReadCompat(new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc});
    }

    /**
     * Implementation of testReadCompat with a given value.
     */
    private void testReadCompat(byte[] val) throws Exception {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BYTES;
        final long fieldId = fieldFlags | ((long) 150 & 0x0ffffffffL);

        final Test.All all = new Test.All();
        all.bytesField = val;

        final byte[] proto = MessageNano.toByteArray(all);

        final ProtoInputStream pi = new ProtoInputStream(proto);
        final Test.All readback = Test.All.parseFrom(proto);

        byte[] result = new byte[val.length];
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId:
                    result = pi.readBytes(fieldId);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }

        assertEquals(readback.bytesField.length, result.length);
        for (int i = 0; i < result.length; i++) {
            assertEquals(readback.bytesField[i], result[i]);
        }
    }


    public void testRepeated() throws IOException {
        testRepeated(0);
        testRepeated(1);
        testRepeated(5);
    }

    private void testRepeated(int chunkSize) throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_REPEATED | ProtoStream.FIELD_TYPE_BYTES;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);
        final long fieldId4 = fieldFlags | ((long) 4 & 0x0ffffffffL);
        final long fieldId5 = fieldFlags | ((long) 5 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> null - default value, written when repeated
                (byte) 0x0a,
                (byte) 0x00,
                // 2 -> { } - default value, written when repeated
                (byte) 0x12,
                (byte) 0x00,
                // 3 -> { 0, 1, 2, 3, 4, 5 }
                (byte) 0x1a,
                (byte) 0x06,
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05,
                // 4 -> { (byte)0xff, (byte)0xfe, (byte)0xfd, (byte)0xfc }
                (byte) 0x22,
                (byte) 0x04,
                (byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc,

                // 5 -> { 0, 1, 2, 3, 4}
                (byte) 0x2a,
                (byte) 0x05,
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04,

                // 1 -> null - default value, written when repeated
                (byte) 0x0a,
                (byte) 0x00,
                // 2 -> { } - default value, written when repeated
                (byte) 0x12,
                (byte) 0x00,
                // 3 -> { 0, 1, 2, 3, 4, 5 }
                (byte) 0x1a,
                (byte) 0x06,
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05,
                // 4 -> { (byte)0xff, (byte)0xfe, (byte)0xfd, (byte)0xfc }
                (byte) 0x22,
                (byte) 0x04,
                (byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);
        byte[][][] results = new byte[4][2][];
        int[] indices = new int[4];
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {

            switch (pi.getFieldNumber()) {
                case (int) fieldId1:
                    results[0][indices[0]++] = pi.readBytes(fieldId1);
                    break;
                case (int) fieldId2:
                    results[1][indices[1]++] = pi.readBytes(fieldId2);
                    break;
                case (int) fieldId3:
                    results[2][indices[2]++] = pi.readBytes(fieldId3);
                    break;
                case (int) fieldId4:
                    results[3][indices[3]++] = pi.readBytes(fieldId4);
                    break;
                case (int) fieldId5:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assert (Arrays.equals(new byte[]{}, results[0][0]));
        assert (Arrays.equals(new byte[]{}, results[0][1]));
        assert (Arrays.equals(new byte[]{}, results[1][0]));
        assert (Arrays.equals(new byte[]{}, results[1][1]));
        assert (Arrays.equals(new byte[]{1, 2, 3, 4}, results[2][0]));
        assert (Arrays.equals(new byte[]{1, 2, 3, 4}, results[2][1]));
        assert (Arrays.equals(new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc},
                results[3][0]));
        assert (Arrays.equals(new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc},
                results[3][1]));
    }

    /**
     * Test that reading with ProtoInputStream matches, and can read the output of standard proto.
     */
    public void testRepeatedCompat() throws Exception {
        testRepeatedCompat(new byte[0][]);
        testRepeatedCompat(new byte[][]{
                new byte[0],
                new byte[]{1, 2, 3, 4},
                new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc}
        });
    }

    /**
     * Implementation of testRepeatedCompat with a given value.
     */
    private void testRepeatedCompat(byte[][] val) throws Exception {
        final long fieldFlags = ProtoStream.FIELD_COUNT_REPEATED | ProtoStream.FIELD_TYPE_BYTES;
        final long fieldId = fieldFlags | ((long) 151 & 0x0ffffffffL);

        final Test.All all = new Test.All();
        all.bytesFieldRepeated = val;

        final byte[] proto = MessageNano.toByteArray(all);

        final ProtoInputStream pi = new ProtoInputStream(proto);
        final Test.All readback = Test.All.parseFrom(proto);

        byte[][] result = new byte[val.length][]; // start off with default value
        int index = 0;
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId:
                    result[index++] = pi.readBytes(fieldId);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }

        assertEquals(readback.bytesFieldRepeated.length, result.length);
        for (int i = 0; i < result.length; i++) {
            assertEquals(readback.bytesFieldRepeated[i].length, result[i].length);
            for (int j = 0; j < result[i].length; j++) {
                assertEquals(readback.bytesFieldRepeated[i][j], result[i][j]);
            }
        }
    }

    /**
     * Test that using the wrong read method throws an exception
     */
    public void testBadReadType() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BYTES;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> {1}
                (byte) 0x0a,
                (byte) 0x01,
                (byte) 0x01,
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
            pi.readLong(fieldId1);
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
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BYTES;

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
                (byte) 0x01,
                (byte) 0x01,
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
                        pi.readBytes(fieldId1);
                        fail("Should have thrown a WireTypeMismatchException");
                        break;
                    case (int) fieldId2:
                        pi.readBytes(fieldId2);
                        fail("Should have thrown a WireTypeMismatchException");
                        break;
                    case (int) fieldId3:
                        pi.readBytes(fieldId3);
                        // don't fail, length delimited is ok
                        break;
                    case (int) fieldId6:
                        pi.readBytes(fieldId6);
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
