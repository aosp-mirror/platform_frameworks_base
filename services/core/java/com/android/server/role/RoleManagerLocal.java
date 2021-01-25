/*
 * Copyright (C) 2019 The Android Open Source Project
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

import java.util.Map;
import java.util.Set;

/**
 * Internal calls into {@link RoleService}.
 *
 * @hide
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface RoleManagerLocal {
    /**
     * Get all roles and their holders.
     *
     * @param userId The user to query to roles for
     *
     * @return The roles and their holders
     */
    @NonNull
    Map<String, Set<String>> getRolesAndHolders(@UserIdInt int userId);
}
