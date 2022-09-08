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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.widget.scaffold.MoreOptionsAction
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold
import com.android.settingslib.spa.widget.ui.Spinner
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.template.common.WorkProfilePager

@Composable
fun <T : AppRecord> AppListPage(
    title: String,
    listModel: AppListModel<T>,
    primaryUserOnly: Boolean = false,
    appItem: @Composable (itemState: AppListItemModel<T>) -> Unit,
) {
    val showSystem = rememberSaveable { mutableStateOf(false) }
    // TODO: Use SearchScaffold here.
    SettingsScaffold(
        title = title,
        actions = {
            ShowSystemAction(showSystem.value) { showSystem.value = it }
        },
    ) { paddingValues ->
        Spacer(Modifier.padding(paddingValues))
        WorkProfilePager(primaryUserOnly) { userInfo ->
            Column(Modifier.fillMaxSize()) {
                val options = remember { listModel.getSpinnerOptions() }
                val selectedOption = rememberSaveable { mutableStateOf(0) }
                Spinner(options, selectedOption.value) { selectedOption.value = it }
                AppList(
                    userInfo = userInfo,
                    listModel = listModel,
                    showSystem = showSystem,
                    option = selectedOption,
                    searchQuery = stateOf(""),
                    appItem = appItem,
                )
            }
        }
    }
}

@Composable
private fun ShowSystemAction(showSystem: Boolean, setShowSystem: (showSystem: Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    MoreOptionsAction { expanded = true }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        val menuText = if (showSystem) R.string.menu_hide_system else R.string.menu_show_system
        DropdownMenuItem(
            text = { Text(stringResource(menuText)) },
            onClick = {
                expanded = false
                setShowSystem(!showSystem)
            },
        )
    }
}
