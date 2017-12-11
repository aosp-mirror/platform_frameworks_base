/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.telephony.uicc.asn1;

import com.android.internal.telephony.uicc.IccUtils;

/**
 * This represents a decoder helping decode an array of bytes or a hex string into
 * {@link Asn1Node}s. This class tracks the next position for decoding. This class is not
 * thread-safe.
 */
public final class Asn1Decoder {
    // Source byte array.
    private final byte[] mSrc;
    // Next position of the byte in the source array for decoding.
    private int mPosition;
    // Exclusive end of the range in the array for decoding.
    private final int mEnd;

    /** Creates a decoder on a hex string. */
    public Asn1Decoder(String hex) {
        this(IccUtils.hexStringToBytes(hex));
    }

    /** Creates a decoder on a byte array. */
    public Asn1Decoder(byte[] src) {
        this(src, 0, src.length);
    }

    /**
     * Creates a decoder on a byte array slice.
     *
     * @throws IndexOutOfBoundsException If the range defined by {@code offset} and {@code length}
     *         exceeds the bounds of {@code bytes}.
     */
    public Asn1Decoder(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException(
                    "Out of the bounds: bytes=["
                            + bytes.length
                            + "], offset="
                            + offset
                            + ", length="
                            + length);
        }
        mSrc = bytes;
        mPosition = offset;
        mEnd = offset + length;
    }

    /** @return The next start position for decoding. */
    public int getPosition() {
        return mPosition;
    }

    /** Returns whether the node has a next node. */
    public boolean hasNextNode() {
        return mPosition < mEnd;
    }

    /**
     * Parses the next node. If the node is a constructed node, its children will be parsed only
     * when they are accessed, e.g., though {@link Asn1Node#getChildren()}.
     *
     * @return The next decoded {@link Asn1Node}. If success, the next decoding position will also
     *         be updated. If any error happens, e.g., moving over the end position, {@code null}
     *         will be returned and the next decoding position won't be modified.
     * @throws InvalidAsn1DataException If the bytes cannot be parsed.
     */
    public Asn1Node nextNode() throws InvalidAsn1DataException {
        if (mPosition >= mEnd) {
            throw new IllegalStateException("No bytes to parse.");
        }

        int offset = mPosition;

        // Extracts the tag.
        int tagStart = offset;
        byte b = mSrc[offset++];
        if ((b & 0x1F) == 0x1F) {
            // High-tag-number form
            while (offset < mEnd && (mSrc[offset++] & 0x80) != 0) {
                // Do nothing.
            }
        }
        if (offset >= mEnd) {
            // No length bytes or the tag is too long.
            throw new InvalidAsn1DataException(0, "Invalid length at position: " + offset);
        }
        int tag;
        try {
            tag = IccUtils.bytesToInt(mSrc, tagStart, offset - tagStart);
        } catch (IllegalArgumentException e) {
            // Cannot parse the tag as an integer.
            throw new InvalidAsn1DataException(0, "Cannot parse tag at position: " + tagStart, e);
        }

        // Extracts the length.
        int dataLen;
        b = mSrc[offset++];
        if ((b & 0x80) == 0) {
            // Short-form length
            dataLen = b;
        } else {
            // Long-form length
            int lenLen = b & 0x7F;
            if (offset + lenLen > mEnd) {
                // No enough bytes for the long-form length
                throw new InvalidAsn1DataException(
                        tag, "Cannot parse length at position: " + offset);
            }
            try {
                dataLen = IccUtils.bytesToInt(mSrc, offset, lenLen);
            } catch (IllegalArgumentException e) {
                // Cannot parse the data length as an integer.
                throw new InvalidAsn1DataException(
                        tag, "Cannot parse length at position: " + offset, e);
            }
            offset += lenLen;
        }
        if (offset + dataLen > mEnd) {
            // No enough data left.
            throw new InvalidAsn1DataException(
                    tag,
                    "Incomplete data at position: "
                            + offset
                            + ", expected bytes: "
                            + dataLen
                            + ", actual bytes: "
                            + (mEnd - offset));
        }

        Asn1Node root = new Asn1Node(tag, mSrc, offset, dataLen);
        mPosition = offset + dataLen;
        return root;
    }
}
