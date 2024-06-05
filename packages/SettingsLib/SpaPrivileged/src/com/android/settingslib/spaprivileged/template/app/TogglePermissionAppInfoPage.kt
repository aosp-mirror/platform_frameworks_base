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
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.model.app.IPackageManagers
import com.android.settingslib.spaprivileged.model.app.PackageManagers
import com.android.settingslib.spaprivileged.model.app.toRoute
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderFactory
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProviderImpl
import com.android.settingslib.spaprivileged.template.preference.RestrictedSwitchPreference
import kotlinx.coroutines.Dispatchers

internal class TogglePermissionAppInfoPageProvider(
    private val appListTemplate: TogglePermissionAppListTemplate,
) : SettingsPageProvider {
    override val name = PAGE_NAME

    override val parameter = PAGE_PARAMETER

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = SettingsPage.create(name, parameter = parameter, arguments = arguments)
        return listOf(SettingsEntryBuilder.create("AllowControl", owner).build())
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val permissionType = arguments?.getString(PERMISSION)!!
        val packageName = arguments.getString(PACKAGE_NAME)!!
        val userId = arguments.getInt(USER_ID)
        appListTemplate.rememberModel(permissionType)
            .TogglePermissionAppInfoPage(packageName, userId)
    }

    companion object {
        private const val PAGE_NAME = "TogglePermissionAppInfoPage"
        private const val PERMISSION = "permission"
        private const val PACKAGE_NAME = "rt_packageName"
        private const val USER_ID = "rt_userId"

        private val PAGE_PARAMETER = listOf(
            navArgument(PERMISSION) { type = NavType.StringType },
            navArgument(PACKAGE_NAME) { type = NavType.StringType },
            navArgument(USER_ID) { type = NavType.IntType },
        )

        /**
         * Gets the route prefix to this page.
         *
         * Expose route prefix to enable enter from non-SPA pages.
         */
        fun getRoutePrefix(permissionType: String) = "$PAGE_NAME/$permissionType"

        @Composable
        fun navigator(permissionType: String, app: ApplicationInfo) =
            navigator(route = "$PAGE_NAME/$permissionType/${app.toRoute()}")

        fun buildPageData(permissionType: String): SettingsPage {
            return SettingsPage.create(
                name = PAGE_NAME,
                parameter = PAGE_PARAMETER,
                arguments = bundleOf(PERMISSION to permissionType)
            )
        }
    }
}

@Composable
internal fun <T : AppRecord> TogglePermissionAppListModel<T>.TogglePermissionAppInfoPageEntryItem(
    permissionType: String,
    app: ApplicationInfo,
) {
    val record = remember { transformItem(app) }
    if (!remember { isChangeable(record) }) return
    val context = LocalContext.current
    val internalListModel = remember {
        TogglePermissionInternalAppListModel(
            context = context,
            permissionType = permissionType,
            listModel = this,
            restrictionsProviderFactory = ::RestrictionsProviderImpl,
        )
    }
    Preference(
        object : PreferenceModel {
            override val title = stringResource(pageTitleResId)
            override val summary = internalListModel.getSummary(record)
            override val onClick =
                TogglePermissionAppInfoPageProvider.navigator(permissionType, app)
        }
    )
}

@VisibleForTesting
@Composable
internal fun TogglePermissionAppListModel<out AppRecord>.TogglePermissionAppInfoPage(
    packageName: String,
    userId: Int,
    packageManagers: IPackageManagers = PackageManagers,
    restrictionsProviderFactory: RestrictionsProviderFactory = ::RestrictionsProviderImpl,
) {
    AppInfoPage(
        title = stringResource(pageTitleResId),
        packageName = packageName,
        userId = userId,
        footerText = stringResource(footerResId),
        footerContent = footerContent(),
        packageManagers = packageManagers,
    ) {
        val model = createSwitchModel(checkNotNull(applicationInfo))
        val restrictions = Restrictions(userId, switchRestrictionKeys)
        RestrictedSwitchPreference(model, restrictions, restrictionsProviderFactory)
    }
}

@Composable
private fun <T : AppRecord> TogglePermissionAppListModel<T>.createSwitchModel(
    app: ApplicationInfo,
): TogglePermissionSwitchModel<T> {
    val context = LocalContext.current
    val record = remember(app) { transformItem(app) }
    val isAllowed = isAllowed(record)
    return remember(record) { TogglePermissionSwitchModel(context, this, record, isAllowed) }
        .also { model -> LaunchedEffect(model, Dispatchers.IO) { model.initState() } }
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
