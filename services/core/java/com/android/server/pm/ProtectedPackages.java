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

package com.android.server.pm;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

/**
 * Manages package names that need special protection.
 *
 * TODO: This class should persist the information by itself, and also keeps track of device admin
 * packages for all users.  Then PMS.isPackageDeviceAdmin() should use it instead of talking
 * to DPMS.
 */
public class ProtectedPackages {
    @UserIdInt
    @GuardedBy("this")
    private int mDeviceOwnerUserId;

    @Nullable
    @GuardedBy("this")
    private String mDeviceOwnerPackage;

    @Nullable
    @GuardedBy("this")
    private SparseArray<String> mProfileOwnerPackages;

    @Nullable
    @GuardedBy("this")
    private final String mDeviceProvisioningPackage;

    private final Context mContext;

    public ProtectedPackages(Context context) {
        mContext = context;
        mDeviceProvisioningPackage = mContext.getResources().getString(
                R.string.config_deviceProvisioningPackage);
    }

    /**
     * Sets the device/profile owner information.
     */
    public synchronized void setDeviceAndProfileOwnerPackages(
            int deviceOwnerUserId, String deviceOwnerPackage,
            SparseArray<String> profileOwnerPackages) {
        mDeviceOwnerUserId = deviceOwnerUserId;
        mDeviceOwnerPackage =
                (deviceOwnerUserId == UserHandle.USER_NULL) ? null : deviceOwnerPackage;
        mProfileOwnerPackages = (profileOwnerPackages == null) ? null
                : profileOwnerPackages.clone();
    }

    private synchronized boolean hasDeviceOwnerOrProfileOwner(int userId, String packageName) {
        if (packageName == null) {
            return false;
        }
        if (mDeviceOwnerPackage != null) {
            if ((mDeviceOwnerUserId == userId)
                    && (packageName.equals(mDeviceOwnerPackage))) {
                return true;
            }
        }
        if (mProfileOwnerPackages != null) {
            if (packageName.equals(mProfileOwnerPackages.get(userId))) {
                return true;
            }
        }
        return false;
    }

    public synchronized String getDeviceOwnerOrProfileOwnerPackage(int userId) {
        if (mDeviceOwnerUserId == userId) {
            return mDeviceOwnerPackage;
        }
        return mProfileOwnerPackages.get(userId);
    }

    /**
     * Returns {@code true} if a given package is protected. Otherwise, returns {@code false}.
     *
     * <p>A protected package means that, apart from the package owner, no system or privileged apps
     * can modify its data or package state.
     */
    private synchronized boolean isProtectedPackage(String packageName) {
        return packageName != null && packageName.equals(mDeviceProvisioningPackage);
    }

    /**
     * Returns {@code true} if a given package's state is protected. Otherwise, returns
     * {@code false}.
     *
     * <p>This is not applicable if the caller is the package owner.
     */
    public boolean isPackageStateProtected(@UserIdInt int userId, String packageName) {
        return hasDeviceOwnerOrProfileOwner(userId, packageName)
                || isProtectedPackage(packageName);
    }

    /**
     * Returns {@code true} if a given package's data is protected. Otherwise, returns
     * {@code false}.
     */
    public boolean isPackageDataProtected(@UserIdInt int userId, String packageName) {
        return hasDeviceOwnerOrProfileOwner(userId, packageName)
                || isProtectedPackage(packageName);
    }
}
