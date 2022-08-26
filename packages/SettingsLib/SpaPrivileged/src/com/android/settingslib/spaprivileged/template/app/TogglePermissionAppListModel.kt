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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.framework.api.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.rememberContext
import com.android.settingslib.spa.framework.util.asyncMapItem
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import kotlinx.coroutines.flow.Flow

interface TogglePermissionAppListModel<T : AppRecord> {
    val pageTitleResId: Int
    val switchTitleResId: Int
    val footerResId: Int

    fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>): Flow<List<T>> =
        appListFlow.asyncMapItem(::transformItem)

    fun transformItem(app: ApplicationInfo): T
    fun filter(userIdFlow: Flow<Int>, recordListFlow: Flow<List<T>>): Flow<List<T>>

    @Composable
    fun isAllowed(record: T): State<Boolean?>

    fun isChangeable(record: T): Boolean
    fun setAllowed(record: T, newAllowed: Boolean)
}

interface TogglePermissionAppListModelFactory {
    fun createModel(
        permission: String,
        context: Context,
    ): TogglePermissionAppListModel<out AppRecord>

    fun createPageProviders(): List<SettingsPageProvider> = listOf(
        TogglePermissionAppListPageProvider(this),
        TogglePermissionAppInfoPageProvider(this),
    )

    @Composable
    fun EntryItem(permissionType: String) {
        val listModel = rememberModel(permissionType)
        Preference(
            object : PreferenceModel {
                override val title = stringResource(listModel.pageTitleResId)
                override val onClick = TogglePermissionAppListPageProvider.navigator(permissionType)
            }
        )
    }
}

@Composable
internal fun TogglePermissionAppListModelFactory.rememberModel(permission: String) =
    rememberContext { context -> createModel(permission, context) }
