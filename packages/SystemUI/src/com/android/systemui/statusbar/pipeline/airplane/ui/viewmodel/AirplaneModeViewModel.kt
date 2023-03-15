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

package com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Models the UI state for the status bar airplane mode icon.
 *
 * IMPORTANT: This is currently *not* used to render any airplane mode information anywhere. See
 * [com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository] for
 * more details.
 */
interface AirplaneModeViewModel {
    /** True if the airplane mode icon is currently visible in the status bar. */
    val isAirplaneModeIconVisible: StateFlow<Boolean>
}

@SysUISingleton
class AirplaneModeViewModelImpl
@Inject
constructor(
    interactor: AirplaneModeInteractor,
    logger: ConnectivityPipelineLogger,
    @Application private val scope: CoroutineScope,
) : AirplaneModeViewModel {
    override val isAirplaneModeIconVisible: StateFlow<Boolean> =
        combine(interactor.isAirplaneMode, interactor.isForceHidden) {
                isAirplaneMode,
                isAirplaneIconForceHidden ->
                isAirplaneMode && !isAirplaneIconForceHidden
            }
            .distinctUntilChanged()
            .logOutputChange(logger, "isAirplaneModeIconVisible")
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = false)
}
