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

import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyManager;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Indicates which user is screen capture disallowed on. Can be {@link UserHandle#USER_NULL},
     * {@link UserHandle#USER_ALL} or a concrete user ID.
     */
    @GuardedBy("mLock")
    private int mScreenCaptureDisallowedUser = UserHandle.USER_NULL;

    @GuardedBy("mLock")
    private final SparseIntArray mPasswordQuality = new SparseIntArray();

    @GuardedBy("mLock")
    private final SparseIntArray mPermissionPolicy = new SparseIntArray();

    /** Maps to {@code ActiveAdmin.mAdminCanGrantSensorsPermissions}. */
    private final AtomicBoolean mCanGrantSensorsPermissions = new AtomicBoolean(false);

    public void onUserRemoved(int userHandle) {
        synchronized (mLock) {
            mPasswordQuality.delete(userHandle);
            mPermissionPolicy.delete(userHandle);
        }
    }

    @Override
    public boolean isScreenCaptureAllowed(int userHandle) {
        synchronized (mLock) {
            return mScreenCaptureDisallowedUser != UserHandle.USER_ALL
                    && mScreenCaptureDisallowedUser != userHandle;
        }
    }

    public int getScreenCaptureDisallowedUser() {
        synchronized (mLock) {
            return mScreenCaptureDisallowedUser;
        }
    }

    public void setScreenCaptureDisallowedUser(int userHandle) {
        synchronized (mLock) {
            mScreenCaptureDisallowedUser = userHandle;
        }
    }

    @Override
    public int getPasswordQuality(@UserIdInt int userHandle) {
        synchronized (mLock) {
            return mPasswordQuality.get(userHandle,
                    DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
        }
    }

    /** Updat the password quality cache for the given user */
    public void setPasswordQuality(int userHandle, int quality) {
        synchronized (mLock) {
            mPasswordQuality.put(userHandle, quality);
        }
    }

    @Override
    public int getPermissionPolicy(@UserIdInt int userHandle) {
        synchronized (mLock) {
            return mPermissionPolicy.get(userHandle,
                    DevicePolicyManager.PERMISSION_POLICY_PROMPT);
        }
    }

    /** Update the permission policy for the given user. */
    public void setPermissionPolicy(@UserIdInt int userHandle, int policy) {
        synchronized (mLock) {
            mPermissionPolicy.put(userHandle, policy);
        }
    }

    @Override
    public boolean canAdminGrantSensorsPermissions() {
        return mCanGrantSensorsPermissions.get();
    }

    /** Sets admin control over permission grants. */
    public void setAdminCanGrantSensorsPermissions(boolean canGrant) {
        mCanGrantSensorsPermissions.set(canGrant);
    }

    /** Dump content */
    public void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("Device policy cache:");
            pw.increaseIndent();
            pw.println("Screen capture disallowed user: " + mScreenCaptureDisallowedUser);
            pw.println("Password quality: " + mPasswordQuality);
            pw.println("Permission policy: " + mPermissionPolicy);
            pw.println("Admin can grant sensors permission: " + mCanGrantSensorsPermissions.get());
            pw.decreaseIndent();
        }
    }
}
