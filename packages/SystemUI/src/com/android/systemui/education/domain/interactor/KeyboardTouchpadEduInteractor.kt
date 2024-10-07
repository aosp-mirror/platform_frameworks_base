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
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.dagger.ContextualEducationModule.EduClock
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.education.shared.model.EducationInfo
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.inputdevice.data.repository.UserInputDeviceRepository
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Allow listening to new contextual education triggered */
@SysUISingleton
class KeyboardTouchpadEduInteractor
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    private val contextualEducationInteractor: ContextualEducationInteractor,
    private val userInputDeviceRepository: UserInputDeviceRepository,
    @EduClock private val clock: Clock,
) : CoreStartable {

    companion object {
        const val TAG = "KeyboardTouchpadEduInteractor"
        const val MAX_SIGNAL_COUNT: Int = 2
        const val MAX_EDUCATION_SHOW_COUNT: Int = 2
        val usageSessionDuration =
            getDurationForConfig("persist.contextual_edu.usage_session_sec", 3.days)
        val minIntervalBetweenEdu =
            getDurationForConfig("persist.contextual_edu.edu_interval_sec", 7.days)

        private fun getDurationForConfig(
            systemPropertyKey: String,
            defaultDuration: Duration
        ): Duration =
            SystemProperties.getLong(
                    systemPropertyKey,
                    /* defaultValue= */ defaultDuration.inWholeSeconds
                )
                .toDuration(DurationUnit.SECONDS)
    }

    private val _educationTriggered = MutableStateFlow<EducationInfo?>(null)
    val educationTriggered = _educationTriggered.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        backgroundScope.launch {
            contextualEducationInteractor.eduDeviceConnectionTimeFlow
                .flatMapLatest {
                    val gestureFlows = mutableListOf<Flow<GestureEduModel>>()
                    if (it.touchpadFirstConnectionTime != null) {
                        gestureFlows.add(contextualEducationInteractor.backGestureModelFlow)
                        gestureFlows.add(contextualEducationInteractor.homeGestureModelFlow)
                        gestureFlows.add(contextualEducationInteractor.overviewGestureModelFlow)
                    }

                    if (it.keyboardFirstConnectionTime != null) {
                        gestureFlows.add(contextualEducationInteractor.allAppsGestureModelFlow)
                    }
                    gestureFlows.merge()
                }
                .collect {
                    if (isUsageSessionExpired(it)) {
                        contextualEducationInteractor.startNewUsageSession(it.gestureType)
                    } else if (isEducationNeeded(it)) {
                        _educationTriggered.value =
                            EducationInfo(it.gestureType, getEduType(it), it.userId)
                        contextualEducationInteractor.updateOnEduTriggered(it.gestureType)
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
            contextualEducationInteractor.keyboardShortcutTriggered.collect {
                contextualEducationInteractor.updateShortcutTriggerTime(it)
            }
        }
    }

    private fun isEducationNeeded(model: GestureEduModel): Boolean {
        val lessThanMaxEduCount = model.educationShownCount < MAX_EDUCATION_SHOW_COUNT
        val noShortcutTriggered = model.lastShortcutTriggeredTime == null
        val signalCountReached = model.signalCount >= MAX_SIGNAL_COUNT
        val isPreviousEduOlderThanMinInterval =
            if (model.educationShownCount == 1) {
                model.lastEducationTime
                    ?.plusSeconds(minIntervalBetweenEdu.inWholeSeconds)
                    ?.isBefore(clock.instant()) ?: true
            } else true

        return lessThanMaxEduCount &&
            noShortcutTriggered &&
            signalCountReached &&
            isPreviousEduOlderThanMinInterval
    }

    private fun isUsageSessionExpired(model: GestureEduModel): Boolean {
        return model.usageSessionStartTime
            ?.plusSeconds(usageSessionDuration.inWholeSeconds)
            ?.isBefore(clock.instant()) ?: false
    }

    private fun getEduType(model: GestureEduModel) =
        if (model.educationShownCount > 0) EducationUiType.Notification else EducationUiType.Toast
}
