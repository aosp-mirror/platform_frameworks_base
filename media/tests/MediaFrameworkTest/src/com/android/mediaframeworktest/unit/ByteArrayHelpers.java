/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import android.util.Log;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class ByteArrayHelpers {

    private static final String TAG = "ByteArrayHelpers";
    private static boolean VERBOSE = false;

    /**
     * Convert an array of byte primitives to a {@code byte[]} using native endian order.
     *
     * <p>This function is a pass-through; it's here only to provide overloads for
     * every single type of primitive arrays.
     *
     * @param array array of primitives
     * @return array
     */
    public static byte[] toByteArray(byte[] array) {
        return array;
    }

    /**
     * Convert an array of shorts to a {@code byte[]} using native endian order.
     *
     * @param array array of shorts
     * @return array converted into byte array using native endian order
     */
    public static byte[] toByteArray(short[] array) {
        return toByteArray(array, Short.SIZE);
    }

    /**
     * Convert an array of chars to a {@code byte[]} using native endian order.
     *
     * @param array array of chars
     * @return array converted into byte array using native endian order
     */
    public static byte[] toByteArray(char[] array) {
        return toByteArray(array, Character.SIZE);
    }
    /**
     * Convert an array of ints to a {@code byte[]} using native endian order.
     *
     * @param array array of ints
     * @return array converted into byte array using native endian order
     */
    public static byte[] toByteArray(int[] array) {
        return toByteArray(array, Integer.SIZE);
    }
    /**
     * Convert an array of longs to a {@code byte[]} using native endian order.
     *
     * @param array array of longs
     * @return array converted into byte array using native endian order
     */
    public static byte[] toByteArray(long[] array) {
        return toByteArray(array, Long.SIZE);
    }
    /**
     * Convert an array of floats to a {@code byte[]} using native endian order.
     *
     * @param array array of floats
     * @return array converted into byte array using native endian order
     */
    public static byte[] toByteArray(float[] array) {
        return toByteArray(array, Float.SIZE);
    }
    /**
     * Convert an array of doubles to a {@code byte[]} using native endian order.
     *
     * @param array array of doubles
     * @return array converted into byte array using native endian order
     */
    public static byte[] toByteArray(double[] array) {
        return toByteArray(array, Double.SIZE);
    }

    /**
     * Convert an array of primitives to a {@code byte[]} using native endian order.
     *
     * <p>Arguments other than arrays are not supported. The array component must be primitive,
     * the wrapper class is not allowed (e.g. {@code int[]} is ok, but {@code Integer[]} is not.</p>
     *
     * @param array array of primitives
     * @return array converted into byte array using native endian order
     *
     * @throws IllegalArgumentException if {@code array} was not an array of primitives
     */
    public static <T> byte[] toByteArray(T array) {
        @SuppressWarnings("unchecked")
        Class<T> klass = (Class<T>) array.getClass();

        if (!klass.isArray()) {
            throw new IllegalArgumentException("array class must be an array");
        }

        Class<?> componentClass = klass.getComponentType();

        if (!componentClass.isPrimitive()) {
            throw new IllegalArgumentException("array's component must be a primitive");
        }

        int sizeInBits;
        if (klass == int.class) {
            sizeInBits = Integer.SIZE;
        } else if (klass == float.class) {
            sizeInBits = Float.SIZE;
        } else if (klass == double.class) {
            sizeInBits = Double.SIZE;
        } else if (klass == short.class) {
            sizeInBits = Short.SIZE;
        } else if (klass == char.class) {
            sizeInBits = Character.SIZE;
        } else if (klass == long.class) {
            sizeInBits = Long.SIZE;
        } else if (klass == byte.class) {
            sizeInBits = Byte.SIZE;
        } else {
            throw new AssertionError();
        }

        return toByteArray(array, sizeInBits);
    }

    /**
     * Convert a variadic list of {@code Number}s into a byte array using native endian order.
     *
     * <p>Each {@link Number} must be an instance of a primitive wrapper class
     * (e.g. {@link Integer} is OK, since it wraps {@code int}, but {@code BigInteger} is not.</p>
     *
     * @param numbers variadic list of numeric values
     * @return array converted into byte array using native endian order
     *
     * @throws IllegalArgumentException
     *          if {@code numbers} contained a class that wasn't a primitive wrapper
     */
    public static byte[] toByteArray(Number... numbers) {
        if (numbers.length == 0) {
            throw new IllegalArgumentException("too few numbers");
        }

        if (VERBOSE) Log.v(TAG, "toByteArray - input: " + Arrays.toString(numbers));

        // Have a large enough capacity to fit in every number as a double
        ByteBuffer byteBuffer = ByteBuffer.allocate(numbers.length * (Double.SIZE / Byte.SIZE))
                .order(ByteOrder.nativeOrder());

        for (int i = 0; i < numbers.length; ++i) {
            Number value = numbers[i];
            Class<? extends Number> klass = value.getClass();

            if (VERBOSE) Log.v(TAG, "toByteArray - number " + i + ", class " + klass);

            if (klass == Integer.class) {
                byteBuffer.putInt((Integer)value);
            } else if (klass == Float.class) {
                byteBuffer.putFloat((Float)value);
            } else if (klass == Double.class) {
                byteBuffer.putDouble((Double)value);
            } else if (klass == Short.class) {
                byteBuffer.putShort((Short)value);
            } else if (klass == Long.class) {
                byteBuffer.putLong((Long)value);
            } else if (klass == Byte.class) {
                byteBuffer.put((Byte)value);
            } else {
                throw new IllegalArgumentException(
                        "number class invalid; must be wrapper around primitive class");
            }
        }

        if (VERBOSE) Log.v(TAG, "toByteArray - end of loop");

        // Each number written is at least 1 byte, so the position should be at least length
        if (numbers.length != 0 && byteBuffer.position() < numbers.length) {
            throw new AssertionError(String.format(
                    "Had %d numbers, but byte buffer position was only %d",
                    numbers.length, byteBuffer.position()));
        }

        byteBuffer.flip();

        byte[] bytes = new byte[byteBuffer.limit()];
        byteBuffer.get(bytes);

        if (VERBOSE) Log.v(TAG, "toByteArray - output: " + Arrays.toString(bytes));

        return bytes;
    }

    private static <T> byte[] toByteArray(T array, int sizeOfTBits) {
        @SuppressWarnings("unchecked")
        Class<T> klass = (Class<T>) array.getClass();

        if (!klass.isArray()) {
            throw new IllegalArgumentException("array class must be an array");
        }

        int sizeOfT = sizeOfTBits / Byte.SIZE;
        int byteLength = Array.getLength(array) * sizeOfT;

        if (klass == byte[].class) {
            // Always return a copy
            return Arrays.copyOf((byte[])array, byteLength);
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(byteLength).order(ByteOrder.nativeOrder());

        if (klass == int[].class) {
            byteBuffer.asIntBuffer().put((int[])array);
        } else if (klass == float[].class) {
            byteBuffer.asFloatBuffer().put((float[])array);
        } else if (klass == double[].class) {
            byteBuffer.asDoubleBuffer().put((double[])array);
        } else if (klass == short[].class) {
            byteBuffer.asShortBuffer().put((short[])array);
        } else if (klass == char[].class) {
            byteBuffer.asCharBuffer().put((char[])array);
        } else if (klass == long[].class) {
            byteBuffer.asLongBuffer().put((long[])array);
        } else {
            throw new IllegalArgumentException("array class invalid; must be a primitive array");
        }

        return byteBuffer.array();
    }

    private ByteArrayHelpers() {
        throw new AssertionError();
    }
}
