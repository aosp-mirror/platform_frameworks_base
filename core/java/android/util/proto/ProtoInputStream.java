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

package android.util.proto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Class to read to a protobuf stream.
 *
 * Each read method takes an ID code from the protoc generated classes
 * and return a value of the field. To read a nested object, call #start
 * and then #end when you are done.
 *
 * The ID codes have type information embedded into them, so if you call
 * the incorrect function you will get an IllegalArgumentException.
 *
 * nextField will return the field number of the next field, which can be
 * matched to the protoc generated ID code and used to determine how to
 * read the next field.
 *
 * It is STRONGLY RECOMMENDED to read from the ProtoInputStream with a switch
 * statement wrapped in a while loop. Additionally, it is worth logging or
 * storing unexpected fields or ones that do not match the expected wire type
 *
 * ex:
 * void parseFromProto(ProtoInputStream stream) {
 *     while(stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
 *         try {
 *             switch (stream.getFieldNumber()) {
 *                 case (int) DummyProto.NAME:
 *                     mName = stream.readString(DummyProto.NAME);
 *                     break;
 *                 case (int) DummyProto.VALUE:
 *                     mValue = stream.readInt(DummyProto.VALUE);
 *                     break;
 *                 default:
 *                     LOG(TAG, "Unhandled field in proto!\n"
 *                              + ProtoUtils.currentFieldToString(stream));
 *             }
 *         } catch (WireTypeMismatchException wtme) {
 *             LOG(TAG, "Wire Type mismatch in proto!\n" + ProtoUtils.currentFieldToString(stream));
 *         }
 *     }
 * }
 *
 * @hide
 */
public final class ProtoInputStream extends ProtoStream {

    public static final int NO_MORE_FIELDS = -1;

    /**
     * Our stream.  If there is one.
     */
    private InputStream mStream;

    /**
     * The field number of the current field. Will be equal to NO_MORE_FIELDS if end of message is
     * reached
     */
    private int mFieldNumber;

    /**
     * The wire type of the current field
     */
    private int mWireType;

    private static final byte STATE_STARTED_FIELD_READ = 1 << 0;
    private static final byte STATE_READING_PACKED = 1 << 1;
    private static final byte STATE_FIELD_MISS = 2 << 1;

    /**
     * Tracks some boolean states for the proto input stream
     * bit 0: Started Field Read, true - tag has been read, ready to read field data.
     * false - field data has been read, reading to start next field.
     * bit 1: Reading Packed Field, true - currently reading values from a packed field
     * false - not reading from packed field.
     */
    private byte mState = 0;

    /**
     * Keeps track of the currently read nested Objects, for end object sanity checking and debug
     */
    private ArrayList<Long> mExpectedObjectTokenStack = null;

    /**
     * Current nesting depth of start calls.
     */
    private int mDepth = -1;

    /**
     * Buffer for the to be read data. If mStream is not null, it will be constantly refilled from
     * the stream.
     */
    private byte[] mBuffer;

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    /**
     * Size of the buffer if reading from a stream.
     */
    private final int mBufferSize;

    /**
     * The number of bytes that have been skipped or dropped from the buffer.
     */
    private int mDiscardedBytes = 0;

    /**
     * Current offset in the buffer
     * mOffset + mDiscardedBytes = current offset in proto binary
     */
    private int mOffset = 0;

    /**
     * Note the offset of the last byte in the buffer. Usually will equal the size of the buffer.
     * mEnd + mDiscardedBytes = the last known byte offset + 1
     */
    private int mEnd = 0;

    /**
     * Packed repeated fields are not read in one go. mPackedEnd keeps track of where the packed
     * field ends in the proto binary if current field is packed.
     */
    private int mPackedEnd = 0;

    /**
     * Construct a ProtoInputStream on top of an InputStream to read a proto. Also specify the
     * number of bytes the ProtoInputStream will buffer from the input stream
     *
     * @param stream from which the proto is read
     */
    public ProtoInputStream(InputStream stream, int bufferSize) {
        mStream = stream;
        if (bufferSize > 0) {
            mBufferSize = bufferSize;
        } else {
            mBufferSize = DEFAULT_BUFFER_SIZE;
        }
        mBuffer = new byte[mBufferSize];
    }

    /**
     * Construct a ProtoInputStream on top of an InputStream to read a proto
     *
     * @param stream from which the proto is read
     */
    public ProtoInputStream(InputStream stream) {
        this(stream, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Construct a ProtoInputStream to read a proto directly from a byte array
     *
     * @param buffer - the byte array to be parsed
     */
    public ProtoInputStream(byte[] buffer) {
        mBufferSize = buffer.length;
        mEnd = buffer.length;
        mBuffer = buffer;
        mStream = null;
    }

    /**
     * Get the field number of the current field.
     */
    public int getFieldNumber() {
        return mFieldNumber;
    }

    /**
     * Get the wire type of the current field.
     *
     * @return an int that matches one of the ProtoStream WIRE_TYPE_ constants
     */
    public int getWireType() {
        if ((mState & STATE_READING_PACKED) == STATE_READING_PACKED) {
            // mWireType got overwritten when STATE_READING_PACKED was set. Send length delimited
            // constant instead
            return WIRE_TYPE_LENGTH_DELIMITED;
        }
        return mWireType;
    }

    /**
     * Get the current offset in the proto binary.
     */
    public int getOffset() {
        return mOffset + mDiscardedBytes;
    }

    /**
     * Reads the tag of the next field from the stream. If previous field value was not read, its
     * data will be skipped over.
     *
     * @return the field number of the next field
     * @throws IOException if an I/O error occurs
     */
    public int nextField() throws IOException {

        if ((mState & STATE_FIELD_MISS) == STATE_FIELD_MISS) {
            // Data from the last nextField was not used, reuse the info
            mState &= ~STATE_FIELD_MISS;
            return mFieldNumber;
        }
        if ((mState & STATE_STARTED_FIELD_READ) == STATE_STARTED_FIELD_READ) {
            // Field data was not read, skip to the next field
            skip();
            mState &= ~STATE_STARTED_FIELD_READ;
        }
        if ((mState & STATE_READING_PACKED) == STATE_READING_PACKED) {
            if (getOffset() < mPackedEnd) {
                // In the middle of a packed field, return the same tag until last packed value
                // has been read
                mState |= STATE_STARTED_FIELD_READ;
                return mFieldNumber;
            } else if (getOffset() == mPackedEnd) {
                // Reached the end of the packed field
                mState &= ~STATE_READING_PACKED;
            } else {
                throw new ProtoParseException(
                        "Unexpectedly reached end of packed field at offset 0x"
                                + Integer.toHexString(mPackedEnd)
                                + dumpDebugData());
            }
        }

        if ((mDepth >= 0) && (getOffset() == getOffsetFromToken(
                mExpectedObjectTokenStack.get(mDepth)))) {
            // reached end of a embedded message
            mFieldNumber = NO_MORE_FIELDS;
        } else {
            readTag();
        }
        return mFieldNumber;
    }

    /**
     * Reads the tag of the next field from the stream. If previous field value was not read, its
     * data will be skipped over. If {@code fieldId} matches the next field ID, the field data will
     * be ready to read. If it does not match, {@link #nextField()} or {@link #nextField(long)} will
     * need to be called again before the field data can be read.
     *
     * @return true if fieldId matches the next field, false if not
     */
    public boolean nextField(long fieldId) throws IOException {
        if (nextField() == (int) fieldId) {
            return true;
        }
        // Note to reuse the info from the nextField call in the next call.
        mState |= STATE_FIELD_MISS;
        return false;
    }

    /**
     * Read a single double.
     * Will throw if the current wire type is not fixed64
     *
     * @param fieldId - must match the current field number and field type
     */
    public double readDouble(long fieldId) throws IOException {
        assertFreshData();
        assertFieldNumber(fieldId);
        checkPacked(fieldId);

        double value;
        switch ((int) ((fieldId & FIELD_TYPE_MASK)
                >>> FIELD_TYPE_SHIFT)) {
            case (int) (FIELD_TYPE_DOUBLE >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_FIXED64);
                value = Double.longBitsToDouble(readFixed64());
                break;
            default:
                throw new IllegalArgumentException(
                        "Requested field id (" + getFieldIdString(fieldId)
                                + ") cannot be read as a double"
                                + dumpDebugData());
        }
        // Successfully read the field
        mState &= ~STATE_STARTED_FIELD_READ;
        return value;
    }

    /**
     * Read a single float.
     * Will throw if the current wire type is not fixed32
     *
     * @param fieldId - must match the current field number and field type
     */
    public float readFloat(long fieldId) throws IOException {
        assertFreshData();
        assertFieldNumber(fieldId);
        checkPacked(fieldId);

        float value;
        switch ((int) ((fieldId & FIELD_TYPE_MASK)
                >>> FIELD_TYPE_SHIFT)) {
            case (int) (FIELD_TYPE_FLOAT >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_FIXED32);
                value = Float.intBitsToFloat(readFixed32());
                break;
            default:
                throw new IllegalArgumentException(
                        "Requested field id (" + getFieldIdString(fieldId) + ") is not a float"
                                + dumpDebugData());
        }
        // Successfully read the field
        mState &= ~STATE_STARTED_FIELD_READ;
        return value;
    }

    /**
     * Read a single 32bit or varint proto type field as an int.
     * Will throw if the current wire type is not varint or fixed32
     *
     * @param fieldId - must match the current field number and field type
     */
    public int readInt(long fieldId) throws IOException {
        assertFreshData();
        assertFieldNumber(fieldId);
        checkPacked(fieldId);

        int value;
        switch ((int) ((fieldId & FIELD_TYPE_MASK)
                >>> FIELD_TYPE_SHIFT)) {
            case (int) (FIELD_TYPE_FIXED32 >>> FIELD_TYPE_SHIFT):
            case (int) (FIELD_TYPE_SFIXED32 >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_FIXED32);
                value = readFixed32();
                break;
            case (int) (FIELD_TYPE_SINT32 >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_VARINT);
                value = decodeZigZag32((int) readVarint());
                break;
            case (int) (FIELD_TYPE_INT32 >>> FIELD_TYPE_SHIFT):
            case (int) (FIELD_TYPE_UINT32 >>> FIELD_TYPE_SHIFT):
            case (int) (FIELD_TYPE_ENUM >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_VARINT);
                value = (int) readVarint();
                break;
            default:
                throw new IllegalArgumentException(
                        "Requested field id (" + getFieldIdString(fieldId) + ") is not an int"
                                + dumpDebugData());
        }
        // Successfully read the field
        mState &= ~STATE_STARTED_FIELD_READ;
        return value;
    }

    /**
     * Read a single 64bit or varint proto type field as an long.
     *
     * @param fieldId - must match the current field number
     */
    public long readLong(long fieldId) throws IOException {
        assertFreshData();
        assertFieldNumber(fieldId);
        checkPacked(fieldId);

        long value;
        switch ((int) ((fieldId & FIELD_TYPE_MASK)
                >>> FIELD_TYPE_SHIFT)) {
            case (int) (FIELD_TYPE_FIXED64 >>> FIELD_TYPE_SHIFT):
            case (int) (FIELD_TYPE_SFIXED64 >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_FIXED64);
                value = readFixed64();
                break;
            case (int) (FIELD_TYPE_SINT64 >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_VARINT);
                value = decodeZigZag64(readVarint());
                break;
            case (int) (FIELD_TYPE_INT64 >>> FIELD_TYPE_SHIFT):
            case (int) (FIELD_TYPE_UINT64 >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_VARINT);
                value = readVarint();
                break;
            default:
                throw new IllegalArgumentException(
                        "Requested field id (" + getFieldIdString(fieldId) + ") is not an long"
                                + dumpDebugData());
        }
        // Successfully read the field
        mState &= ~STATE_STARTED_FIELD_READ;
        return value;
    }

    /**
     * Read a single 32bit or varint proto type field as an boolean.
     *
     * @param fieldId - must match the current field number
     */
    public boolean readBoolean(long fieldId) throws IOException {
        assertFreshData();
        assertFieldNumber(fieldId);
        checkPacked(fieldId);

        boolean value;
        switch ((int) ((fieldId & FIELD_TYPE_MASK)
                >>> FIELD_TYPE_SHIFT)) {
            case (int) (FIELD_TYPE_BOOL >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_VARINT);
                value = readVarint() != 0;
                break;
            default:
                throw new IllegalArgumentException(
                        "Requested field id (" + getFieldIdString(fieldId) + ") is not an boolean"
                                + dumpDebugData());
        }
        // Successfully read the field
        mState &= ~STATE_STARTED_FIELD_READ;
        return value;
    }

    /**
     * Read a string field
     *
     * @param fieldId - must match the current field number
     */
    public String readString(long fieldId) throws IOException {
        assertFreshData();
        assertFieldNumber(fieldId);

        String value;
        switch ((int) ((fieldId & FIELD_TYPE_MASK) >>> FIELD_TYPE_SHIFT)) {
            case (int) (FIELD_TYPE_STRING >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_LENGTH_DELIMITED);
                int len = (int) readVarint();
                value = readRawString(len);
                break;
            default:
                throw new IllegalArgumentException(
                        "Requested field id(" + getFieldIdString(fieldId)
                                + ") is not an string"
                                + dumpDebugData());
        }
        // Successfully read the field
        mState &= ~STATE_STARTED_FIELD_READ;
        return value;
    }

    /**
     * Read a bytes field
     *
     * @param fieldId - must match the current field number
     */
    public byte[] readBytes(long fieldId) throws IOException {
        assertFreshData();
        assertFieldNumber(fieldId);

        byte[] value;
        switch ((int) ((fieldId & FIELD_TYPE_MASK) >>> FIELD_TYPE_SHIFT)) {
            case (int) (FIELD_TYPE_MESSAGE >>> FIELD_TYPE_SHIFT):
            case (int) (FIELD_TYPE_BYTES >>> FIELD_TYPE_SHIFT):
                assertWireType(WIRE_TYPE_LENGTH_DELIMITED);
                int len = (int) readVarint();
                value = readRawBytes(len);
                break;
            default:
                throw new IllegalArgumentException(
                        "Requested field type (" + getFieldIdString(fieldId)
                                + ") cannot be read as raw bytes"
                                + dumpDebugData());
        }
        // Successfully read the field
        mState &= ~STATE_STARTED_FIELD_READ;
        return value;
    }

    /**
     * Start the read of an embedded Object
     *
     * @param fieldId - must match the current field number
     * @return a token. The token must be handed back when finished reading embedded Object
     */
    public long start(long fieldId) throws IOException {
        assertFreshData();
        assertFieldNumber(fieldId);
        assertWireType(WIRE_TYPE_LENGTH_DELIMITED);

        int messageSize = (int) readVarint();

        if (mExpectedObjectTokenStack == null) {
            mExpectedObjectTokenStack = new ArrayList<>();
        }
        if (++mDepth == mExpectedObjectTokenStack.size()) {
            // Create a token to keep track of nested Object and extend the object stack
            mExpectedObjectTokenStack.add(makeToken(0,
                    (fieldId & FIELD_COUNT_REPEATED) == FIELD_COUNT_REPEATED, mDepth,
                    (int) fieldId, getOffset() + messageSize));

        } else {
            // Create a token to keep track of nested Object
            mExpectedObjectTokenStack.set(mDepth, makeToken(0,
                    (fieldId & FIELD_COUNT_REPEATED) == FIELD_COUNT_REPEATED, mDepth,
                    (int) fieldId, getOffset() + messageSize));
        }

        // Sanity check
        if (mDepth > 0
                && getOffsetFromToken(mExpectedObjectTokenStack.get(mDepth))
                > getOffsetFromToken(mExpectedObjectTokenStack.get(mDepth - 1))) {
            throw new ProtoParseException("Embedded Object ("
                    + token2String(mExpectedObjectTokenStack.get(mDepth))
                    + ") ends after of parent Objects's ("
                    + token2String(mExpectedObjectTokenStack.get(mDepth - 1))
                    + ") end"
                    + dumpDebugData());
        }
        mState &= ~STATE_STARTED_FIELD_READ;
        return mExpectedObjectTokenStack.get(mDepth);
    }

    /**
     * Note the end of a nested object. Must be called to continue streaming the rest of the proto.
     * end can be called mid object parse. The offset will be moved to the next field outside the
     * object.
     *
     * @param token - token
     */
    public void end(long token) {
        // Sanity check to make sure user is keeping track of their embedded messages
        if (mExpectedObjectTokenStack.get(mDepth) != token) {
            throw new ProtoParseException(
                    "end token " + token + " does not match current message token "
                            + mExpectedObjectTokenStack.get(mDepth)
                            + dumpDebugData());
        }
        if (getOffsetFromToken(mExpectedObjectTokenStack.get(mDepth)) > getOffset()) {
            // Did not read all of the message, skip to the end
            incOffset(getOffsetFromToken(mExpectedObjectTokenStack.get(mDepth)) - getOffset());
        }
        mDepth--;
        mState &= ~STATE_STARTED_FIELD_READ;
    }

    /**
     * Read the tag at the start of the next field and collect field number and wire type.
     * Will set mFieldNumber to NO_MORE_FIELDS if end of buffer/stream reached.
     */
    private void readTag() throws IOException {
        fillBuffer();
        if (mOffset >= mEnd) {
            // reached end of the stream
            mFieldNumber = NO_MORE_FIELDS;
            return;
        }
        int tag = (int) readVarint();
        mFieldNumber = tag >>> FIELD_ID_SHIFT;
        mWireType = tag & WIRE_TYPE_MASK;
        mState |= STATE_STARTED_FIELD_READ;
    }

    /**
     * Decode a 32 bit ZigZag encoded signed int.
     *
     * @param n - int to decode
     * @return the decoded signed int
     */
    public int decodeZigZag32(final int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    /**
     * Decode a 64 bit ZigZag encoded signed long.
     *
     * @param n - long to decode
     * @return the decoded signed long
     */
    public long decodeZigZag64(final long n) {
        return (n >>> 1) ^ -(n & 1);
    }

    /**
     * Read a varint from the buffer
     *
     * @return the varint as a long
     */
    private long readVarint() throws IOException {
        long value = 0;
        int shift = 0;
        while (true) {
            fillBuffer();
            // Limit how much bookkeeping is done by checking how far away the end of the buffer is
            // and directly accessing buffer up until the end.
            final int fragment = mEnd - mOffset;
            if (fragment < 0) {
                throw new ProtoParseException(
                        "Incomplete varint at offset 0x"
                                + Integer.toHexString(getOffset())
                                + dumpDebugData());
            }
            for (int i = 0; i < fragment; i++) {
                byte b = mBuffer[(mOffset + i)];
                value |= (b & 0x7FL) << shift;
                if ((b & 0x80) == 0) {
                    incOffset(i + 1);
                    return value;
                }
                shift += 7;
                if (shift > 63) {
                    throw new ProtoParseException(
                            "Varint is too large at offset 0x"
                                    + Integer.toHexString(getOffset() + i)
                                    + dumpDebugData());
                }
            }
            // Hit the end of the buffer, do some incrementing and checking, then continue
            incOffset(fragment);
        }
    }

    /**
     * Read a fixed 32 bit int from the buffer
     *
     * @return the fixed32 as a int
     */
    private int readFixed32() throws IOException {
        // check for fast path, which is likely with a reasonable buffer size
        if (mOffset + 4 <= mEnd) {
            // don't bother filling buffer since we know the end is plenty far away
            incOffset(4);
            return (mBuffer[mOffset - 4] & 0xFF)
                    | ((mBuffer[mOffset - 3] & 0xFF) << 8)
                    | ((mBuffer[mOffset - 2] & 0xFF) << 16)
                    | ((mBuffer[mOffset - 1] & 0xFF) << 24);
        }

        // the Fixed32 crosses the edge of a chunk, read the Fixed32 in multiple fragments.
        // There will be two fragment reads except when the chunk size is 2 or less.
        int value = 0;
        int shift = 0;
        int bytesLeft = 4;
        while (bytesLeft > 0) {
            fillBuffer();
            // Find the number of bytes available until the end of the chunk or Fixed32
            int fragment = (mEnd - mOffset) < bytesLeft ? (mEnd - mOffset) : bytesLeft;
            if (fragment < 0) {
                throw new ProtoParseException(
                        "Incomplete fixed32 at offset 0x"
                                + Integer.toHexString(getOffset())
                                + dumpDebugData());
            }
            incOffset(fragment);
            bytesLeft -= fragment;
            while (fragment > 0) {
                value |= ((mBuffer[mOffset - fragment] & 0xFF) << shift);
                fragment--;
                shift += 8;
            }
        }
        return value;
    }

    /**
     * Read a fixed 64 bit long from the buffer
     *
     * @return the fixed64 as a long
     */
    private long readFixed64() throws IOException {
        // check for fast path, which is likely with a reasonable buffer size
        if (mOffset + 8 <= mEnd) {
            // don't bother filling buffer since we know the end is plenty far away
            incOffset(8);
            return (mBuffer[mOffset - 8] & 0xFFL)
                    | ((mBuffer[mOffset - 7] & 0xFFL) << 8)
                    | ((mBuffer[mOffset - 6] & 0xFFL) << 16)
                    | ((mBuffer[mOffset - 5] & 0xFFL) << 24)
                    | ((mBuffer[mOffset - 4] & 0xFFL) << 32)
                    | ((mBuffer[mOffset - 3] & 0xFFL) << 40)
                    | ((mBuffer[mOffset - 2] & 0xFFL) << 48)
                    | ((mBuffer[mOffset - 1] & 0xFFL) << 56);
        }

        // the Fixed64 crosses the edge of a chunk, read the Fixed64 in multiple fragments.
        // There will be two fragment reads except when the chunk size is 6 or less.
        long value = 0;
        int shift = 0;
        int bytesLeft = 8;
        while (bytesLeft > 0) {
            fillBuffer();
            // Find the number of bytes available until the end of the chunk or Fixed64
            int fragment = (mEnd - mOffset) < bytesLeft ? (mEnd - mOffset) : bytesLeft;
            if (fragment < 0) {
                throw new ProtoParseException(
                        "Incomplete fixed64 at offset 0x"
                                + Integer.toHexString(getOffset())
                                + dumpDebugData());
            }
            incOffset(fragment);
            bytesLeft -= fragment;
            while (fragment > 0) {
                value |= ((mBuffer[(mOffset - fragment)] & 0xFFL) << shift);
                fragment--;
                shift += 8;
            }
        }
        return value;
    }

    /**
     * Read raw bytes from the buffer
     *
     * @param n - number of bytes to read
     * @return a byte array with raw bytes
     */
    private byte[] readRawBytes(int n) throws IOException {
        byte[] buffer = new byte[n];
        int pos = 0;
        while (mOffset + n - pos > mEnd) {
            int fragment = mEnd - mOffset;
            if (fragment > 0) {
                System.arraycopy(mBuffer, mOffset, buffer, pos, fragment);
                incOffset(fragment);
                pos += fragment;
            }
            fillBuffer();
            if (mOffset >= mEnd) {
                throw new ProtoParseException(
                        "Unexpectedly reached end of the InputStream at offset 0x"
                                + Integer.toHexString(mEnd)
                                + dumpDebugData());
            }
        }
        System.arraycopy(mBuffer, mOffset, buffer, pos, n - pos);
        incOffset(n - pos);
        return buffer;
    }

    /**
     * Read raw string from the buffer
     *
     * @param n - number of bytes to read
     * @return a string
     */
    private String readRawString(int n) throws IOException {
        fillBuffer();
        if (mOffset + n <= mEnd) {
            // fast path read. String is well within the current buffer
            String value = new String(mBuffer, mOffset, n, StandardCharsets.UTF_8);
            incOffset(n);
            return value;
        } else if (n <= mBufferSize) {
            // String extends past buffer, but can be encapsulated in a buffer. Copy the first chunk
            // of the string to the start of the buffer and then fill the rest of the buffer from
            // the stream.
            final int stringHead = mEnd - mOffset;
            System.arraycopy(mBuffer, mOffset, mBuffer, 0, stringHead);
            mEnd = stringHead + mStream.read(mBuffer, stringHead, n - stringHead);

            mDiscardedBytes += mOffset;
            mOffset = 0;

            String value = new String(mBuffer, mOffset, n, StandardCharsets.UTF_8);
            incOffset(n);
            return value;
        }
        // Otherwise, the string is too large to use the buffer. Create the string from a
        // separate byte array.
        return new String(readRawBytes(n), 0, n, StandardCharsets.UTF_8);
    }

    /**
     * Fill the buffer with a chunk from the stream if need be.
     * Will skip chunks until mOffset is reached
     */
    private void fillBuffer() throws IOException {
        if (mOffset >= mEnd && mStream != null) {
            mOffset -= mEnd;
            mDiscardedBytes += mEnd;
            if (mOffset >= mBufferSize) {
                int skipped = (int) mStream.skip((mOffset / mBufferSize) * mBufferSize);
                mDiscardedBytes += skipped;
                mOffset -= skipped;
            }
            mEnd = mStream.read(mBuffer);
        }
    }

    /**
     * Skips the rest of current field and moves to the start of the next field. This should only be
     * called while state is STATE_STARTED_FIELD_READ
     */
    public void skip() throws IOException {
        if ((mState & STATE_READING_PACKED) == STATE_READING_PACKED) {
            incOffset(mPackedEnd - getOffset());
        } else {
            switch (mWireType) {
                case WIRE_TYPE_VARINT:
                    byte b;
                    do {
                        fillBuffer();
                        b = mBuffer[mOffset];
                        incOffset(1);
                    } while ((b & 0x80) != 0);
                    break;
                case WIRE_TYPE_FIXED64:
                    incOffset(8);
                    break;
                case WIRE_TYPE_LENGTH_DELIMITED:
                    fillBuffer();
                    int length = (int) readVarint();
                    incOffset(length);
                    break;
                /*
            case WIRE_TYPE_START_GROUP:
                // Not implemented
                break;
            case WIRE_TYPE_END_GROUP:
                // Not implemented
                break;
                */
                case WIRE_TYPE_FIXED32:
                    incOffset(4);
                    break;
                default:
                    throw new ProtoParseException(
                            "Unexpected wire type: " + mWireType + " at offset 0x"
                                    + Integer.toHexString(mOffset)
                                    + dumpDebugData());
            }
        }
        mState &= ~STATE_STARTED_FIELD_READ;
    }

    /**
     * Increment the offset and handle all the relevant bookkeeping
     * Refilling the buffer when its end is reached will be handled elsewhere (ideally just before
     * a read, to avoid unnecessary reads from stream)
     *
     * @param n - number of bytes to increment
     */
    private void incOffset(int n) {
        mOffset += n;

        if (mDepth >= 0 && getOffset() > getOffsetFromToken(
                mExpectedObjectTokenStack.get(mDepth))) {
            throw new ProtoParseException("Unexpectedly reached end of embedded object.  "
                    + token2String(mExpectedObjectTokenStack.get(mDepth))
                    + dumpDebugData());
        }
    }

    /**
     * Check the current wire type to determine if current numeric field is packed. If it is packed,
     * set up to deal with the field
     * This should only be called for primitive numeric field types.
     *
     * @param fieldId - used to determine what the packed wire type is.
     */
    private void checkPacked(long fieldId) throws IOException {
        if (mWireType == WIRE_TYPE_LENGTH_DELIMITED) {
            // Primitive Field is length delimited, must be a packed field.
            final int length = (int) readVarint();
            mPackedEnd = getOffset() + length;
            mState |= STATE_READING_PACKED;

            // Fake the wire type, based on the field type
            switch ((int) ((fieldId & FIELD_TYPE_MASK)
                    >>> FIELD_TYPE_SHIFT)) {
                case (int) (FIELD_TYPE_FLOAT >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_FIXED32 >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_SFIXED32 >>> FIELD_TYPE_SHIFT):
                    if (length % 4 != 0) {
                        throw new IllegalArgumentException(
                                "Requested field id (" + getFieldIdString(fieldId)
                                        + ") packed length " + length
                                        + " is not aligned for fixed32"
                                        + dumpDebugData());
                    }
                    mWireType = WIRE_TYPE_FIXED32;
                    break;
                case (int) (FIELD_TYPE_DOUBLE >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_FIXED64 >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_SFIXED64 >>> FIELD_TYPE_SHIFT):
                    if (length % 8 != 0) {
                        throw new IllegalArgumentException(
                                "Requested field id (" + getFieldIdString(fieldId)
                                        + ") packed length " + length
                                        + " is not aligned for fixed64"
                                        + dumpDebugData());
                    }
                    mWireType = WIRE_TYPE_FIXED64;
                    break;
                case (int) (FIELD_TYPE_SINT32 >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_INT32 >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_UINT32 >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_SINT64 >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_INT64 >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_UINT64 >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_ENUM >>> FIELD_TYPE_SHIFT):
                case (int) (FIELD_TYPE_BOOL >>> FIELD_TYPE_SHIFT):
                    mWireType = WIRE_TYPE_VARINT;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Requested field id (" + getFieldIdString(fieldId)
                                    + ") is not a packable field"
                                    + dumpDebugData());
            }
        }
    }


    /**
     * Check a field id constant against current field number
     *
     * @param fieldId - throws if fieldId does not match mFieldNumber
     */
    private void assertFieldNumber(long fieldId) {
        if ((int) fieldId != mFieldNumber) {
            throw new IllegalArgumentException("Requested field id (" + getFieldIdString(fieldId)
                    + ") does not match current field number (0x" + Integer.toHexString(
                    mFieldNumber)
                    + ") at offset 0x" + Integer.toHexString(getOffset())
                    + dumpDebugData());
        }
    }


    /**
     * Check a wire type against current wire type.
     *
     * @param wireType - throws if wireType does not match mWireType.
     */
    private void assertWireType(int wireType) {
        if (wireType != mWireType) {
            throw new WireTypeMismatchException(
                    "Current wire type " + getWireTypeString(mWireType)
                            + " does not match expected wire type " + getWireTypeString(wireType)
                            + " at offset 0x" + Integer.toHexString(getOffset())
                            + dumpDebugData());
        }
    }

    /**
     * Check if there is data ready to be read.
     */
    private void assertFreshData() {
        if ((mState & STATE_STARTED_FIELD_READ) != STATE_STARTED_FIELD_READ) {
            throw new ProtoParseException(
                    "Attempting to read already read field at offset 0x" + Integer.toHexString(
                            getOffset()) + dumpDebugData());
        }
    }

    /**
     * Dump debugging data about the buffer.
     */
    public String dumpDebugData() {
        StringBuilder sb = new StringBuilder();

        sb.append("\nmFieldNumber : 0x" + Integer.toHexString(mFieldNumber));
        sb.append("\nmWireType : 0x" + Integer.toHexString(mWireType));
        sb.append("\nmState : 0x" + Integer.toHexString(mState));
        sb.append("\nmDiscardedBytes : 0x" + Integer.toHexString(mDiscardedBytes));
        sb.append("\nmOffset : 0x" + Integer.toHexString(mOffset));
        sb.append("\nmExpectedObjectTokenStack : ");
        if (mExpectedObjectTokenStack == null) {
            sb.append("null");
        } else {
            sb.append(mExpectedObjectTokenStack);
        }
        sb.append("\nmDepth : 0x" + Integer.toHexString(mDepth));
        sb.append("\nmBuffer : ");
        if (mBuffer == null) {
            sb.append("null");
        } else {
            sb.append(mBuffer);
        }
        sb.append("\nmBufferSize : 0x" + Integer.toHexString(mBufferSize));
        sb.append("\nmEnd : 0x" + Integer.toHexString(mEnd));

        return sb.toString();
    }
}
