/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.pm.ApplicationInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.permission.LegacyPermissionState;

@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public abstract class SettingBase {
    int pkgFlags;
    int pkgPrivateFlags;

    /**
     * The legacy permission state that is read from package settings persistence for migration.
     * This state here can not reflect the current permission state and should not be used for
     * purposes other than migration.
     */
    @Deprecated
    protected final LegacyPermissionState mLegacyPermissionsState;

    SettingBase(int pkgFlags, int pkgPrivateFlags) {
        setFlags(pkgFlags);
        setPrivateFlags(pkgPrivateFlags);
        mLegacyPermissionsState = new LegacyPermissionState();
    }

    SettingBase(SettingBase orig) {
        mLegacyPermissionsState = new LegacyPermissionState();
        doCopy(orig);
    }

    public void copyFrom(SettingBase orig) {
        doCopy(orig);
    }

    private void doCopy(SettingBase orig) {
        pkgFlags = orig.pkgFlags;
        pkgPrivateFlags = orig.pkgPrivateFlags;
        mLegacyPermissionsState.copyFrom(orig.mLegacyPermissionsState);
    }

    @Deprecated
    public LegacyPermissionState getLegacyPermissionState() {
        return mLegacyPermissionsState;
    }

    void setFlags(int pkgFlags) {
        this.pkgFlags = pkgFlags
                & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_EXTERNAL_STORAGE);
    }

    void setPrivateFlags(int pkgPrivateFlags) {
        this.pkgPrivateFlags = pkgPrivateFlags
                & (ApplicationInfo.PRIVATE_FLAG_PRIVILEGED
                | ApplicationInfo.PRIVATE_FLAG_OEM
                | ApplicationInfo.PRIVATE_FLAG_VENDOR
                | ApplicationInfo.PRIVATE_FLAG_PRODUCT
                | ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT
                | ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER
                | ApplicationInfo.PRIVATE_FLAG_ODM);
    }
}
