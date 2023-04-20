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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Common interface for all of the location-based mobile icon view models. */
interface MobileIconViewModelCommon {
    val subscriptionId: Int
    /** An int consumable by [SignalDrawable] for display */
    val iconId: Flow<Int>
    val roaming: Flow<Boolean>
    /** The RAT icon (LTE, 3G, 5G, etc) to be displayed. Null if we shouldn't show anything */
    val networkTypeIcon: Flow<Icon?>
    val activityInVisible: Flow<Boolean>
    val activityOutVisible: Flow<Boolean>
    val activityContainerVisible: Flow<Boolean>
}

/**
 * View model for the state of a single mobile icon. Each [MobileIconViewModel] will keep watch over
 * a single line of service via [MobileIconInteractor] and update the UI based on that
 * subscription's information.
 *
 * There will be exactly one [MobileIconViewModel] per filtered subscription offered from
 * [MobileIconsInteractor.filteredSubscriptions].
 *
 * For the sake of keeping log spam in check, every flow funding the [MobileIconViewModelCommon]
 * interface is implemented as a [StateFlow]. This ensures that each location-based mobile icon view
 * model gets the exact same information, as well as allows us to log that unified state only once
 * per icon.
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileIconViewModel
constructor(
    override val subscriptionId: Int,
    iconInteractor: MobileIconInteractor,
    logger: ConnectivityPipelineLogger,
    constants: ConnectivityConstants,
    scope: CoroutineScope,
) : MobileIconViewModelCommon {
    /** Whether or not to show the error state of [SignalDrawable] */
    private val showExclamationMark: Flow<Boolean> =
        iconInteractor.isDefaultDataEnabled.mapLatest { !it }

    override val iconId: Flow<Int> = run {
        val initial = SignalDrawable.getEmptyState(iconInteractor.numberOfLevels.value)
        combine(
                iconInteractor.level,
                iconInteractor.numberOfLevels,
                showExclamationMark,
                iconInteractor.isInService,
            ) { level, numberOfLevels, showExclamationMark, isInService ->
                if (!isInService) {
                    SignalDrawable.getEmptyState(numberOfLevels)
                } else {
                    SignalDrawable.getState(level, numberOfLevels, showExclamationMark)
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                iconInteractor.tableLogBuffer,
                columnPrefix = "",
                columnName = "iconId",
                initialValue = initial,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

    override val networkTypeIcon: Flow<Icon?> =
        combine(
                iconInteractor.networkTypeIconGroup,
                iconInteractor.isDataConnected,
                iconInteractor.isDataEnabled,
                iconInteractor.isDefaultConnectionFailed,
                iconInteractor.alwaysShowDataRatIcon,
            ) { networkTypeIconGroup, dataConnected, dataEnabled, failedConnection, alwaysShow ->
                val desc =
                    if (networkTypeIconGroup.dataContentDescription != 0)
                        ContentDescription.Resource(networkTypeIconGroup.dataContentDescription)
                    else null
                val icon = Icon.Resource(networkTypeIconGroup.dataType, desc)
                return@combine when {
                    alwaysShow -> icon
                    !dataConnected -> null
                    !dataEnabled -> null
                    failedConnection -> null
                    else -> icon
                }
            }
            .distinctUntilChanged()
            .onEach {
                // This is done as an onEach side effect since Icon is not Diffable (yet)
                iconInteractor.tableLogBuffer.logChange(
                    prefix = "",
                    columnName = "networkTypeIcon",
                    value = it.toString(),
                )
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val roaming: StateFlow<Boolean> =
        iconInteractor.isRoaming
            .logDiffsForTable(
                iconInteractor.tableLogBuffer,
                columnPrefix = "",
                columnName = "roaming",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val activity: Flow<DataActivityModel?> =
        if (!constants.shouldShowActivityConfig) {
            flowOf(null)
        } else {
            iconInteractor.activity
        }

    override val activityInVisible: Flow<Boolean> =
        activity
            .map { it?.hasActivityIn ?: false }
            .distinctUntilChanged()
            .logDiffsForTable(
                iconInteractor.tableLogBuffer,
                columnPrefix = "",
                columnName = "activityInVisible",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val activityOutVisible: Flow<Boolean> =
        activity
            .map { it?.hasActivityOut ?: false }
            .distinctUntilChanged()
            .logDiffsForTable(
                iconInteractor.tableLogBuffer,
                columnPrefix = "",
                columnName = "activityOutVisible",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val activityContainerVisible: Flow<Boolean> =
        activity
            .map { it != null && (it.hasActivityIn || it.hasActivityOut) }
            .distinctUntilChanged()
            .logDiffsForTable(
                iconInteractor.tableLogBuffer,
                columnPrefix = "",
                columnName = "activityContainerVisible",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
}
