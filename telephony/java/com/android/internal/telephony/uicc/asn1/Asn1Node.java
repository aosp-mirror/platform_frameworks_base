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

import android.annotation.Nullable;

import com.android.internal.telephony.uicc.IccUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This represents a primitive or constructed data defined by ASN.1. A constructed node can have
 * child nodes. A non-constructed node can have a value. This class is read-only. To build a node,
 * you can use the {@link #newBuilder(int)} method to get a {@link Builder} instance. This class is
 * not thread-safe.
 */
public final class Asn1Node {
    private static final int INT_BYTES = Integer.SIZE / Byte.SIZE;
    private static final List<Asn1Node> EMPTY_NODE_LIST = Collections.emptyList();

    // Bytes for boolean values.
    private static final byte[] TRUE_BYTES = new byte[] {-1};
    private static final byte[] FALSE_BYTES = new byte[] {0};

    /**
     * This class is used to build an Asn1Node instance of a constructed tag. This class is not
     * thread-safe.
     */
    public static final class Builder {
        private final int mTag;
        private final List<Asn1Node> mChildren;

        private Builder(int tag) {
            if (!isConstructedTag(tag)) {
                throw new IllegalArgumentException(
                        "Builder should be created for a constructed tag: " + tag);
            }
            mTag = tag;
            mChildren = new ArrayList<>();
        }

        /**
         * Adds a child from an existing node.
         *
         * @return This builder.
         * @throws IllegalArgumentException If the child is a non-existing node.
         */
        public Builder addChild(Asn1Node child) {
            mChildren.add(child);
            return this;
        }

        /**
         * Adds a child from another builder. The child will be built with the call to this method,
         * and any changes to the child builder after the call to this method doesn't have effect.
         *
         * @return This builder.
         */
        public Builder addChild(Builder child) {
            mChildren.add(child.build());
            return this;
        }

        /**
         * Adds children from bytes. This method calls {@link Asn1Decoder} to verify the {@code
         * encodedBytes} and adds all nodes parsed from it as children.
         *
         * @return This builder.
         * @throws InvalidAsn1DataException If the data bytes cannot be parsed.
         */
        public Builder addChildren(byte[] encodedBytes) throws InvalidAsn1DataException {
            Asn1Decoder subDecoder = new Asn1Decoder(encodedBytes, 0, encodedBytes.length);
            while (subDecoder.hasNextNode()) {
                mChildren.add(subDecoder.nextNode());
            }
            return this;
        }

        /**
         * Adds a child of non-constructed tag with an integer as the data.
         *
         * @return This builder.
         * @throws IllegalStateException If the {@code tag} is not constructed..
         */
        public Builder addChildAsInteger(int tag, int value) {
            if (isConstructedTag(tag)) {
                throw new IllegalStateException("Cannot set value of a constructed tag: " + tag);
            }
            byte[] dataBytes = IccUtils.signedIntToBytes(value);
            addChild(new Asn1Node(tag, dataBytes, 0, dataBytes.length));
            return this;
        }

        /**
         * Adds a child of non-constructed tag with a string as the data.
         *
         * @return This builder.
         * @throws IllegalStateException If the {@code tag} is not constructed..
         */
        public Builder addChildAsString(int tag, String value) {
            if (isConstructedTag(tag)) {
                throw new IllegalStateException("Cannot set value of a constructed tag: " + tag);
            }
            byte[] dataBytes = value.getBytes(StandardCharsets.UTF_8);
            addChild(new Asn1Node(tag, dataBytes, 0, dataBytes.length));
            return this;
        }

        /**
         * Adds a child of non-constructed tag with a byte array as the data.
         *
         * @param value The value will be owned by this node.
         * @return This builder.
         * @throws IllegalStateException If the {@code tag} is not constructed..
         */
        public Builder addChildAsBytes(int tag, byte[] value) {
            if (isConstructedTag(tag)) {
                throw new IllegalStateException("Cannot set value of a constructed tag: " + tag);
            }
            addChild(new Asn1Node(tag, value, 0, value.length));
            return this;
        }

        /**
         * Adds a child of non-constructed tag with a byte array as the data from a hex string.
         *
         * @return This builder.
         * @throws IllegalStateException If the {@code tag} is not constructed..
         */
        public Builder addChildAsBytesFromHex(int tag, String hex) {
            return addChildAsBytes(tag, IccUtils.hexStringToBytes(hex));
        }

        /**
         * Adds a child of non-constructed tag with bits as the data.
         *
         * @return This builder.
         * @throws IllegalStateException If the {@code tag} is not constructed..
         */
        public Builder addChildAsBits(int tag, int value) {
            if (isConstructedTag(tag)) {
                throw new IllegalStateException("Cannot set value of a constructed tag: " + tag);
            }
            // Always allocate 5 bytes for simplicity.
            byte[] dataBytes = new byte[INT_BYTES + 1];
            // Puts the integer into the byte[1-4].
            value = Integer.reverse(value);
            int dataLength = 0;
            for (int i = 1; i < dataBytes.length; i++) {
                dataBytes[i] = (byte) (value >> ((INT_BYTES - i) * Byte.SIZE));
                if (dataBytes[i] != 0) {
                    dataLength = i;
                }
            }
            dataLength++;
            // The first byte is the number of trailing zeros of the last byte.
            dataBytes[0] = IccUtils.countTrailingZeros(dataBytes[dataLength - 1]);
            addChild(new Asn1Node(tag, dataBytes, 0, dataLength));
            return this;
        }

        /**
         * Adds a child of non-constructed tag with a boolean as the data.
         *
         * @return This builder.
         * @throws IllegalStateException If the {@code tag} is not constructed..
         */
        public Builder addChildAsBoolean(int tag, boolean value) {
            if (isConstructedTag(tag)) {
                throw new IllegalStateException("Cannot set value of a constructed tag: " + tag);
            }
            addChild(new Asn1Node(tag, value ? TRUE_BYTES : FALSE_BYTES, 0, 1));
            return this;
        }

        /** Builds the node. */
        public Asn1Node build() {
            return new Asn1Node(mTag, mChildren);
        }
    }

    private final int mTag;
    private final boolean mConstructed;
    // Do not use this field directly in the methods other than the constructor and encoding
    // methods (e.g., toBytes()), but always use getChildren() instead.
    private final List<Asn1Node> mChildren;

    // Byte array that actually holds the data. For a non-constructed node, this stores its actual
    // value. If the value is not set, this is null. For constructed node, this stores encoded data
    // of its children, which will be decoded on the first call to getChildren().
    private @Nullable byte[] mDataBytes;
    // Offset of the data in above byte array.
    private int mDataOffset;
    // Length of the data in above byte array. If it's a constructed node, this is always the total
    // length of all its children.
    private int mDataLength;
    // Length of the total bytes required to encode this node.
    private int mEncodedLength;

    /**
     * Creates a new ASN.1 data node builder with the given tag. The tag is an encoded tag including
     * the tag class, tag number, and constructed mask.
     */
    public static Builder newBuilder(int tag) {
        return new Builder(tag);
    }

    private static boolean isConstructedTag(int tag) {
        // Constructed mask is at the 6th bit.
        byte[] tagBytes = IccUtils.unsignedIntToBytes(tag);
        return (tagBytes[0] & 0x20) != 0;
    }

    private static int calculateEncodedBytesNumForLength(int length) {
        // Constructed mask is at the 6th bit.
        int len = 1;
        if (length > 127) {
            len += IccUtils.byteNumForUnsignedInt(length);
        }
        return len;
    }

    /**
     * Creates a node with given data bytes. If it is a constructed node, its children will be
     * parsed when they are visited.
     */
    Asn1Node(int tag, @Nullable byte[] src, int offset, int length) {
        mTag = tag;
        // Constructed mask is at the 6th bit.
        mConstructed = isConstructedTag(tag);
        mDataBytes = src;
        mDataOffset = offset;
        mDataLength = length;
        mChildren = mConstructed ? new ArrayList<Asn1Node>() : EMPTY_NODE_LIST;
        mEncodedLength =
                IccUtils.byteNumForUnsignedInt(mTag)
                        + calculateEncodedBytesNumForLength(mDataLength)
                        + mDataLength;
    }

    /** Creates a constructed node with given children. */
    private Asn1Node(int tag, List<Asn1Node> children) {
        mTag = tag;
        mConstructed = true;
        mChildren = children;

        mDataLength = 0;
        int size = children.size();
        for (int i = 0; i < size; i++) {
            mDataLength += children.get(i).mEncodedLength;
        }
        mEncodedLength =
                IccUtils.byteNumForUnsignedInt(mTag)
                        + calculateEncodedBytesNumForLength(mDataLength)
                        + mDataLength;
    }

    public int getTag() {
        return mTag;
    }

    public boolean isConstructed() {
        return mConstructed;
    }

    /**
     * Tests if a node has a child.
     *
     * @param tag The tag of an immediate child.
     * @param tags The tags of lineal descendant.
     */
    public boolean hasChild(int tag, int... tags) throws InvalidAsn1DataException {
        try {
            getChild(tag, tags);
        } catch (TagNotFoundException e) {
            return false;
        }
        return true;
    }

    /**
     * Gets the first child node having the given {@code tag} and {@code tags}.
     *
     * @param tag The tag of an immediate child.
     * @param tags The tags of lineal descendant.
     * @throws TagNotFoundException If the child cannot be found.
     */
    public Asn1Node getChild(int tag, int... tags)
            throws TagNotFoundException, InvalidAsn1DataException {
        if (!mConstructed) {
            throw new TagNotFoundException(tag);
        }
        int index = 0;
        Asn1Node node = this;
        while (node != null) {
            List<Asn1Node> children = node.getChildren();
            int size = children.size();
            Asn1Node foundChild = null;
            for (int i = 0; i < size; i++) {
                Asn1Node child = children.get(i);
                if (child.getTag() == tag) {
                    foundChild = child;
                    break;
                }
            }
            node = foundChild;
            if (index >= tags.length) {
                break;
            }
            tag = tags[index++];
        }
        if (node == null) {
            throw new TagNotFoundException(tag);
        }
        return node;
    }

    /**
     * Gets all child nodes which have the given {@code tag}.
     *
     * @return If this is primitive or no such children are found, an empty list will be returned.
     */
    public List<Asn1Node> getChildren(int tag)
            throws TagNotFoundException, InvalidAsn1DataException {
        if (!mConstructed) {
            return EMPTY_NODE_LIST;
        }

        List<Asn1Node> children = getChildren();
        if (children.isEmpty()) {
            return EMPTY_NODE_LIST;
        }
        List<Asn1Node> output = new ArrayList<>();
        int size = children.size();
        for (int i = 0; i < size; i++) {
            Asn1Node child = children.get(i);
            if (child.getTag() == tag) {
                output.add(child);
            }
        }
        return output.isEmpty() ? EMPTY_NODE_LIST : output;
    }

    /**
     * Gets all child nodes of this node. If it's a constructed node having encoded data, it's
     * children will be decoded here.
     *
     * @return If this is primitive, an empty list will be returned. Do not modify the returned list
     *     directly.
     */
    public List<Asn1Node> getChildren() throws InvalidAsn1DataException {
        if (!mConstructed) {
            return EMPTY_NODE_LIST;
        }

        if (mDataBytes != null) {
            Asn1Decoder subDecoder = new Asn1Decoder(mDataBytes, mDataOffset, mDataLength);
            while (subDecoder.hasNextNode()) {
                mChildren.add(subDecoder.nextNode());
            }
            mDataBytes = null;
            mDataOffset = 0;
        }
        return mChildren;
    }

    /** @return Whether this node has a value. False will be returned for a constructed node. */
    public boolean hasValue() {
        return !mConstructed && mDataBytes != null;
    }

    /**
     * @return The data as an integer. If the data length is larger than 4, only the first 4 bytes
     *     will be parsed.
     * @throws InvalidAsn1DataException If the data bytes cannot be parsed.
     */
    public int asInteger() throws InvalidAsn1DataException {
        if (mConstructed) {
            throw new IllegalStateException("Cannot get value of a constructed node.");
        }
        if (mDataBytes == null) {
            throw new InvalidAsn1DataException(mTag, "Data bytes cannot be null.");
        }
        try {
            return IccUtils.bytesToInt(mDataBytes, mDataOffset, mDataLength);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new InvalidAsn1DataException(mTag, "Cannot parse data bytes.", e);
        }
    }

    /**
     * @return The data as a long variable which can be both positive and negative. If the data
     *     length is larger than 8, only the first 8 bytes will be parsed.
     * @throws InvalidAsn1DataException If the data bytes cannot be parsed.
     */
    public long asRawLong() throws InvalidAsn1DataException {
        if (mConstructed) {
            throw new IllegalStateException("Cannot get value of a constructed node.");
        }
        if (mDataBytes == null) {
            throw new InvalidAsn1DataException(mTag, "Data bytes cannot be null.");
        }
        try {
            return IccUtils.bytesToRawLong(mDataBytes, mDataOffset, mDataLength);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new InvalidAsn1DataException(mTag, "Cannot parse data bytes.", e);
        }
    }

    /**
     * @return The data as a string in UTF-8 encoding.
     * @throws InvalidAsn1DataException If the data bytes cannot be parsed.
     */
    public String asString() throws InvalidAsn1DataException {
        if (mConstructed) {
            throw new IllegalStateException("Cannot get value of a constructed node.");
        }
        if (mDataBytes == null) {
            throw new InvalidAsn1DataException(mTag, "Data bytes cannot be null.");
        }
        try {
            return new String(mDataBytes, mDataOffset, mDataLength, StandardCharsets.UTF_8);
        } catch (IndexOutOfBoundsException e) {
            throw new InvalidAsn1DataException(mTag, "Cannot parse data bytes.", e);
        }
    }

    /**
     * @return The data as a byte array.
     * @throws InvalidAsn1DataException If the data bytes cannot be parsed.
     */
    public byte[] asBytes() throws InvalidAsn1DataException {
        if (mConstructed) {
            throw new IllegalStateException("Cannot get value of a constructed node.");
        }
        if (mDataBytes == null) {
            throw new InvalidAsn1DataException(mTag, "Data bytes cannot be null.");
        }
        byte[] output = new byte[mDataLength];
        try {
            System.arraycopy(mDataBytes, mDataOffset, output, 0, mDataLength);
        } catch (IndexOutOfBoundsException e) {
            throw new InvalidAsn1DataException(mTag, "Cannot parse data bytes.", e);
        }
        return output;
    }

    /**
     * Gets the data as an integer for BIT STRING. DER actually stores the bits in a reversed order.
     * The returned integer here has the order fixed (first bit is at the lowest position). This
     * method currently only support at most 32 bits which fit in an integer.
     *
     * @return The data as an integer. If this is constructed, a {@code null} will be returned.
     * @throws InvalidAsn1DataException If the data bytes cannot be parsed.
     */
    public int asBits() throws InvalidAsn1DataException {
        if (mConstructed) {
            throw new IllegalStateException("Cannot get value of a constructed node.");
        }
        if (mDataBytes == null) {
            throw new InvalidAsn1DataException(mTag, "Data bytes cannot be null.");
        }
        int bits;
        try {
            bits = IccUtils.bytesToInt(mDataBytes, mDataOffset + 1, mDataLength - 1);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new InvalidAsn1DataException(mTag, "Cannot parse data bytes.", e);
        }
        for (int i = mDataLength - 1; i < INT_BYTES; i++) {
            bits <<= Byte.SIZE;
        }
        return Integer.reverse(bits);
    }

    /**
     * @return The data as a boolean.
     * @throws InvalidAsn1DataException If the data bytes cannot be parsed.
     */
    public boolean asBoolean() throws InvalidAsn1DataException {
        if (mConstructed) {
            throw new IllegalStateException("Cannot get value of a constructed node.");
        }
        if (mDataBytes == null) {
            throw new InvalidAsn1DataException(mTag, "Data bytes cannot be null.");
        }
        if (mDataLength != 1) {
            throw new InvalidAsn1DataException(
                    mTag, "Cannot parse data bytes as boolean: length=" + mDataLength);
        }
        if (mDataOffset < 0 || mDataOffset >= mDataBytes.length) {
            throw new InvalidAsn1DataException(
                    mTag,
                    "Cannot parse data bytes.",
                    new ArrayIndexOutOfBoundsException(mDataOffset));
        }
        // ASN.1 has "true" as 0xFF.
        if (mDataBytes[mDataOffset] == -1) {
            return Boolean.TRUE;
        } else if (mDataBytes[mDataOffset] == 0) {
            return Boolean.FALSE;
        }
        throw new InvalidAsn1DataException(
                mTag, "Cannot parse data bytes as boolean: " + mDataBytes[mDataOffset]);
    }

    /** @return The number of required bytes for encoding this node in DER. */
    public int getEncodedLength() {
        return mEncodedLength;
    }

    /** @return The number of required bytes for encoding this node's data in DER. */
    public int getDataLength() {
        return mDataLength;
    }

    /**
     * Writes the DER encoded bytes of this node into a byte array. The number of written bytes is
     * {@link #getEncodedLength()}.
     *
     * @throws IndexOutOfBoundsException If the {@code dest} doesn't have enough space to write.
     */
    public void writeToBytes(byte[] dest, int offset) {
        if (offset < 0 || offset + mEncodedLength > dest.length) {
            throw new IndexOutOfBoundsException(
                    "Not enough space to write. Required bytes: " + mEncodedLength);
        }
        write(dest, offset);
    }

    /** Writes the DER encoded bytes of this node into a new byte array. */
    public byte[] toBytes() {
        byte[] dest = new byte[mEncodedLength];
        write(dest, 0);
        return dest;
    }

    /** Gets a hex string representing the DER encoded bytes of this node. */
    public String toHex() {
        return IccUtils.bytesToHexString(toBytes());
    }

    /** Gets header (tag + length) as hex string. */
    public String getHeadAsHex() {
        String headHex = IccUtils.bytesToHexString(IccUtils.unsignedIntToBytes(mTag));
        if (mDataLength <= 127) {
            headHex += IccUtils.byteToHex((byte) mDataLength);
        } else {
            byte[] lenBytes = IccUtils.unsignedIntToBytes(mDataLength);
            headHex += IccUtils.byteToHex((byte) (lenBytes.length | 0x80));
            headHex += IccUtils.bytesToHexString(lenBytes);
        }
        return headHex;
    }

    /** Returns the new offset where to write the next node data. */
    private int write(byte[] dest, int offset) {
        // Writes the tag.
        offset += IccUtils.unsignedIntToBytes(mTag, dest, offset);
        // Writes the length.
        if (mDataLength <= 127) {
            dest[offset++] = (byte) mDataLength;
        } else {
            // Bytes required for encoding the length
            int lenLen = IccUtils.unsignedIntToBytes(mDataLength, dest, ++offset);
            dest[offset - 1] = (byte) (lenLen | 0x80);
            offset += lenLen;
        }
        // Writes the data.
        if (mConstructed && mDataBytes == null) {
            int size = mChildren.size();
            for (int i = 0; i < size; i++) {
                Asn1Node child = mChildren.get(i);
                offset = child.write(dest, offset);
            }
        } else if (mDataBytes != null) {
            System.arraycopy(mDataBytes, mDataOffset, dest, offset, mDataLength);
            offset += mDataLength;
        }
        return offset;
    }
}
