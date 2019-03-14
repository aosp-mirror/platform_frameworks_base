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

public class ProtoInputStreamBoolTest extends TestCase {

    /**
     * Test reading single bool field
     */
    public void testRead() throws IOException {
        testRead(0);
        testRead(1);
        testRead(5);
    }

    /**
     * Implementation of testRead with a given chunkSize.
     */
    private void testRead(int chunkSize) throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BOOL;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 0 - default value, not written
                // 3 -> 1
                (byte) 0x18,
                (byte) 0x01,
                // 2 -> 1
                (byte) 0x10,
                (byte) 0x01,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);
        boolean[] results = new boolean[2];
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId1:
                    fail("Should never reach this");
                    break;
                case (int) fieldId2:
                    results[1] = pi.readBoolean(fieldId2);
                    break;
                case (int) fieldId3:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertEquals(false, results[0]);
        assertEquals(true, results[1]);
    }

    /**
     * Test that reading with ProtoInputStream matches, and can read the output of standard proto.
     */
    public void testReadCompat() throws Exception {
        testReadCompat(false);
        testReadCompat(true);
    }

    /**
     * Implementation of testReadCompat with a given value.
     */
    private void testReadCompat(boolean val) throws Exception {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BOOL;
        final long fieldId = fieldFlags | ((long) 130 & 0x0ffffffffL);

        final Test.All all = new Test.All();
        all.boolField = val;

        final byte[] proto = MessageNano.toByteArray(all);

        final ProtoInputStream pi = new ProtoInputStream(proto);
        final Test.All readback = Test.All.parseFrom(proto);

        boolean result = false; // start off with default value
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId:
                    result = pi.readBoolean(fieldId);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }

        assertEquals(readback.boolField, result);
    }


    /**
     * Test reading repeated bool field
     */
    public void testRepeated() throws IOException {
        testRepeated(0);
        testRepeated(1);
        testRepeated(5);
    }

    /**
     * Implementation of testRepeated with a given chunkSize.
     */
    private void testRepeated(int chunkSize) throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_REPEATED | ProtoStream.FIELD_TYPE_BOOL;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);
        final long fieldId4 = fieldFlags | ((long) 4 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 0 - default value, written when repeated
                (byte) 0x08,
                (byte) 0x00,
                // 2 -> 1
                (byte) 0x10,
                (byte) 0x01,

                // 4 -> 0
                (byte) 0x20,
                (byte) 0x00,
                // 4 -> 1
                (byte) 0x20,
                (byte) 0x01,

                // 1 -> 0 - default value, written when repeated
                (byte) 0x08,
                (byte) 0x00,
                // 2 -> 1
                (byte) 0x10,
                (byte) 0x01,

                // 3 -> 0
                (byte) 0x18,
                (byte) 0x00,
                // 3 -> 1
                (byte) 0x18,
                (byte) 0x01,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);
        boolean[][] results = new boolean[3][2];
        int[] indices = new int[3];
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {

            switch (pi.getFieldNumber()) {
                case (int) fieldId1:
                    results[0][indices[0]++] = pi.readBoolean(fieldId1);
                    break;
                case (int) fieldId2:
                    results[1][indices[1]++] = pi.readBoolean(fieldId2);
                    break;
                case (int) fieldId3:
                    results[2][indices[2]++] = pi.readBoolean(fieldId3);
                    break;
                case (int) fieldId4:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertEquals(false, results[0][0]);
        assertEquals(false, results[0][1]);
        assertEquals(true, results[1][0]);
        assertEquals(true, results[1][1]);
        assertEquals(false, results[2][0]);
        assertEquals(true, results[2][1]);
    }


    /**
     * Test that reading with ProtoInputStream matches, and can read the output of standard proto.
     */
    public void testRepeatedCompat() throws Exception {
        testRepeatedCompat(new boolean[0]);
        testRepeatedCompat(new boolean[]{false, true});
    }

    /**
     * Implementation of testRepeatedCompat with a given value.
     */
    private void testRepeatedCompat(boolean[] val) throws Exception {
        final long fieldFlags = ProtoStream.FIELD_COUNT_REPEATED | ProtoStream.FIELD_TYPE_BOOL;
        final long fieldId = fieldFlags | ((long) 131 & 0x0ffffffffL);

        final Test.All all = new Test.All();
        all.boolFieldRepeated = val;

        final byte[] proto = MessageNano.toByteArray(all);

        final ProtoInputStream pi = new ProtoInputStream(proto);
        final Test.All readback = Test.All.parseFrom(proto);

        boolean[] result = new boolean[val.length];
        int index = 0;
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId:
                    result[index++] = pi.readBoolean(fieldId);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }

        assertEquals(readback.boolFieldRepeated.length, result.length);
        for (int i = 0; i < result.length; i++) {
            assertEquals(readback.boolFieldRepeated[i], result[i]);
        }
    }

    /**
     * Test reading packed bool field
     */
    public void testPacked() throws IOException {
        testPacked(0);
        testPacked(1);
        testPacked(5);
    }

    /**
     * Implementation of testPacked with a given chunkSize.
     */
    public void testPacked(int chunkSize) throws IOException {

        final long fieldFlags = ProtoStream.FIELD_COUNT_PACKED | ProtoStream.FIELD_TYPE_BOOL;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);
        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);
        final long fieldId3 = fieldFlags | ((long) 3 & 0x0ffffffffL);
        final long fieldId4 = fieldFlags | ((long) 4 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 0 - default value, written when repeated
                (byte) 0x0a,
                (byte) 0x02,
                (byte) 0x00,
                (byte) 0x00,
                // 4 -> 0,1
                (byte) 0x22,
                (byte) 0x02,
                (byte) 0x00,
                (byte) 0x01,
                // 2 -> 1
                (byte) 0x12,
                (byte) 0x02,
                (byte) 0x01,
                (byte) 0x01,
                // 3 -> 0,1
                (byte) 0x1a,
                (byte) 0x02,
                (byte) 0x00,
                (byte) 0x01,
        };


        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);
        boolean[][] results = new boolean[3][2];
        int[] indices = new int[3];

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {

            switch (pi.getFieldNumber()) {
                case (int) fieldId1:
                    results[0][indices[0]++] = pi.readBoolean(fieldId1);
                    break;
                case (int) fieldId2:
                    results[1][indices[1]++] = pi.readBoolean(fieldId2);
                    break;
                case (int) fieldId3:
                    results[2][indices[2]++] = pi.readBoolean(fieldId3);
                    break;
                case (int) fieldId4:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertEquals(false, results[0][0]);
        assertEquals(false, results[0][1]);
        assertEquals(true, results[1][0]);
        assertEquals(true, results[1][1]);
        assertEquals(false, results[2][0]);
        assertEquals(true, results[2][1]);
    }


    /**
     * Test that reading with ProtoInputStream matches, and can read the output of standard proto.
     */
    public void testPackedCompat() throws Exception {
        testPackedCompat(new boolean[0]);
        testPackedCompat(new boolean[]{false, true});
    }

    /**
     * Implementation of testPackedCompat with a given value.
     */
    private void testPackedCompat(boolean[] val) throws Exception {
        final long fieldFlags = ProtoStream.FIELD_COUNT_PACKED | ProtoStream.FIELD_TYPE_BOOL;
        final long fieldId = fieldFlags | ((long) 132 & 0x0ffffffffL);

        final Test.All all = new Test.All();
        all.boolFieldPacked = val;

        final byte[] proto = MessageNano.toByteArray(all);

        final ProtoInputStream pi = new ProtoInputStream(proto);
        final Test.All readback = Test.All.parseFrom(proto);

        boolean[] result = new boolean[val.length];
        int index = 0;
        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) fieldId:
                    result[index++] = pi.readBoolean(fieldId);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }

        assertEquals(readback.boolFieldPacked.length, result.length);
        for (int i = 0; i < result.length; i++) {
            assertEquals(readback.boolFieldPacked[i], result[i]);
        }
    }

    /**
     * Test that using the wrong read method throws an exception
     */
    public void testBadReadType() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BOOL;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 1
                (byte) 0x08,
                (byte) 0x01,
        };

        ProtoInputStream pi = new ProtoInputStream(protobuf);
        pi.isNextField(fieldId1);
        try {
            pi.readFloat(fieldId1);
            fail("Should have throw IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.isNextField(fieldId1);
        try {
            pi.readDouble(fieldId1);
            fail("Should have throw IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.isNextField(fieldId1);
        try {
            pi.readInt(fieldId1);
            fail("Should have throw IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.isNextField(fieldId1);
        try {
            pi.readLong(fieldId1);
            fail("Should have throw IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.isNextField(fieldId1);
        try {
            pi.readBytes(fieldId1);
            fail("Should have throw IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }

        pi = new ProtoInputStream(protobuf);
        pi.isNextField(fieldId1);
        try {
            pi.readString(fieldId1);
            fail("Should have throw IllegalArgumentException");
        } catch (IllegalArgumentException iae) {
            // good
        }
    }

    /**
     * Test that unexpected wrong wire types will throw an exception
     */
    public void testBadWireType() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BOOL;

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
                        pi.readBoolean(fieldId1);
                        // don't fail, varint is ok
                        break;
                    case (int) fieldId2:
                        pi.readBoolean(fieldId2);
                        fail("Should have thrown a WireTypeMismatchException");
                        break;
                    case (int) fieldId3:
                        pi.readBoolean(fieldId3);
                        // don't fail, length delimited is ok (represents packed booleans)
                        break;
                    case (int) fieldId6:
                        pi.readBoolean(fieldId6);
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
