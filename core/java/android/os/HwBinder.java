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
import java.util.NoSuchElementException;
import libcore.util.NativeAllocationRegistry;

/** @hide */
public abstract class HwBinder implements IHwBinder {
    private static final String TAG = "HwBinder";

    private static final NativeAllocationRegistry sNativeRegistry;

    public HwBinder() {
        native_setup();

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    @Override
    public final native void transact(
            int code, HwParcel request, HwParcel reply, int flags)
        throws RemoteException;

    public abstract void onTransact(
            int code, HwParcel request, HwParcel reply, int flags)
        throws RemoteException;

    public native final void registerService(String serviceName)
        throws RemoteException;

    public static native final IHwBinder getService(
            String iface,
            String serviceName)
        throws RemoteException, NoSuchElementException;

    public static native final void configureRpcThreadpool(
            long maxThreads, boolean callerWillJoin);

    public static native final void joinRpcThreadpool();

    // Returns address of the "freeFunction".
    private static native final long native_init();

    private native final void native_setup();

    static {
        long freeFunction = native_init();

        sNativeRegistry = new NativeAllocationRegistry(
                HwBinder.class.getClassLoader(),
                freeFunction,
                128 /* size */);
    }

    private long mNativeContext;
}
