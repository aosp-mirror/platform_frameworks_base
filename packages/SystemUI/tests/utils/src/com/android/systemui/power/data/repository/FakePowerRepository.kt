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
 *
 */

package com.android.systemui.power.data.repository

import android.os.PowerManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.power.shared.model.WakefulnessState
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class FakePowerRepository @Inject constructor() : PowerRepository {
    private val _isInteractive = MutableStateFlow(true)
    override val isInteractive: Flow<Boolean> = _isInteractive.asStateFlow()

    private val _wakefulness = MutableStateFlow(WakefulnessModel())
    override val wakefulness = _wakefulness.asStateFlow()

    private val _screenPowerState = MutableStateFlow(ScreenPowerState.SCREEN_OFF)
    override val screenPowerState = _screenPowerState.asStateFlow()

    var lastWakeWhy: String? = null
    var lastWakeReason: Int? = null

    var userTouchRegistered = false

    fun setInteractive(value: Boolean) {
        _isInteractive.value = value
    }

    override fun wakeUp(why: String, @PowerManager.WakeReason wakeReason: Int) {
        lastWakeWhy = why
        lastWakeReason = wakeReason
    }

    override fun userTouch(noChangeLights: Boolean) {
        userTouchRegistered = true
    }

    override fun updateWakefulness(
        rawState: WakefulnessState,
        lastWakeReason: WakeSleepReason,
        lastSleepReason: WakeSleepReason,
        powerButtonLaunchGestureTriggered: Boolean
    ) {
        _wakefulness.value =
            WakefulnessModel(
                rawState,
                lastWakeReason,
                lastSleepReason,
                powerButtonLaunchGestureTriggered,
            )
    }

    override fun setScreenPowerState(state: ScreenPowerState) {
        _screenPowerState.value = state
    }
}

@Module
interface FakePowerRepositoryModule {
    @Binds fun bindFake(fake: FakePowerRepository): PowerRepository
}
