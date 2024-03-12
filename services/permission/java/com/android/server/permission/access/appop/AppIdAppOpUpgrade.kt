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

import android.app.AppOpsManager
import com.android.server.permission.access.MutateStateScope
import com.android.server.pm.pkg.PackageState

class AppIdAppOpUpgrade(private val policy: AppIdAppOpPolicy) {
    fun MutateStateScope.upgradePackageState(
        packageState: PackageState,
        userId: Int,
        version: Int,
    ) {
        if (version <= 2) {
            with(policy) {
                val appOpMode =
                    getAppOpMode(packageState.appId, userId, AppOpsManager.OPSTR_RUN_IN_BACKGROUND)
                setAppOpMode(
                    packageState.appId,
                    userId,
                    AppOpsManager.OPSTR_RUN_ANY_IN_BACKGROUND,
                    appOpMode
                )
            }
        }
        if (version <= 13) {
            val permissionName = AppOpsManager.opToPermission(AppOpsManager.OP_SCHEDULE_EXACT_ALARM)
            if (permissionName in packageState.androidPackage!!.requestedPermissions) {
                with(policy) {
                    val appOpMode =
                        getAppOpMode(
                            packageState.appId,
                            userId,
                            AppOpsManager.OPSTR_SCHEDULE_EXACT_ALARM
                        )
                    val defaultAppOpMode =
                        AppOpsManager.opToDefaultMode(AppOpsManager.OP_SCHEDULE_EXACT_ALARM)
                    if (appOpMode == defaultAppOpMode) {
                        setAppOpMode(
                            packageState.appId,
                            userId,
                            AppOpsManager.OPSTR_SCHEDULE_EXACT_ALARM,
                            AppOpsManager.MODE_ALLOWED
                        )
                    }
                }
            }
        }
    }
}
