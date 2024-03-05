/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.keyguard

import android.content.BroadcastReceiver
import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogcatOnlyMessageBuffer
import com.android.systemui.plugins.clocks.ClockAnimations
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockEvents
import com.android.systemui.plugins.clocks.ClockFaceConfig
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.plugins.clocks.ClockFaceEvents
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockTickRate
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import java.util.TimeZone
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ClockEventControllerTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var batteryController: BatteryController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var animations: ClockAnimations
    @Mock private lateinit var events: ClockEvents
    @Mock private lateinit var clock: ClockController
    @Mock private lateinit var mainExecutor: DelayableExecutor
    @Mock private lateinit var bgExecutor: Executor
    @Mock private lateinit var smallClockController: ClockFaceController
    @Mock private lateinit var smallClockView: View
    @Mock private lateinit var smallClockViewTreeObserver: ViewTreeObserver
    @Mock private lateinit var smallClockFrame: FrameLayout
    @Mock private lateinit var smallClockFrameViewTreeObserver: ViewTreeObserver
    @Mock private lateinit var largeClockController: ClockFaceController
    @Mock private lateinit var largeClockView: View
    @Mock private lateinit var largeClockViewTreeObserver: ViewTreeObserver
    @Mock private lateinit var smallClockEvents: ClockFaceEvents
    @Mock private lateinit var largeClockEvents: ClockFaceEvents
    @Mock private lateinit var parentView: View
    private lateinit var repository: FakeKeyguardRepository
    @Mock private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    private val messageBuffer = LogcatOnlyMessageBuffer(LogLevel.DEBUG)
    private val clockBuffers = ClockMessageBuffers(messageBuffer, messageBuffer, messageBuffer)
    private lateinit var underTest: ClockEventController
    @Mock private lateinit var zenModeController: ZenModeController

    @Before
    fun setUp() {
        whenever(clock.smallClock).thenReturn(smallClockController)
        whenever(clock.largeClock).thenReturn(largeClockController)
        whenever(smallClockController.view).thenReturn(smallClockView)
        whenever(smallClockView.parent).thenReturn(smallClockFrame)
        whenever(smallClockView.viewTreeObserver).thenReturn(smallClockViewTreeObserver)
        whenever(smallClockFrame.viewTreeObserver).thenReturn(smallClockFrameViewTreeObserver)
        whenever(largeClockController.view).thenReturn(largeClockView)
        whenever(largeClockView.viewTreeObserver).thenReturn(largeClockViewTreeObserver)
        whenever(smallClockController.events).thenReturn(smallClockEvents)
        whenever(largeClockController.events).thenReturn(largeClockEvents)
        whenever(clock.events).thenReturn(events)
        whenever(smallClockController.animations).thenReturn(animations)
        whenever(largeClockController.animations).thenReturn(animations)
        whenever(smallClockController.config)
            .thenReturn(ClockFaceConfig(tickRate = ClockTickRate.PER_MINUTE))
        whenever(largeClockController.config)
            .thenReturn(ClockFaceConfig(tickRate = ClockTickRate.PER_MINUTE))

        repository = FakeKeyguardRepository()

        val withDeps =
            KeyguardInteractorFactory.create(
                repository = repository,
            )

        withDeps.featureFlags.apply { set(Flags.REGION_SAMPLING, false) }
        underTest =
            ClockEventController(
                withDeps.keyguardInteractor,
                keyguardTransitionInteractor,
                broadcastDispatcher,
                batteryController,
                keyguardUpdateMonitor,
                configurationController,
                context.resources,
                context,
                mainExecutor,
                bgExecutor,
                clockBuffers,
                withDeps.featureFlags,
                zenModeController
            )
        underTest.clock = clock

        runBlocking(IMMEDIATE) {
            underTest.registerListeners(parentView)

            repository.setIsDozing(true)
            repository.setDozeAmount(1f)
        }
    }

    @Test
    fun clockSet_validateInitialization() {
        verify(clock).initialize(any(), anyFloat(), anyFloat())
    }

    @Test
    fun clockUnset_validateState() {
        underTest.clock = null

        assertEquals(underTest.clock, null)
    }

    @Test
    fun themeChanged_verifyClockPaletteUpdated() =
        runBlocking(IMMEDIATE) {
            verify(smallClockEvents).onRegionDarknessChanged(anyBoolean())
            verify(largeClockEvents).onRegionDarknessChanged(anyBoolean())

            val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
            verify(configurationController).addCallback(capture(captor))
            captor.value.onThemeChanged()

            verify(events).onColorPaletteChanged(any())
        }

    @Test
    fun fontChanged_verifyFontSizeUpdated() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
            verify(configurationController).addCallback(capture(captor))
            captor.value.onDensityOrFontScaleChanged()

            verify(smallClockEvents, times(2)).onFontSettingChanged(anyFloat())
            verify(largeClockEvents, times(2)).onFontSettingChanged(anyFloat())
        }

    @Test
    fun batteryCallback_keyguardShowingCharging_verifyChargeAnimation() =
        runBlocking(IMMEDIATE) {
            val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(batteryCaptor))
            val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
            keyguardCaptor.value.onKeyguardVisibilityChanged(true)
            batteryCaptor.value.onBatteryLevelChanged(10, false, true)

            verify(animations, times(2)).charge()
        }

    @Test
    fun batteryCallback_keyguardShowingCharging_Duplicate_verifyChargeAnimation() =
        runBlocking(IMMEDIATE) {
            val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(batteryCaptor))
            val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
            keyguardCaptor.value.onKeyguardVisibilityChanged(true)
            batteryCaptor.value.onBatteryLevelChanged(10, false, true)
            batteryCaptor.value.onBatteryLevelChanged(10, false, true)

            verify(animations, times(2)).charge()
        }

    @Test
    fun batteryCallback_keyguardHiddenCharging_verifyChargeAnimation() =
        runBlocking(IMMEDIATE) {
            val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(batteryCaptor))
            val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
            keyguardCaptor.value.onKeyguardVisibilityChanged(false)
            batteryCaptor.value.onBatteryLevelChanged(10, false, true)

            verify(animations, never()).charge()
        }

    @Test
    fun batteryCallback_keyguardShowingNotCharging_verifyChargeAnimation() =
        runBlocking(IMMEDIATE) {
            val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
            verify(batteryController).addCallback(capture(batteryCaptor))
            val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
            keyguardCaptor.value.onKeyguardVisibilityChanged(true)
            batteryCaptor.value.onBatteryLevelChanged(10, false, false)

            verify(animations, never()).charge()
        }

    @Test
    fun localeCallback_verifyClockNotified() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<BroadcastReceiver>()
            verify(broadcastDispatcher)
                .registerReceiver(capture(captor), any(), eq(null), eq(null), anyInt(), eq(null))
            captor.value.onReceive(context, mock())

            verify(events).onLocaleChanged(any())
        }

    @Test
    fun keyguardCallback_visibilityChanged_clockDozeCalled() =
        runBlocking(IMMEDIATE) {
            mSetFlagsRule.disableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(captor))

            captor.value.onKeyguardVisibilityChanged(true)
            verify(animations, never()).doze(0f)

            captor.value.onKeyguardVisibilityChanged(false)
            verify(animations, times(2)).doze(0f)
        }

    @Test
    fun keyguardCallback_timeFormat_clockNotified() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(captor))
            captor.value.onTimeFormatChanged("12h")

            verify(events).onTimeFormatChanged(false)
        }

    @Test
    fun keyguardCallback_timezoneChanged_clockNotified() =
        runBlocking(IMMEDIATE) {
            val mockTimeZone = mock<TimeZone>()
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(captor))
            captor.value.onTimeZoneChanged(mockTimeZone)

            verify(events).onTimeZoneChanged(mockTimeZone)
        }

    @Test
    fun keyguardCallback_userSwitched_clockNotified() =
        runBlocking(IMMEDIATE) {
            val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(keyguardUpdateMonitor).registerCallback(capture(captor))
            captor.value.onUserSwitchComplete(10)

            verify(events).onTimeFormatChanged(false)
        }

    @Test
    fun keyguardCallback_verifyKeyguardChanged() =
        runBlocking(IMMEDIATE) {
            val job = underTest.listenForDozeAmount(this)
            repository.setDozeAmount(0.4f)

            yield()

            verify(animations, times(2)).doze(0.4f)

            job.cancel()
        }

    @Test
    fun listenForDozeAmountTransition_updatesClockDozeAmount() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.lockscreenToAodTransition)
                .thenReturn(transitionStep)
            whenever(keyguardTransitionInteractor.aodToLockscreenTransition)
                .thenReturn(transitionStep)

            val job = underTest.listenForDozeAmountTransition(this)
            transitionStep.value =
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    value = 0.4f
                )
            yield()

            verify(animations, times(2)).doze(0.4f)

            job.cancel()
        }

    @Test
    fun listenForTransitionToAodFromGone_updatesClockDozeAmountToOne() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.transitionStepsToState(KeyguardState.AOD))
                .thenReturn(transitionStep)

            val job = underTest.listenForAnyStateToAodTransition(this)
            transitionStep.value =
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            yield()

            verify(animations, times(2)).doze(1f)

            job.cancel()
        }

    @Test
    fun listenForTransitionToAodFromLockscreen_neverUpdatesClockDozeAmount() =
        runBlocking(IMMEDIATE) {
            val transitionStep = MutableStateFlow(TransitionStep())
            whenever(keyguardTransitionInteractor.transitionStepsToState(KeyguardState.AOD))
                .thenReturn(transitionStep)

            val job = underTest.listenForAnyStateToAodTransition(this)
            transitionStep.value =
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            yield()

            verify(animations, never()).doze(1f)

            job.cancel()
        }

    @Test
    fun unregisterListeners_validate() =
        runBlocking(IMMEDIATE) {
            underTest.unregisterListeners()
            verify(broadcastDispatcher).unregisterReceiver(any())
            verify(configurationController).removeCallback(any())
            verify(batteryController).removeCallback(any())
            verify(keyguardUpdateMonitor).removeCallback(any())
            verify(smallClockController.view)
                .removeOnAttachStateChangeListener(underTest.smallClockOnAttachStateChangeListener)
            verify(largeClockController.view)
                .removeOnAttachStateChangeListener(underTest.largeClockOnAttachStateChangeListener)
        }

    @Test
    fun registerOnAttachStateChangeListener_validate() =
        runBlocking(IMMEDIATE) {
            verify(smallClockController.view)
                .addOnAttachStateChangeListener(underTest.smallClockOnAttachStateChangeListener)
            verify(largeClockController.view)
                .addOnAttachStateChangeListener(underTest.largeClockOnAttachStateChangeListener)
        }

    @Test
    fun registerAndRemoveOnGlobalLayoutListener_correctly() =
        runBlocking(IMMEDIATE) {
            underTest.smallClockOnAttachStateChangeListener!!.onViewAttachedToWindow(smallClockView)
            verify(smallClockFrame.viewTreeObserver).addOnGlobalLayoutListener(any())
            underTest.smallClockOnAttachStateChangeListener!!.onViewDetachedFromWindow(
                smallClockView
            )
            verify(smallClockFrame.viewTreeObserver).removeOnGlobalLayoutListener(any())
        }

    @Test
    fun registerOnGlobalLayoutListener_RemoveOnAttachStateChangeListener_correctly() =
        runBlocking(IMMEDIATE) {
            underTest.smallClockOnAttachStateChangeListener!!.onViewAttachedToWindow(smallClockView)
            verify(smallClockFrame.viewTreeObserver).addOnGlobalLayoutListener(any())
            underTest.unregisterListeners()
            verify(smallClockFrame.viewTreeObserver).removeOnGlobalLayoutListener(any())
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
