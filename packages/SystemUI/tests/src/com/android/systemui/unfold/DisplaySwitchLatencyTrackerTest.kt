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
import android.content.res.Resources
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.DeviceStateRepository.DeviceState
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.unfold.DisplaySwitchLatencyTracker.Companion.FOLDABLE_DEVICE_STATE_CLOSED
import com.android.systemui.unfold.DisplaySwitchLatencyTracker.Companion.FOLDABLE_DEVICE_STATE_HALF_OPEN
import com.android.systemui.unfold.DisplaySwitchLatencyTracker.DisplaySwitchLatencyEvent
import com.android.systemui.unfold.data.repository.UnfoldTransitionRepositoryImpl
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractorImpl
import com.android.systemui.util.animation.data.repository.AnimationStatusRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class DisplaySwitchLatencyTrackerTest : SysuiTestCase() {
    private lateinit var displaySwitchLatencyTracker: DisplaySwitchLatencyTracker
    @Captor private lateinit var loggerArgumentCaptor: ArgumentCaptor<DisplaySwitchLatencyEvent>

    private val mockContext = mock<Context>()
    private val resources = mock<Resources>()
    private val foldStateRepository = mock<DeviceStateRepository>()
    private val powerInteractor = mock<PowerInteractor>()
    private val animationStatusRepository = mock<AnimationStatusRepository>()
    private val keyguardInteractor = mock<KeyguardInteractor>()
    private val displaySwitchLatencyLogger = mock<DisplaySwitchLatencyLogger>()

    private val nonEmptyClosedDeviceStatesArray: IntArray = IntArray(2) { 0 }
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private val testScope: TestScope = TestScope(testDispatcher)
    private val isAsleep = MutableStateFlow(false)
    private val isAodAvailable = MutableStateFlow(false)
    private val deviceState = MutableStateFlow(DeviceState.UNFOLDED)
    private val screenPowerState = MutableStateFlow(ScreenPowerState.SCREEN_ON)
    private val areAnimationEnabled = MutableStateFlow(true)
    private val lastWakefulnessEvent = MutableStateFlow(WakefulnessModel())
    private val systemClock = FakeSystemClock()
    private val unfoldTransitionProgressProvider = TestUnfoldTransitionProvider()
    private val unfoldTransitionRepository =
        UnfoldTransitionRepositoryImpl(Optional.of(unfoldTransitionProgressProvider))
    private val unfoldTransitionInteractor =
        UnfoldTransitionInteractorImpl(unfoldTransitionRepository)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(mockContext.resources).thenReturn(resources)
        whenever(resources.getIntArray(R.array.config_foldedDeviceStates))
            .thenReturn(nonEmptyClosedDeviceStatesArray)
        whenever(foldStateRepository.state).thenReturn(deviceState)
        whenever(powerInteractor.isAsleep).thenReturn(isAsleep)
        whenever(animationStatusRepository.areAnimationsEnabled()).thenReturn(areAnimationEnabled)
        whenever(powerInteractor.screenPowerState).thenReturn(screenPowerState)
        whenever(keyguardInteractor.isAodAvailable).thenReturn(isAodAvailable)
        whenever(powerInteractor.detailedWakefulness).thenReturn(lastWakefulnessEvent)

        displaySwitchLatencyTracker =
            DisplaySwitchLatencyTracker(
                mockContext,
                foldStateRepository,
                powerInteractor,
                unfoldTransitionInteractor,
                animationStatusRepository,
                keyguardInteractor,
                testDispatcher.asExecutor(),
                testScope.backgroundScope,
                displaySwitchLatencyLogger,
                systemClock
            )
    }

    @Test
    fun unfold_logsLatencyTillTransitionStarted() {
        testScope.runTest {
            areAnimationEnabled.emit(true)

            displaySwitchLatencyTracker.start()
            deviceState.emit(DeviceState.FOLDED)
            screenPowerState.emit(ScreenPowerState.SCREEN_OFF)
            systemClock.advanceTime(50)
            runCurrent()
            deviceState.emit(DeviceState.HALF_FOLDED)
            runCurrent()
            systemClock.advanceTime(50)
            screenPowerState.emit(ScreenPowerState.SCREEN_ON)
            systemClock.advanceTime(200)
            unfoldTransitionProgressProvider.onTransitionStarted()
            runCurrent()
            deviceState.emit(DeviceState.UNFOLDED)

            verify(displaySwitchLatencyLogger).log(capture(loggerArgumentCaptor))
            val loggedEvent = loggerArgumentCaptor.value
            val expectedLoggedEvent =
                DisplaySwitchLatencyEvent(
                    latencyMs = 250,
                    fromFoldableDeviceState = FOLDABLE_DEVICE_STATE_CLOSED,
                    toFoldableDeviceState = FOLDABLE_DEVICE_STATE_HALF_OPEN
                )
            assertThat(loggedEvent).isEqualTo(expectedLoggedEvent)
        }
    }

    @Test
    fun unfold_progressUnavailable_logsLatencyTillScreenTurnedOn() {
        testScope.runTest {
            val unfoldTransitionInteractorWithEmptyProgressProvider =
                UnfoldTransitionInteractorImpl(UnfoldTransitionRepositoryImpl(Optional.empty()))
            displaySwitchLatencyTracker =
                DisplaySwitchLatencyTracker(
                    mockContext,
                    foldStateRepository,
                    powerInteractor,
                    unfoldTransitionInteractorWithEmptyProgressProvider,
                    animationStatusRepository,
                    keyguardInteractor,
                    testDispatcher.asExecutor(),
                    testScope.backgroundScope,
                    displaySwitchLatencyLogger,
                    systemClock
                )
            areAnimationEnabled.emit(true)

            displaySwitchLatencyTracker.start()
            deviceState.emit(DeviceState.FOLDED)
            screenPowerState.emit(ScreenPowerState.SCREEN_OFF)
            systemClock.advanceTime(50)
            runCurrent()
            deviceState.emit(DeviceState.HALF_FOLDED)
            systemClock.advanceTime(50)
            runCurrent()
            screenPowerState.emit(ScreenPowerState.SCREEN_ON)
            systemClock.advanceTime(50)
            runCurrent()
            systemClock.advanceTime(200)
            unfoldTransitionProgressProvider.onTransitionStarted()
            runCurrent()
            deviceState.emit(DeviceState.UNFOLDED)

            verify(displaySwitchLatencyLogger).log(capture(loggerArgumentCaptor))
            val loggedEvent = loggerArgumentCaptor.value
            val expectedLoggedEvent =
                DisplaySwitchLatencyEvent(
                    latencyMs = 50,
                    fromFoldableDeviceState = FOLDABLE_DEVICE_STATE_CLOSED,
                    toFoldableDeviceState = FOLDABLE_DEVICE_STATE_HALF_OPEN
                )
            assertThat(loggedEvent).isEqualTo(expectedLoggedEvent)
        }
    }

    @Test
    fun unfold_animationDisabled_logsLatencyTillScreenTurnedOn() {
        testScope.runTest {
            areAnimationEnabled.emit(false)

            displaySwitchLatencyTracker.start()
            deviceState.emit(DeviceState.FOLDED)
            screenPowerState.emit(ScreenPowerState.SCREEN_OFF)
            systemClock.advanceTime(50)
            runCurrent()
            deviceState.emit(DeviceState.HALF_FOLDED)
            systemClock.advanceTime(50)
            runCurrent()
            screenPowerState.emit(ScreenPowerState.SCREEN_ON)
            systemClock.advanceTime(50)
            runCurrent()
            unfoldTransitionProgressProvider.onTransitionStarted()
            systemClock.advanceTime(200)
            runCurrent()
            deviceState.emit(DeviceState.UNFOLDED)

            verify(displaySwitchLatencyLogger).log(capture(loggerArgumentCaptor))
            val loggedEvent = loggerArgumentCaptor.value
            val expectedLoggedEvent =
                DisplaySwitchLatencyEvent(
                    latencyMs = 50,
                    fromFoldableDeviceState = FOLDABLE_DEVICE_STATE_CLOSED,
                    toFoldableDeviceState = FOLDABLE_DEVICE_STATE_HALF_OPEN
                )
            assertThat(loggedEvent).isEqualTo(expectedLoggedEvent)
        }
    }

    @Test
    fun foldWhileStayingAwake_logsLatency() {
        testScope.runTest {
            areAnimationEnabled.emit(true)
            deviceState.emit(DeviceState.UNFOLDED)
            screenPowerState.emit(ScreenPowerState.SCREEN_ON)

            displaySwitchLatencyTracker.start()
            deviceState.emit(DeviceState.HALF_FOLDED)
            systemClock.advanceTime(50)
            runCurrent()
            deviceState.emit(DeviceState.FOLDED)
            screenPowerState.emit(ScreenPowerState.SCREEN_OFF)
            runCurrent()
            systemClock.advanceTime(200)
            screenPowerState.emit(ScreenPowerState.SCREEN_ON)
            runCurrent()

            verify(displaySwitchLatencyLogger).log(capture(loggerArgumentCaptor))
            val loggedEvent = loggerArgumentCaptor.value
            val expectedLoggedEvent =
                DisplaySwitchLatencyEvent(
                    latencyMs = 200,
                    fromFoldableDeviceState = FOLDABLE_DEVICE_STATE_HALF_OPEN,
                    toFoldableDeviceState = FOLDABLE_DEVICE_STATE_CLOSED
                )
            assertThat(loggedEvent).isEqualTo(expectedLoggedEvent)
        }
    }

    @Test
    fun foldToAod_capturesToStateAsAod() {
        testScope.runTest {
            areAnimationEnabled.emit(true)
            deviceState.emit(DeviceState.UNFOLDED)
            isAodAvailable.emit(true)

            displaySwitchLatencyTracker.start()
            deviceState.emit(DeviceState.HALF_FOLDED)
            systemClock.advanceTime(50)
            runCurrent()
            deviceState.emit(DeviceState.FOLDED)
            lastWakefulnessEvent.emit(
                WakefulnessModel(
                    internalWakefulnessState = WakefulnessState.ASLEEP,
                    lastSleepReason = WakeSleepReason.FOLD
                )
            )
            screenPowerState.emit(ScreenPowerState.SCREEN_OFF)
            runCurrent()
            systemClock.advanceTime(200)
            screenPowerState.emit(ScreenPowerState.SCREEN_ON)
            runCurrent()

            verify(displaySwitchLatencyLogger).log(capture(loggerArgumentCaptor))
            val loggedEvent = loggerArgumentCaptor.value
            val expectedLoggedEvent =
                DisplaySwitchLatencyEvent(
                    latencyMs = 200,
                    fromFoldableDeviceState = FOLDABLE_DEVICE_STATE_HALF_OPEN,
                    toFoldableDeviceState = FOLDABLE_DEVICE_STATE_CLOSED,
                    toState = SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__TO_STATE__AOD
                )
            assertThat(loggedEvent).isEqualTo(expectedLoggedEvent)
        }
    }

    @Test
    fun fold_notAFoldable_shouldNotLogLatency() {
        testScope.runTest {
            areAnimationEnabled.emit(true)
            deviceState.emit(DeviceState.UNFOLDED)
            whenever(resources.getIntArray(R.array.config_foldedDeviceStates))
                .thenReturn(IntArray(0))

            displaySwitchLatencyTracker.start()
            deviceState.emit(DeviceState.HALF_FOLDED)
            systemClock.advanceTime(50)
            runCurrent()
            deviceState.emit(DeviceState.FOLDED)
            screenPowerState.emit(ScreenPowerState.SCREEN_OFF)
            runCurrent()
            systemClock.advanceTime(200)
            screenPowerState.emit(ScreenPowerState.SCREEN_ON)
            runCurrent()

            verify(displaySwitchLatencyLogger, never()).log(any())
        }
    }

    @Test
    fun foldToScreenOff_capturesToStateAsScreenOff() {
        testScope.runTest {
            areAnimationEnabled.emit(true)
            deviceState.emit(DeviceState.UNFOLDED)
            isAodAvailable.emit(false)

            displaySwitchLatencyTracker.start()
            deviceState.emit(DeviceState.HALF_FOLDED)
            systemClock.advanceTime(50)
            runCurrent()
            deviceState.emit(DeviceState.FOLDED)
            lastWakefulnessEvent.emit(
                WakefulnessModel(
                    internalWakefulnessState = WakefulnessState.ASLEEP,
                    lastSleepReason = WakeSleepReason.FOLD
                )
            )
            screenPowerState.emit(ScreenPowerState.SCREEN_OFF)
            runCurrent()

            verify(displaySwitchLatencyLogger).log(capture(loggerArgumentCaptor))
            val loggedEvent = loggerArgumentCaptor.value
            val expectedLoggedEvent =
                DisplaySwitchLatencyEvent(
                    latencyMs = 0,
                    fromFoldableDeviceState = FOLDABLE_DEVICE_STATE_HALF_OPEN,
                    toFoldableDeviceState = FOLDABLE_DEVICE_STATE_CLOSED,
                    toState = SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED__TO_STATE__SCREEN_OFF
                )
            assertThat(loggedEvent).isEqualTo(expectedLoggedEvent)
        }
    }
}
