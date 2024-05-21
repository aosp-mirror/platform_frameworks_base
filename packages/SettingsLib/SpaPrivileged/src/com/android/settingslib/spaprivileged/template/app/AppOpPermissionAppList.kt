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

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.spa.framework.util.asyncMapItem
import com.android.settingslib.spa.framework.util.filterItem
import com.android.settingslib.spaprivileged.model.app.AppOps
import com.android.settingslib.spaprivileged.model.app.AppOpsPermissionController
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.model.app.IAppOpsPermissionController
import com.android.settingslib.spaprivileged.model.app.IPackageManagers
import com.android.settingslib.spaprivileged.model.app.PackageManagers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class AppOpPermissionRecord(
    override val app: ApplicationInfo,
    val hasRequestBroaderPermission: Boolean,
    val hasRequestPermission: Boolean,
    var appOpsPermissionController: IAppOpsPermissionController,
) : AppRecord

abstract class AppOpPermissionListModel(
    protected val context: Context,
    private val packageManagers: IPackageManagers = PackageManagers,
) : TogglePermissionAppListModel<AppOpPermissionRecord> {

    abstract val appOps: AppOps
    abstract val permission: String

    override val enhancedConfirmationKey: String?
        get() = AppOpsManager.opToPublicName(appOps.op)

    /**
     * When set, specifies the broader permission who trumps the [permission].
     *
     * When trumped, the [permission] is not changeable and model shows the [permission] as allowed.
     */
    open val broaderPermission: String? = null

    /**
     * Indicates whether [permission] has protection level appop flag.
     *
     * If true, it uses getAppOpPermissionPackages() to fetch bits to decide whether the permission
     * is requested.
     */
    open val permissionHasAppOpFlag: Boolean = true

    /** These not changeable packages will also be hidden from app list. */
    private val notChangeablePackages =
        setOf("android", "com.android.systemui", context.packageName)

    private fun createAppOpsPermissionController(app: ApplicationInfo) =
        AppOpsPermissionController(context, app, appOps, permission)

    private fun createRecord(
        app: ApplicationInfo,
        hasRequestPermission: Boolean
    ): AppOpPermissionRecord =
        with(packageManagers) {
            AppOpPermissionRecord(
                app = app,
                hasRequestBroaderPermission = broaderPermission?.let {
                    app.hasRequestPermission(it)
                } ?: false,
                hasRequestPermission = hasRequestPermission,
                appOpsPermissionController = createAppOpsPermissionController(app),
            )
        }

    override fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>) =
        if (permissionHasAppOpFlag) {
            userIdFlow
                .map { userId -> packageManagers.getAppOpPermissionPackages(userId, permission) }
                .combine(appListFlow) { packageNames, appList ->
                    appList.map { app ->
                        createRecord(
                            app = app,
                            hasRequestPermission = app.packageName in packageNames,
                        )
                    }
                }
        } else {
            appListFlow.asyncMapItem { app ->
                with(packageManagers) { createRecord(app, app.hasRequestPermission(permission)) }
            }
        }

    override fun transformItem(app: ApplicationInfo) =
        with(packageManagers) {
            createRecord(
                app = app,
                hasRequestPermission = app.hasRequestPermission(permission),
            )
        }

    override fun filter(userIdFlow: Flow<Int>, recordListFlow: Flow<List<AppOpPermissionRecord>>) =
        recordListFlow.filterItem(::isChangeable)

    /**
     * Defining the default behavior as permissible as long as the package requested this permission
     * (This means pre-M gets approval during install time; M apps gets approval during runtime).
     */
    @Composable
    override fun isAllowed(record: AppOpPermissionRecord): () -> Boolean? {
        if (record.hasRequestBroaderPermission) {
            // Broader permission trumps the specific permission.
            return { true }
        }
        val isAllowed by record.appOpsPermissionController.isAllowedFlow
            .collectAsStateWithLifecycle(initialValue = null)
        return { isAllowed }
    }

    override fun isChangeable(record: AppOpPermissionRecord) =
        record.hasRequestPermission &&
            !record.hasRequestBroaderPermission &&
            record.app.packageName !in notChangeablePackages

    override fun setAllowed(record: AppOpPermissionRecord, newAllowed: Boolean) {
        record.appOpsPermissionController.setAllowed(newAllowed)
    }
}
