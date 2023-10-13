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

package com.android.server.permission.access.appop

import android.os.Process
import android.util.Slog
import com.android.server.LocalServices
import com.android.server.appop.AppOpMigrationHelper
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.PackageVersionMigration

class AppIdAppOpMigration {
    fun migrateUserState(state: MutableAccessState, userId: Int) {
        val legacyAppOpsManager = LocalServices.getService(AppOpMigrationHelper::class.java)!!
        if (!legacyAppOpsManager.hasLegacyAppOpState()) {
            return
        }

        val legacyAppIdAppOpModes = legacyAppOpsManager.getLegacyAppIdAppOpModes(userId)
        val version = PackageVersionMigration.getVersion(userId)

        val userState = state.mutateUserState(userId)!!
        val appIdAppOpModes = userState.mutateAppIdAppOpModes()
        legacyAppIdAppOpModes.forEach { (appId, legacyAppOpModes) ->
            val packageNames = state.externalState.appIdPackageNames[appId]
            // Non-application UIDs may not have an Android package but may still have app op state.
            if (packageNames == null && appId >= Process.FIRST_APPLICATION_UID) {
                Slog.w(LOG_TAG, "Dropping unknown app ID $appId when migrating app op state")
                return@forEach
            }

            val appOpModes = MutableIndexedMap<String, Int>()
            appIdAppOpModes[appId] = appOpModes
            legacyAppOpModes.forEach { (appOpName, appOpMode) -> appOpModes[appOpName] = appOpMode }

            if (packageNames != null) {
                val packageVersions = userState.mutatePackageVersions()
                packageNames.forEachIndexed { _, packageName ->
                    packageVersions[packageName] = version
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = AppIdAppOpMigration::class.java.simpleName
    }
}
