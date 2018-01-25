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

import android.annotation.SystemApi;

import libcore.util.NativeAllocationRegistry;

import java.util.NoSuchElementException;

/** @hide */
@SystemApi
public abstract class HwBinder implements IHwBinder {
    private static final String TAG = "HwBinder";

    private static final NativeAllocationRegistry sNativeRegistry;

    /** @hide */
    public HwBinder() {
        native_setup();

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    /** @hide */
    @Override
    public final native void transact(
            int code, HwParcel request, HwParcel reply, int flags)
        throws RemoteException;

    /** @hide */
    public abstract void onTransact(
            int code, HwParcel request, HwParcel reply, int flags)
        throws RemoteException;

    /** @hide */
    public native final void registerService(String serviceName)
        throws RemoteException;

    /** @hide */
    public static final IHwBinder getService(
            String iface,
            String serviceName)
        throws RemoteException, NoSuchElementException {
        return getService(iface, serviceName, false /* retry */);
    }
    /** @hide */
    public static native final IHwBinder getService(
            String iface,
            String serviceName,
            boolean retry)
        throws RemoteException, NoSuchElementException;

    /**
     * Configures how many threads the process-wide hwbinder threadpool
     * has to process incoming requests.
     *
     * @hide
     */
    @SystemApi
    public static native final void configureRpcThreadpool(
            long maxThreads, boolean callerWillJoin);

    /**
     * Current thread will join hwbinder threadpool and process
     * commands in the pool. Should be called after configuring
     * a threadpool with callerWillJoin true and then registering
     * the provided service if this thread doesn't need to do
     * anything else.
     *
     * @hide
     */
    @SystemApi
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

    private static native void native_report_sysprop_change();

    /**
     * Enable instrumentation if available.
     * @hide
     */
    public static void enableInstrumentation() {
        native_report_sysprop_change();
    }

    /**
     * Notifies listeners that a system property has changed
     *
     * TODO(b/72480743): remove this method
     *
     * @hide
     */
    public static void reportSyspropChanged() {
        native_report_sysprop_change();
    }
}
