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
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.plugins.ClockAnimations
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockEvents
import com.android.systemui.plugins.ClockFaceController
import com.android.systemui.plugins.ClockFaceConfig
import com.android.systemui.plugins.ClockFaceEvents
import com.android.systemui.plugins.ClockTickRate
import com.android.systemui.log.LogBuffer
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import java.util.TimeZone
import java.util.concurrent.Executor
import org.mockito.Mockito.`when` as whenever

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
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var smallClockController: ClockFaceController
    @Mock private lateinit var largeClockController: ClockFaceController
    @Mock private lateinit var smallClockEvents: ClockFaceEvents
    @Mock private lateinit var largeClockEvents: ClockFaceEvents
    @Mock private lateinit var parentView: View
    @Mock private lateinit var transitionRepository: KeyguardTransitionRepository
    @Mock private lateinit var commandQueue: CommandQueue
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var bouncerRepository: FakeKeyguardBouncerRepository
    @Mock private lateinit var smallLogBuffer: LogBuffer
    @Mock private lateinit var largeLogBuffer: LogBuffer
    private lateinit var underTest: ClockEventController

    @Before
    fun setUp() {
        whenever(clock.smallClock).thenReturn(smallClockController)
        whenever(clock.largeClock).thenReturn(largeClockController)
        whenever(smallClockController.view).thenReturn(TextView(context))
        whenever(largeClockController.view).thenReturn(TextView(context))
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
        bouncerRepository = FakeKeyguardBouncerRepository()

        underTest = ClockEventController(
            KeyguardInteractor(
                repository = repository,
                commandQueue = commandQueue,
                featureFlags = featureFlags,
                bouncerRepository = bouncerRepository,
            ),
            KeyguardTransitionInteractor(repository = transitionRepository),
            broadcastDispatcher,
            batteryController,
            keyguardUpdateMonitor,
            configurationController,
            context.resources,
            context,
            mainExecutor,
            bgExecutor,
            smallLogBuffer,
            largeLogBuffer,
            featureFlags
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
    fun themeChanged_verifyClockPaletteUpdated() = runBlocking(IMMEDIATE) {
        // TODO(b/266103601): delete this test and add more coverage for updateColors()
        // verify(smallClockEvents).onRegionDarknessChanged(anyBoolean())
        // verify(largeClockEvents).onRegionDarknessChanged(anyBoolean())

        val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
        verify(configurationController).addCallback(capture(captor))
        captor.value.onThemeChanged()

        verify(events).onColorPaletteChanged(any())
    }

    @Test
    fun fontChanged_verifyFontSizeUpdated() = runBlocking(IMMEDIATE) {
        val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
        verify(configurationController).addCallback(capture(captor))
        captor.value.onDensityOrFontScaleChanged()

        verify(smallClockEvents, times(2)).onFontSettingChanged(anyFloat())
        verify(largeClockEvents, times(2)).onFontSettingChanged(anyFloat())
    }

    @Test
    fun batteryCallback_keyguardShowingCharging_verifyChargeAnimation() = runBlocking(IMMEDIATE) {
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
    fun batteryCallback_keyguardHiddenCharging_verifyChargeAnimation() = runBlocking(IMMEDIATE) {
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
    fun localeCallback_verifyClockNotified() = runBlocking(IMMEDIATE) {
        val captor = argumentCaptor<BroadcastReceiver>()
        verify(broadcastDispatcher).registerReceiver(
            capture(captor), any(), eq(null), eq(null), anyInt(), eq(null)
        )
        captor.value.onReceive(context, mock())

        verify(events).onLocaleChanged(any())
    }

    @Test
    fun keyguardCallback_visibilityChanged_clockDozeCalled() = runBlocking(IMMEDIATE) {
        val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(captor))

        captor.value.onKeyguardVisibilityChanged(true)
        verify(animations, never()).doze(0f)

        captor.value.onKeyguardVisibilityChanged(false)
        verify(animations, times(2)).doze(0f)
    }

    @Test
    fun keyguardCallback_timeFormat_clockNotified() = runBlocking(IMMEDIATE) {
        val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(captor))
        captor.value.onTimeFormatChanged("12h")

        verify(events).onTimeFormatChanged(false)
    }

    @Test
    fun keyguardCallback_timezoneChanged_clockNotified() = runBlocking(IMMEDIATE) {
        val mockTimeZone = mock<TimeZone>()
        val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(captor))
        captor.value.onTimeZoneChanged(mockTimeZone)

        verify(events).onTimeZoneChanged(mockTimeZone)
    }

    @Test
    fun keyguardCallback_userSwitched_clockNotified() = runBlocking(IMMEDIATE) {
        val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(captor))
        captor.value.onUserSwitchComplete(10)

        verify(events).onTimeFormatChanged(false)
    }

    @Test
    fun keyguardCallback_verifyKeyguardChanged() = runBlocking(IMMEDIATE) {
        val job = underTest.listenForDozeAmount(this)
        repository.setDozeAmount(0.4f)

        yield()

        verify(animations, times(2)).doze(0.4f)

        job.cancel()
    }

    @Test
    fun unregisterListeners_validate() = runBlocking(IMMEDIATE) {
        underTest.unregisterListeners()
        verify(broadcastDispatcher).unregisterReceiver(any())
        verify(configurationController).removeCallback(any())
        verify(batteryController).removeCallback(any())
        verify(keyguardUpdateMonitor).removeCallback(any())
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
