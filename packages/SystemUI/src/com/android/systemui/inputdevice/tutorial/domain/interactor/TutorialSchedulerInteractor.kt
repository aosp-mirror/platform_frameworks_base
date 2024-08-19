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
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration
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
            repo.updateFirstConnectionTime(deviceType, Instant.now())
        }
        delay(remainingTime(start = repo.firstConnectionTime(deviceType)!!))
        waitForDeviceConnection(deviceType)
    }

    private suspend fun waitForDeviceConnection(deviceType: DeviceType) =
        isAnyDeviceConnected[deviceType]!!.filter { it }.first()

    private suspend fun launchTutorial(tutorialType: TutorialType) {
        if (tutorialType == TutorialType.KEYBOARD || tutorialType == TutorialType.BOTH)
            repo.updateLaunchTime(KEYBOARD, Instant.now())
        if (tutorialType == TutorialType.TOUCHPAD || tutorialType == TutorialType.BOTH)
            repo.updateLaunchTime(TOUCHPAD, Instant.now())
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

    private fun remainingTime(start: Instant): kotlin.time.Duration {
        val elapsed = Duration.between(start, Instant.now())
        return LAUNCH_DELAY.minus(elapsed).toKotlinDuration()
    }

    companion object {
        const val TAG = "TutorialSchedulerInteractor"
        private val DEFAULT_LAUNCH_DELAY_SEC = 72.hours.inWholeSeconds
        private val LAUNCH_DELAY: Duration
            get() =
                Duration.ofSeconds(
                    SystemProperties.getLong(
                        "persist.peripheral_tutorial_delay_sec",
                        DEFAULT_LAUNCH_DELAY_SEC
                    )
                )
    }

    enum class TutorialType {
        KEYBOARD,
        TOUCHPAD,
        BOTH,
        NONE
    }
}
