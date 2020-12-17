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

package com.android.server.role;

import android.annotation.NonNull;
import android.annotation.UserIdInt;

import java.util.List;

/**
 * A provider for migrating legacy "role"s to their actual role implementation.
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface LegacyRoleHolderProvider {
    /**
     * Get the list of holders of a legacy "role" before its actual role is introduced.
     * <p>
     * This method will only be called for the first time a role is made available in the platform.
     *
     * @param roleName the name of the role
     * @param userId the user ID
     * @return a list of holders for the given role
     */
    @NonNull
    List<String> getLegacyRoleHolders(@NonNull String roleName, @UserIdInt int userId);
}
