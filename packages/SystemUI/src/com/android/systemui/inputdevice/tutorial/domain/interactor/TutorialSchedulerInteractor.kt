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

package com.android.systemui.inputdevice.tutorial.domain.interactor

import android.content.Context
import android.content.Intent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.inputdevice.tutorial.data.model.DeviceSchedulerInfo
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.inputdevice.tutorial.data.repository.TutorialSchedulerRepository
import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.touchpad.data.repository.TouchpadRepository
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * When the first time a keyboard or touchpad is connected, wait for [LAUNCH_DELAY], then launch the
 * tutorial as soon as there's a connected device
 */
@SysUISingleton
class TutorialSchedulerInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    private val keyboardRepository: KeyboardRepository,
    private val touchpadRepository: TouchpadRepository,
    private val tutorialSchedulerRepository: TutorialSchedulerRepository
) {
    fun start() {
        applicationScope.launch {
            val info = tutorialSchedulerRepository.loadData()
            if (!info.keyboard.isLaunched) {
                applicationScope.launch {
                    schedule(
                        keyboardRepository.isAnyKeyboardConnected,
                        info.keyboard,
                        DeviceType.KEYBOARD
                    )
                }
            }
            if (!info.touchpad.isLaunched) {
                applicationScope.launch {
                    schedule(
                        touchpadRepository.isAnyTouchpadConnected,
                        info.touchpad,
                        DeviceType.TOUCHPAD
                    )
                }
            }
        }
    }

    private suspend fun schedule(
        isAnyDeviceConnected: Flow<Boolean>,
        info: DeviceSchedulerInfo,
        deviceType: DeviceType
    ) {
        if (!info.wasEverConnected) {
            waitForDeviceConnection(isAnyDeviceConnected)
            info.connectTime = Instant.now().toEpochMilli()
            tutorialSchedulerRepository.updateConnectTime(deviceType, info.connectTime!!)
        }
        delay(remainingTimeMillis(info.connectTime!!))
        waitForDeviceConnection(isAnyDeviceConnected)
        info.isLaunched = true
        tutorialSchedulerRepository.updateLaunch(deviceType)
        launchTutorial()
    }

    private suspend fun waitForDeviceConnection(isAnyDeviceConnected: Flow<Boolean>): Boolean {
        return isAnyDeviceConnected.filter { it }.first()
    }

    private fun launchTutorial() {
        val intent = Intent(TUTORIAL_ACTION)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun remainingTimeMillis(start: Long): Long {
        val elapsed = Instant.now().toEpochMilli() - start
        return LAUNCH_DELAY - elapsed
    }

    companion object {
        const val TUTORIAL_ACTION = "com.android.systemui.action.TOUCHPAD_TUTORIAL"
        private val LAUNCH_DELAY = Duration.ofHours(72).toMillis()
    }
}
