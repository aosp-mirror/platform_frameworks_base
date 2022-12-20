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

import android.graphics.Color
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest

/**
 * View model for the state of a single mobile icon. Each [MobileIconViewModel] will keep watch over
 * a single line of service via [MobileIconInteractor] and update the UI based on that
 * subscription's information.
 *
 * There will be exactly one [MobileIconViewModel] per filtered subscription offered from
 * [MobileIconsInteractor.filteredSubscriptions]
 *
 * TODO: figure out where carrier merged and VCN models go (probably here?)
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileIconViewModel
constructor(
    val subscriptionId: Int,
    iconInteractor: MobileIconInteractor,
    logger: ConnectivityPipelineLogger,
) {
    /** Whether or not to show the error state of [SignalDrawable] */
    private val showExclamationMark: Flow<Boolean> =
        iconInteractor.isDefaultDataEnabled.mapLatest { !it }

    /** An int consumable by [SignalDrawable] for display */
    val iconId: Flow<Int> =
        combine(iconInteractor.level, iconInteractor.numberOfLevels, showExclamationMark) {
                level,
                numberOfLevels,
                showExclamationMark ->
                SignalDrawable.getState(level, numberOfLevels, showExclamationMark)
            }
            .distinctUntilChanged()
            .logOutputChange(logger, "iconId($subscriptionId)")

    /** The RAT icon (LTE, 3G, 5G, etc) to be displayed. Null if we shouldn't show anything */
    val networkTypeIcon: Flow<Icon?> =
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

    val tint: Flow<Int> = flowOf(Color.CYAN)
}
