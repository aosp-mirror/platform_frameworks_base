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

package android.net.wifi.nan;

import libcore.io.Memory;

import java.nio.BufferOverflowException;
import java.nio.ByteOrder;
import java.util.Iterator;

/**
 * Utility class to construct and parse byte arrays using the TLV format -
 * Type/Length/Value format. The utilities accept a configuration of the size of
 * the Type field and the Length field. A Type field size of 0 is allowed -
 * allowing usage for LV (no T) array formats.
 *
 * @hide PROPOSED_NAN_API
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
     * The final byte array is obtained using {@link TlvConstructor#getArray()}
     * and {@link TlvConstructor#getActualLength()} methods.
     */
    public static class TlvConstructor {
        private int mTypeSize;
        private int mLengthSize;

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
        }

        /**
         * Set the byte array to be used to construct the TLV.
         *
         * @param array Byte array to be formatted.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public TlvConstructor wrap(byte[] array) {
            mArray = array;
            mArrayLength = array.length;
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
        public TlvConstructor putByteArray(int type, byte[] array, int offset, int length) {
            checkLength(length);
            addHeader(type, length);
            System.arraycopy(array, offset, mArray, mPosition, length);
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
        public TlvConstructor putByteArray(int type, byte[] array) {
            return putByteArray(type, array, 0, array.length);
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
            Memory.pokeShort(mArray, mPosition, data, ByteOrder.BIG_ENDIAN);
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
            Memory.pokeInt(mArray, mPosition, data, ByteOrder.BIG_ENDIAN);
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
        public TlvConstructor putString(int type, String data) {
            return putByteArray(type, data.getBytes(), 0, data.length());
        }

        /**
         * Returns the constructed TLV formatted byte-array. Note that the
         * returned array is the fully wrapped (
         * {@link TlvConstructor#wrap(byte[])}) or allocated (
         * {@link TlvConstructor#allocate(int)}) array - which isn't necessarily
         * the actual size of the formatted data. Use
         * {@link TlvConstructor#getActualLength()} to obtain the size of the
         * formatted data.
         *
         * @return The byte array containing the TLV formatted structure.
         */
        public byte[] getArray() {
            return mArray;
        }

        /**
         * Returns the size of the TLV formatted portion of the wrapped or
         * allocated byte array. The array itself is returned with
         * {@link TlvConstructor#getArray()}.
         *
         * @return The size of the TLV formatted portion of the byte array.
         */
        public int getActualLength() {
            return mPosition;
        }

        private void checkLength(int dataLength) {
            if (mPosition + mTypeSize + mLengthSize + dataLength > mArrayLength) {
                throw new BufferOverflowException();
            }
        }

        private void addHeader(int type, int length) {
            if (mTypeSize == 1) {
                mArray[mPosition] = (byte) type;
            } else if (mTypeSize == 2) {
                Memory.pokeShort(mArray, mPosition, (short) type, ByteOrder.BIG_ENDIAN);
            }
            mPosition += mTypeSize;

            if (mLengthSize == 1) {
                mArray[mPosition] = (byte) length;
            } else if (mLengthSize == 2) {
                Memory.pokeShort(mArray, mPosition, (short) length, ByteOrder.BIG_ENDIAN);
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
        public int mType;

        /**
         * The Length (L) field of the current TLV element.
         */
        public int mLength;

        /**
         * The Value (V) field - a raw byte array representing the current TLV
         * element where the entry starts at {@link TlvElement#mOffset}.
         */
        public byte[] mRefArray;

        /**
         * The offset to be used into {@link TlvElement#mRefArray} to access the
         * raw data representing the current TLV element.
         */
        public int mOffset;

        private TlvElement(int type, int length, byte[] refArray, int offset) {
            mType = type;
            mLength = length;
            mRefArray = refArray;
            mOffset = offset;
        }

        /**
         * Utility function to return a byte representation of a TLV element of
         * length 1. Note: an attempt to call this function on a TLV item whose
         * {@link TlvElement#mLength} is != 1 will result in an exception.
         *
         * @return byte representation of current TLV element.
         */
        public byte getByte() {
            if (mLength != 1) {
                throw new IllegalArgumentException(
                        "Accesing a byte from a TLV element of length " + mLength);
            }
            return mRefArray[mOffset];
        }

        /**
         * Utility function to return a short representation of a TLV element of
         * length 2. Note: an attempt to call this function on a TLV item whose
         * {@link TlvElement#mLength} is != 2 will result in an exception.
         *
         * @return short representation of current TLV element.
         */
        public short getShort() {
            if (mLength != 2) {
                throw new IllegalArgumentException(
                        "Accesing a short from a TLV element of length " + mLength);
            }
            return Memory.peekShort(mRefArray, mOffset, ByteOrder.BIG_ENDIAN);
        }

        /**
         * Utility function to return an integer representation of a TLV element
         * of length 4. Note: an attempt to call this function on a TLV item
         * whose {@link TlvElement#mLength} is != 4 will result in an exception.
         *
         * @return integer representation of current TLV element.
         */
        public int getInt() {
            if (mLength != 4) {
                throw new IllegalArgumentException(
                        "Accesing an int from a TLV element of length " + mLength);
            }
            return Memory.peekInt(mRefArray, mOffset, ByteOrder.BIG_ENDIAN);
        }

        /**
         * Utility function to return a String representation of a TLV element.
         *
         * @return String repersentation of the current TLV element.
         */
        public String getString() {
            return new String(mRefArray, mOffset, mLength);
        }
    }

    /**
     * Utility class to iterate over a TLV formatted byte-array.
     */
    public static class TlvIterable implements Iterable<TlvElement> {
        private int mTypeSize;
        private int mLengthSize;
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
         * @param lengthSize Number of bytes sued for the Length (L) field.
         *            Values values are 1 or 2 bytes.
         * @param array The TLV formatted byte-array to parse.
         * @param length The number of bytes of the array to be used in the
         *            parsing.
         */
        public TlvIterable(int typeSize, int lengthSize, byte[] array, int length) {
            if (typeSize < 0 || typeSize > 2 || lengthSize <= 0 || lengthSize > 2) {
                throw new IllegalArgumentException(
                        "Invalid sizes - typeSize=" + typeSize + ", lengthSize=" + lengthSize);
            }
            mTypeSize = typeSize;
            mLengthSize = lengthSize;
            mArray = array;
            mArrayLength = length;
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
                    builder.append("T=" + tlv.mType + ",");
                }
                builder.append("L=" + tlv.mLength + ") ");
                if (tlv.mLength == 0) {
                    builder.append("<null>");
                } else if (tlv.mLength == 1) {
                    builder.append(tlv.getByte());
                } else if (tlv.mLength == 2) {
                    builder.append(tlv.getShort());
                } else if (tlv.mLength == 4) {
                    builder.append(tlv.getInt());
                } else {
                    builder.append("<bytes>");
                }
                if (tlv.mLength != 0) {
                    builder.append(" (S='" + tlv.getString() + "')");
                }
            }
            builder.append("]");

            return builder.toString();
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
                    int type = 0;
                    if (mTypeSize == 1) {
                        type = mArray[mOffset];
                    } else if (mTypeSize == 2) {
                        type = Memory.peekShort(mArray, mOffset, ByteOrder.BIG_ENDIAN);
                    }
                    mOffset += mTypeSize;

                    int length = 0;
                    if (mLengthSize == 1) {
                        length = mArray[mOffset];
                    } else if (mLengthSize == 2) {
                        length = Memory.peekShort(mArray, mOffset, ByteOrder.BIG_ENDIAN);
                    }
                    mOffset += mLengthSize;

                    TlvElement tlv = new TlvElement(type, length, mArray, mOffset);
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
}
