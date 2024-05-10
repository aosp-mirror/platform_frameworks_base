/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.pm.SigningDetails;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.pm.pkg.component.ParsedProcess;
import com.android.server.pm.permission.LegacyPermissionState;

import java.util.List;

/** @hide */
public interface SharedUserApi {

    @NonNull
    String getName();

    @UserIdInt
    int getAppId();

    // flags that are associated with this uid, regardless of any package flags
    int getUidFlags();
    int getPrivateUidFlags();

    // The lowest targetSdkVersion of all apps in the sharedUserSetting, used to assign seinfo so
    // that all apps within the sharedUser run in the same selinux context.
    int getSeInfoTargetSdkVersion();

    /**
     * @return the list of packages that uses this shared UID
     */
    @NonNull
    List<AndroidPackage> getPackages();

    @NonNull
    ArraySet<? extends PackageStateInternal> getPackageStates();

    // It is possible for a system app to leave shared user ID by an update.
    // We need to keep track of the shadowed PackageSettings so that it is possible to uninstall
    // the update and revert the system app back into the original shared user ID.
    @NonNull
    ArraySet<? extends PackageStateInternal> getDisabledPackageStates();

    @NonNull
    SigningDetails getSigningDetails();

    @NonNull
    ArrayMap<String, ParsedProcess> getProcesses();

    boolean isPrivileged();

    @NonNull
    LegacyPermissionState getSharedUserLegacyPermissionState();
}
