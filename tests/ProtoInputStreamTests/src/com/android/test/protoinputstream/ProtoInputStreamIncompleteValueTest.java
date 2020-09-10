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

package com.android.test.protoinputstream;

import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoParseException;
import android.util.proto.ProtoStream;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ProtoInputStreamIncompleteValueTest extends TestCase {

    /**
     * Test that an incomplete varint at the end of a stream throws an exception
     */
    public void testIncompleteVarint() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_INT32;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 : varint -> invalid varint value
                (byte) 0x08,
                (byte) 0xff,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream);

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            try {
                switch (pi.getFieldNumber()) {
                    case (int) fieldId1:
                        pi.readInt(fieldId1);
                        fail("Should have thrown a ProtoParseException");
                        break;
                    default:
                        fail("Unexpected field id " + pi.getFieldNumber());
                }
            } catch (ProtoParseException ppe) {
                // good
                stream.close();
                return;
            }
        }
        stream.close();
        fail("Test should not have reached this point...");
    }

    /**
     * Test that an incomplete fixed64 at the end of a stream throws an exception
     */
    public void testIncompleteFixed64() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_FIXED64;

        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 2 -> invalid fixed64
                (byte) 0x11,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream);

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            try {
                switch (pi.getFieldNumber()) {
                    case (int) fieldId2:
                        pi.readLong(fieldId2);
                        fail("Should have thrown a ProtoParseException");
                        break;
                    default:
                        fail("Unexpected field id " + pi.getFieldNumber());
                }
            } catch (ProtoParseException ppe) {
                // good
                stream.close();
                return;
            }
        }
        stream.close();
        fail("Test should not have reached this point...");
    }

    /**
     * Test that an incomplete length delimited value at the end of a stream throws an exception
     */
    public void testIncompleteLengthDelimited() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_BYTES;

        final long fieldId5 = fieldFlags | ((long) 5 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 5 -> invalid byte array (has size 5 but only 4 values)
                (byte) 0x2a,
                (byte) 0x05,
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream);

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            try {
                switch (pi.getFieldNumber()) {
                    case (int) fieldId5:
                        pi.readBytes(fieldId5);
                        fail("Should have thrown a ProtoParseException");
                        break;
                    default:
                        fail("Unexpected field id " + pi.getFieldNumber());
                }
            } catch (ProtoParseException ppe) {
                // good
                stream.close();
                return;
            }
        }
        stream.close();
        fail("Test should not have reached this point...");
    }

    /**
     * Test that an incomplete fixed32 at the end of a stream throws an exception
     */
    public void testIncompleteFixed32() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_FIXED32;

        final long fieldId2 = fieldFlags | ((long) 2 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 2 ->  invalid fixed32
                (byte) 0x15,
                (byte) 0x01, (byte) 0x00, (byte) 0x00,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream);

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            try {
                switch (pi.getFieldNumber()) {
                    case (int) fieldId2:
                        pi.readInt(fieldId2);
                        fail("Should have thrown a ProtoParseException");
                        break;
                    default:
                        fail("Unexpected field id " + pi.getFieldNumber());
                }
            } catch (ProtoParseException ppe) {
                // good
                stream.close();
                return;
            }
        }
        stream.close();
        fail("Test should not have reached this point...");
    }
}
