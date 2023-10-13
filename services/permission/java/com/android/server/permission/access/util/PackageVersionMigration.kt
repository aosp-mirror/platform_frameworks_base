/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.permission.access.util

import com.android.server.LocalServices
import com.android.server.appop.AppOpMigrationHelper
import com.android.server.pm.permission.PermissionMigrationHelper

object PackageVersionMigration {
    /**
     * Maps existing permission and app-op version to a unified version during OTA upgrade. The new
     * unified version is used in determining the upgrade steps for a package (for both permission
     * and app-ops).
     *
     * @return unified permission/app-op version
     * @throws IllegalStateException if the method is called when there is nothing to migrate i.e.
     *   permission and app-op file does not exist.
     */
    internal fun getVersion(userId: Int): Int {
        val permissionMigrationHelper =
            LocalServices.getService(PermissionMigrationHelper::class.java)
        val permissionVersion = permissionMigrationHelper.getLegacyPermissionStateVersion(userId)

        val appOpMigrationHelper = LocalServices.getService(AppOpMigrationHelper::class.java)
        val appOpVersion = appOpMigrationHelper.legacyAppOpVersion

        return when {
            // Both files don't exist.
            permissionVersion == -1 && appOpVersion == -1 ->
                error("getVersion() called when there are no legacy files")
            // merging combination of versions based on released android version
            // permissions version 1-8 were released in Q, 9 in S, 10 in T and 11 in U
            // app ops version 1 was released in P, 3 in U.
            permissionVersion >= 11 && appOpVersion >= 3 -> 15
            permissionVersion >= 10 && appOpVersion >= 3 -> 14
            permissionVersion >= 10 && appOpVersion >= 1 -> 13
            permissionVersion >= 9 && appOpVersion >= 1 -> 12
            permissionVersion >= 8 && appOpVersion >= 1 -> 11
            permissionVersion >= 7 && appOpVersion >= 1 -> 10
            permissionVersion >= 6 && appOpVersion >= 1 -> 9
            permissionVersion >= 5 && appOpVersion >= 1 -> 8
            permissionVersion >= 4 && appOpVersion >= 1 -> 7
            permissionVersion >= 3 && appOpVersion >= 1 -> 6
            permissionVersion >= 2 && appOpVersion >= 1 -> 5
            permissionVersion >= 1 && appOpVersion >= 1 -> 4
            // Permission file exist w/o version, app op file has version as 1.
            permissionVersion >= 0 && appOpVersion >= 1 -> 3
            // Both file exist but w/o any version.
            permissionVersion >= 0 && appOpVersion >= 0 -> 2
            // Permission file doesn't exit, app op file exist w/o version.
            permissionVersion >= -1 && appOpVersion >= 0 -> 1
            // Re-run all upgrades to be safe.
            else -> 0
        }
    }
}
