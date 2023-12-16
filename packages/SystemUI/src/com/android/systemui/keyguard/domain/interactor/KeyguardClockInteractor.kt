/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch.ClockSize
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardClockRepository
import com.android.systemui.keyguard.shared.model.SettingsClockSize
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

private val TAG = KeyguardClockInteractor::class.simpleName
/** Manages keyguard clock for the lockscreen root view. */
/** Encapsulates business-logic related to the keyguard clock. */
@SysUISingleton
class KeyguardClockInteractor
@Inject
constructor(
    private val keyguardClockRepository: KeyguardClockRepository,
) {

    val selectedClockSize: Flow<SettingsClockSize> = keyguardClockRepository.selectedClockSize

    val currentClockId: Flow<ClockId> = keyguardClockRepository.currentClockId

    val currentClock: StateFlow<ClockController?> = keyguardClockRepository.currentClock

    var clock: ClockController? by keyguardClockRepository.clockEventController::clock

    val clockSize: StateFlow<Int> = keyguardClockRepository.clockSize
    fun setClockSize(@ClockSize size: Int) {
        keyguardClockRepository.setClockSize(size)
    }

    val clockEventController: ClockEventController
        get() {
            return keyguardClockRepository.clockEventController
        }
}
