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

import android.hardware.input.InputManager
import android.hardware.input.InputManager.KeyGestureEventListener
import android.hardware.input.KeyGestureEvent
import com.android.systemui.CoreStartable
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.dagger.ContextualEducationModule.EduClock
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.education.shared.model.EducationInfo
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.inputdevice.data.repository.UserInputDeviceRepository
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.time.Clock
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Allow listening to new contextual education triggered */
@SysUISingleton
class KeyboardTouchpadEduInteractor
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    private val contextualEducationInteractor: ContextualEducationInteractor,
    private val userInputDeviceRepository: UserInputDeviceRepository,
    private val inputManager: InputManager,
    @EduClock private val clock: Clock,
) : CoreStartable {

    companion object {
        const val TAG = "KeyboardTouchpadEduInteractor"
        const val MAX_SIGNAL_COUNT: Int = 2
        val usageSessionDuration = 72.hours
    }

    private val _educationTriggered = MutableStateFlow<EducationInfo?>(null)
    val educationTriggered = _educationTriggered.asStateFlow()

    private val keyboardShortcutTriggered: Flow<GestureType> = conflatedCallbackFlow {
        val listener = KeyGestureEventListener { event ->
            // Only store keyboard shortcut time for gestures providing keyboard education
            val shortcutType =
                when (event.keyGestureType) {
                    KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS -> ALL_APPS
                    else -> null
                }

            if (shortcutType != null) {
                trySendWithFailureLogging(shortcutType, TAG)
            }
        }

        inputManager.registerKeyGestureEventListener(Executor(Runnable::run), listener)
        awaitClose { inputManager.unregisterKeyGestureEventListener(listener) }
    }

    override fun start() {
        backgroundScope.launch {
            contextualEducationInteractor.backGestureModelFlow.collect {
                if (isUsageSessionExpired(it)) {
                    contextualEducationInteractor.startNewUsageSession(BACK)
                } else if (isEducationNeeded(it)) {
                    _educationTriggered.value = EducationInfo(BACK, getEduType(it), it.userId)
                    contextualEducationInteractor.updateOnEduTriggered(BACK)
                }
            }
        }

        backgroundScope.launch {
            userInputDeviceRepository.isAnyTouchpadConnectedForUser.collect {
                if (
                    it.isConnected &&
                        contextualEducationInteractor
                            .getEduDeviceConnectionTime()
                            .touchpadFirstConnectionTime == null
                ) {
                    contextualEducationInteractor.updateTouchpadFirstConnectionTime()
                }
            }
        }

        backgroundScope.launch {
            userInputDeviceRepository.isAnyKeyboardConnectedForUser.collect {
                if (
                    it.isConnected &&
                        contextualEducationInteractor
                            .getEduDeviceConnectionTime()
                            .keyboardFirstConnectionTime == null
                ) {
                    contextualEducationInteractor.updateKeyboardFirstConnectionTime()
                }
            }
        }

        backgroundScope.launch {
            keyboardShortcutTriggered.collect {
                contextualEducationInteractor.updateShortcutTriggerTime(it)
            }
        }
    }

    private fun isEducationNeeded(model: GestureEduModel): Boolean {
        // Todo: b/354884305 - add complete education logic to show education in correct scenarios
        val noShortcutTriggered = model.lastShortcutTriggeredTime == null
        val signalCountReached = model.signalCount >= MAX_SIGNAL_COUNT
        return noShortcutTriggered && signalCountReached
    }

    private fun isUsageSessionExpired(model: GestureEduModel): Boolean {
        return model.usageSessionStartTime
            ?.plusSeconds(usageSessionDuration.inWholeSeconds)
            ?.isBefore(clock.instant()) ?: false
    }

    private fun getEduType(model: GestureEduModel) =
        if (model.educationShownCount > 0) EducationUiType.Notification else EducationUiType.Toast
}
