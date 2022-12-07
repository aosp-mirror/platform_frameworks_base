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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.widget.scaffold.MoreOptionsAction
import com.android.settingslib.spa.widget.scaffold.MoreOptionsScope
import com.android.settingslib.spa.widget.scaffold.SearchScaffold
import com.android.settingslib.spa.widget.ui.Spinner
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppListConfig
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.template.common.WorkProfilePager

/**
 * The full screen template for an App List page.
 *
 * @param header the description header appears before all the applications.
 */
@Composable
fun <T : AppRecord> AppListPage(
    title: String,
    listModel: AppListModel<T>,
    showInstantApps: Boolean = false,
    primaryUserOnly: Boolean = false,
    moreOptions: @Composable MoreOptionsScope.() -> Unit = {},
    header: @Composable () -> Unit = {},
    appList: @Composable AppListInput<T>.() -> Unit = { AppList() },
    appItem: @Composable AppListItemModel<T>.() -> Unit,
) {
    val showSystem = rememberSaveable { mutableStateOf(false) }
    SearchScaffold(
        title = title,
        actions = {
            MoreOptionsAction {
                ShowSystemAction(showSystem.value) { showSystem.value = it }
                moreOptions()
            }
        },
    ) { bottomPadding, searchQuery ->
        WorkProfilePager(primaryUserOnly) { userInfo ->
            Column(Modifier.fillMaxSize()) {
                val options = remember { listModel.getSpinnerOptions() }
                val selectedOption = rememberSaveable { mutableStateOf(0) }
                Spinner(options, selectedOption.value) { selectedOption.value = it }
                val appListInput = AppListInput(
                    config = AppListConfig(
                        userId = userInfo.id,
                        showInstantApps = showInstantApps,
                    ),
                    listModel = listModel,
                    state = AppListState(
                        showSystem = showSystem,
                        option = selectedOption,
                        searchQuery = searchQuery,
                    ),
                    header = header,
                    appItem = appItem,
                    bottomPadding = bottomPadding,
                )
                appList(appListInput)
            }
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
