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

import java.nio.ByteOrder;
import java.util.Iterator;

/**
 * Utility class to construct and parse byte arrays using the LV format -
 * Length/Value format. The utilities accept a configuration of the size of
 * the Length field.
 *
 * @hide PROPOSED_AWARE_API
 */
public class LvBufferUtils {
    private LvBufferUtils() {
        // no reason to ever create this class
    }

    /**
     * Utility class to construct byte arrays using the LV format - Length/Value.
     * <p>
     * A constructor is created specifying the size of the Length (L) field.
     * <p>
     * The byte array is either provided (using
     * {@link LvBufferUtils.LvConstructor#wrap(byte[])}) or allocated (using
     * {@link LvBufferUtils.LvConstructor#allocate(int)}).
     * <p>
     * Values are added to the structure using the {@code LvConstructor.put*()}
     * methods.
     * <p>
     * The final byte array is obtained using {@link LvBufferUtils.LvConstructor#getArray()}.
     */
    public static class LvConstructor {
        private TlvBufferUtils.TlvConstructor mTlvImpl;

        /**
         * Define a LV constructor with the specified size of the Length (L) field.
         *
         * @param lengthSize Number of bytes used for the Length (L) field.
         *            Values of 1 or 2 bytes are allowed.
         */
        public LvConstructor(int lengthSize) {
            mTlvImpl = new TlvBufferUtils.TlvConstructor(0, lengthSize);
        }

        /**
         * Set the byte array to be used to construct the LV.
         *
         * @param array Byte array to be formatted.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor wrap(@Nullable byte[] array) {
            mTlvImpl.wrap(array);
            return this;
        }

        /**
         * Allocates a new byte array to be used ot construct a LV.
         *
         * @param capacity The size of the byte array to be allocated.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor allocate(int capacity) {
            mTlvImpl.allocate(capacity);
            return this;
        }

        /**
         * Copies a byte into the LV array.
         *
         * @param b The byte to be inserted into the structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor putByte(byte b) {
            mTlvImpl.putByte(0, b);
            return this;
        }

        /**
         * Copies a byte array into the LV.
         *
         * @param array The array to be copied into the LV structure.
         * @param offset Start copying from the array at the specified offset.
         * @param length Copy the specified number (length) of bytes from the
         *            array.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor putByteArray(@Nullable byte[] array, int offset,
                int length) {
            mTlvImpl.putByteArray(0, array, offset, length);
            return this;
        }

        /**
         * Copies a byte array into the LV.
         *
         * @param array The array to be copied (in full) into the LV structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor putByteArray(int type, @Nullable byte[] array) {
            return putByteArray(array, 0, (array == null) ? 0 : array.length);
        }

        /**
         * Places a zero length element (i.e. Length field = 0) into the LV.
         *
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor putZeroLengthElement() {
            mTlvImpl.putZeroLengthElement(0);
            return this;
        }

        /**
         * Copies short into the LV.
         *
         * @param data The short to be inserted into the structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor putShort(short data) {
            mTlvImpl.putShort(0, data);
            return this;
        }

        /**
         * Copies integer into the LV.
         *
         * @param data The integer to be inserted into the structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor putInt(int data) {
            mTlvImpl.putInt(0, data);
            return this;
        }

        /**
         * Copies a String's byte representation into the LV.
         *
         * @param data The string whose bytes are to be inserted into the
         *            structure.
         * @return The constructor to facilitate chaining
         *         {@code ctr.putXXX(..).putXXX(..)}.
         */
        public LvBufferUtils.LvConstructor putString(@Nullable String data) {
            mTlvImpl.putString(0, data);
            return this;
        }

        /**
         * Returns the constructed LV formatted byte-array. This array is a copy of the wrapped
         * or allocated array - truncated to just the significant bytes - i.e. those written into
         * the LV.
         *
         * @return The byte array containing the LV formatted structure.
         */
        public byte[] getArray() {
            return mTlvImpl.getArray();
        }
    }

    /**
     * Utility class used when iterating over an LV formatted byte-array. Use
     * {@link LvBufferUtils.LvIterable} to iterate over array. A {@link LvBufferUtils.LvElement}
     * represents each entry in a LV formatted byte-array.
     */
    public static class LvElement {
        /**
         * The Length (L) field of the current LV element.
         */
        public int length;

        /**
         * The Value (V) field - a raw byte array representing the current LV
         * element where the entry starts at {@link LvBufferUtils.LvElement#offset}.
         */
        public byte[] refArray;

        /**
         * The offset to be used into {@link LvBufferUtils.LvElement#refArray} to access the
         * raw data representing the current LV element.
         */
        public int offset;

        private LvElement(int length, @Nullable byte[] refArray, int offset) {
            this.length = length;
            this.refArray = refArray;
            this.offset = offset;
        }

        /**
         * Utility function to return a byte representation of a LV element of
         * length 1. Note: an attempt to call this function on a LV item whose
         * {@link LvBufferUtils.LvElement#length} is != 1 will result in an exception.
         *
         * @return byte representation of current LV element.
         */
        public byte getByte() {
            if (length != 1) {
                throw new IllegalArgumentException(
                        "Accesing a byte from a LV element of length " + length);
            }
            return refArray[offset];
        }

        /**
         * Utility function to return a short representation of a LV element of
         * length 2. Note: an attempt to call this function on a LV item whose
         * {@link LvBufferUtils.LvElement#length} is != 2 will result in an exception.
         *
         * @return short representation of current LV element.
         */
        public short getShort() {
            if (length != 2) {
                throw new IllegalArgumentException(
                        "Accesing a short from a LV element of length " + length);
            }
            return Memory.peekShort(refArray, offset, ByteOrder.BIG_ENDIAN);
        }

        /**
         * Utility function to return an integer representation of a LV element
         * of length 4. Note: an attempt to call this function on a LV item
         * whose {@link LvBufferUtils.LvElement#length} is != 4 will result in an exception.
         *
         * @return integer representation of current LV element.
         */
        public int getInt() {
            if (length != 4) {
                throw new IllegalArgumentException(
                        "Accesing an int from a LV element of length " + length);
            }
            return Memory.peekInt(refArray, offset, ByteOrder.BIG_ENDIAN);
        }

        /**
         * Utility function to return a String representation of a LV element.
         *
         * @return String representation of the current LV element.
         */
        public String getString() {
            return new String(refArray, offset, length);
        }
    }

    /**
     * Utility class to iterate over a LV formatted byte-array.
     */
    public static class LvIterable implements Iterable<LvBufferUtils.LvElement> {
        private final TlvBufferUtils.TlvIterable mTlvIterable;

        /**
         * Constructs an LvIterable object - specifying the format of the LV
         * (the size of the Length field), and the byte array whose data is to be parsed.
         *
         * @param lengthSize Number of bytes sued for the Length (L) field.
         *            Values values are 1 or 2 bytes.
         * @param array The LV formatted byte-array to parse.
         */
        public LvIterable(int lengthSize, @Nullable byte[] array) {
            mTlvIterable = new TlvBufferUtils.TlvIterable(0, lengthSize, array);
        }

        /**
         * Prints out a parsed representation of the LV-formatted byte array.
         * Whenever possible bytes, shorts, and integer are printed out (for
         * fields whose length is 1, 2, or 4 respectively).
         */
        @Override
        public String toString() {
            return mTlvIterable.toString();
        }

        /**
         * Returns an iterator to step through a LV formatted byte-array. The
         * individual elements returned by the iterator are {@link LvBufferUtils.LvElement}.
         */
        @Override
        public Iterator<LvBufferUtils.LvElement> iterator() {
            return new Iterator<LvBufferUtils.LvElement>() {
                private Iterator<TlvBufferUtils.TlvElement> mTlvIterator = mTlvIterable.iterator();

                @Override
                public boolean hasNext() {
                    return mTlvIterator.hasNext();
                }

                @Override
                public LvBufferUtils.LvElement next() {
                    TlvBufferUtils.TlvElement tlvE = mTlvIterator.next();

                    return new LvElement(tlvE.length, tlvE.refArray, tlvE.offset);
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    /**
     * Validates that a LV array is constructed correctly. I.e. that its specified Length
     * fields correctly fill the specified length (and do not overshoot).
     *
     * @param array The LV array to verify.
     * @param lengthSize The size (in bytes) of the length field. Valid values are 1 or 2.
     * @return A boolean indicating whether the array is valid (true) or invalid (false).
     */
    public static boolean isValid(@Nullable byte[] array, int lengthSize) {
        return TlvBufferUtils.isValid(array, 0, lengthSize);
    }
}
