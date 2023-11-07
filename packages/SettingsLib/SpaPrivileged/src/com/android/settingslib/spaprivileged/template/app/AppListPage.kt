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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.widget.scaffold.MoreOptionsAction
import com.android.settingslib.spa.widget.scaffold.MoreOptionsScope
import com.android.settingslib.spa.widget.scaffold.SearchScaffold
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.template.common.UserProfilePager

/**
 * The full screen template for an App List page.
 *
 * @param noMoreOptions default false. If true, then do not display more options action button,
 * including the "Show System" / "Hide System" action.
 * @param header the description header appears before all the applications.
 */
@Composable
fun <T : AppRecord> AppListPage(
    title: String,
    listModel: AppListModel<T>,
    showInstantApps: Boolean = false,
    noMoreOptions: Boolean = false,
    matchAnyUserForAdmin: Boolean = false,
    noItemMessage: String? = null,
    moreOptions: @Composable MoreOptionsScope.() -> Unit = {},
    header: @Composable () -> Unit = {},
    appList: @Composable AppListInput<T>.() -> Unit = { AppList() },
) {
    var showSystem by rememberSaveable { mutableStateOf(false) }
    SearchScaffold(
        title = title,
        actions = {
            if (!noMoreOptions) {
                MoreOptionsAction {
                    ShowSystemAction(showSystem) { showSystem = it }
                    moreOptions()
                }
            }
        },
    ) { bottomPadding, searchQuery ->
        UserProfilePager { userGroup ->
            val appListInput = AppListInput(
                config = AppListConfig(
                    userIds = userGroup.userInfos.map { it.id },
                    showInstantApps = showInstantApps,
                    matchAnyUserForAdmin = matchAnyUserForAdmin,
                ),
                listModel = listModel,
                state = AppListState(
                    showSystem = { showSystem },
                    searchQuery = searchQuery,
                ),
                header = header,
                bottomPadding = bottomPadding,
                noItemMessage = noItemMessage,
            )
            appList(appListInput)
        }
    }
}

@Composable
private fun MoreOptionsScope.ShowSystemAction(
    showSystem: Boolean,
    setShowSystem: (showSystem: Boolean) -> Unit,
) {
    val menuText = if (showSystem) R.string.menu_hide_system else R.string.menu_show_system
    MenuItem(text = stringResource(menuText)) {
        setShowSystem(!showSystem)
    }
}
