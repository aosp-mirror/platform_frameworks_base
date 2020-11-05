/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.devicepolicy;

import android.app.admin.DeviceStateCache;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;

/**
 * Implementation of {@link DeviceStateCache}, to which {@link DevicePolicyManagerService} pushes
 * device state.
 *
 */
public class DeviceStateCacheImpl extends DeviceStateCache {
    /**
     * Lock object. For simplicity we just always use this as the lock. We could use each object
     * as a lock object to make it more fine-grained, but that'd make copy-paste error-prone.
     */
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mIsDeviceProvisioned = false;

    @Override
    public boolean isDeviceProvisioned() {
        return mIsDeviceProvisioned;
    }

    /** Update the device provisioned flag for USER_SYSTEM */
    public void setDeviceProvisioned(boolean provisioned) {
        synchronized (mLock) {
            mIsDeviceProvisioned = provisioned;
        }
    }

    /** Dump content */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Device state cache:");
        pw.increaseIndent();
        pw.println("Device provisioned: " + mIsDeviceProvisioned);
        pw.decreaseIndent();
    }
}
