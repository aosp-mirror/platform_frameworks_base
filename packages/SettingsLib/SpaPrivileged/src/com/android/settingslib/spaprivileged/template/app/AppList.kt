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

import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settingslib.spa.framework.compose.LogCompositions
import com.android.settingslib.spa.framework.compose.TimeMeasurer.Companion.rememberTimeMeasurer
import com.android.settingslib.spa.framework.compose.rememberLazyListStateAndHideKeyboardWhenStartScroll
import com.android.settingslib.spa.framework.compose.toState
import com.android.settingslib.spa.widget.ui.CategoryTitle
import com.android.settingslib.spa.widget.ui.PlaceholderTitle
import com.android.settingslib.spa.widget.ui.Spinner
import com.android.settingslib.spa.widget.ui.SpinnerOption
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.framework.compose.DisposableBroadcastReceiverAsUser
import com.android.settingslib.spaprivileged.model.app.AppEntry
import com.android.settingslib.spaprivileged.model.app.AppListConfig
import com.android.settingslib.spaprivileged.model.app.AppListData
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppListViewModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spaprivileged.model.app.IAppListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "AppList"
private const val CONTENT_TYPE_HEADER = "header"

data class AppListState(
    val showSystem: State<Boolean>,
    val searchQuery: State<String>,
)

data class AppListInput<T : AppRecord>(
    val config: AppListConfig,
    val listModel: AppListModel<T>,
    val state: AppListState,
    val header: @Composable () -> Unit,
    val noItemMessage: String? = null,
    val bottomPadding: Dp,
)

/**
 * The template to render an App List.
 *
 * This UI element will take the remaining space on the screen to show the App List.
 */
@Composable
fun <T : AppRecord> AppListInput<T>.AppList() {
    AppListImpl { rememberViewModel(config, listModel, state) }
}

@Composable
internal fun <T : AppRecord> AppListInput<T>.AppListImpl(
    viewModelSupplier: @Composable () -> IAppListViewModel<T>,
) {
    LogCompositions(TAG, config.userId.toString())
    val viewModel = viewModelSupplier()
    Column(Modifier.fillMaxSize()) {
        val optionsState = viewModel.spinnerOptionsFlow.collectAsState(null, Dispatchers.IO)
        SpinnerOptions(optionsState, viewModel.optionFlow)
        val appListData = viewModel.appListDataFlow.collectAsState(null, Dispatchers.IO)
        listModel.AppListWidget(appListData, header, bottomPadding, noItemMessage)
    }
}

@Composable
private fun SpinnerOptions(
    optionsState: State<List<SpinnerOption>?>,
    optionFlow: MutableStateFlow<Int?>,
) {
    val options = optionsState.value
    LaunchedEffect(options) {
        if (options != null && !options.any { it.id == optionFlow.value }) {
            // Reset to first option if the available options changed, and the current selected one
            // does not in the new options.
            optionFlow.value = options.let { it.firstOrNull()?.id ?: -1 }
        }
    }
    if (options != null) {
        Spinner(options, optionFlow.collectAsState().value) { optionFlow.value = it }
    }
}

@Composable
private fun <T : AppRecord> AppListModel<T>.AppListWidget(
    appListData: State<AppListData<T>?>,
    header: @Composable () -> Unit,
    bottomPadding: Dp,
    noItemMessage: String?
) {
    val timeMeasurer = rememberTimeMeasurer(TAG)
    appListData.value?.let { (list, option) ->
        timeMeasurer.logFirst("app list first loaded")
        if (list.isEmpty()) {
            header()
            PlaceholderTitle(noItemMessage ?: stringResource(R.string.no_applications))
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberLazyListStateAndHideKeyboardWhenStartScroll(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item(contentType = CONTENT_TYPE_HEADER) {
                header()
            }

            items(count = list.size, key = { option to list[it].record.app.packageName }) {
                remember(list) { getGroupTitleIfFirst(option, list, it) }
                    ?.let { group -> CategoryTitle(title = group) }

                val appEntry = list[it]
                val summary = getSummary(option, appEntry.record) ?: "".toState()
                remember(appEntry) {
                    AppListItemModel(appEntry.record, appEntry.label, summary)
                }.AppItem()
            }
        }
    }
}

/** Returns group title if this is the first item of the group. */
private fun <T : AppRecord> AppListModel<T>.getGroupTitleIfFirst(
    option: Int,
    list: List<AppEntry<T>>,
    index: Int,
): String? = getGroupTitle(option, list[index].record)?.takeIf {
    index == 0 || it != getGroupTitle(option, list[index - 1].record)
}

@Composable
private fun <T : AppRecord> rememberViewModel(
    config: AppListConfig,
    listModel: AppListModel<T>,
    state: AppListState,
): AppListViewModel<T> {
    val viewModel: AppListViewModel<T> = viewModel(key = config.userId.toString())
    viewModel.appListConfig.setIfAbsent(config)
    viewModel.listModel.setIfAbsent(listModel)
    viewModel.showSystem.Sync(state.showSystem)
    viewModel.searchQuery.Sync(state.searchQuery)

    DisposableBroadcastReceiverAsUser(
        intentFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        },
        userHandle = UserHandle.of(config.userId),
        onStart = { viewModel.reloadApps() },
    ) { viewModel.reloadApps() }

    return viewModel
}