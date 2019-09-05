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

package android.net.wifi.aware;

import android.annotation.Nullable;

import libcore.io.Memory;

import java.nio.BufferOverflowException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Utility class to construct and parse byte arrays using the TLV format -
 * Type/Length/Value format. The utilities accept a configuration of the size of
 * the Type field and the Length field. A Type field size of 0 is allowed -
 * allowing usage for LV (no T) array formats.
 *
 * @hide
 */
public class TlvBufferUtils {
    private TlvBufferUtils() {
        // no reason to ever create this class
    }

    /**
     * Utility class to construct byte arrays using the TLV format -
     * Type/Length/Value.
     * <p>
     * A constructor is created specifying the size of the Type (T) and Length
     * (L) fields. A specification of zero size T field is allowed - resulting
     * in LV type format.
     * <p>
     * The byte array is either provided (using
     * {@link TlvConstructor#wrap(byte[])}) or allocated (using
     * {@link TlvConstructor#allocate(int)}).
     * <p>
     * Values are added to the structure using the {@code TlvConstructor.put*()}
     * methods.
     * <p>
     * The final byte array is obtained using {@link TlvConstructor#getArray()}.
     */
    public static class TlvConstructor {
        private int mTypeSize;
        private int mLengthSize;
        private ByteOrder mByteOrder = ByteOrder.BIG_ENDIAN;

        private byte[] mArray;
        private int mArrayLength;
        private int mPosition;

        /**
         * Define a TLV constructor with the specified size of the Type (T) and
         * Length (L) fields.
         *
         * @param typeSize Number of bytes used for the Type (T) field. Values
         *            of 0, 1, or 2 bytes are allowed. A specification of 0
         *            bytes implies that the field being constructed has the LV
         *            format rather than the TLV format.
         * @param lengthSize Number of bytes used for the Length (L) field.
         *            Values of 1 or 2 bytes are allowed.
         */
        public TlvConstructor(int typeSize, int lengthSize) {
            if (typeSize < 0 || typeSize > 2 || lengthSize <= 0 || lengthSize > 2) {
                throw new IllegalArgumentException(
                        "Invalid sizes - typeSize=" + typeSize + ", lengthSize=" + lengthSize);
            }
            mTypeSize = typeSize;
            mLengthSize = lengthSize;
            mPosition = 0;
        }

        /**
         * Configure the TLV constructor to use a particular byte order. Should be
         * {@link ByteOrder#BIG_ENDIAN} (the default at construction) or
         * {@link ByteOrder#LITTLE_ENDIAN}.
         *
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor setByteOrder(ByteOrder byteOrder) {
            mByteOrder = byteOrder;
            return this;
        }

        /**
         * Set the byte array to be used to construct the TLV.
         *
         * @param array Byte array to be formatted.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor wrap(@Nullable byte[] array) {
            mArray = array;
            mArrayLength = (array == null) ? 0 : array.length;
            mPosition = 0;
            return this;
        }

        /**
         * Allocates a new byte array to be used ot construct a TLV.
         *
         * @param capacity The size of the byte array to be allocated.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor allocate(int capacity) {
            mArray = new byte[capacity];
            mArrayLength = capacity;
            mPosition = 0;
            return this;
        }

        /**
         * Creates a TLV array (of the previously specified Type and Length sizes) from the input
         * list. Allocates an array matching the contents (and required Type and Length
         * fields), copies the contents, and set the Length fields. The Type field is set to 0.
         *
         * @param list A list of fields to be added to the TLV buffer.
         * @return The constructor of the TLV.
         */
        public TlvConstructor allocateAndPut(@Nullable List<byte[]> list) {
            if (list != null) {
                int size = 0;
                for (byte[] field : list) {
                    size += mTypeSize + mLengthSize;
                    if (field != null) {
                        size += field.length;
                    }
                }
                allocate(size);
                for (byte[] field : list) {
                    putByteArray(0, field);
                }
            }
            return this;
        }

        /**
         * Copies a byte into the TLV with the indicated type. For an LV
         * formatted structure (i.e. typeLength=0 in {@link TlvConstructor
         * TlvConstructor(int, int)} ) the type field is ignored.
         *
         * @param type The value to be placed into the Type field.
         * @param b The byte to be inserted into the structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putByte(int type, byte b) {
            checkLength(1);
            addHeader(type, 1);
            mArray[mPosition++] = b;
            return this;
        }

        /**
         * Copies a raw byte into the TLV buffer - without a type or a length.
         *
         * @param b The byte to be inserted into the structure.
         * @return The constructor to facilitate chaining {@code cts.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putRawByte(byte b) {
            checkRawLength(1);
            mArray[mPosition++] = b;
            return this;
        }

        /**
         * Copies a byte array into the TLV with the indicated type. For an LV
         * formatted structure (i.e. typeLength=0 in {@link TlvConstructor
         * TlvConstructor(int, int)} ) the type field is ignored.
         *
         * @param type The value to be placed into the Type field.
         * @param array The array to be copied into the TLV structure.
         * @param offset Start copying from the array at the specified offset.
         * @param length Copy the specified number (length) of bytes from the
         *            array.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putByteArray(int type, @Nullable byte[] array, int offset,
                int length) {
            checkLength(length);
            addHeader(type, length);
            if (length != 0) {
                System.arraycopy(array, offset, mArray, mPosition, length);
            }
            mPosition += length;
            return this;
        }

        /**
         * Copies a byte array into the TLV with the indicated type. For an LV
         * formatted structure (i.e. typeLength=0 in {@link TlvConstructor
         * TlvConstructor(int, int)} ) the type field is ignored.
         *
         * @param type The value to be placed into the Type field.
         * @param array The array to be copied (in full) into the TLV structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putByteArray(int type, @Nullable byte[] array) {
            return putByteArray(type, array, 0, (array == null) ? 0 : array.length);
        }

        /**
         * Copies a byte array into the TLV - without a type or a length.
         *
         * @param array The array to be copied (in full) into the TLV structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putRawByteArray(@Nullable byte[] array) {
            if (array == null) return this;

            checkRawLength(array.length);
            System.arraycopy(array, 0, mArray, mPosition, array.length);
            mPosition += array.length;
            return this;
        }

        /**
         * Places a zero length element (i.e. Length field = 0) into the TLV.
         * For an LV formatted structure (i.e. typeLength=0 in
         * {@link TlvConstructor TlvConstructor(int, int)} ) the type field is
         * ignored.
         *
         * @param type The value to be placed into the Type field.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putZeroLengthElement(int type) {
            checkLength(0);
            addHeader(type, 0);
            return this;
        }

        /**
         * Copies short into the TLV with the indicated type. For an LV
         * formatted structure (i.e. typeLength=0 in {@link TlvConstructor
         * TlvConstructor(int, int)} ) the type field is ignored.
         *
         * @param type The value to be placed into the Type field.
         * @param data The short to be inserted into the structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putShort(int type, short data) {
            checkLength(2);
            addHeader(type, 2);
            Memory.pokeShort(mArray, mPosition, data, mByteOrder);
            mPosition += 2;
            return this;
        }

        /**
         * Copies integer into the TLV with the indicated type. For an LV
         * formatted structure (i.e. typeLength=0 in {@link TlvConstructor
         * TlvConstructor(int, int)} ) the type field is ignored.
         *
         * @param type The value to be placed into the Type field.
         * @param data The integer to be inserted into the structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putInt(int type, int data) {
            checkLength(4);
            addHeader(type, 4);
            Memory.pokeInt(mArray, mPosition, data, mByteOrder);
            mPosition += 4;
            return this;
        }

        /**
         * Copies a String's byte representation into the TLV with the indicated
         * type. For an LV formatted structure (i.e. typeLength=0 in
         * {@link TlvConstructor TlvConstructor(int, int)} ) the type field is
         * ignored.
         *
         * @param type The value to be placed into the Type field.
         * @param data The string whose bytes are to be inserted into the
         *            structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor putString(int type, @Nullable String data) {
            byte[] bytes = null;
            int length = 0;
            if (data != null) {
                bytes = data.getBytes();
                length = bytes.length;
            }
            return putByteArray(type, bytes, 0, length);
        }

        /**
         * Returns the constructed TLV formatted byte-array. This array is a copy of the wrapped
         * or allocated array - truncated to just the significant bytes - i.e. those written into
         * the (T)LV.
         *
         * @return The byte array containing the TLV formatted structure.
         */
        public byte[] getArray() {
            return Arrays.copyOf(mArray, getActualLength());
        }

        /**
         * Returns the size of the TLV formatted portion of the wrapped or
         * allocated byte array. The array itself is returned with
         * {@link TlvConstructor#getArray()}.
         *
         * @return The size of the TLV formatted portion of the byte array.
         */
        private int getActualLength() {
            return mPosition;
        }

        private void checkLength(int dataLength) {
            if (mPosition + mTypeSize + mLengthSize + dataLength > mArrayLength) {
                throw new BufferOverflowException();
            }
        }

        private void checkRawLength(int dataLength) {
            if (mPosition + dataLength > mArrayLength) {
                throw new BufferOverflowException();
            }
        }

        private void addHeader(int type, int length) {
            if (mTypeSize == 1) {
                mArray[mPosition] = (byte) type;
            } else if (mTypeSize == 2) {
                Memory.pokeShort(mArray, mPosition, (short) type, mByteOrder);
            }
            mPosition += mTypeSize;

            if (mLengthSize == 1) {
                mArray[mPosition] = (byte) length;
            } else if (mLengthSize == 2) {
                Memory.pokeShort(mArray, mPosition, (short) length, mByteOrder);
            }
            mPosition += mLengthSize;
        }
    }

    /**
     * Utility class used when iterating over a TLV formatted byte-array. Use
     * {@link TlvIterable} to iterate over array. A {@link TlvElement}
     * represents each entry in a TLV formatted byte-array.
     */
    public static class TlvElement {
        /**
         * The Type (T) field of the current TLV element. Note that for LV
         * formatted byte-arrays (i.e. TLV whose Type/T size is 0) the value of
         * this field is undefined.
         */
        public int type;

        /**
         * The Length (L) field of the current TLV element.
         */
        public int length;

        /**
         * Control of the endianess of the TLV element - true for big-endian, false for little-
         * endian.
         */
        public ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;

        /**
         * The Value (V) field - a raw byte array representing the current TLV
         * element where the entry starts at {@link TlvElement#offset}.
         */
        private byte[] mRefArray;

        /**
         * The offset to be used into {@link TlvElement#mRefArray} to access the
         * raw data representing the current TLV element.
         */
        public int offset;

        private TlvElement(int type, int length, @Nullable byte[] refArray, int offset) {
            this.type = type;
            this.length = length;
            mRefArray = refArray;
            this.offset = offset;

            if (offset + length > refArray.length) {
                throw new BufferOverflowException();
            }
        }

        /**
         * Return the raw byte array of the Value (V) field.
         *
         * @return The Value (V) field as a byte array.
         */
        public byte[] getRawData() {
            return Arrays.copyOfRange(mRefArray, offset, offset + length);
        }

        /**
         * Utility function to return a byte representation of a TLV element of
         * length 1. Note: an attempt to call this function on a TLV item whose
         * {@link TlvElement#length} is != 1 will result in an exception.
         *
         * @return byte representation of current TLV element.
         */
        public byte getByte() {
            if (length != 1) {
                throw new IllegalArgumentException(
                        "Accesing a byte from a TLV element of length " + length);
            }
            return mRefArray[offset];
        }

        /**
         * Utility function to return a short representation of a TLV element of
         * length 2. Note: an attempt to call this function on a TLV item whose
         * {@link TlvElement#length} is != 2 will result in an exception.
         *
         * @return short representation of current TLV element.
         */
        public short getShort() {
            if (length != 2) {
                throw new IllegalArgumentException(
                        "Accesing a short from a TLV element of length " + length);
            }
            return Memory.peekShort(mRefArray, offset, byteOrder);
        }

        /**
         * Utility function to return an integer representation of a TLV element
         * of length 4. Note: an attempt to call this function on a TLV item
         * whose {@link TlvElement#length} is != 4 will result in an exception.
         *
         * @return integer representation of current TLV element.
         */
        public int getInt() {
            if (length != 4) {
                throw new IllegalArgumentException(
                        "Accesing an int from a TLV element of length " + length);
            }
            return Memory.peekInt(mRefArray, offset, byteOrder);
        }

        /**
         * Utility function to return a String representation of a TLV element.
         *
         * @return String repersentation of the current TLV element.
         */
        public String getString() {
            return new String(mRefArray, offset, length);
        }
    }

    /**
     * Utility class to iterate over a TLV formatted byte-array.
     */
    public static class TlvIterable implements Iterable<TlvElement> {
        private int mTypeSize;
        private int mLengthSize;
        private ByteOrder mByteOrder = ByteOrder.BIG_ENDIAN;
        private byte[] mArray;
        private int mArrayLength;

        /**
         * Constructs a TlvIterable object - specifying the format of the TLV
         * (the sizes of the Type and Length fields), and the byte array whose
         * data is to be parsed.
         *
         * @param typeSize Number of bytes used for the Type (T) field. Valid
         *            values are 0 (i.e. indicating the format is LV rather than
         *            TLV), 1, and 2 bytes.
         * @param lengthSize Number of bytes used for the Length (L) field.
         *            Values values are 1 or 2 bytes.
         * @param array The TLV formatted byte-array to parse.
         */
        public TlvIterable(int typeSize, int lengthSize, @Nullable byte[] array) {
            if (typeSize < 0 || typeSize > 2 || lengthSize <= 0 || lengthSize > 2) {
                throw new IllegalArgumentException(
                        "Invalid sizes - typeSize=" + typeSize + ", lengthSize=" + lengthSize);
            }
            mTypeSize = typeSize;
            mLengthSize = lengthSize;
            mArray = array;
            mArrayLength = (array == null) ? 0 : array.length;
        }

        /**
         * Configure the TLV iterator to use little-endian byte ordering.
         */
        public void setByteOrder(ByteOrder byteOrder) {
            mByteOrder = byteOrder;
        }

        /**
         * Prints out a parsed representation of the TLV-formatted byte array.
         * Whenever possible bytes, shorts, and integer are printed out (for
         * fields whose length is 1, 2, or 4 respectively).
         */
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();

            builder.append("[");
            boolean first = true;
            for (TlvElement tlv : this) {
                if (!first) {
                    builder.append(",");
                }
                first = false;
                builder.append(" (");
                if (mTypeSize != 0) {
                    builder.append("T=" + tlv.type + ",");
                }
                builder.append("L=" + tlv.length + ") ");
                if (tlv.length == 0) {
                    builder.append("<null>");
                } else if (tlv.length == 1) {
                    builder.append(tlv.getByte());
                } else if (tlv.length == 2) {
                    builder.append(tlv.getShort());
                } else if (tlv.length == 4) {
                    builder.append(tlv.getInt());
                } else {
                    builder.append("<bytes>");
                }
                if (tlv.length != 0) {
                    builder.append(" (S='" + tlv.getString() + "')");
                }
            }
            builder.append("]");

            return builder.toString();
        }

        /**
         * Returns a List with the raw contents (no types) of the iterator.
         */
        public List<byte[]> toList() {
            List<byte[]> list = new ArrayList<>();
            for (TlvElement tlv : this) {
                list.add(Arrays.copyOfRange(tlv.mRefArray, tlv.offset, tlv.offset + tlv.length));
            }

            return list;
        }

        /**
         * Returns an iterator to step through a TLV formatted byte-array. The
         * individual elements returned by the iterator are {@link TlvElement}.
         */
        @Override
        public Iterator<TlvElement> iterator() {
            return new Iterator<TlvElement>() {
                private int mOffset = 0;

                @Override
                public boolean hasNext() {
                    return mOffset < mArrayLength;
                }

                @Override
                public TlvElement next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }

                    int type = 0;
                    if (mTypeSize == 1) {
                        type = mArray[mOffset];
                    } else if (mTypeSize == 2) {
                        type = Memory.peekShort(mArray, mOffset, mByteOrder);
                    }
                    mOffset += mTypeSize;

                    int length = 0;
                    if (mLengthSize == 1) {
                        length = mArray[mOffset];
                    } else if (mLengthSize == 2) {
                        length = Memory.peekShort(mArray, mOffset, mByteOrder);
                    }
                    mOffset += mLengthSize;

                    TlvElement tlv = new TlvElement(type, length, mArray, mOffset);
                    tlv.byteOrder = mByteOrder;
                    mOffset += length;
                    return tlv;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    /**
     * Validates that a (T)LV array is constructed correctly. I.e. that its specified Length
     * fields correctly fill the specified length (and do not overshoot). Uses big-endian
     * byte ordering.
     *
     * @param array The (T)LV array to verify.
     * @param typeSize The size (in bytes) of the type field. Valid values are 0, 1, or 2.
     * @param lengthSize The size (in bytes) of the length field. Valid values are 1 or 2.
     * @return A boolean indicating whether the array is valid (true) or invalid (false).
     */
    public static boolean isValid(@Nullable byte[] array, int typeSize, int lengthSize) {
        return isValidEndian(array, typeSize, lengthSize, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Validates that a (T)LV array is constructed correctly. I.e. that its specified Length
     * fields correctly fill the specified length (and do not overshoot).
     *
     * @param array The (T)LV array to verify.
     * @param typeSize The size (in bytes) of the type field. Valid values are 0, 1, or 2.
     * @param lengthSize The size (in bytes) of the length field. Valid values are 1 or 2.
     * @param byteOrder The endianness of the byte array: {@link ByteOrder#BIG_ENDIAN} or
     *                  {@link ByteOrder#LITTLE_ENDIAN}.
     * @return A boolean indicating whether the array is valid (true) or invalid (false).
     */
    public static boolean isValidEndian(@Nullable byte[] array, int typeSize, int lengthSize,
            ByteOrder byteOrder) {
        if (typeSize < 0 || typeSize > 2) {
            throw new IllegalArgumentException(
                    "Invalid arguments - typeSize must be 0, 1, or 2: typeSize=" + typeSize);
        }
        if (lengthSize <= 0 || lengthSize > 2) {
            throw new IllegalArgumentException(
                    "Invalid arguments - lengthSize must be 1 or 2: lengthSize=" + lengthSize);
        }
        if (array == null) {
            return true;
        }

        int nextTlvIndex = 0;
        while (nextTlvIndex + typeSize + lengthSize <= array.length) {
            nextTlvIndex += typeSize;
            if (lengthSize == 1) {
                nextTlvIndex += lengthSize + array[nextTlvIndex];
            } else {
                nextTlvIndex += lengthSize + Memory.peekShort(array, nextTlvIndex, byteOrder);
            }
        }

        return nextTlvIndex == array.length;
    }
}
