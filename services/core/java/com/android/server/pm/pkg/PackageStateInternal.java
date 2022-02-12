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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.SigningDetails;
import android.util.SparseArray;

import com.android.server.pm.InstallSource;
import com.android.server.pm.PackageKeySetData;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.LegacyPermissionState;

import java.util.UUID;

/**
 * Exposes internal types for internal usage of {@link PackageState}.
 */
public interface PackageStateInternal extends PackageState {

    @NonNull
    AndroidPackage getPkg();

    // TODO: Remove in favor of exposing APIs directly?
    @NonNull
    PackageStateUnserialized getTransientState();

    @NonNull
    UUID getDomainSetId();

    @NonNull
    SigningDetails getSigningDetails();

    @NonNull
    InstallSource getInstallSource();

    // TODO: Remove this in favor of boolean APIs
    int getFlags();
    int getPrivateFlags();

    @NonNull
    SparseArray<? extends PackageUserStateInternal> getUserStates();

    /**
     * @return the result of {@link #getUserStates()}.get(userId) or
     * {@link PackageUserState#DEFAULT} if the state doesn't exist.
     */
    @NonNull
    default PackageUserStateInternal getUserStateOrDefault(@UserIdInt int userId) {
        PackageUserStateInternal userState = getUserStates().get(userId);
        return userState == null ? PackageUserStateInternal.DEFAULT : userState;
    }

    @NonNull
    LegacyPermissionState getLegacyPermissionState();

    @Nullable
    String getRealName();

    boolean isLoading();

    @NonNull
    String getPathString();

    float getLoadingProgress();

    @NonNull
    PackageKeySetData getKeySetData();
}
