/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.template.app

import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_DEFAULT
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import com.android.settingslib.spa.framework.util.filterItem
import com.android.settingslib.spaprivileged.model.app.AppOpsController
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.model.app.IAppOpsController
import com.android.settingslib.spaprivileged.model.app.IPackageManagers
import com.android.settingslib.spaprivileged.model.app.PackageManagers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class AppOpPermissionRecord(
    override val app: ApplicationInfo,
    val hasRequestPermission: Boolean,
    var appOpsController: IAppOpsController,
) : AppRecord

abstract class AppOpPermissionListModel(
    private val context: Context,
    private val packageManagers: IPackageManagers = PackageManagers,
) : TogglePermissionAppListModel<AppOpPermissionRecord> {

    abstract val appOp: Int
    abstract val permission: String

    /** These not changeable packages will also be hidden from app list. */
    private val notChangeablePackages =
        setOf("android", "com.android.systemui", context.packageName)

    override fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>) =
        userIdFlow.map { userId ->
            packageManagers.getAppOpPermissionPackages(userId, permission)
        }.combine(appListFlow) { packageNames, appList ->
            appList.map { app ->
                AppOpPermissionRecord(
                    app = app,
                    hasRequestPermission = app.packageName in packageNames,
                    appOpsController = AppOpsController(context = context, app = app, op = appOp),
                )
            }
        }

    override fun transformItem(app: ApplicationInfo) = AppOpPermissionRecord(
        app = app,
        hasRequestPermission = with(packageManagers) { app.hasRequestPermission(permission) },
        appOpsController = AppOpsController(context = context, app = app, op = appOp),
    )

    override fun filter(userIdFlow: Flow<Int>, recordListFlow: Flow<List<AppOpPermissionRecord>>) =
        recordListFlow.filterItem(::isChangeable)

    /**
     * Defining the default behavior as permissible as long as the package requested this permission
     * (This means pre-M gets approval during install time; M apps gets approval during runtime).
     */
    @Composable
    override fun isAllowed(record: AppOpPermissionRecord): State<Boolean?> {
        val mode = record.appOpsController.mode.observeAsState()
        return remember {
            derivedStateOf {
                when (mode.value) {
                    null -> null
                    MODE_ALLOWED -> true
                    MODE_DEFAULT -> with(packageManagers) {
                        record.app.hasGrantPermission(permission)
                    }
                    else -> false
                }
            }
        }
    }

    override fun isChangeable(record: AppOpPermissionRecord) =
        record.hasRequestPermission && record.app.packageName !in notChangeablePackages

    override fun setAllowed(record: AppOpPermissionRecord, newAllowed: Boolean) {
        record.appOpsController.setAllowed(newAllowed)
    }
}
