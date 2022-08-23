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
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.api.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.rememberContext
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.model.app.PackageManagers
import kotlinx.coroutines.Dispatchers

private const val PERMISSION = "permission"
private const val PACKAGE_NAME = "packageName"
private const val USER_ID = "userId"

open class TogglePermissionAppInfoPageProvider(
    private val factory: TogglePermissionAppListModelFactory,
) : SettingsPageProvider {
    override val name = "TogglePermissionAppInfoPage"

    override val arguments = listOf(
        navArgument(PERMISSION) { type = NavType.StringType },
        navArgument(PACKAGE_NAME) { type = NavType.StringType },
        navArgument(USER_ID) { type = NavType.IntType },
    )

    @Composable
    override fun Page(arguments: Bundle?) {
        checkNotNull(arguments)
        val permission = checkNotNull(arguments.getString(PERMISSION))
        val packageName = checkNotNull(arguments.getString(PACKAGE_NAME))
        val userId = arguments.getInt(USER_ID)
        val listModel = rememberContext { context -> factory.createModel(permission, context) }
        TogglePermissionAppInfoPage(listModel, packageName, userId)
    }

    fun getRoute(permissionType: String, packageName: String, userId: Int): String =
        "$name/$permissionType/$packageName/$userId"
}

@Composable
private fun TogglePermissionAppInfoPage(
    listModel: TogglePermissionAppListModel<out AppRecord>,
    packageName: String,
    userId: Int,
) {
    AppInfoPage(
        title = stringResource(listModel.pageTitleResId),
        packageName = packageName,
        userId = userId,
        footerText = stringResource(listModel.footerResId),
    ) {
        val model = createSwitchModel(listModel, packageName, userId)
        LaunchedEffect(model, Dispatchers.Default) {
            model.initState()
        }
        SwitchPreference(model)
    }
}

@Composable
private fun <T : AppRecord> createSwitchModel(
    listModel: TogglePermissionAppListModel<T>,
    packageName: String,
    userId: Int,
): TogglePermissionSwitchModel<T> {
    val record = remember {
        val app = PackageManagers.getApplicationInfoAsUser(packageName, userId)
        listModel.transformItem(app)
    }
    val context = LocalContext.current
    val isAllowed = listModel.isAllowed(record)
    return remember {
        TogglePermissionSwitchModel(context, listModel, record, isAllowed)
    }
}

private class TogglePermissionSwitchModel<T : AppRecord>(
    context: Context,
    private val listModel: TogglePermissionAppListModel<T>,
    private val record: T,
    isAllowed: State<Boolean?>,
) : SwitchPreferenceModel {
    override val title: String = context.getString(listModel.switchTitleResId)
    override val checked = isAllowed
    override val changeable = mutableStateOf(true)

    fun initState() {
        changeable.value = listModel.isChangeable(record)
    }

    override val onCheckedChange: (Boolean) -> Unit = { newChecked ->
        listModel.setAllowed(record, newChecked)
    }
}
