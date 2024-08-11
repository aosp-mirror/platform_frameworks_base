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

import android.os.SystemProperties
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.KEYBOARD
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.TOUCHPAD
import com.android.systemui.inputdevice.tutorial.data.repository.TutorialSchedulerRepository
import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.touchpad.data.repository.TouchpadRepository
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * When the first time a keyboard or touchpad is connected, wait for [LAUNCH_DELAY], then launch the
 * tutorial as soon as there's a connected device
 */
@SysUISingleton
class TutorialSchedulerInteractor
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    keyboardRepository: KeyboardRepository,
    touchpadRepository: TouchpadRepository,
    private val repo: TutorialSchedulerRepository
) {
    private val isAnyDeviceConnected =
        mapOf(
            KEYBOARD to keyboardRepository.isAnyKeyboardConnected,
            TOUCHPAD to touchpadRepository.isAnyTouchpadConnected
        )

    fun start() {
        backgroundScope.launch {
            // Merging two flows to ensure that launch tutorial is launched consecutively in order
            // to avoid race condition
            merge(touchpadScheduleFlow, keyboardScheduleFlow).collect {
                val tutorialType = resolveTutorialType(it)
                launchTutorial(tutorialType)
            }
        }
    }

    private val touchpadScheduleFlow = flow {
        if (!repo.isLaunched(TOUCHPAD)) {
            schedule(TOUCHPAD)
            emit(TOUCHPAD)
        }
    }

    private val keyboardScheduleFlow = flow {
        if (!repo.isLaunched(KEYBOARD)) {
            schedule(KEYBOARD)
            emit(KEYBOARD)
        }
    }

    private suspend fun schedule(deviceType: DeviceType) {
        if (!repo.wasEverConnected(deviceType)) {
            waitForDeviceConnection(deviceType)
            repo.updateConnectTime(deviceType, Instant.now().toEpochMilli())
        }
        delay(remainingTimeMillis(start = repo.connectTime(deviceType)))
        waitForDeviceConnection(deviceType)
    }

    private suspend fun waitForDeviceConnection(deviceType: DeviceType) =
        isAnyDeviceConnected[deviceType]!!.filter { it }.first()

    private suspend fun launchTutorial(tutorialType: TutorialType) {
        if (tutorialType == TutorialType.KEYBOARD || tutorialType == TutorialType.BOTH)
            repo.updateLaunch(KEYBOARD)
        if (tutorialType == TutorialType.TOUCHPAD || tutorialType == TutorialType.BOTH)
            repo.updateLaunch(TOUCHPAD)
        // TODO: launch tutorial
        Log.d(TAG, "Launch tutorial for $tutorialType")
    }

    private suspend fun resolveTutorialType(deviceType: DeviceType): TutorialType {
        // Resolve the type of tutorial depending on which device are connected when the tutorial is
        // launched. E.g. when the keyboard is connected for [LAUNCH_DELAY], both keyboard and
        // touchpad are connected, we launch the tutorial for both.
        if (repo.isLaunched(deviceType)) return TutorialType.NONE
        val otherDevice = if (deviceType == KEYBOARD) TOUCHPAD else KEYBOARD
        val isOtherDeviceConnected = isAnyDeviceConnected[otherDevice]!!.first()
        if (!repo.isLaunched(otherDevice) && isOtherDeviceConnected) return TutorialType.BOTH
        return if (deviceType == KEYBOARD) TutorialType.KEYBOARD else TutorialType.TOUCHPAD
    }

    private fun remainingTimeMillis(start: Long): Long {
        val elapsed = Instant.now().toEpochMilli() - start
        return LAUNCH_DELAY - elapsed
    }

    companion object {
        const val TAG = "TutorialSchedulerInteractor"
        private val DEFAULT_LAUNCH_DELAY = 72.hours.inWholeMilliseconds
        private val LAUNCH_DELAY: Long
            get() =
                SystemProperties.getLong(
                    "persist.peripheral_tutorial_delay_ms",
                    DEFAULT_LAUNCH_DELAY
                )
    }

    enum class TutorialType {
        KEYBOARD,
        TOUCHPAD,
        BOTH,
        NONE
    }
}
