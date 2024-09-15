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

package com.android.systemui.statusbar.pipeline.airplane.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * The business logic layer for airplane mode.
 *
 * IMPORTANT: This is currently *not* used to render any airplane mode information anywhere. See
 * [AirplaneModeRepository] for more details.
 */
@SysUISingleton
class AirplaneModeInteractor
@Inject
constructor(
    private val airplaneModeRepository: AirplaneModeRepository,
    connectivityRepository: ConnectivityRepository,
    private val mobileConnectionsRepository: MobileConnectionsRepository,
) {
    /** True if the device is currently in airplane mode. */
    val isAirplaneMode: StateFlow<Boolean> = airplaneModeRepository.isAirplaneMode

    /** True if we're configured to force-hide the airplane mode icon and false otherwise. */
    val isForceHidden: Flow<Boolean> =
        connectivityRepository.forceHiddenSlots.map { it.contains(ConnectivitySlot.AIRPLANE) }

    /** Sets airplane mode state returning the result of the operation. */
    suspend fun setIsAirplaneMode(isInAirplaneMode: Boolean): SetResult =
        if (mobileConnectionsRepository.isInEcmMode()) {
            SetResult.BLOCKED_BY_ECM
        } else {
            airplaneModeRepository.setIsAirplaneMode(isInAirplaneMode)
            SetResult.SUCCESS
        }

    enum class SetResult {
        SUCCESS,
        BLOCKED_BY_ECM,
    }
}
