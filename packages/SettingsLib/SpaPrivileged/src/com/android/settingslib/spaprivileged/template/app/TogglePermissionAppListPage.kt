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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.api.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import kotlinx.coroutines.flow.Flow

private const val NAME = "TogglePermissionAppList"
private const val PERMISSION = "permission"

internal class TogglePermissionAppListPageProvider(
    private val factory: TogglePermissionAppListModelFactory,
) : SettingsPageProvider {
    override val name = NAME

    override val arguments = listOf(
        navArgument(PERMISSION) { type = NavType.StringType },
    )

    @Composable
    override fun Page(arguments: Bundle?) {
        checkNotNull(arguments)
        val permissionType = checkNotNull(arguments.getString(PERMISSION))
        TogglePermissionAppList(permissionType)
    }

    @Composable
    private fun TogglePermissionAppList(permissionType: String) {
        val listModel = factory.rememberModel(permissionType)
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
        @Composable
        internal fun navigator(permissionType: String) = navigator(route = "$NAME/$permissionType")
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
        val allowed = listModel.isAllowed(record)
        return remember {
            derivedStateOf {
                when (allowed.value) {
                    true -> context.getString(R.string.app_permission_summary_allowed)
                    false -> context.getString(R.string.app_permission_summary_not_allowed)
                    else -> ""
                }
            }
        }
    }
}
