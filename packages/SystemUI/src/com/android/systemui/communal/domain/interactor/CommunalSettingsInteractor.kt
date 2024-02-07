/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.communal.domain.interactor

import com.android.systemui.communal.data.model.CommunalEnabledState
import com.android.systemui.communal.data.model.CommunalWidgetCategories
import com.android.systemui.communal.data.repository.CommunalSettingsRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.dagger.CommunalTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalSettingsInteractor
@Inject
constructor(
    @Background private val bgScope: CoroutineScope,
    private val repository: CommunalSettingsRepository,
    userInteractor: SelectedUserInteractor,
    @CommunalTableLog tableLogBuffer: TableLogBuffer,
) {
    /** Whether or not communal is enabled for the currently selected user. */
    val isCommunalEnabled: StateFlow<Boolean> =
        userInteractor.selectedUserInfo
            .flatMapLatest { user -> repository.getEnabledState(user) }
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnPrefix = "disabledReason",
                initialValue = CommunalEnabledState()
            )
            .map { model -> model.enabled }
            // Start this eagerly since the value is accessed synchronously in many places.
            .stateIn(scope = bgScope, started = SharingStarted.Eagerly, initialValue = false)

    /** What widget categories to show on the hub. */
    val communalWidgetCategories: StateFlow<Int> =
        userInteractor.selectedUserInfo
            .flatMapLatest { user -> repository.getWidgetCategories(user) }
            .map { categories -> categories.categories }
            .stateIn(
                scope = bgScope,
                // Start this eagerly since the value can be accessed synchronously.
                started = SharingStarted.Eagerly,
                initialValue = CommunalWidgetCategories().categories
            )
}
