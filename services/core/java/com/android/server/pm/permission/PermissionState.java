/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.permission;

import android.annotation.NonNull;
import android.annotation.UserIdInt;

import com.android.internal.annotations.GuardedBy;

/**
 * State for a single permission.
 */
public final class PermissionState {

    @NonNull
    private final Permission mPermission;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mGranted;

    @GuardedBy("mLock")
    private int mFlags;

    public PermissionState(@NonNull Permission permission) {
        mPermission = permission;
    }

    public PermissionState(@NonNull PermissionState other) {
        this(other.mPermission);

        mGranted = other.mGranted;
        mFlags = other.mFlags;
    }

    @NonNull
    public Permission getPermission() {
        return mPermission;
    }

    @NonNull
    public String getName() {
        return mPermission.getName();
    }

    @NonNull
    public int[] computeGids(@UserIdInt int userId) {
        return mPermission.computeGids(userId);
    }

    public boolean isGranted() {
        synchronized (mLock) {
            return mGranted;
        }
    }

    public boolean grant() {
        synchronized (mLock) {
            if (mGranted) {
                return false;
            }
            mGranted = true;
            UidPermissionState.invalidateCache();
            return true;
        }
    }

    public boolean revoke() {
        synchronized (mLock) {
            if (!mGranted) {
                return false;
            }
            mGranted = false;
            UidPermissionState.invalidateCache();
            return true;
        }
    }

    public int getFlags() {
        synchronized (mLock) {
            return mFlags;
        }
    }

    public boolean updateFlags(int flagMask, int flagValues) {
        synchronized (mLock) {
            final int newFlags = flagValues & flagMask;

            // Okay to do before the modification because we hold the lock.
            UidPermissionState.invalidateCache();

            final int oldFlags = mFlags;
            mFlags = (mFlags & ~flagMask) | newFlags;
            return mFlags != oldFlags;
        }
    }

    public boolean isDefault() {
        synchronized (mLock) {
            return !mGranted && mFlags == 0;
        }
    }
}
