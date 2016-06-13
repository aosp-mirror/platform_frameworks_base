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

import android.annotation.UserIdInt;
import android.os.UserHandle;
import android.util.SparseArray;

/**
 * Manages package names that need special protection.
 *
 * TODO: This class should persist the information by itself, and also keeps track of device admin
 * packages for all users.  Then PMS.isPackageDeviceAdmin() should use it instead of talking
 * to DPMS.
 */
public class ProtectedPackages {
    @UserIdInt
    private int mDeviceOwnerUserId;

    private String mDeviceOwnerPackage;

    private SparseArray<String> mProfileOwnerPackages;

    private final Object mLock = new Object();

    /**
     * Sets the device/profile owner information.
     */
    public void setDeviceAndProfileOwnerPackages(
            int deviceOwnerUserId, String deviceOwnerPackage,
            SparseArray<String> profileOwnerPackages) {
        synchronized (mLock) {
            mDeviceOwnerUserId = deviceOwnerUserId;
            mDeviceOwnerPackage =
                    (deviceOwnerUserId == UserHandle.USER_NULL) ? null : deviceOwnerPackage;
            mProfileOwnerPackages = (profileOwnerPackages == null) ? null
                    : profileOwnerPackages.clone();
        }
    }

    private boolean hasDeviceOwnerOrProfileOwner(int userId, String packageName) {
        if (packageName == null) {
            return false;
        }
        synchronized (mLock) {
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
        }
        return false;
    }

    /**
     * Whether a package or the components in a package's enabled state can be changed
     * by other callers than itself.
     */
    public boolean canPackageStateBeChanged(@UserIdInt int userId, String packageName) {
        return hasDeviceOwnerOrProfileOwner(userId, packageName);
    }

    /**
     * Whether a package's data be cleared.
     */
    public boolean canPackageBeWiped(@UserIdInt int userId, String packageName) {
        return hasDeviceOwnerOrProfileOwner(userId, packageName);
    }
}
