/*
 * Copyright (C) 2025 The Android Open Source Project
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
import java.io.OutputStream;
import java.util.function.Predicate;

/**
 * A utility class that reads raw protobuf data from an InputStream
 * and copies only those fields for which a given predicate returns true.
 *
 * <p>
 * This is a low-level approach that does not fully decode fields
 * (unless necessary to determine lengths). It simply:
 * <ul>
 *   <li>Parses each field's tag (varint for field number & wire type)</li>
 *   <li>If {@code includeFn(fieldNumber) == true}, copies
 *       the tag bytes and the field bytes directly to the output</li>
 *   <li>Otherwise, skips that field in the input</li>
 * </ul>
 * </p>
 *
 * <p>
 * Because we do not re-encode, unknown or unrecognized fields are copied
 * <i>verbatim</i> and remain exactly as in the input (useful for partial
 * parsing or partial transformations).
 * </p>
 *
 * <p>
 * Note: This class only filters based on top-level field numbers. For length-delimited
 * fields (including nested messages), the entire contents are either copied or skipped
 * as a single unit. The class is not capable of nested filtering.
 * </p>
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ProtoFieldFilter {

    private static final int BUFFER_SIZE_BYTES = 4096;

    private final Predicate<Integer> mFieldPredicate;
    // General-purpose buffer for reading proto fields and their data
    private final byte[] mBuffer;
    // Buffer specifically designated to hold varint values (max 10 bytes in protobuf encoding)
    private final byte[] mVarIntBuffer = new byte[10];

    /**
    * Constructs a ProtoFieldFilter with a predicate that considers depth.
    *
    * @param fieldPredicate A predicate returning true if the given fieldNumber should be
    *                       included in the output.
    * @param bufferSize The size of the internal buffer used for processing proto fields.
    *                   Larger buffers may improve performance when processing large
    *                   length-delimited fields.
    */
    public ProtoFieldFilter(Predicate<Integer> fieldPredicate, int bufferSize) {
        this.mFieldPredicate = fieldPredicate;
        this.mBuffer = new byte[bufferSize];
    }

    /**
    * Constructs a ProtoFieldFilter with a predicate that considers depth and
    * uses a default buffer size.
    *
    * @param fieldPredicate A predicate returning true if the given fieldNumber should be
    *                       included in the output.
    */
    public ProtoFieldFilter(Predicate<Integer> fieldPredicate) {
        this(fieldPredicate, BUFFER_SIZE_BYTES);
    }

    /**
     * Reads raw protobuf data from {@code in} and writes only those fields
     * passing {@code includeFn} to {@code out}. The predicate is given
     * (fieldNumber, wireType) for each encountered field.
     *
     * @param in        The input stream of protobuf data
     * @param out       The output stream to which we write the filtered protobuf
     * @throws IOException If reading or writing fails, or if the protobuf data is corrupted
     */
    public void filter(InputStream in, OutputStream out) throws IOException {
        int tagBytesLength;
        while ((tagBytesLength = readRawVarint(in)) > 0) {
            // Parse the varint loaded in mVarIntBuffer, through readRawVarint
            long tagVal = parseVarint(mVarIntBuffer, tagBytesLength);
            int fieldNumber = (int) (tagVal >>> ProtoStream.FIELD_ID_SHIFT);
            int wireType   = (int) (tagVal & ProtoStream.WIRE_TYPE_MASK);

            if (fieldNumber == 0) {
                break;
            }
            if (mFieldPredicate.test(fieldNumber)) {
                out.write(mVarIntBuffer, 0, tagBytesLength);
                copyFieldData(in, out, wireType);
            } else {
                skipFieldData(in, wireType);
            }
        }
    }

    /**
     * Reads a varint (up to 10 bytes) from the stream as raw bytes
     * and returns it in a byte array. If the stream is at EOF, returns null.
     *
     * @param in The input stream
     * @return the size of the varint bytes moved to mVarIntBuffer
     * @throws IOException If an error occurs, or if we detect a malformed varint
     */
    private int readRawVarint(InputStream in) throws IOException {
        // We attempt to read 1 byte. If none available => null
        int b = in.read();
        if (b < 0) {
            return 0;
        }
        int count = 0;
        mVarIntBuffer[count++] = (byte) b;
        // If the continuation bit is set, we continue
        while ((b & 0x80) != 0) {
            // read next byte
            b = in.read();
            // EOF
            if (b < 0) {
                throw new IOException("Malformed varint: reached EOF mid-varint");
            }
            // max 10 bytes for varint 64
            if (count >= 10) {
                throw new IOException("Malformed varint: too many bytes (max 10)");
            }
            mVarIntBuffer[count++] = (byte) b;
        }
        return count;
    }

    /**
     * Parses a varint from the given raw bytes and returns it as a long.
     *
     * @param rawVarint The bytes representing the varint
     * @param byteLength The number of bytes to read from rawVarint
     * @return The decoded long value
     */
    private static long parseVarint(byte[] rawVarint, int byteLength) throws IOException {
        long result = 0;
        int shift = 0;
        for (int i = 0; i < byteLength; i++) {
            result |= ((rawVarint[i] & 0x7F) << shift);
            shift += 7;
            if (shift > 63) {
                throw new IOException("Malformed varint: exceeds 64 bits");
            }
        }
        return result;
    }

    /**
     * Copies the wire data for a single field from {@code in} to {@code out},
     * assuming we have already read the field's tag.
     *
     * @param in       The input stream (protobuf data)
     * @param out      The output stream
     * @param wireType The wire type (0=varint, 1=fixed64, 2=length-delim, 5=fixed32)
     * @throws IOException if reading/writing fails or data is malformed
     */
    private void copyFieldData(InputStream in, OutputStream out, int wireType)
            throws IOException {
        switch (wireType) {
            case ProtoStream.WIRE_TYPE_VARINT:
                copyVarint(in, out);
                break;
            case ProtoStream.WIRE_TYPE_FIXED64:
                copyFixed(in, out, 8);
                break;
            case ProtoStream.WIRE_TYPE_LENGTH_DELIMITED:
                copyLengthDelimited(in, out);
                break;
            case ProtoStream.WIRE_TYPE_FIXED32:
                copyFixed(in, out, 4);
                break;
            // case WIRE_TYPE_START_GROUP:
                // Not Supported
            // case WIRE_TYPE_END_GROUP:
                // Not Supported
            default:
                // Error or unrecognized wire type
                throw new IOException("Unknown or unsupported wire type: " + wireType);
        }
    }

    /**
     * Skips the wire data for a single field from {@code in},
     * assuming the field's tag was already read.
     */
    private void skipFieldData(InputStream in, int wireType) throws IOException {
        switch (wireType) {
            case ProtoStream.WIRE_TYPE_VARINT:
                skipVarint(in);
                break;
            case ProtoStream.WIRE_TYPE_FIXED64:
                skipBytes(in, 8);
                break;
            case ProtoStream.WIRE_TYPE_LENGTH_DELIMITED:
                skipLengthDelimited(in);
                break;
            case ProtoStream.WIRE_TYPE_FIXED32:
                skipBytes(in, 4);
                break;
             // case WIRE_TYPE_START_GROUP:
                // Not Supported
            // case WIRE_TYPE_END_GROUP:
                // Not Supported
            default:
                throw new IOException("Unknown or unsupported wire type: " + wireType);
        }
    }

    /** Copies a varint (the field's value) from in to out. */
    private static void copyVarint(InputStream in, OutputStream out) throws IOException {
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("EOF while copying varint");
            }
            out.write(b);
            if ((b & 0x80) == 0) {
                break;
            }
        }
    }

    /**
     * Copies exactly {@code length} bytes from {@code in} to {@code out}.
     */
    private void copyFixed(InputStream in, OutputStream out,
                int length) throws IOException {
        int toRead = length;
        while (toRead > 0) {
            int chunk = Math.min(toRead, mBuffer.length);
            int readCount = in.read(mBuffer, 0, chunk);
            if (readCount < 0) {
                throw new IOException("EOF while copying fixed" + (length * 8) + " field");
            }
            out.write(mBuffer, 0, readCount);
            toRead -= readCount;
        }
    }

    /** Copies a length-delimited field */
    private void copyLengthDelimited(InputStream in,
                    OutputStream out) throws IOException {
        // 1) read length varint (and copy)
        int lengthVarintLength = readRawVarint(in);
        if (lengthVarintLength <= 0) {
            throw new IOException("EOF reading length for length-delimited field");
        }
        out.write(mVarIntBuffer, 0, lengthVarintLength);

        long lengthVal = parseVarint(mVarIntBuffer, lengthVarintLength);
        if (lengthVal < 0 || lengthVal > Integer.MAX_VALUE) {
            throw new IOException("Invalid length for length-delimited field: " + lengthVal);
        }

        // 2) copy that many bytes
        copyFixed(in, out, (int) lengthVal);
    }

    /** Skips a varint in the input (does not write anything). */
    private static void skipVarint(InputStream in) throws IOException {
        int bytesSkipped = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("EOF while skipping varint");
            }
            if ((b & 0x80) == 0) {
                break;
            }
            bytesSkipped++;
            if (bytesSkipped > 10) {
                throw new IOException("Malformed varint: exceeds maximum length of 10 bytes");
            }
        }
    }

    /** Skips exactly n bytes. */
    private void skipBytes(InputStream in, long n) throws IOException {
        long skipped = in.skip(n);
        // If skip fails, fallback to reading the remaining bytes
        if (skipped < n) {
            long bytesRemaining = n - skipped;

            while (bytesRemaining > 0) {
                int bytesToRead = (int) Math.min(bytesRemaining, mBuffer.length);
                int bytesRead = in.read(mBuffer, 0, bytesToRead);
                if (bytesRemaining < 0) {
                    throw new IOException("EOF while skipping bytes");
                }
                bytesRemaining -= bytesRead;
            }
        }
    }

    /**
     * Skips a length-delimited field.
     * 1) read the length as varint,
     * 2) skip that many bytes
     */
    private void skipLengthDelimited(InputStream in) throws IOException {
        int lengthVarintLength = readRawVarint(in);
        if (lengthVarintLength <= 0) {
            throw new IOException("EOF reading length for length-delimited field");
        }
        long lengthVal = parseVarint(mVarIntBuffer, lengthVarintLength);
        if (lengthVal < 0 || lengthVal > Integer.MAX_VALUE) {
            throw new IOException("Invalid length to skip: " + lengthVal);
        }
        skipBytes(in, lengthVal);
    }

}
