/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.admin.DevicePolicyCache;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;

/**
 * Implementation of {@link DevicePolicyCache}, to which {@link DevicePolicyManagerService} pushes
 * policies.
 *
 * TODO Move other copies of policies into this class too.
 */
public class DevicePolicyCacheImpl extends DevicePolicyCache {
    /**
     * Lock object. For simplicity we just always use this as the lock. We could use each object
     * as a lock object to make it more fine-grained, but that'd make copy-paste error-prone.
     */
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseBooleanArray mScreenCaptureDisabled = new SparseBooleanArray();

    public void onUserRemoved(int userHandle) {
        synchronized (mLock) {
            mScreenCaptureDisabled.delete(userHandle);
        }
    }

    @Override
    public boolean getScreenCaptureDisabled(int userHandle) {
        synchronized (mLock) {
            return mScreenCaptureDisabled.get(userHandle);
        }
    }

    public void setScreenCaptureDisabled(int userHandle, boolean disabled) {
        synchronized (mLock) {
            mScreenCaptureDisabled.put(userHandle, disabled);
        }
    }
}
