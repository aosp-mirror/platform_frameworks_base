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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.UserHandle
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.rememberContext
import com.android.settingslib.spa.framework.util.asyncMapItem
import com.android.settingslib.spaprivileged.model.app.AppRecord
import kotlinx.coroutines.flow.Flow

/**
 * Implement this interface to build an App List which toggles a permission on / off.
 */
interface TogglePermissionAppListModel<T : AppRecord> {
    val pageTitleResId: Int
    val switchTitleResId: Int
    val footerResId: Int
    val switchRestrictionKeys: List<String>
        get() = emptyList()

    val enhancedConfirmationKey: String?
        get() = null

    /**
     * Loads the extra info for the App List, and generates the [AppRecord] List.
     *
     * Default is implemented by [transformItem]
     */
    fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>): Flow<List<T>> =
        appListFlow.asyncMapItem(::transformItem)

    /**
     * Loads the extra info for one app, and generates the [AppRecord].
     *
     * This must be implemented, because when show the App Info page for single app, this will be
     * used instead of [transform].
     */
    fun transformItem(app: ApplicationInfo): T

    /**
     * Filters the [AppRecord] list.
     *
     * @return the [AppRecord] list which will be displayed.
     */
    fun filter(userIdFlow: Flow<Int>, recordListFlow: Flow<List<T>>): Flow<List<T>>

    /**
     * Gets whether the permission is allowed for the given app.
     */
    @Composable
    fun isAllowed(record: T): () -> Boolean?

    /**
     * Gets whether the permission on / off is changeable for the given app.
     */
    fun isChangeable(record: T): Boolean

    /**
     * Sets whether the permission is allowed for the given app.
     */
    fun setAllowed(record: T, newAllowed: Boolean)

    @Composable
    fun InfoPageAdditionalContent(record: T, isAllowed: () -> Boolean?) {}
}

/**
 * And if the given app has system or root UID.
 *
 * If true, the app gets all permissions, so the permission toggle always not changeable.
 */
fun AppRecord.isSystemOrRootUid(): Boolean =
    UserHandle.getAppId(app.uid) in listOf(Process.SYSTEM_UID, Process.ROOT_UID)

/**
 * Gets whether the permission on / off is changeable for the given app.
 *
 * And if the given app has system or root UID, it gets all permissions, so always not changeable.
 */
fun <T : AppRecord> TogglePermissionAppListModel<T>.isChangeableWithSystemUidCheck(
    record: T,
): Boolean = !record.isSystemOrRootUid() && isChangeable(record)


interface TogglePermissionAppListProvider {
    val permissionType: String

    fun createModel(context: Context): TogglePermissionAppListModel<out AppRecord>

    fun buildAppListInjectEntry(): SettingsEntryBuilder =
        TogglePermissionAppListPageProvider.buildInjectEntry(permissionType) { createModel(it) }

    /**
     * Gets the route to the toggle permission App List page.
     *
     * Expose route to enable enter from non-SPA pages.
     */
    fun getAppListRoute(): String =
        TogglePermissionAppListPageProvider.getRoute(permissionType)

    /**
     * Gets the route prefix to the toggle permission App Info page.
     *
     * Expose route prefix to enable enter from non-SPA pages.
     */
    fun getAppInfoRoutePrefix(): String =
        TogglePermissionAppInfoPageProvider.getRoutePrefix(permissionType)

    @Composable
    fun InfoPageEntryItem(app: ApplicationInfo) {
        val listModel = rememberContext(::createModel)
        listModel.TogglePermissionAppInfoPageEntryItem(permissionType, app)
    }
}

class TogglePermissionAppListTemplate(
    allProviders: List<TogglePermissionAppListProvider>,
) {
    private val listModelProviderMap = allProviders.associateBy { it.permissionType }

    fun createPageProviders(): List<SettingsPageProvider> = listOf(
        TogglePermissionAppListPageProvider(this),
        TogglePermissionAppInfoPageProvider(this),
    )

    @Composable
    internal fun rememberModel(permissionType: String) = rememberContext { context ->
        listModelProviderMap.getValue(permissionType).createModel(context)
    }
}
