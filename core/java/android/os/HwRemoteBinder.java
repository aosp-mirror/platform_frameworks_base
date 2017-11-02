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
public class HwRemoteBinder implements IHwBinder {
    private static final String TAG = "HwRemoteBinder";

    private static final NativeAllocationRegistry sNativeRegistry;

    public HwRemoteBinder() {
        native_setup_empty();

        sNativeRegistry.registerNativeAllocation(
                this,
                mNativeContext);
    }

    @Override
    public IHwInterface queryLocalInterface(String descriptor) {
        return null;
    }

    @Override
    public native final void transact(
            int code, HwParcel request, HwParcel reply, int flags)
        throws RemoteException;

    public native boolean linkToDeath(DeathRecipient recipient, long cookie);
    public native boolean unlinkToDeath(DeathRecipient recipient);

    private static native final long native_init();

    private native final void native_setup_empty();

    static {
        long freeFunction = native_init();

        sNativeRegistry = new NativeAllocationRegistry(
                HwRemoteBinder.class.getClassLoader(),
                freeFunction,
                128 /* size */);
    }

    private static final void sendDeathNotice(DeathRecipient recipient, long cookie) {
        recipient.serviceDied(cookie);
    }

    private long mNativeContext;

    @Override
    public final native boolean equals(Object other);
    @Override
    public final native int hashCode();
}
