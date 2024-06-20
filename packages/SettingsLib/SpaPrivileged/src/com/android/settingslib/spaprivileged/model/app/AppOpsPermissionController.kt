/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.model.app

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

interface IAppOpsPermissionController {
    val isAllowedFlow: Flow<Boolean>
    fun setAllowed(allowed: Boolean)
}

class AppOpsPermissionController(
    context: Context,
    private val app: ApplicationInfo,
    appOps: AppOps,
    private val permission: String,
    private val packageManagers: IPackageManagers = PackageManagers,
    private val appOpsController: IAppOpsController = AppOpsController(context, app, appOps),
) : IAppOpsPermissionController {
    override val isAllowedFlow: Flow<Boolean> = appOpsController.modeFlow.map { mode ->
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true

            AppOpsManager.MODE_DEFAULT -> {
                with(packageManagers) { app.hasGrantPermission(permission) }
            }

            else -> false
        }
    }.conflate().onEach { Log.d(TAG, "isAllowed: $it") }.flowOn(Dispatchers.Default)

    override fun setAllowed(allowed: Boolean) {
        appOpsController.setAllowed(allowed)
    }

    private companion object {
        private const val TAG = "AppOpsPermissionControl"
    }
}
