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

import android.content.pm.UserInfo
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settingslib.spa.framework.compose.LogCompositions
import com.android.settingslib.spa.framework.compose.toState
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.ui.PlaceholderTitle
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppListData
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppListViewModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import kotlinx.coroutines.Dispatchers

private const val TAG = "AppList"

@Composable
fun <T : AppRecord> AppList(
    userInfo: UserInfo,
    listModel: AppListModel<T>,
    showSystem: State<Boolean>,
    option: State<Int>,
    searchQuery: State<String>,
    appItem: @Composable (itemState: AppListItemModel<T>) -> Unit,
) {
    LogCompositions(TAG, userInfo.id.toString())
    val appListData = loadAppEntries(userInfo, listModel, showSystem, option, searchQuery)
    AppListWidget(appListData, listModel, appItem)
}

@Composable
private fun <T : AppRecord> AppListWidget(
    appListData: State<AppListData<T>?>,
    listModel: AppListModel<T>,
    appItem: @Composable (itemState: AppListItemModel<T>) -> Unit,
) {
    appListData.value?.let { (list, option) ->
        if (list.isEmpty()) {
            PlaceholderTitle(stringResource(R.string.no_applications))
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(bottom = SettingsDimension.itemPaddingVertical),
        ) {
            items(count = list.size, key = { option to list[it].record.app.packageName }) {
                val appEntry = list[it]
                val summary = getSummary(listModel, option, appEntry.record)
                val itemModel = remember(appEntry) {
                    AppListItemModel(appEntry.record, appEntry.label, summary)
                }
                appItem(itemModel)
            }
        }
    }
}

@Composable
private fun <T : AppRecord> loadAppEntries(
    userInfo: UserInfo,
    listModel: AppListModel<T>,
    showSystem: State<Boolean>,
    option: State<Int>,
    searchQuery: State<String>,
): State<AppListData<T>?> {
    val viewModel: AppListViewModel<T> = viewModel(key = userInfo.id.toString())
    viewModel.userInfo.setIfAbsent(userInfo)
    viewModel.listModel.setIfAbsent(listModel)
    viewModel.showSystem.Sync(showSystem)
    viewModel.option.Sync(option)
    viewModel.searchQuery.Sync(searchQuery)

    return viewModel.appListDataFlow.collectAsState(null, Dispatchers.Default)
}

@Composable
private fun <T : AppRecord> getSummary(
    listModel: AppListModel<T>,
    option: Int,
    record: T,
): State<String> = remember(option) { listModel.getSummary(option, record) }
    ?.collectAsState(stringResource(R.string.summary_placeholder), Dispatchers.Default)
    ?: "".toState()
