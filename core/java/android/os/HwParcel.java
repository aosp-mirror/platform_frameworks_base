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
    public native final void writeInt8(byte val);
    public native final void writeInt16(short val);
    public native final void writeInt32(int val);
    public native final void writeInt64(long val);
    public native final void writeFloat(float val);
    public native final void writeDouble(double val);
    public native final void writeString(String val);

    public native final void writeInt8Array(int size, byte[] val);
    public native final void writeInt8Vector(byte[] val);
    public native final void writeInt16Array(int size, short[] val);
    public native final void writeInt16Vector(short[] val);
    public native final void writeInt32Array(int size, int[] val);
    public native final void writeInt32Vector(int[] val);
    public native final void writeInt64Array(int size, long[] val);
    public native final void writeInt64Vector(long[] val);
    public native final void writeFloatArray(int size, float[] val);
    public native final void writeFloatVector(float[] val);
    public native final void writeDoubleArray(int size, double[] val);
    public native final void writeDoubleVector(double[] val);
    public native final void writeStringArray(int size, String[] val);
    public native final void writeStringVector(String[] val);

    public native final void writeStrongBinder(IHwBinder binder);

    public native final void enforceInterface(String interfaceName);
    public native final byte readInt8();
    public native final short readInt16();
    public native final int readInt32();
    public native final long readInt64();
    public native final float readFloat();
    public native final double readDouble();
    public native final String readString();

    public native final byte[] readInt8Array(int size);
    public native final byte[] readInt8Vector();
    public native final short[] readInt16Array(int size);
    public native final short[] readInt16Vector();
    public native final int[] readInt32Array(int size);
    public native final int[] readInt32Vector();
    public native final long[] readInt64Array(int size);
    public native final long[] readInt64Vector();
    public native final float[] readFloatArray(int size);
    public native final float[] readFloatVector();
    public native final double[] readDoubleArray(int size);
    public native final double[] readDoubleVector();
    public native final String[] readStringArray(int size);
    public native final String[] readStringVector();

    public native final IHwBinder readStrongBinder();

    public native final void writeStatus(int status);
    public native final void verifySuccess();
    public native final void releaseTemporaryStorage();

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

