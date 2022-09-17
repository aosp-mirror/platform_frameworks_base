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
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.os.bundleOf
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.util.getStringArg
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.model.app.userId
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProvider
import com.android.settingslib.spaprivileged.template.preference.RestrictedSwitchPreference
import kotlinx.coroutines.flow.Flow

private const val ENTRY_NAME = "AppList"
private const val PERMISSION = "permission"
private const val PAGE_NAME = "TogglePermissionAppList"
private val PAGE_PARAMETER = listOf(
    navArgument(PERMISSION) { type = NavType.StringType },
)

internal class TogglePermissionAppListPageProvider(
    private val appListTemplate: TogglePermissionAppListTemplate,
) : SettingsPageProvider {
    override val name = PAGE_NAME

    override val parameter = PAGE_PARAMETER

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val permissionType = parameter.getStringArg(PERMISSION, arguments)!!
        val appListPage = SettingsPage.create(name, parameter, arguments)
        val appInfoPage = TogglePermissionAppInfoPageProvider.buildPageData(permissionType)
        val entryList = mutableListOf<SettingsEntry>()
        // TODO: add more categories, such as personal, work, cloned, etc.
        for (category in listOf("personal")) {
            entryList.add(
                SettingsEntryBuilder.createLinkFrom("${ENTRY_NAME}_$category", appListPage)
                    .setLink(toPage = appInfoPage)
                    .setIsAllowSearch(false)
                    .build()
            )
        }
        return entryList
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        TogglePermissionAppList(arguments?.getString(PERMISSION)!!)
    }

    @Composable
    private fun TogglePermissionAppList(permissionType: String) {
        val listModel = appListTemplate.rememberModel(permissionType)
        val context = LocalContext.current
        val internalListModel = remember {
            TogglePermissionInternalAppListModel(context, listModel)
        }
        AppListPage(
            title = stringResource(listModel.pageTitleResId),
            listModel = internalListModel,
        ) { itemModel ->
            AppListItem(
                itemModel = itemModel,
                onClick = TogglePermissionAppInfoPageProvider.navigator(
                    permissionType = permissionType,
                    app = itemModel.record.app,
                ),
            )
        }
    }

    companion object {
        /**
         * Gets the route to this page.
         *
         * Expose route to enable enter from non-SPA pages.
         */
        internal fun getRoute(permissionType: String) = "$PAGE_NAME/$permissionType"

        @Composable
        internal fun navigator(permissionType: String) = navigator(route = getRoute(permissionType))

        internal fun buildInjectEntry(permissionType: String): SettingsEntryBuilder {
            val appListPage = SettingsPage.create(
                PAGE_NAME, PAGE_PARAMETER, bundleOf(PERMISSION to permissionType))
            return SettingsEntryBuilder.createInject(owner = appListPage).setIsAllowSearch(false)
        }
    }
}

private class TogglePermissionInternalAppListModel<T : AppRecord>(
    private val context: Context,
    private val listModel: TogglePermissionAppListModel<T>,
) : AppListModel<T> {
    override fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>) =
        listModel.transform(userIdFlow, appListFlow)

    override fun filter(userIdFlow: Flow<Int>, option: Int, recordListFlow: Flow<List<T>>) =
        listModel.filter(userIdFlow, recordListFlow)

    @Composable
    override fun getSummary(option: Int, record: T): State<String> {
        val restrictionsProvider = remember {
            val restrictions = Restrictions(
                userId = record.app.userId,
                keys = listModel.switchRestrictionKeys,
            )
            RestrictionsProvider(context, restrictions)
        }
        val restrictedMode = restrictionsProvider.restrictedMode.observeAsState()
        val allowed = listModel.isAllowed(record)
        return remember {
            derivedStateOf {
                RestrictedSwitchPreference.getSummary(
                    context = context,
                    restrictedMode = restrictedMode.value,
                    noRestrictedSummary = getNoRestrictedSummary(allowed),
                    checked = allowed,
                ).value
            }
        }
    }

    private fun getNoRestrictedSummary(allowed: State<Boolean?>) = derivedStateOf {
        when (allowed.value) {
            true -> context.getString(R.string.app_permission_summary_allowed)
            false -> context.getString(R.string.app_permission_summary_not_allowed)
            else -> context.getString(R.string.summary_placeholder)
        }
    }
}
