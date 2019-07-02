/*
 * Copyright (C) 2018 The Android Open Source Project
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

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ProtoInputStreamObjectTest extends TestCase {


    class SimpleObject {
        public char mChar;
        public char mLargeChar;
        public String mString;
        public SimpleObject mNested;

        void parseProto(ProtoInputStream pi) throws IOException {
            final long uintFieldFlags =
                    ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_UINT32;
            final long stringFieldFlags =
                    ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_STRING;
            final long messageFieldFlags =
                    ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_MESSAGE;
            final long charId = uintFieldFlags | ((long) 2 & 0x0ffffffffL);
            final long largeCharId = uintFieldFlags | ((long) 5000 & 0x0ffffffffL);
            final long stringId = stringFieldFlags | ((long) 4 & 0x0ffffffffL);
            final long nestedId = messageFieldFlags | ((long) 5 & 0x0ffffffffL);

            while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (pi.getFieldNumber()) {
                    case (int) charId:
                        mChar = (char) pi.readInt(charId);
                        break;
                    case (int) largeCharId:
                        mLargeChar = (char) pi.readInt(largeCharId);
                        break;
                    case (int) stringId:
                        mString = pi.readString(stringId);
                        break;
                    case (int) nestedId:
                        long token = pi.start(nestedId);
                        mNested = new SimpleObject();
                        mNested.parseProto(pi);
                        pi.end(token);
                        break;
                    default:
                        fail("Unexpected field id " + pi.getFieldNumber());
                }
            }
        }

    }

    /**
     * Test reading an object with one char in it.
     */
    public void testObjectOneChar() throws IOException {
        testObjectOneChar(0);
        testObjectOneChar(1);
        testObjectOneChar(5);
    }

    /**
     * Implementation of testObjectOneChar for a given chunkSize.
     */
    private void testObjectOneChar(int chunkSize) throws IOException {
        final long messageFieldFlags =
                ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_MESSAGE;

        final long messageId1 = messageFieldFlags | ((long) 1 & 0x0ffffffffL);
        final long messageId2 = messageFieldFlags | ((long) 2 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // Message 2 : { char 2 : 'c' }
                (byte) 0x12, (byte) 0x02, (byte) 0x10, (byte) 0x63,
                // Message 1 : { char 2 : 'b' }
                (byte) 0x0a, (byte) 0x02, (byte) 0x10, (byte) 0x62,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);

        SimpleObject result = null;

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) messageId1:
                    final long token = pi.start(messageId1);
                    result = new SimpleObject();
                    result.parseProto(pi);
                    pi.end(token);
                    break;
                case (int) messageId2:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertNotNull(result);
        assertEquals('b', result.mChar);
    }

    /**
     * Test reading an object with one multibyte unicode char in it.
     */
    public void testObjectOneLargeChar() throws IOException {
        testObjectOneLargeChar(0);
        testObjectOneLargeChar(1);
        testObjectOneLargeChar(5);
    }

    /**
     * Implementation of testObjectOneLargeChar for a given chunkSize.
     */
    private void testObjectOneLargeChar(int chunkSize) throws IOException {
        final long messageFieldFlags =
                ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_MESSAGE;

        final long messageId1 = messageFieldFlags | ((long) 1 & 0x0ffffffffL);
        final long messageId2 = messageFieldFlags | ((long) 2 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // Message 2 : { char 5000 : '\u3110' }
                (byte) 0x12, (byte) 0x05, (byte) 0xc0, (byte) 0xb8,
                (byte) 0x02, (byte) 0x90, (byte) 0x62,
                // Message 1 : { char 5000 : '\u3110' }
                (byte) 0x0a, (byte) 0x05, (byte) 0xc0, (byte) 0xb8,
                (byte) 0x02, (byte) 0x90, (byte) 0x62,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);

        SimpleObject result = null;

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) messageId1:
                    final long token = pi.start(messageId1);
                    result = new SimpleObject();
                    result.parseProto(pi);
                    pi.end(token);
                    break;
                case (int) messageId2:
                    // Intentionally don't read the data. Parse should continue normally
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertNotNull(result);
        assertEquals('\u3110', result.mLargeChar);
    }

    /**
     * Test reading a char, then an object, then a char.
     */
    public void testObjectAndTwoChars() throws IOException {
        testObjectAndTwoChars(0);
        testObjectAndTwoChars(1);
        testObjectAndTwoChars(5);
    }

    /**
     * Implementation of testObjectAndTwoChars for a given chunkSize.
     */
    private void testObjectAndTwoChars(int chunkSize) throws IOException  {
        final long uintFieldFlags =
                ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_UINT32;
        final long messageFieldFlags =
                ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_MESSAGE;

        final long charId1 = uintFieldFlags | ((long) 1 & 0x0ffffffffL);
        final long messageId2 = messageFieldFlags | ((long) 2 & 0x0ffffffffL);
        final long charId4 = uintFieldFlags | ((long) 4 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 'a'
                (byte) 0x08, (byte) 0x61,
                // Message 1 : { char 2 : 'b' }
                (byte) 0x12, (byte) 0x02, (byte) 0x10, (byte) 0x62,
                // 4 -> 'c'
                (byte) 0x20, (byte) 0x63,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);

        SimpleObject obj = null;
        char char1 = '\0';
        char char4 = '\0';

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) charId1:
                    char1 = (char) pi.readInt(charId1);
                    break;
                case (int) messageId2:
                    final long token = pi.start(messageId2);
                    obj = new SimpleObject();
                    obj.parseProto(pi);
                    pi.end(token);
                    break;
                case (int) charId4:
                    char4 = (char) pi.readInt(charId4);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertEquals('a', char1);
        assertNotNull(obj);
        assertEquals('b', obj.mChar);
        assertEquals('c', char4);
    }

    /**
     * Test reading a char, then an object with an int and a string in it, then a char.
     */
    public void testComplexObject() throws IOException {
        testComplexObject(0);
        testComplexObject(1);
        testComplexObject(5);
    }

    /**
     * Implementation of testComplexObject for a given chunkSize.
     */
    private void testComplexObject(int chunkSize) throws IOException  {
        final long uintFieldFlags =
                ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_UINT32;
        final long messageFieldFlags =
                ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_MESSAGE;

        final long charId1 = uintFieldFlags | ((long) 1 & 0x0ffffffffL);
        final long messageId2 = messageFieldFlags | ((long) 2 & 0x0ffffffffL);
        final long charId4 = uintFieldFlags | ((long) 4 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> 'x'
                (byte) 0x08, (byte) 0x78,
                // begin object 2
                (byte) 0x12, (byte) 0x10,
                // 2 -> 'y'
                (byte) 0x10, (byte) 0x79,
                // 4 -> "abcdefghijkl"
                (byte) 0x22, (byte) 0x0c,
                (byte) 0x61, (byte) 0x62, (byte) 0x63, (byte) 0x64, (byte) 0x65, (byte) 0x66,
                (byte) 0x67, (byte) 0x68, (byte) 0x69, (byte) 0x6a, (byte) 0x6b, (byte) 0x6c,
                // 4 -> 'z'
                (byte) 0x20, (byte) 0x7a,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);

        SimpleObject obj = null;
        char char1 = '\0';
        char char4 = '\0';

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) charId1:
                    char1 = (char) pi.readInt(charId1);
                    break;
                case (int) messageId2:
                    final long token = pi.start(messageId2);
                    obj = new SimpleObject();
                    obj.parseProto(pi);
                    pi.end(token);
                    break;
                case (int) charId4:
                    char4 = (char) pi.readInt(charId4);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertEquals('x', char1);
        assertNotNull(obj);
        assertEquals('y', obj.mChar);
        assertEquals("abcdefghijkl", obj.mString);
        assertEquals('z', char4);
    }

    /**
     * Test reading 3 levels deep of objects.
     */
    public void testDeepObjects() throws IOException {
        testDeepObjects(0);
        testDeepObjects(1);
        testDeepObjects(5);
    }

    /**
     * Implementation of testDeepObjects for a given chunkSize.
     */
    private void testDeepObjects(int chunkSize) throws IOException  {
        final long messageFieldFlags =
                ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_MESSAGE;
        final long messageId2 = messageFieldFlags | ((long) 2 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // begin object id 2
                (byte) 0x12, (byte) 0x1a,
                // 2 -> 'a'
                (byte) 0x10, (byte) 0x61,
                // begin nested object id 5
                (byte) 0x2a, (byte) 0x15,
                // 5000 -> '\u3110'
                (byte) 0xc0, (byte) 0xb8,
                (byte) 0x02, (byte) 0x90, (byte) 0x62,
                // begin nested object id 5
                (byte) 0x2a, (byte) 0x0e,
                // 4 -> "abcdefghijkl"
                (byte) 0x22, (byte) 0x0c,
                (byte) 0x61, (byte) 0x62, (byte) 0x63, (byte) 0x64, (byte) 0x65, (byte) 0x66,
                (byte) 0x67, (byte) 0x68, (byte) 0x69, (byte) 0x6a, (byte) 0x6b, (byte) 0x6c,
        };

        InputStream stream = new ByteArrayInputStream(protobuf);
        final ProtoInputStream pi = new ProtoInputStream(stream, chunkSize);

        SimpleObject obj = null;

        while (pi.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pi.getFieldNumber()) {
                case (int) messageId2:
                    final long token = pi.start(messageId2);
                    obj = new SimpleObject();
                    obj.parseProto(pi);
                    pi.end(token);
                    break;
                default:
                    fail("Unexpected field id " + pi.getFieldNumber());
            }
        }
        stream.close();

        assertNotNull(obj);
        assertEquals('a', obj.mChar);
        assertNotNull(obj.mNested);
        assertEquals('\u3110', obj.mNested.mLargeChar);
        assertNotNull(obj.mNested.mNested);
        assertEquals("abcdefghijkl", obj.mNested.mNested.mString);
    }

    /**
     * Test that using the wrong read method throws an exception
     */
    public void testBadReadType() throws IOException {
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_MESSAGE;

        final long fieldId1 = fieldFlags | ((long) 1 & 0x0ffffffffL);

        final byte[] protobuf = new byte[]{
                // 1 -> {1}
                (byte) 0x0a,
                (byte) 0x01,
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
            pi.readBoolean(fieldId1);
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
        final long fieldFlags = ProtoStream.FIELD_COUNT_SINGLE | ProtoStream.FIELD_TYPE_MESSAGE;

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
