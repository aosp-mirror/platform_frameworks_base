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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.graphics.Point
import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.power.data.repository.PowerRepository
import com.android.systemui.power.shared.model.DozeScreenStateModel
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import dagger.Lazy
import javax.inject.Inject

@SysUISingleton
class DozeInteractor
@Inject
constructor(
    private val keyguardRepository: KeyguardRepository,
    private val powerRepository: PowerRepository,
    // TODO(b/336364825) Remove Lazy when SceneContainerFlag is released -
    // while the flag is off, creating this object too early results in a crash
    private val sceneInteractor: Lazy<SceneInteractor>,
) {
    fun canDozeFromCurrentScene(): Boolean {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            return false
        }
        return sceneInteractor.get().currentScene.value == Scenes.Lockscreen
    }

    fun setDozeScreenState(state: Int) {
        powerRepository.dozeScreenState.value =
            when (state) {
                Display.STATE_UNKNOWN -> DozeScreenStateModel.UNKNOWN
                Display.STATE_OFF -> DozeScreenStateModel.OFF
                Display.STATE_ON -> DozeScreenStateModel.ON
                Display.STATE_DOZE -> DozeScreenStateModel.DOZE
                Display.STATE_DOZE_SUSPEND -> DozeScreenStateModel.DOZE_SUSPEND
                Display.STATE_VR -> DozeScreenStateModel.VR
                Display.STATE_ON_SUSPEND -> DozeScreenStateModel.ON_SUSPEND
                else -> throw IllegalArgumentException("Invalid DozeScreenState: $state")
            }
    }

    fun setAodAvailable(value: Boolean) {
        keyguardRepository.setAodAvailable(value)
    }

    fun setIsDozing(isDozing: Boolean) {
        keyguardRepository.setIsDozing(isDozing)
    }

    fun setLastTapToWakePosition(position: Point) {
        keyguardRepository.setLastDozeTapToWakePosition(position)
    }

    fun dozeTimeTick() {
        keyguardRepository.dozeTimeTick()
    }
}
