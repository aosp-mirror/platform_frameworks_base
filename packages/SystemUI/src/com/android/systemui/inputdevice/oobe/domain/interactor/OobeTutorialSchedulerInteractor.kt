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

package com.android.systemui.inputdevice.oobe.domain.interactor

import android.content.Context
import android.content.Intent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.touchpad.data.repository.TouchpadRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** When keyboards or touchpads are connected, schedule a tutorial after given time has elapsed */
@SysUISingleton
class OobeTutorialSchedulerInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    keyboardRepository: KeyboardRepository,
    touchpadRepository: TouchpadRepository
) {
    private val isAnyKeyboardConnected = keyboardRepository.isAnyKeyboardConnected
    private val isAnyTouchpadConnected = touchpadRepository.isAnyTouchpadConnected

    fun start() {
        applicationScope.launch { isAnyKeyboardConnected.collect { startOobe() } }
        applicationScope.launch { isAnyTouchpadConnected.collect { startOobe() } }
    }

    private fun startOobe() {
        val intent = Intent(TUTORIAL_ACTION)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        const val TAG = "OobeSchedulerInteractor"
        const val TUTORIAL_ACTION = "com.android.systemui.action.TOUCHPAD_TUTORIAL"
    }
}
