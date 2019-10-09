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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;

import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;

/** @hide */
@SystemApi
@TestApi
public class HwParcel {
    private static final String TAG = "HwParcel";

    @IntDef(prefix = { "STATUS_" }, value = {
        STATUS_SUCCESS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {}

    /**
     * Success return error for a transaction. Written to parcels
     * using writeStatus.
     */
    public static final int STATUS_SUCCESS      = 0;

    private static final NativeAllocationRegistry sNativeRegistry;

    @UnsupportedAppUsage
    private HwParcel(boolean allocate) {
        native_setup(allocate);

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    /**
     * Creates an initialized and empty parcel.
     */
    public HwParcel() {
        native_setup(true /* allocate */);

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    /**
     * Writes an interface token into the parcel used to verify that
     * a transaction has made it to the right type of interface.
     *
     * @param interfaceName fully qualified name of interface message
     *     is being sent to.
     */
    @FastNative
    public native final void writeInterfaceToken(String interfaceName);
    /**
     * Writes a boolean value to the end of the parcel.
     * @param val to write
     */
    @FastNative
    public native final void writeBool(boolean val);
    /**
     * Writes a byte value to the end of the parcel.
     * @param val to write
     */
    @FastNative
    public native final void writeInt8(byte val);
    /**
     * Writes a short value to the end of the parcel.
     * @param val to write
     */
    @FastNative
    public native final void writeInt16(short val);
    /**
     * Writes a int value to the end of the parcel.
     * @param val to write
     */
    @FastNative
    public native final void writeInt32(int val);
    /**
     * Writes a long value to the end of the parcel.
     * @param val to write
     */
    @FastNative
    public native final void writeInt64(long val);
    /**
     * Writes a float value to the end of the parcel.
     * @param val to write
     */
    @FastNative
    public native final void writeFloat(float val);
    /**
     * Writes a double value to the end of the parcel.
     * @param val to write
     */
    @FastNative
    public native final void writeDouble(double val);
    /**
     * Writes a String value to the end of the parcel.
     *
     * Note, this will be converted to UTF-8 when it is written.
     *
     * @param val to write
     */
    @FastNative
    public native final void writeString(String val);
    /**
     * Writes a native handle (without duplicating the underlying
     * file descriptors) to the end of the parcel.
     *
     * @param val to write
     */
    @FastNative
    public native final void writeNativeHandle(@Nullable NativeHandle val);

    /**
     * Writes an array of boolean values to the end of the parcel.
     * @param val to write
     */
    @FastNative
    private native final void writeBoolVector(boolean[] val);
    /**
     * Writes an array of byte values to the end of the parcel.
     * @param val to write
     */
    @FastNative
    private native final void writeInt8Vector(byte[] val);
    /**
     * Writes an array of short values to the end of the parcel.
     * @param val to write
     */
    @FastNative
    private native final void writeInt16Vector(short[] val);
    /**
     * Writes an array of int values to the end of the parcel.
     * @param val to write
     */
    @FastNative
    private native final void writeInt32Vector(int[] val);
    /**
     * Writes an array of long values to the end of the parcel.
     * @param val to write
     */
    @FastNative
    private native final void writeInt64Vector(long[] val);
    /**
     * Writes an array of float values to the end of the parcel.
     * @param val to write
     */
    @FastNative
    private native final void writeFloatVector(float[] val);
    /**
     * Writes an array of double values to the end of the parcel.
     * @param val to write
     */
    @FastNative
    private native final void writeDoubleVector(double[] val);
    /**
     * Writes an array of String values to the end of the parcel.
     *
     * Note, these will be converted to UTF-8 as they are written.
     *
     * @param val to write
     */
    @FastNative
    private native final void writeStringVector(String[] val);
    /**
     * Writes an array of native handles to the end of the parcel.
     *
     * Individual elements may be null but not the whole array.
     *
     * @param val array of {@link NativeHandle} objects to write
     */
    @FastNative
    private native final void writeNativeHandleVector(NativeHandle[] val);

    /**
     * Helper method to write a list of Booleans to val.
     * @param val list to write
     */
    public final void writeBoolVector(ArrayList<Boolean> val) {
        final int n = val.size();
        boolean[] array = new boolean[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeBoolVector(array);
    }

    /**
     * Helper method to write a list of Booleans to the end of the parcel.
     * @param val list to write
     */
    public final void writeInt8Vector(ArrayList<Byte> val) {
        final int n = val.size();
        byte[] array = new byte[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeInt8Vector(array);
    }

    /**
     * Helper method to write a list of Shorts to the end of the parcel.
     * @param val list to write
     */
    public final void writeInt16Vector(ArrayList<Short> val) {
        final int n = val.size();
        short[] array = new short[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeInt16Vector(array);
    }

    /**
     * Helper method to write a list of Integers to the end of the parcel.
     * @param val list to write
     */
    public final void writeInt32Vector(ArrayList<Integer> val) {
        final int n = val.size();
        int[] array = new int[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeInt32Vector(array);
    }

    /**
     * Helper method to write a list of Longs to the end of the parcel.
     * @param val list to write
     */
    public final void writeInt64Vector(ArrayList<Long> val) {
        final int n = val.size();
        long[] array = new long[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeInt64Vector(array);
    }

    /**
     * Helper method to write a list of Floats to the end of the parcel.
     * @param val list to write
     */
    public final void writeFloatVector(ArrayList<Float> val) {
        final int n = val.size();
        float[] array = new float[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeFloatVector(array);
    }

    /**
     * Helper method to write a list of Doubles to the end of the parcel.
     * @param val list to write
     */
    public final void writeDoubleVector(ArrayList<Double> val) {
        final int n = val.size();
        double[] array = new double[n];
        for (int i = 0; i < n; ++i) {
            array[i] = val.get(i);
        }

        writeDoubleVector(array);
    }

    /**
     * Helper method to write a list of Strings to the end of the parcel.
     * @param val list to write
     */
    public final void writeStringVector(ArrayList<String> val) {
        writeStringVector(val.toArray(new String[val.size()]));
    }

    /**
     * Helper method to write a list of native handles to the end of the parcel.
     * @param val list of {@link NativeHandle} objects to write
     */
    public final void writeNativeHandleVector(@NonNull ArrayList<NativeHandle> val) {
        writeNativeHandleVector(val.toArray(new NativeHandle[val.size()]));
    }

    /**
     * Write a hwbinder object to the end of the parcel.
     * @param binder value to write
     */
    @FastNative
    public native final void writeStrongBinder(IHwBinder binder);

    /**
     * Checks to make sure that the interface name matches the name written by the parcel
     * sender by writeInterfaceToken
     *
     * @throws SecurityException interface doesn't match
     */
    public native final void enforceInterface(String interfaceName);

    /**
     * Reads a boolean value from the current location in the parcel.
     * @return value parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final boolean readBool();
    /**
     * Reads a byte value from the current location in the parcel.
     * @return value parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final byte readInt8();
    /**
     * Reads a short value from the current location in the parcel.
     * @return value parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final short readInt16();
    /**
     * Reads a int value from the current location in the parcel.
     * @return value parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final int readInt32();
    /**
     * Reads a long value from the current location in the parcel.
     * @return value parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final long readInt64();
    /**
     * Reads a float value from the current location in the parcel.
     * @return value parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final float readFloat();
    /**
     * Reads a double value from the current location in the parcel.
     * @return value parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final double readDouble();
    /**
     * Reads a String value from the current location in the parcel.
     * @return value parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final String readString();
    /**
     * Reads a native handle (without duplicating the underlying file
     * descriptors) from the parcel. These file descriptors will only
     * be open for the duration that the binder window is open. If they
     * are needed further, you must call {@link NativeHandle#dup()}.
     *
     * @return a {@link NativeHandle} instance parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final @Nullable NativeHandle readNativeHandle();
    /**
     * Reads an embedded native handle (without duplicating the underlying
     * file descriptors) from the parcel. These file descriptors will only
     * be open for the duration that the binder window is open. If they
     * are needed further, you must call {@link NativeHandle#dup()}. You
     * do not need to call close on the NativeHandle returned from this.
     *
     * @param parentHandle handle from which to read the embedded object
     * @param offset offset into parent
     * @return a {@link NativeHandle} instance parsed from the parcel
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final @Nullable NativeHandle readEmbeddedNativeHandle(
            long parentHandle, long offset);

    /**
     * Reads an array of boolean values from the parcel.
     * @return array of parsed values
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final boolean[] readBoolVectorAsArray();
    /**
     * Reads an array of byte values from the parcel.
     * @return array of parsed values
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final byte[] readInt8VectorAsArray();
    /**
     * Reads an array of short values from the parcel.
     * @return array of parsed values
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final short[] readInt16VectorAsArray();
    /**
     * Reads an array of int values from the parcel.
     * @return array of parsed values
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final int[] readInt32VectorAsArray();
    /**
     * Reads an array of long values from the parcel.
     * @return array of parsed values
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final long[] readInt64VectorAsArray();
    /**
     * Reads an array of float values from the parcel.
     * @return array of parsed values
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final float[] readFloatVectorAsArray();
    /**
     * Reads an array of double values from the parcel.
     * @return array of parsed values
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final double[] readDoubleVectorAsArray();
    /**
     * Reads an array of String values from the parcel.
     * @return array of parsed values
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final String[] readStringVectorAsArray();
    /**
     * Reads an array of native handles from the parcel.
     * @return array of {@link NativeHandle} objects
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    private native final NativeHandle[] readNativeHandleAsArray();

    /**
     * Convenience method to read a Boolean vector as an ArrayList.
     * @return array of parsed values.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final ArrayList<Boolean> readBoolVector() {
        Boolean[] array = HwBlob.wrapArray(readBoolVectorAsArray());

        return new ArrayList<Boolean>(Arrays.asList(array));
    }

    /**
     * Convenience method to read a Byte vector as an ArrayList.
     * @return array of parsed values.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final ArrayList<Byte> readInt8Vector() {
        Byte[] array = HwBlob.wrapArray(readInt8VectorAsArray());

        return new ArrayList<Byte>(Arrays.asList(array));
    }

    /**
     * Convenience method to read a Short vector as an ArrayList.
     * @return array of parsed values.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final ArrayList<Short> readInt16Vector() {
        Short[] array = HwBlob.wrapArray(readInt16VectorAsArray());

        return new ArrayList<Short>(Arrays.asList(array));
    }

    /**
     * Convenience method to read a Integer vector as an ArrayList.
     * @return array of parsed values.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final ArrayList<Integer> readInt32Vector() {
        Integer[] array = HwBlob.wrapArray(readInt32VectorAsArray());

        return new ArrayList<Integer>(Arrays.asList(array));
    }

    /**
     * Convenience method to read a Long vector as an ArrayList.
     * @return array of parsed values.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final ArrayList<Long> readInt64Vector() {
        Long[] array = HwBlob.wrapArray(readInt64VectorAsArray());

        return new ArrayList<Long>(Arrays.asList(array));
    }

    /**
     * Convenience method to read a Float vector as an ArrayList.
     * @return array of parsed values.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final ArrayList<Float> readFloatVector() {
        Float[] array = HwBlob.wrapArray(readFloatVectorAsArray());

        return new ArrayList<Float>(Arrays.asList(array));
    }

    /**
     * Convenience method to read a Double vector as an ArrayList.
     * @return array of parsed values.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final ArrayList<Double> readDoubleVector() {
        Double[] array = HwBlob.wrapArray(readDoubleVectorAsArray());

        return new ArrayList<Double>(Arrays.asList(array));
    }

    /**
     * Convenience method to read a String vector as an ArrayList.
     * @return array of parsed values.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final ArrayList<String> readStringVector() {
        return new ArrayList<String>(Arrays.asList(readStringVectorAsArray()));
    }

    /**
     * Convenience method to read a vector of native handles as an ArrayList.
     * @return array of {@link NativeHandle} objects.
     * @throws IllegalArgumentException if the parcel has no more data
     */
    public final @NonNull ArrayList<NativeHandle> readNativeHandleVector() {
        return new ArrayList<NativeHandle>(Arrays.asList(readNativeHandleAsArray()));
    }

    /**
     * Reads a strong binder value from the parcel.
     * @return binder object read from parcel or null if no binder can be read
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final IHwBinder readStrongBinder();

    /**
     * Read opaque segment of data as a blob.
     * @return blob of size expectedSize
     * @throws IllegalArgumentException if the parcel has no more data
     */
    @FastNative
    public native final HwBlob readBuffer(long expectedSize);

    /**
     * Read a buffer written using scatter gather.
     *
     * @param expectedSize size that buffer should be
     * @param parentHandle handle from which to read the embedded buffer
     * @param offset offset into parent
     * @param nullable whether or not to allow for a null return
     * @return blob of data with size expectedSize
     * @throws NoSuchElementException if an embedded buffer is not available to read
     * @throws IllegalArgumentException if expectedSize < 0
     * @throws NullPointerException if the transaction specified the blob to be null
     *    but nullable is false
     */
    @FastNative
    public native final HwBlob readEmbeddedBuffer(
            long expectedSize, long parentHandle, long offset,
            boolean nullable);

    /**
     * Write a buffer into the transaction.
     * @param blob blob to write into the parcel.
     */
    @FastNative
    public native final void writeBuffer(HwBlob blob);
    /**
     * Write a status value into the blob.
     * @param status value to write
     */
    @FastNative
    public native final void writeStatus(int status);
    /**
     * @throws IllegalArgumentException if a success vaue cannot be read
     * @throws RemoteException if success value indicates a transaction error
     */
    @FastNative
    public native final void verifySuccess();
    /**
     * Should be called to reduce memory pressure when this object no longer needs
     * to be written to.
     */
    @FastNative
    public native final void releaseTemporaryStorage();
    /**
     * Should be called when object is no longer needed to reduce possible memory
     * pressure if the Java GC does not get to this object in time.
     */
    @FastNative
    public native final void release();

    /**
     * Sends the parcel to the specified destination.
     */
    public native final void send();

    // Returns address of the "freeFunction".
    private static native final long native_init();

    @FastNative
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

