/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.domain.interactor

import android.os.SystemProperties
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.dagger.ContextualEducationModule.EduClock
import com.android.systemui.inputdevice.data.repository.UserInputDeviceRepository
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.KEYBOARD
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.TOUCHPAD
import com.android.systemui.inputdevice.tutorial.data.repository.TutorialSchedulerRepository
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Encapsulates the update functions of KeyboardTouchpadEduStatsInteractor. This encapsulation is
 * for having a different implementation of interactor when the feature flag is off.
 */
interface KeyboardTouchpadEduStatsInteractor {
    fun incrementSignalCount(gestureType: GestureType)

    fun updateShortcutTriggerTime(gestureType: GestureType)
}

/** Allow update to education data related to keyboard/touchpad. */
@SysUISingleton
class KeyboardTouchpadEduStatsInteractorImpl
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    private val contextualEducationInteractor: ContextualEducationInteractor,
    private val inputDeviceRepository: UserInputDeviceRepository,
    private val tutorialRepository: TutorialSchedulerRepository,
    @EduClock private val clock: Clock,
) : KeyboardTouchpadEduStatsInteractor {

    companion object {
        val initialDelayDuration: Duration
            get() =
                SystemProperties.getLong(
                        "persist.contextual_edu.initial_delay_sec",
                        /* defaultValue= */ 72.hours.inWholeSeconds
                    )
                    .toDuration(DurationUnit.SECONDS)
    }

    override fun incrementSignalCount(gestureType: GestureType) {
        backgroundScope.launch {
            val targetDevice = getTargetDevice(gestureType)
            if (isTargetDeviceConnected(targetDevice) && hasInitialDelayElapsed(targetDevice)) {
                contextualEducationInteractor.incrementSignalCount(gestureType)
            }
        }
    }

    override fun updateShortcutTriggerTime(gestureType: GestureType) {
        backgroundScope.launch {
            contextualEducationInteractor.updateShortcutTriggerTime(gestureType)
        }
    }

    private suspend fun isTargetDeviceConnected(deviceType: DeviceType): Boolean {
        return when (deviceType) {
            KEYBOARD -> inputDeviceRepository.isAnyKeyboardConnectedForUser.first().isConnected
            TOUCHPAD -> inputDeviceRepository.isAnyTouchpadConnectedForUser.first().isConnected
        }
    }

    /**
     * Keyboard shortcut education would be provided for All Apps. Touchpad gesture education would
     * be provided for the rest of the gesture types (i.e. Home, Overview, Back). This method maps
     * gesture to its target education device.
     */
    private fun getTargetDevice(gestureType: GestureType) =
        when (gestureType) {
            ALL_APPS -> KEYBOARD
            else -> TOUCHPAD
        }

    private suspend fun hasInitialDelayElapsed(deviceType: DeviceType): Boolean {
        val oobeLaunchTime = tutorialRepository.launchTime(deviceType) ?: return false
        return clock
            .instant()
            .isAfter(oobeLaunchTime.plusSeconds(initialDelayDuration.inWholeSeconds))
    }
}
