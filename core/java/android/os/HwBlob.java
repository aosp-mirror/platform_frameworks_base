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

package android.os;

import android.annotation.NonNull;
import android.annotation.SystemApi;

import libcore.util.NativeAllocationRegistry;

/**
 * Represents fixed sized allocation of marshalled data used. Helper methods
 * allow for access to the unmarshalled data in a variety of ways.
 *
 * @hide
 */
@SystemApi
public class HwBlob {
    private static final String TAG = "HwBlob";

    private static final NativeAllocationRegistry sNativeRegistry;

    public HwBlob(int size) {
        native_setup(size);

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    /**
     * @param offset offset to unmarshall a boolean from
     * @return the unmarshalled boolean value
     * @throws IndexOutOfBoundsException when offset is out of this HwBlob
     */
    public native final boolean getBool(long offset);
    /**
     * @param offset offset to unmarshall a byte from
     * @return the unmarshalled byte value
     * @throws IndexOutOfBoundsException when offset is out of this HwBlob
     */
    public native final byte getInt8(long offset);
    /**
     * @param offset offset to unmarshall a short from
     * @return the unmarshalled short value
     * @throws IndexOutOfBoundsException when offset is out of this HwBlob
     */
    public native final short getInt16(long offset);
    /**
     * @param offset offset to unmarshall an int from
     * @return the unmarshalled int value
     * @throws IndexOutOfBoundsException when offset is out of this HwBlob
     */
    public native final int getInt32(long offset);
    /**
     * @param offset offset to unmarshall a long from
     * @return the unmarshalled long value
     * @throws IndexOutOfBoundsException when offset is out of this HwBlob
     */
    public native final long getInt64(long offset);
    /**
     * @param offset offset to unmarshall a float from
     * @return the unmarshalled float value
     * @throws IndexOutOfBoundsException when offset is out of this HwBlob
     */
    public native final float getFloat(long offset);
    /**
     * @param offset offset to unmarshall a double from
     * @return the unmarshalled double value
     * @throws IndexOutOfBoundsException when offset is out of this HwBlob
     */
    public native final double getDouble(long offset);
    /**
     * @param offset offset to unmarshall a string from
     * @return the unmarshalled string value
     * @throws IndexOutOfBoundsException when offset is out of this HwBlob
     */
    public native final String getString(long offset);

    /**
     * Copy the blobs data starting from the given byte offset into the range, copying
     * a total of size elements.
     *
     * @param offset starting location in blob
     * @param array destination array
     * @param size total number of elements to copy
     * @throws IllegalArgumentException array.length < size
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jboolean)] out of the blob.
     */
    public native final void copyToBoolArray(long offset, boolean[] array, int size);
    /**
     * Copy the blobs data starting from the given byte offset into the range, copying
     * a total of size elements.
     *
     * @param offset starting location in blob
     * @param array destination array
     * @param size total number of elements to copy
     * @throws IllegalArgumentException array.length < size
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jbyte)] out of the blob.
     */
    public native final void copyToInt8Array(long offset, byte[] array, int size);
    /**
     * Copy the blobs data starting from the given byte offset into the range, copying
     * a total of size elements.
     *
     * @param offset starting location in blob
     * @param array destination array
     * @param size total number of elements to copy
     * @throws IllegalArgumentException array.length < size
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jshort)] out of the blob.
     */
    public native final void copyToInt16Array(long offset, short[] array, int size);
    /**
     * Copy the blobs data starting from the given byte offset into the range, copying
     * a total of size elements.
     *
     * @param offset starting location in blob
     * @param array destination array
     * @param size total number of elements to copy
     * @throws IllegalArgumentException array.length < size
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jint)] out of the blob.
     */
    public native final void copyToInt32Array(long offset, int[] array, int size);
    /**
     * Copy the blobs data starting from the given byte offset into the range, copying
     * a total of size elements.
     *
     * @param offset starting location in blob
     * @param array destination array
     * @param size total number of elements to copy
     * @throws IllegalArgumentException array.length < size
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jlong)] out of the blob.
     */
    public native final void copyToInt64Array(long offset, long[] array, int size);
    /**
     * Copy the blobs data starting from the given byte offset into the range, copying
     * a total of size elements.
     *
     * @param offset starting location in blob
     * @param array destination array
     * @param size total number of elements to copy
     * @throws IllegalArgumentException array.length < size
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jfloat)] out of the blob.
     */
    public native final void copyToFloatArray(long offset, float[] array, int size);
    /**
     * Copy the blobs data starting from the given byte offset into the range, copying
     * a total of size elements.
     *
     * @param offset starting location in blob
     * @param array destination array
     * @param size total number of elements to copy
     * @throws IllegalArgumentException array.length < size
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jdouble)] out of the blob.
     */
    public native final void copyToDoubleArray(long offset, double[] array, int size);

    /**
     * Writes a boolean value at an offset.
     *
     * @param offset location to write value
     * @param x value to write
     * @throws IndexOutOfBoundsException when [offset, offset + sizeof(jboolean)] is out of range
     */
    public native final void putBool(long offset, boolean x);
    /**
     * Writes a byte value at an offset.
     *
     * @param offset location to write value
     * @param x value to write
     * @throws IndexOutOfBoundsException when [offset, offset + sizeof(jbyte)] is out of range
     */
    public native final void putInt8(long offset, byte x);
    /**
     * Writes a short value at an offset.
     *
     * @param offset location to write value
     * @param x value to write
     * @throws IndexOutOfBoundsException when [offset, offset + sizeof(jshort)] is out of range
     */
    public native final void putInt16(long offset, short x);
    /**
     * Writes a int value at an offset.
     *
     * @param offset location to write value
     * @param x value to write
     * @throws IndexOutOfBoundsException when [offset, offset + sizeof(jint)] is out of range
     */
    public native final void putInt32(long offset, int x);
    /**
     * Writes a long value at an offset.
     *
     * @param offset location to write value
     * @param x value to write
     * @throws IndexOutOfBoundsException when [offset, offset + sizeof(jlong)] is out of range
     */
    public native final void putInt64(long offset, long x);
    /**
     * Writes a float value at an offset.
     *
     * @param offset location to write value
     * @param x value to write
     * @throws IndexOutOfBoundsException when [offset, offset + sizeof(jfloat)] is out of range
     */
    public native final void putFloat(long offset, float x);
    /**
     * Writes a double value at an offset.
     *
     * @param offset location to write value
     * @param x value to write
     * @throws IndexOutOfBoundsException when [offset, offset + sizeof(jdouble)] is out of range
     */
    public native final void putDouble(long offset, double x);
    /**
     * Writes a string value at an offset.
     *
     * @param offset location to write value
     * @param x value to write
     * @throws IndexOutOfBoundsException when [offset, offset + sizeof(jstring)] is out of range
     */
    public native final void putString(long offset, String x);

    /**
     * Put a boolean array contiguously at an offset in the blob.
     *
     * @param offset location to write values
     * @param x array to write
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jboolean)] out of the blob.
     */
    public native final void putBoolArray(long offset, boolean[] x);
    /**
     * Put a byte array contiguously at an offset in the blob.
     *
     * @param offset location to write values
     * @param x array to write
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jbyte)] out of the blob.
     */
    public native final void putInt8Array(long offset, byte[] x);
    /**
     * Put a short array contiguously at an offset in the blob.
     *
     * @param offset location to write values
     * @param x array to write
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jshort)] out of the blob.
     */
    public native final void putInt16Array(long offset, short[] x);
    /**
     * Put a int array contiguously at an offset in the blob.
     *
     * @param offset location to write values
     * @param x array to write
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jint)] out of the blob.
     */
    public native final void putInt32Array(long offset, int[] x);
    /**
     * Put a long array contiguously at an offset in the blob.
     *
     * @param offset location to write values
     * @param x array to write
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jlong)] out of the blob.
     */
    public native final void putInt64Array(long offset, long[] x);
    /**
     * Put a float array contiguously at an offset in the blob.
     *
     * @param offset location to write values
     * @param x array to write
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jfloat)] out of the blob.
     */
    public native final void putFloatArray(long offset, float[] x);
    /**
     * Put a double array contiguously at an offset in the blob.
     *
     * @param offset location to write values
     * @param x array to write
     * @throws IndexOutOfBoundsException [offset, offset + size * sizeof(jdouble)] out of the blob.
     */
    public native final void putDoubleArray(long offset, double[] x);

    /**
     * Write another HwBlob into this blob at the specified location.
     *
     * @param offset location to write value
     * @param blob data to write
     * @throws IndexOutOfBoundsException if [offset, offset + blob's size] outside of the range of
     *     this blob.
     */
    public native final void putBlob(long offset, HwBlob blob);

    /**
     * @return current handle of HwBlob for reference in a parcelled binder transaction
     */
    public native final long handle();

    /**
     * Convert a primitive to a wrapped array for boolean.
     *
     * @param array from array
     * @return transformed array
     */
    public static Boolean[] wrapArray(@NonNull boolean[] array) {
        final int n = array.length;
        Boolean[] wrappedArray = new Boolean[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    /**
     * Convert a primitive to a wrapped array for long.
     *
     * @param array from array
     * @return transformed array
     */
    public static Long[] wrapArray(@NonNull long[] array) {
        final int n = array.length;
        Long[] wrappedArray = new Long[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    /**
     * Convert a primitive to a wrapped array for byte.
     *
     * @param array from array
     * @return transformed array
     */
    public static Byte[] wrapArray(@NonNull byte[] array) {
        final int n = array.length;
        Byte[] wrappedArray = new Byte[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    /**
     * Convert a primitive to a wrapped array for short.
     *
     * @param array from array
     * @return transformed array
     */
    public static Short[] wrapArray(@NonNull short[] array) {
        final int n = array.length;
        Short[] wrappedArray = new Short[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    /**
     * Convert a primitive to a wrapped array for int.
     *
     * @param array from array
     * @return transformed array
     */
    public static Integer[] wrapArray(@NonNull int[] array) {
        final int n = array.length;
        Integer[] wrappedArray = new Integer[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    /**
     * Convert a primitive to a wrapped array for float.
     *
     * @param array from array
     * @return transformed array
     */
    public static Float[] wrapArray(@NonNull float[] array) {
        final int n = array.length;
        Float[] wrappedArray = new Float[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    /**
     * Convert a primitive to a wrapped array for double.
     *
     * @param array from array
     * @return transformed array
     */
    public static Double[] wrapArray(@NonNull double[] array) {
        final int n = array.length;
        Double[] wrappedArray = new Double[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    // Returns address of the "freeFunction".
    private static native final long native_init();

    private native final void native_setup(int size);

    static {
        long freeFunction = native_init();

        sNativeRegistry = new NativeAllocationRegistry(
                HwBlob.class.getClassLoader(),
                freeFunction,
                128 /* size */);
    }

    private long mNativeContext;
}


