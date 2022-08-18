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
import android.content.pm.ApplicationInfo
import android.content.pm.UserInfo
import android.icu.text.Collator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.settingslib.spa.framework.util.StateFlowBridge
import com.android.settingslib.spa.framework.util.asyncMapItem
import com.android.settingslib.spa.framework.util.waitFirst
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.plus

internal data class AppListData<T : AppRecord>(
    val appEntries: List<AppEntry<T>>,
    val option: Int,
) {
    fun filter(predicate: (AppEntry<T>) -> Boolean) =
        AppListData(appEntries.filter(predicate), option)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class AppListViewModel<T : AppRecord>(
    application: Application,
) : AndroidViewModel(application) {
    val userInfo = StateFlowBridge<UserInfo>()
    val listModel = StateFlowBridge<AppListModel<T>>()
    val showSystem = StateFlowBridge<Boolean>()
    val option = StateFlowBridge<Int>()
    val searchQuery = StateFlowBridge<String>()

    private val appsRepository = AppsRepository(application)
    private val appRepository = AppRepositoryImpl(application)
    private val collator = Collator.getInstance().freeze()
    private val labelMap = ConcurrentHashMap<String, String>()
    private val scope = viewModelScope + Dispatchers.Default

    private val userIdFlow = userInfo.flow.map { it.id }

    private val recordListFlow = listModel.flow
        .flatMapLatest { it.transform(userIdFlow, appsRepository.loadApps(userInfo.flow)) }
        .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

    private val systemFilteredFlow = appsRepository.showSystemPredicate(userIdFlow, showSystem.flow)
        .combine(recordListFlow) { showAppPredicate, recordList ->
            recordList.filter { showAppPredicate(it.app) }
        }

    val appListDataFlow = option.flow.flatMapLatest(::filterAndSort)
        .combine(searchQuery.flow) { appListData, searchQuery ->
            appListData.filter {
                it.label.contains(other = searchQuery, ignoreCase = true)
            }
        }
        .shareIn(scope = scope, started = SharingStarted.Eagerly, replay = 1)

    init {
        scheduleOnFirstLoaded()
    }

    private fun filterAndSort(option: Int) = listModel.flow.flatMapLatest { listModel ->
        listModel.filter(userIdFlow, option, systemFilteredFlow)
            .asyncMapItem { record ->
                val label = getLabel(record.app)
                AppEntry(
                    record = record,
                    label = label,
                    labelCollationKey = collator.getCollationKey(label),
                )
            }
            .map { appEntries ->
                AppListData(
                    appEntries = appEntries.sortedWith(listModel.getComparator(option)),
                    option = option,
                )
            }
    }

    private fun scheduleOnFirstLoaded() {
        recordListFlow
            .waitFirst(appListDataFlow)
            .combine(listModel.flow) { recordList, listModel ->
                listModel.maybePreFetchLabels(recordList)
                listModel.onFirstLoaded(recordList)
            }
            .launchIn(scope)
    }

    private fun AppListModel<T>.maybePreFetchLabels(recordList: List<T>) {
        if (getSpinnerOptions().isNotEmpty()) {
            for (record in recordList) {
                getLabel(record.app)
            }
        }
    }

    private fun getLabel(app: ApplicationInfo) = labelMap.computeIfAbsent(app.packageName) {
        appRepository.loadLabel(app)
    }
}
