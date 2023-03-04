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

import android.util.Log
import com.android.server.LocalServices
import com.android.server.appop.AppOpMigrationHelper
import com.android.server.permission.access.AccessPolicy
import com.android.server.pm.permission.PermissionMigrationHelper

object PackageVersionMigration {
    /**
     * This function returns a unified version for permissions and app-ops, this
     * version is assigned to all migrated packages during OTA upgrade. Later this version is used
     * in determining the upgrade steps for a package.
     */
    internal fun getVersion(userId: Int): Int {
        val permissionMigrationHelper =
            LocalServices.getService(PermissionMigrationHelper::class.java)
        val permissionVersion = permissionMigrationHelper.getLegacyPermissionsVersion(userId)

        val appOpMigrationHelper = LocalServices.getService(AppOpMigrationHelper::class.java)
        val appOpVersion = appOpMigrationHelper.legacyAppOpVersion

        return when {
            // Both files don't exist.
            permissionVersion == 0 && appOpVersion == -2 -> 0
            // Permission file doesn't exit, app op file exist w/o version.
            permissionVersion == 0 && appOpVersion == -1 -> 1
            // Both file exist but w/o any version.
            permissionVersion == -1 && appOpVersion == -1 -> 2
            // Permission file exist w/o version, app op file has version as 1.
            permissionVersion == -1 && appOpVersion == 1 -> 3
            // merging combination of versions based on released android version
            // permissions version 1-8 were released in Q, 9 in S and 10 in T
            // app ops version 1 was released in P, 3 in U.
            permissionVersion == 1 && appOpVersion == 1 -> 4
            permissionVersion == 2 && appOpVersion == 1 -> 5
            permissionVersion == 3 && appOpVersion == 1 -> 6
            permissionVersion == 4 && appOpVersion == 1 -> 7
            permissionVersion == 5 && appOpVersion == 1 -> 8
            permissionVersion == 6 && appOpVersion == 1 -> 9
            permissionVersion == 7 && appOpVersion == 1 -> 10
            permissionVersion == 8 && appOpVersion == 1 -> 11
            permissionVersion == 9 && appOpVersion == 1 -> 12
            permissionVersion == 10 && appOpVersion == 1 -> 13
            permissionVersion == 10 && appOpVersion == 3 -> AccessPolicy.VERSION_LATEST
            else -> {
                Log.w(
                    "PackageVersionMigration", "Version combination not recognized, permission" +
                        "version: $permissionVersion, app-op version: $appOpVersion"
                )
                AccessPolicy.VERSION_LATEST
            }
        }
    }
}
