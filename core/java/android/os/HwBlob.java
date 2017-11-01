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

import libcore.util.NativeAllocationRegistry;

/** @hide */
public class HwBlob {
    private static final String TAG = "HwBlob";

    private static final NativeAllocationRegistry sNativeRegistry;

    public HwBlob(int size) {
        native_setup(size);

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    public native final boolean getBool(long offset);
    public native final byte getInt8(long offset);
    public native final short getInt16(long offset);
    public native final int getInt32(long offset);
    public native final long getInt64(long offset);
    public native final float getFloat(long offset);
    public native final double getDouble(long offset);
    public native final String getString(long offset);

    /**
      The copyTo... methods copy the blob's data, starting from the given
      byte offset, into the array. A total of "size" _elements_ are copied.
     */
    public native final void copyToBoolArray(long offset, boolean[] array, int size);
    public native final void copyToInt8Array(long offset, byte[] array, int size);
    public native final void copyToInt16Array(long offset, short[] array, int size);
    public native final void copyToInt32Array(long offset, int[] array, int size);
    public native final void copyToInt64Array(long offset, long[] array, int size);
    public native final void copyToFloatArray(long offset, float[] array, int size);
    public native final void copyToDoubleArray(long offset, double[] array, int size);

    public native final void putBool(long offset, boolean x);
    public native final void putInt8(long offset, byte x);
    public native final void putInt16(long offset, short x);
    public native final void putInt32(long offset, int x);
    public native final void putInt64(long offset, long x);
    public native final void putFloat(long offset, float x);
    public native final void putDouble(long offset, double x);
    public native final void putString(long offset, String x);

    public native final void putBoolArray(long offset, boolean[] x);
    public native final void putInt8Array(long offset, byte[] x);
    public native final void putInt16Array(long offset, short[] x);
    public native final void putInt32Array(long offset, int[] x);
    public native final void putInt64Array(long offset, long[] x);
    public native final void putFloatArray(long offset, float[] x);
    public native final void putDoubleArray(long offset, double[] x);

    public native final void putBlob(long offset, HwBlob blob);

    public native final long handle();

    public static Boolean[] wrapArray(@NonNull boolean[] array) {
        final int n = array.length;
        Boolean[] wrappedArray = new Boolean[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    public static Long[] wrapArray(@NonNull long[] array) {
        final int n = array.length;
        Long[] wrappedArray = new Long[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    public static Byte[] wrapArray(@NonNull byte[] array) {
        final int n = array.length;
        Byte[] wrappedArray = new Byte[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    public static Short[] wrapArray(@NonNull short[] array) {
        final int n = array.length;
        Short[] wrappedArray = new Short[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    public static Integer[] wrapArray(@NonNull int[] array) {
        final int n = array.length;
        Integer[] wrappedArray = new Integer[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

    public static Float[] wrapArray(@NonNull float[] array) {
        final int n = array.length;
        Float[] wrappedArray = new Float[n];
        for (int i = 0; i < n; ++i) {
          wrappedArray[i] = array[i];
        }
        return wrappedArray;
    }

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


