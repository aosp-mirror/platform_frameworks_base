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
import androidx.compose.runtime.getValue
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
import com.android.settingslib.spa.framework.compose.rememberContext
import com.android.settingslib.spa.framework.util.getStringArg
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.framework.compose.getPlaceholder
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.model.app.userId
import com.android.settingslib.spaprivileged.model.enterprise.EnhancedConfirmation
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl
import com.android.settingslib.spaprivileged.model.enterprise.rememberRestrictedMode
import com.android.settingslib.spaprivileged.template.preference.RestrictedSwitchPreferenceModel
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
        val appListPage = SettingsPage.create(name, parameter = parameter, arguments = arguments)
        val appInfoPage = TogglePermissionAppInfoPageProvider.buildPageData(permissionType)
        // TODO: add more categories, such as personal, work, cloned, etc.
        return listOf("personal").map { category ->
            SettingsEntryBuilder.createLinkFrom("${ENTRY_NAME}_$category", appListPage)
                .setLink(toPage = appInfoPage)
                .build()
        }
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val permissionType = arguments?.getString(PERMISSION)!!
        appListTemplate.rememberModel(permissionType).TogglePermissionAppList(permissionType)
    }

    companion object {
        /**
         * Gets the route to this page.
         *
         * Expose route to enable enter from non-SPA pages.
         */
        fun getRoute(permissionType: String) = "$PAGE_NAME/$permissionType"

        fun buildInjectEntry(
            permissionType: String,
            listModelSupplier: (Context) -> TogglePermissionAppListModel<out AppRecord>,
        ): SettingsEntryBuilder {
            val appListPage = SettingsPage.create(
                name = PAGE_NAME,
                parameter = PAGE_PARAMETER,
                arguments = bundleOf(PERMISSION to permissionType)
            )
            return SettingsEntryBuilder.createInject(owner = appListPage)
                .setUiLayoutFn {
                    val listModel = rememberContext(listModelSupplier)
                    Preference(
                        object : PreferenceModel {
                            override val title = stringResource(listModel.pageTitleResId)
                            override val onClick = navigator(route = getRoute(permissionType))
                        }
                    )
                }
        }
    }
}

@Composable
internal fun <T : AppRecord> TogglePermissionAppListModel<T>.TogglePermissionAppList(
    permissionType: String,
    restrictionsProviderFactory: RestrictionsProviderFactory = ::RestrictionsProviderImpl,
    appList: @Composable AppListInput<T>.() -> Unit = { AppList() },
) {
    val context = LocalContext.current
    AppListPage(
        title = stringResource(pageTitleResId),
        listModel = remember {
            TogglePermissionInternalAppListModel(
                context = context,
                permissionType = permissionType,
                listModel = this,
                restrictionsProviderFactory = restrictionsProviderFactory,
            )
        },
        appList = appList,
    )
}

internal class TogglePermissionInternalAppListModel<T : AppRecord>(
    private val context: Context,
    private val permissionType: String,
    private val listModel: TogglePermissionAppListModel<T>,
    private val restrictionsProviderFactory: RestrictionsProviderFactory,
) : AppListModel<T> {
    override fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>) =
        listModel.transform(userIdFlow, appListFlow)

    override fun filter(userIdFlow: Flow<Int>, option: Int, recordListFlow: Flow<List<T>>) =
        listModel.filter(userIdFlow, recordListFlow)

    @Composable
    override fun getSummary(option: Int, record: T) = getSummary(record)

    @Composable
    fun getSummary(record: T): () -> String {
        val restrictions = remember(record.app.userId,
                record.app.uid, record.app.packageName) {
            Restrictions(
                userId = record.app.userId,
                keys = listModel.switchRestrictionKeys,
                enhancedConfirmation = listModel.enhancedConfirmationKey?.let {
                    EnhancedConfirmation(
                        key = it,
                        uid = record.app.uid,
                        packageName = record.app.packageName)
                })
        }
        val restrictedMode by restrictionsProviderFactory.rememberRestrictedMode(restrictions)
        val allowed = listModel.isAllowed(record)
        return RestrictedSwitchPreferenceModel.getSummary(
            context = context,
            restrictedModeSupplier = { restrictedMode },
            summaryIfNoRestricted = { getSummaryIfNoRestricted(allowed()) },
            checked = allowed,
        )
    }

    private fun getSummaryIfNoRestricted(allowed: Boolean?): String = when (allowed) {
        true -> context.getString(R.string.app_permission_summary_allowed)
        false -> context.getString(R.string.app_permission_summary_not_allowed)
        null -> context.getPlaceholder()
    }

    @Composable
    override fun AppListItemModel<T>.AppItem() {
        AppListItem(
            onClick = TogglePermissionAppInfoPageProvider.navigator(
                permissionType = permissionType,
                app = record.app,
            ),
        )
    }
}
