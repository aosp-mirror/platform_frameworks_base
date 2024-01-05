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
 * limitations under the License.
 */

package com.android.systemui.unfold

import android.content.Context
import android.util.Log
import com.android.app.tracing.TraceUtils.instantForTrack
import com.android.app.tracing.TraceUtils.traceAsync
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.DeviceStateRepository.DeviceState
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.unfold.dagger.UnfoldSingleThreadBg
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import com.android.systemui.util.Compile
import com.android.systemui.util.Utils.isDeviceFoldable
import com.android.systemui.util.animation.data.repository.AnimationStatusRepository
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.time.measureTimeMillis
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * [DisplaySwitchLatencyTracker] tracks latency and related fields for display switch of a foldable
 * device. This class populates [DisplaySwitchLatencyEvent] while an ongoing display switch event
 */
@SysUISingleton
class DisplaySwitchLatencyTracker
@Inject
constructor(
    private val context: Context,
    private val deviceStateRepository: DeviceStateRepository,
    private val powerInteractor: PowerInteractor,
    private val unfoldTransitionInteractor: UnfoldTransitionInteractor,
    private val animationStatusRepository: AnimationStatusRepository,
    private val keyguardInteractor: KeyguardInteractor,
    @UnfoldSingleThreadBg private val singleThreadBgExecutor: Executor,
    @Application private val applicationScope: CoroutineScope,
    private val displaySwitchLatencyLogger: DisplaySwitchLatencyLogger,
    private val systemClock: SystemClock
) : CoreStartable {

    private val backgroundDispatcher = singleThreadBgExecutor.asCoroutineDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        if (!isDeviceFoldable(context)) {
            return
        }
        applicationScope.launch(backgroundDispatcher) {
            deviceStateRepository.state
                .pairwise()
                .filter {
                    // Start tracking only when the foldable device is
                    //folding(UNFOLDED/HALF_FOLDED -> FOLDED) or
                    //unfolding(FOLDED -> HALF_FOLD/UNFOLDED)
                    foldableDeviceState ->
                    foldableDeviceState.previousValue == DeviceState.FOLDED ||
                        foldableDeviceState.newValue == DeviceState.FOLDED
                }
                .flatMapLatest { foldableDeviceState ->
                    flow {
                        var displaySwitchLatencyEvent = DisplaySwitchLatencyEvent()
                        val toFoldableDeviceState = foldableDeviceState.newValue.toStatsInt()
                        displaySwitchLatencyEvent =
                            displaySwitchLatencyEvent.withBeforeFields(
                                foldableDeviceState.previousValue.toStatsInt()
                            )

                        val displaySwitchTimeMs =
                            measureTimeMillis(systemClock) {
                                traceAsync(TAG, "displaySwitch") {
                                    waitForDisplaySwitch(toFoldableDeviceState)
                                }
                            }

                        displaySwitchLatencyEvent =
                            displaySwitchLatencyEvent.withAfterFields(
                                toFoldableDeviceState,
                                displaySwitchTimeMs.toInt(),
                                getCurrentState()
                            )
                        emit(displaySwitchLatencyEvent)
                    }
                }
                .collect { displaySwitchLatencyLogger.log(it) }
        }
    }

    private fun DeviceState.toStatsInt(): Int =
        when (this) {
            DeviceState.FOLDED -> FOLDABLE_DEVICE_STATE_CLOSED
            DeviceState.HALF_FOLDED -> FOLDABLE_DEVICE_STATE_HALF_OPEN
            DeviceState.UNFOLDED -> FOLDABLE_DEVICE_STATE_OPEN
            DeviceState.CONCURRENT_DISPLAY -> FOLDABLE_DEVICE_STATE_FLIPPED
            else -> FOLDABLE_DEVICE_STATE_UNKNOWN
        }

    private suspend fun waitForDisplaySwitch(toFoldableDeviceState: Int) {
        val isTransitionEnabled =
            unfoldTransitionInteractor.isAvailable &&
                animationStatusRepository.areAnimationsEnabled().first()
        if (shouldWaitForScreenOn(toFoldableDeviceState, isTransitionEnabled)) {
            waitForScreenTurnedOn()
        } else {
            traceAsync(TAG, "waitForTransitionStart()") {
                unfoldTransitionInteractor.waitForTransitionStart()
            }
        }
    }

    private fun shouldWaitForScreenOn(
        toFoldableDeviceState: Int,
        isTransitionEnabled: Boolean
    ): Boolean = (toFoldableDeviceState == FOLDABLE_DEVICE_STATE_CLOSED || !isTransitionEnabled)

    private suspend fun waitForScreenTurnedOn() {
        traceAsync(TAG, "waitForScreenTurnedOn()") {
            powerInteractor.screenPowerState.filter { it == ScreenPowerState.SCREEN_ON }.first()
        }
    }

    private fun getCurrentState(): Int =
        when {
            isStateAod() -> SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__TO_STATE__AOD
            else -> SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__TO_STATE__UNKNOWN
        }

    private fun isStateAod(): Boolean {
        val lastWakefulnessEvent = powerInteractor.detailedWakefulness.value
        val isAodEnabled = keyguardInteractor.isAodAvailable.value

        return (lastWakefulnessEvent.isAsleep() &&
            (lastWakefulnessEvent.lastSleepReason == WakeSleepReason.FOLD) &&
            isAodEnabled)
    }

    private inline fun log(msg: () -> String) {
        if (DEBUG) Log.d(TAG, msg())
    }

    private fun DisplaySwitchLatencyEvent.withBeforeFields(
        fromFoldableDeviceState: Int
    ): DisplaySwitchLatencyEvent {
        log { "fromFoldableDeviceState=$fromFoldableDeviceState" }
        instantForTrack(TAG, "fromFoldableDeviceState=$fromFoldableDeviceState")

        return copy(fromFoldableDeviceState = fromFoldableDeviceState)
    }

    private fun DisplaySwitchLatencyEvent.withAfterFields(
        toFoldableDeviceState: Int,
        displaySwitchTimeMs: Int,
        toState: Int
    ): DisplaySwitchLatencyEvent {
        log {
            "toFoldableDeviceState=$toFoldableDeviceState, " +
                "toState=$toState, " +
                "latencyMs=$displaySwitchTimeMs"
        }
        instantForTrack(TAG, "toFoldableDeviceState=$toFoldableDeviceState, toState=$toState")

        return copy(
            toFoldableDeviceState = toFoldableDeviceState,
            latencyMs = displaySwitchTimeMs,
            toState = toState
        )
    }

    /**
     * Stores values corresponding to all respective [DisplaySwitchLatencyTrackedField] in a single
     * event of display switch for foldable devices.
     *
     * Once the data is captured in this data class and appropriate to log, it is logged through
     * [DisplaySwitchLatencyLogger]
     */
    data class DisplaySwitchLatencyEvent(
        val latencyMs: Int = VALUE_UNKNOWN,
        val fromFoldableDeviceState: Int = FOLDABLE_DEVICE_STATE_UNKNOWN,
        val fromState: Int = SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__FROM_STATE__UNKNOWN,
        val fromFocusedAppUid: Int = VALUE_UNKNOWN,
        val fromPipAppUid: Int = VALUE_UNKNOWN,
        val fromVisibleAppsUid: Set<Int> = setOf(),
        val fromDensityDpi: Int = VALUE_UNKNOWN,
        val toFoldableDeviceState: Int = FOLDABLE_DEVICE_STATE_UNKNOWN,
        val toState: Int = SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__FROM_STATE__UNKNOWN,
        val toFocusedAppUid: Int = VALUE_UNKNOWN,
        val toPipAppUid: Int = VALUE_UNKNOWN,
        val toVisibleAppsUid: Set<Int> = setOf(),
        val toDensityDpi: Int = VALUE_UNKNOWN,
        val notificationCount: Int = VALUE_UNKNOWN,
        val externalDisplayCount: Int = VALUE_UNKNOWN,
        val throttlingLevel: Int =
            SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__THROTTLING_LEVEL__NONE,
        val vskinTemperatureC: Int = VALUE_UNKNOWN,
        val hallSensorToFirstHingeAngleChangeMs: Int = VALUE_UNKNOWN,
        val hallSensorToDeviceStateChangeMs: Int = VALUE_UNKNOWN,
        val onScreenTurningOnToOnDrawnMs: Int = VALUE_UNKNOWN,
        val onDrawnToOnScreenTurnedOnMs: Int = VALUE_UNKNOWN
    )

    companion object {
        private const val VALUE_UNKNOWN = -1
        private const val TAG = "DisplaySwitchLatency"
        private val DEBUG = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.VERBOSE)

        private const val FOLDABLE_DEVICE_STATE_UNKNOWN =
            SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__FROM_FOLDABLE_DEVICE_STATE__STATE_UNKNOWN
        const val FOLDABLE_DEVICE_STATE_CLOSED =
            SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__FROM_FOLDABLE_DEVICE_STATE__STATE_CLOSED
        const val FOLDABLE_DEVICE_STATE_HALF_OPEN =
            SysUiStatsLog
                .DISPLAY_SWITCH_LATENCY_TRACKED__FROM_FOLDABLE_DEVICE_STATE__STATE_HALF_OPENED
        private const val FOLDABLE_DEVICE_STATE_OPEN =
            SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__FROM_FOLDABLE_DEVICE_STATE__STATE_OPENED
        private const val FOLDABLE_DEVICE_STATE_FLIPPED =
            SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__FROM_FOLDABLE_DEVICE_STATE__STATE_FLIPPED
    }
}
