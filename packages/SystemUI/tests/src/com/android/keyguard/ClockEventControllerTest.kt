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
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.plugins.Clock
import com.android.systemui.plugins.ClockAnimations
import com.android.systemui.plugins.ClockEvents
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import java.util.TimeZone
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
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ClockEventControllerTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var batteryController: BatteryController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var animations: ClockAnimations
    @Mock private lateinit var events: ClockEvents
    @Mock private lateinit var clock: Clock

    private lateinit var clockEventController: ClockEventController

    @Before
    fun setUp() {
        whenever(clock.smallClock).thenReturn(TextView(context))
        whenever(clock.largeClock).thenReturn(TextView(context))
        whenever(clock.events).thenReturn(events)
        whenever(clock.animations).thenReturn(animations)

        clockEventController = ClockEventController(
            statusBarStateController,
            broadcastDispatcher,
            batteryController,
            keyguardUpdateMonitor,
            configurationController,
            context.resources,
            context
        )
    }

    @Test
    fun clockSet_validateInitialization() {
        clockEventController.clock = clock

        verify(clock).initialize(any(), anyFloat(), anyFloat())
    }

    @Test
    fun clockUnset_validateState() {
        clockEventController.clock = clock
        clockEventController.clock = null

        assertEquals(clockEventController.clock, null)
    }

    @Test
    fun themeChanged_verifyClockPaletteUpdated() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
        verify(configurationController).addCallback(capture(captor))
        captor.value.onThemeChanged()

        verify(events).onColorPaletteChanged(any())
    }

    @Test
    fun batteryCallback_keyguardShowingCharging_verifyChargeAnimation() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
        verify(batteryController).addCallback(capture(batteryCaptor))
        val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
        keyguardCaptor.value.onKeyguardVisibilityChanged(true)
        batteryCaptor.value.onBatteryLevelChanged(10, false, true)

        verify(animations).charge()
    }

    @Test
    fun batteryCallback_keyguardShowingCharging_Duplicate_verifyChargeAnimation() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
        verify(batteryController).addCallback(capture(batteryCaptor))
        val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
        keyguardCaptor.value.onKeyguardVisibilityChanged(true)
        batteryCaptor.value.onBatteryLevelChanged(10, false, true)
        batteryCaptor.value.onBatteryLevelChanged(10, false, true)

        verify(animations, times(1)).charge()
    }

    @Test
    fun batteryCallback_keyguardHiddenCharging_verifyChargeAnimation() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
        verify(batteryController).addCallback(capture(batteryCaptor))
        val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
        keyguardCaptor.value.onKeyguardVisibilityChanged(false)
        batteryCaptor.value.onBatteryLevelChanged(10, false, true)

        verify(animations, never()).charge()
    }

    @Test
    fun batteryCallback_keyguardShowingNotCharging_verifyChargeAnimation() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val batteryCaptor = argumentCaptor<BatteryController.BatteryStateChangeCallback>()
        verify(batteryController).addCallback(capture(batteryCaptor))
        val keyguardCaptor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(keyguardCaptor))
        keyguardCaptor.value.onKeyguardVisibilityChanged(true)
        batteryCaptor.value.onBatteryLevelChanged(10, false, false)

        verify(animations, never()).charge()
    }

    @Test
    fun localeCallback_verifyClockNotified() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val captor = argumentCaptor<BroadcastReceiver>()
        verify(broadcastDispatcher).registerReceiver(
            capture(captor), any(), eq(null), eq(null), anyInt(), eq(null)
        )
        captor.value.onReceive(context, mock())

        verify(events).onLocaleChanged(any())
    }

    @Test
    fun keyguardCallback_visibilityChanged_clockDozeCalled() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(captor))

        captor.value.onKeyguardVisibilityChanged(true)
        verify(animations, never()).doze(0f)

        captor.value.onKeyguardVisibilityChanged(false)
        verify(animations, times(1)).doze(0f)
    }

    @Test
    fun keyguardCallback_timeFormat_clockNotified() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(captor))
        captor.value.onTimeFormatChanged("12h")

        verify(events).onTimeFormatChanged(false)
    }

    @Test
    fun keyguardCallback_timezoneChanged_clockNotified() {
        val mockTimeZone = mock<TimeZone>()
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(captor))
        captor.value.onTimeZoneChanged(mockTimeZone)

        verify(events).onTimeZoneChanged(mockTimeZone)
    }

    @Test
    fun keyguardCallback_userSwitched_clockNotified() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val captor = argumentCaptor<KeyguardUpdateMonitorCallback>()
        verify(keyguardUpdateMonitor).registerCallback(capture(captor))
        captor.value.onUserSwitchComplete(10)

        verify(events).onTimeFormatChanged(false)
    }

    @Test
    fun keyguardCallback_verifyKeyguardChanged() {
        clockEventController.clock = clock
        clockEventController.registerListeners()

        val captor = argumentCaptor<StatusBarStateController.StateListener>()
        verify(statusBarStateController).addCallback(capture(captor))
        captor.value.onDozeAmountChanged(0.4f, 0.6f)

        verify(animations).doze(0.4f)
    }

    @Test
    fun unregisterListeners_validate() {
        clockEventController.unregisterListeners()
        verify(broadcastDispatcher).unregisterReceiver(any())
        verify(configurationController).removeCallback(any())
        verify(batteryController).removeCallback(any())
        verify(keyguardUpdateMonitor).removeCallback(any())
        verify(statusBarStateController).removeCallback(any())
    }
}
