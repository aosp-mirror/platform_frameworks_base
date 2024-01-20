/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.satellite.domain.interactor.DeviceBasedSatelliteInteractor
import com.android.systemui.statusbar.pipeline.satellite.ui.model.SatelliteIconModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * View-Model for the device-based satellite icon. This icon will only show in the status bar if
 * satellite is available AND all other service states are considered OOS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceBasedSatelliteViewModel
@Inject
constructor(
    interactor: DeviceBasedSatelliteInteractor,
    @Application scope: CoroutineScope,
) {
    private val shouldShowIcon: StateFlow<Boolean> =
        interactor.areAllConnectionsOutOfService
            .flatMapLatest { allOos ->
                if (!allOos) {
                    flowOf(false)
                } else {
                    interactor.isSatelliteAllowed
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    val icon: StateFlow<Icon?> =
        combine(
                shouldShowIcon,
                interactor.connectionState,
                interactor.signalStrength,
            ) { shouldShow, state, signalStrength ->
                if (shouldShow) {
                    SatelliteIconModel.fromConnectionState(state, signalStrength)
                } else {
                    null
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)
}
