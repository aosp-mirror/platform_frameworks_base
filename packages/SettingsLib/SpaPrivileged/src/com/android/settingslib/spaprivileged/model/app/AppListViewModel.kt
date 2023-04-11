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

package com.android.settingslib.spaprivileged.model.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.icu.text.Collator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.settingslib.spa.framework.util.StateFlowBridge
import com.android.settingslib.spa.framework.util.asyncMapItem
import com.android.settingslib.spa.framework.util.waitFirst
import com.android.settingslib.spa.widget.ui.SpinnerOption
import com.android.settingslib.spaprivileged.template.app.AppListConfig
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

internal data class AppListData<T : AppRecord>(
    val appEntries: List<AppEntry<T>>,
    val option: Int,
) {
    fun filter(predicate: (AppEntry<T>) -> Boolean) =
        AppListData(appEntries.filter(predicate), option)
}

internal interface IAppListViewModel<T : AppRecord> {
    val optionFlow: MutableStateFlow<Int?>
    val spinnerOptionsFlow: Flow<List<SpinnerOption>>
    val appListDataFlow: Flow<AppListData<T>>
}

internal class AppListViewModel<T : AppRecord>(
    application: Application,
) : AppListViewModelImpl<T>(application)

@OptIn(ExperimentalCoroutinesApi::class)
internal open class AppListViewModelImpl<T : AppRecord>(
    application: Application,
    appListRepositoryFactory: (Context) -> AppListRepository = ::AppListRepositoryImpl,
    appRepositoryFactory: (Context) -> AppRepository = ::AppRepositoryImpl,
) : AndroidViewModel(application), IAppListViewModel<T> {
    val appListConfig = StateFlowBridge<AppListConfig>()
    val listModel = StateFlowBridge<AppListModel<T>>()
    val showSystem = StateFlowBridge<Boolean>()
    final override val optionFlow = MutableStateFlow<Int?>(null)
    val searchQuery = StateFlowBridge<String>()

    private val appListRepository = appListRepositoryFactory(application)
    private val appRepository = appRepositoryFactory(application)
    private val collator = Collator.getInstance().freeze()
    private val labelMap = ConcurrentHashMap<String, String>()
    private val scope = viewModelScope + Dispatchers.IO

    private val userSubGraphsFlow = appListConfig.flow.map { config ->
        config.userIds.map { userId ->
            UserSubGraph(userId, config.showInstantApps, config.matchAnyUserForAdmin)
        }
    }.shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

    private inner class UserSubGraph(
        private val userId: Int,
        private val showInstantApps: Boolean,
        private val matchAnyUserForAdmin: Boolean,
    ) {
        private val userIdFlow = flowOf(userId)

        private val appsStateFlow = MutableStateFlow<List<ApplicationInfo>?>(null)

        val recordListFlow = listModel.flow
            .flatMapLatest { it.transform(userIdFlow, appsStateFlow.filterNotNull()) }
            .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

        private val systemFilteredFlow =
            appListRepository.showSystemPredicate(userIdFlow, showSystem.flow)
                .combine(recordListFlow) { showAppPredicate, recordList ->
                    recordList.filter { showAppPredicate(it.app) }
                }
                .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

        val listModelFilteredFlow = optionFlow.filterNotNull().flatMapLatest { option ->
            listModel.flow.flatMapLatest { listModel ->
                listModel.filter(this.userIdFlow, option, this.systemFilteredFlow)
            }
        }.shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

        fun reloadApps() {
            scope.launch {
                appsStateFlow.value =
                    appListRepository.loadApps(userId, showInstantApps, matchAnyUserForAdmin)
            }
        }
    }

    private val combinedRecordListFlow = userSubGraphsFlow.flatMapLatest { userSubGraphList ->
        combine(userSubGraphList.map { it.recordListFlow }) { it.toList().flatten() }
    }.shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

    override val spinnerOptionsFlow =
        combinedRecordListFlow.combine(listModel.flow) { recordList, listModel ->
            listModel.getSpinnerOptions(recordList)
        }.shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

    private val appEntryListFlow = userSubGraphsFlow.flatMapLatest { userSubGraphList ->
        combine(userSubGraphList.map { it.listModelFilteredFlow }) { it.toList().flatten() }
    }.asyncMapItem { record ->
        val label = getLabel(record.app)
        AppEntry(
            record = record,
            label = label,
            labelCollationKey = collator.getCollationKey(label),
        )
    }

    override val appListDataFlow =
        combine(
            appEntryListFlow,
            listModel.flow,
            optionFlow.filterNotNull(),
        ) { appEntries, listModel, option ->
            AppListData(
                appEntries = appEntries.sortedWith(listModel.getComparator(option)),
                option = option,
            )
        }.combine(searchQuery.flow) { appListData, searchQuery ->
            appListData.filter {
                it.label.contains(other = searchQuery, ignoreCase = true)
            }
        }.shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

    init {
        scheduleOnFirstLoaded()
    }

    fun reloadApps() {
        scope.launch {
            userSubGraphsFlow.collect { userSubGraphList ->
                for (userSubGraph in userSubGraphList) {
                    userSubGraph.reloadApps()
                }
            }
        }
    }

    private fun scheduleOnFirstLoaded() {
        combinedRecordListFlow
            .waitFirst(appListDataFlow)
            .combine(listModel.flow) { recordList, listModel ->
                if (listModel.onFirstLoaded(recordList)) {
                    preFetchLabels(recordList)
                }
            }
            .launchIn(scope)
    }

    private fun preFetchLabels(recordList: List<T>) {
        for (record in recordList) {
            getLabel(record.app)
        }
    }

    private fun getLabel(app: ApplicationInfo) = labelMap.computeIfAbsent(app.packageName) {
        appRepository.loadLabel(app)
    }
}
