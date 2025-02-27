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
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.contextualeducation.GestureType.HOME
import com.android.systemui.contextualeducation.GestureType.OVERVIEW
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.ContextualEducationMetricsLogger
import com.android.systemui.education.dagger.ContextualEducationModule.EduClock
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.education.shared.model.EducationInfo
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.inputdevice.data.repository.UserInputDeviceRepository
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.KEYBOARD
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.TOUCHPAD
import com.android.systemui.inputdevice.tutorial.data.repository.TutorialSchedulerRepository
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import com.android.app.tracing.coroutines.launchTraced as launch

/** Allow listening to new contextual education triggered */
@SysUISingleton
class KeyboardTouchpadEduInteractor
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    private val contextualEducationInteractor: ContextualEducationInteractor,
    private val userInputDeviceRepository: UserInputDeviceRepository,
    private val tutorialRepository: TutorialSchedulerRepository,
    private val overviewProxyService: OverviewProxyService,
    private val metricsLogger: ContextualEducationMetricsLogger,
    @EduClock private val clock: Clock,
) : CoreStartable {

    companion object {
        const val TAG = "KeyboardTouchpadEduInteractor"
        const val MAX_SIGNAL_COUNT: Int = 2
        const val MAX_EDUCATION_SHOW_COUNT: Int = 2
        const val MAX_TOAST_PER_USAGE_SESSION: Int = 2

        val usageSessionDuration =
            getDurationForConfig("persist.contextual_edu.usage_session_sec", 3.days)
        val minIntervalBetweenEdu =
            getDurationForConfig("persist.contextual_edu.edu_interval_sec", 7.days)
        val initialDelayDuration =
            getDurationForConfig("persist.contextual_edu.initial_delay_sec", 7.days)

        private fun getDurationForConfig(
            systemPropertyKey: String,
            defaultDuration: Duration,
        ): Duration =
            SystemProperties.getLong(
                    systemPropertyKey,
                    /* defaultValue= */ defaultDuration.inWholeSeconds,
                )
                .toDuration(DurationUnit.SECONDS)
    }

    private val _educationTriggered = MutableStateFlow<EducationInfo?>(null)
    val educationTriggered = _educationTriggered.asStateFlow()

    private val statsUpdateRequests: Flow<StatsUpdateRequest> = conflatedCallbackFlow {
        val listener: OverviewProxyListener =
            object : OverviewProxyListener {
                override fun updateContextualEduStats(
                    isTrackpadGesture: Boolean,
                    gestureType: GestureType,
                ) {
                    trySendWithFailureLogging(
                        StatsUpdateRequest(isTrackpadGesture, gestureType),
                        TAG,
                    )
                }
            }

        overviewProxyService.addCallback(listener)
        awaitClose { overviewProxyService.removeCallback(listener) }
    }

    private val gestureModelMap: Flow<Map<GestureType, GestureEduModel>> =
        combine(
            contextualEducationInteractor.backGestureModelFlow,
            contextualEducationInteractor.homeGestureModelFlow,
            contextualEducationInteractor.overviewGestureModelFlow,
            contextualEducationInteractor.allAppsGestureModelFlow,
        ) { back, home, overview, allApps ->
            mapOf(BACK to back, HOME to home, OVERVIEW to overview, ALL_APPS to allApps)
        }

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
                        val educationType = getEduType(it)
                        _educationTriggered.value =
                            EducationInfo(it.gestureType, educationType, it.userId)
                        contextualEducationInteractor.updateOnEduTriggered(it.gestureType)
                        metricsLogger.logContextualEducationTriggered(it.gestureType, educationType)
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

        backgroundScope.launch {
            statsUpdateRequests.collect {
                if (it.isTrackpadGesture) {
                    contextualEducationInteractor.updateShortcutTriggerTime(it.gestureType)
                } else {
                    incrementSignalCount(it.gestureType)
                }
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

    private suspend fun incrementSignalCount(gestureType: GestureType) {
        val targetDevice = getTargetDevice(gestureType)
        if (
            isTargetDeviceConnected(targetDevice) &&
                hasInitialDelayElapsed(targetDevice) &&
                isMinIntervalForToastEduElapsed(gestureType)
        ) {
            contextualEducationInteractor.incrementSignalCount(gestureType)
        }
    }

    private suspend fun isTargetDeviceConnected(deviceType: DeviceType): Boolean {
        return when (deviceType) {
            KEYBOARD -> userInputDeviceRepository.isAnyKeyboardConnectedForUser.first().isConnected
            TOUCHPAD -> userInputDeviceRepository.isAnyTouchpadConnectedForUser.first().isConnected
        }
    }

    private suspend fun isMinIntervalForToastEduElapsed(gestureType: GestureType): Boolean {
        val gestureModelMap = gestureModelMap.first()
        // Only perform checking if the next edu is toast (i.e. no education is shown yet)
        if (gestureModelMap[gestureType]?.educationShownCount != 0) {
            return true
        }

        val wasLastEduToast = { gesture: GestureEduModel -> gesture.educationShownCount == 1 }
        val toastEduTimesInCurrentSession: List<Instant> =
            gestureModelMap.values
                .filter { wasLastEduToast(it) }
                .mapNotNull { it.lastEducationTime }
                .filter { it >= clock.instant().minusSeconds(usageSessionDuration.inWholeSeconds) }

        return if (toastEduTimesInCurrentSession.size >= MAX_TOAST_PER_USAGE_SESSION) {
            val lastToastTime: Instant? = toastEduTimesInCurrentSession.maxOrNull()
            clock.instant().isAfter(lastToastTime?.plusSeconds(usageSessionDuration.inWholeSeconds))
        } else {
            true
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

    private data class StatsUpdateRequest(
        val isTrackpadGesture: Boolean,
        val gestureType: GestureType,
    )
}
