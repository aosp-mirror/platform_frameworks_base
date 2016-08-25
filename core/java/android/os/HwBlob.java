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

    public native final void putBool(long offset, boolean x);
    public native final void putInt8(long offset, byte x);
    public native final void putInt16(long offset, short x);
    public native final void putInt32(long offset, int x);
    public native final void putInt64(long offset, long x);
    public native final void putFloat(long offset, float x);
    public native final void putDouble(long offset, double x);
    public native final void putString(long offset, String x);

    public native final void putBlob(long offset, HwBlob blob);

    public native final long handle();

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


