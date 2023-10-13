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

class PackageAppOpUpgrade(private val policy: PackageAppOpPolicy) {
    fun MutateStateScope.upgradePackageState(
        packageState: PackageState,
        userId: Int,
        version: Int,
    ) {
        if (version <= 2) {
            with(policy) {
                val appOpMode =
                    getAppOpMode(
                        packageState.packageName,
                        userId,
                        AppOpsManager.OPSTR_RUN_IN_BACKGROUND
                    )
                setAppOpMode(
                    packageState.packageName,
                    userId,
                    AppOpsManager.OPSTR_RUN_ANY_IN_BACKGROUND,
                    appOpMode
                )
            }
        }
    }
}
