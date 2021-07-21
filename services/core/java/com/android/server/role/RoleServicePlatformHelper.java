/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.role;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;

import java.util.Map;
import java.util.Set;

/**
 * Helper inside the platform for role service.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface RoleServicePlatformHelper {
    /**
     * Get the legacy role state stored in the platform.
     * <p>
     * The role state may come from two sources, either the different pre-role default app settings,
     * or the pre-modularization roles.xml file stored in platform.
     *
     * @param userId the user ID
     * @return a mapping of role name to its set of holders
     */
    @NonNull
    Map<String, Set<String>> getLegacyRoleState(@UserIdInt int userId);

    /**
     * Compute a hash for the current package state in the system.
     *
     * @param userId the user ID
     * @return the computed hash
     */
    @NonNull
    String computePackageStateHash(@UserIdInt int userId);
}
