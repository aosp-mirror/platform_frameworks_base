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
import android.compat.annotation.UnsupportedAppUsage;

import libcore.util.NativeAllocationRegistry;

import java.util.NoSuchElementException;

/** @hide */
@SystemApi
public abstract class HwBinder implements IHwBinder {
    private static final String TAG = "HwBinder";

    private static final NativeAllocationRegistry sNativeRegistry;

    /**
     * Create and initialize a HwBinder object and the native objects
     * used to allow this to participate in hwbinder transactions.
     */
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

    /**
     * Process a hwbinder transaction.
     *
     * @param code interface specific code for interface.
     * @param request parceled transaction
     * @param reply object to parcel reply into
     * @param flags transaction flags to be chosen by wire protocol
     */
    public abstract void onTransact(
            int code, HwParcel request, HwParcel reply, int flags)
        throws RemoteException;

    /**
     * Registers this service with the hwservicemanager.
     *
     * @param serviceName instance name of the service
     */
    public native final void registerService(String serviceName)
        throws RemoteException;

    /**
     * Returns the specified service from the hwservicemanager. Does not retry.
     *
     * @param iface fully-qualified interface name for example foo.bar@1.3::IBaz
     * @param serviceName the instance name of the service for example default.
     * @throws NoSuchElementException when the service is unavailable
     */
    public static final IHwBinder getService(
            String iface,
            String serviceName)
        throws RemoteException, NoSuchElementException {
        return getService(iface, serviceName, false /* retry */);
    }
    /**
     * Returns the specified service from the hwservicemanager.
     * @param iface fully-qualified interface name for example foo.bar@1.3::IBaz
     * @param serviceName the instance name of the service for example default.
     * @param retry whether to wait for the service to start if it's not already started
     * @throws NoSuchElementException when the service is unavailable
     */
    public static native final IHwBinder getService(
            String iface,
            String serviceName,
            boolean retry)
        throws RemoteException, NoSuchElementException;

    /**
     * This allows getService to bypass the VINTF manifest for testing only.
     *
     * Disabled on user builds.
     * @hide
     */
    public static native final void setTrebleTestingOverride(
            boolean testingOverride);

    /**
     * Configures how many threads the process-wide hwbinder threadpool
     * has to process incoming requests.
     *
     * @param maxThreads total number of threads to create (includes this thread if
     *     callerWillJoin is true)
     * @param callerWillJoin whether joinRpcThreadpool will be called in advance
     */
    public static native final void configureRpcThreadpool(
            long maxThreads, boolean callerWillJoin);

    /**
     * Current thread will join hwbinder threadpool and process
     * commands in the pool. Should be called after configuring
     * a threadpool with callerWillJoin true and then registering
     * the provided service if this thread doesn't need to do
     * anything else.
     */
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
     *
     * On a non-user build, this method:
     * - tries to enable atracing (if enabled)
     * - tries to enable coverage dumps (if running in VTS)
     * - tries to enable record and replay (if running in VTS)
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
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    public static void reportSyspropChanged() {
        native_report_sysprop_change();
    }
}
