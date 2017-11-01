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

import java.util.ArrayList;
import java.util.Arrays;

import libcore.util.NativeAllocationRegistry;

/** @hide */
public class HwParcel {
    private static final String TAG = "HwParcel";

    public static final int STATUS_SUCCESS      = 0;
    public static final int STATUS_ERROR        = -1;

    private static final NativeAllocationRegistry sNativeRegistry;

    private HwParcel(boolean allocate) {
        native_setup(allocate);

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    public HwParcel() {
        native_setup(true /* allocate */);

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    public native final void writeInterfaceToken(String interfaceName);
    public native final void writeBool(boolean val);
    public native final void writeInt8(byte val);
    public native final void writeInt16(short val);
    public native final void writeInt32(int val);
    public native final void writeInt64(long val);
    public native final void writeFloat(float val);
    public native final void writeDouble(double val);
    public native final void writeString(String val);

    private native final void writeBoolVector(boolean[] val);
    private native final void writeInt8Vector(byte[] val);
    private native final void writeInt16Vector(short[] val);
    private native final void writeInt32Vector(int[] val);
    private native final void writeInt64Vector(long[] val);
    private native final void writeFloatVector(float[] val);
    private native final void writeDoubleVector(double[] val);
    private native final void writeStringVector(String[] val);

    public final void writeBoolVector(ArrayList<Boolean> val) {
        final int n = val.size();
        boolean[] array = new boolean[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeBoolVector(array);
    }

    public final void writeInt8Vector(ArrayList<Byte> val) {
        final int n = val.size();
        byte[] array = new byte[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeInt8Vector(array);
    }

    public final void writeInt16Vector(ArrayList<Short> val) {
        final int n = val.size();
        short[] array = new short[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeInt16Vector(array);
    }

    public final void writeInt32Vector(ArrayList<Integer> val) {
        final int n = val.size();
        int[] array = new int[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeInt32Vector(array);
    }

    public final void writeInt64Vector(ArrayList<Long> val) {
        final int n = val.size();
        long[] array = new long[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeInt64Vector(array);
    }

    public final void writeFloatVector(ArrayList<Float> val) {
        final int n = val.size();
        float[] array = new float[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeFloatVector(array);
    }

    public final void writeDoubleVector(ArrayList<Double> val) {
        final int n = val.size();
        double[] array = new double[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeDoubleVector(array);
    }

    public final void writeStringVector(ArrayList<String> val) {
        writeStringVector(val.toArray(new String[val.size()]));
    }

    public native final void writeStrongBinder(IHwBinder binder);

    public native final void enforceInterface(String interfaceName);
    public native final boolean readBool();
    public native final byte readInt8();
    public native final short readInt16();
    public native final int readInt32();
    public native final long readInt64();
    public native final float readFloat();
    public native final double readDouble();
    public native final String readString();

    private native final boolean[] readBoolVectorAsArray();
    private native final byte[] readInt8VectorAsArray();
    private native final short[] readInt16VectorAsArray();
    private native final int[] readInt32VectorAsArray();
    private native final long[] readInt64VectorAsArray();
    private native final float[] readFloatVectorAsArray();
    private native final double[] readDoubleVectorAsArray();
    private native final String[] readStringVectorAsArray();

    public final ArrayList<Boolean> readBoolVector() {
        Boolean[] array = HwBlob.wrapArray(readBoolVectorAsArray());

        return new ArrayList<Boolean>(Arrays.asList(array));
    }

    public final ArrayList<Byte> readInt8Vector() {
        Byte[] array = HwBlob.wrapArray(readInt8VectorAsArray());

        return new ArrayList<Byte>(Arrays.asList(array));
    }

    public final ArrayList<Short> readInt16Vector() {
        Short[] array = HwBlob.wrapArray(readInt16VectorAsArray());

        return new ArrayList<Short>(Arrays.asList(array));
    }

    public final ArrayList<Integer> readInt32Vector() {
        Integer[] array = HwBlob.wrapArray(readInt32VectorAsArray());

        return new ArrayList<Integer>(Arrays.asList(array));
    }

    public final ArrayList<Long> readInt64Vector() {
        Long[] array = HwBlob.wrapArray(readInt64VectorAsArray());

        return new ArrayList<Long>(Arrays.asList(array));
    }

    public final ArrayList<Float> readFloatVector() {
        Float[] array = HwBlob.wrapArray(readFloatVectorAsArray());

        return new ArrayList<Float>(Arrays.asList(array));
    }

    public final ArrayList<Double> readDoubleVector() {
        Double[] array = HwBlob.wrapArray(readDoubleVectorAsArray());

        return new ArrayList<Double>(Arrays.asList(array));
    }

    public final ArrayList<String> readStringVector() {
        return new ArrayList<String>(Arrays.asList(readStringVectorAsArray()));
    }

    public native final IHwBinder readStrongBinder();

    // Handle is stored as part of the blob.
    public native final HwBlob readBuffer(long expectedSize);

    public native final HwBlob readEmbeddedBuffer(
            long expectedSize, long parentHandle, long offset,
            boolean nullable);

    public native final void writeBuffer(HwBlob blob);

    public native final void writeStatus(int status);
    public native final void verifySuccess();
    public native final void releaseTemporaryStorage();
    public native final void release();

    public native final void send();

    // Returns address of the "freeFunction".
    private static native final long native_init();

    private native final void native_setup(boolean allocate);

    static {
        long freeFunction = native_init();

        sNativeRegistry = new NativeAllocationRegistry(
                HwParcel.class.getClassLoader(),
                freeFunction,
                128 /* size */);
    }

    private long mNativeContext;
}

