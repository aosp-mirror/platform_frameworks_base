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

import android.annotation.AppIdInt;
import android.annotation.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An interface for legacy code to read permission data in order to maintain compatibility.
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface LegacyPermissionDataProvider {
    /**
     * Get all the legacy permissions currently registered in the system.
     *
     * @return the legacy permissions
     */
    @NonNull
    List<LegacyPermission> getLegacyPermissions();

    /**
     * Get all the package names requesting app op permissions.
     *
     * @return a map of app op permission names to package names requesting them
     */
    @NonNull
    Map<String, Set<String>> getAllAppOpPermissionPackages();

    /**
     * Get the legacy permission state of an app ID, either a package or a shared user.
     *
     * @param appId the app ID
     * @return the legacy permission state
     */
    @NonNull
    LegacyPermissionState getLegacyPermissionState(@AppIdInt int appId);

    /**
     * Get the GIDs computed from the permission state of a UID, either a package or a shared user.
     *
     * @param uid the UID
     * @return the GIDs for the UID
     */
    @NonNull
    int[] getGidsForUid(int uid);

    /**
     * This method should be in PermissionManagerServiceInternal, however it is made available here
     * as well to avoid serious performance regression in writePermissionSettings(), which seems to
     * be a hot spot and we should delay calling this method until wre are actually writing the
     * file, instead of every time an async write is requested.
     *
     * @see PermissionManagerServiceInternal#writeLegacyPermissionStateTEMP()
     */
    void writeLegacyPermissionStateTEMP();
}
