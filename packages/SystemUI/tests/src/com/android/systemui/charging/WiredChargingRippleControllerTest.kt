/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.charging

import android.testing.AndroidTestingRunner
import android.view.View
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.ripple.RippleView
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class WiredChargingRippleControllerTest : SysuiTestCase() {
    private lateinit var controller: WiredChargingRippleController
    @Mock private lateinit var commandRegistry: CommandRegistry
    @Mock private lateinit var batteryController: BatteryController
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var rippleView: RippleView
    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var uiEventLogger: UiEventLogger
    private val systemClock = FakeSystemClock()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(featureFlags.isEnabled(Flags.CHARGING_RIPPLE)).thenReturn(true)
        controller = WiredChargingRippleController(
                commandRegistry, batteryController, configurationController,
                featureFlags, context, windowManager, systemClock, uiEventLogger)
        rippleView.setupShader()
        controller.rippleView = rippleView // Replace the real ripple view with a mock instance
        controller.registerCallbacks()
    }

    @Test
    fun testTriggerRipple_UnlockedState() {
        val captor = ArgumentCaptor
                .forClass(BatteryController.BatteryStateChangeCallback::class.java)
        verify(batteryController).addCallback(captor.capture())

        // Verify ripple added to window manager.
        captor.value.onBatteryLevelChanged(
                /* unusedBatteryLevel= */ 0,
                /* plugged in= */ true,
                /* charging= */ false)
        val attachListenerCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener::class.java)
        verify(rippleView).addOnAttachStateChangeListener(attachListenerCaptor.capture())
        verify(windowManager).addView(eq(rippleView), any<WindowManager.LayoutParams>())

        // Verify ripple started
        val runnableCaptor =
                ArgumentCaptor.forClass(Runnable::class.java)
        attachListenerCaptor.value.onViewAttachedToWindow(rippleView)
        verify(rippleView).startRipple(runnableCaptor.capture())

        // Verify ripple removed
        runnableCaptor.value.run()
        verify(windowManager).removeView(rippleView)

        // Verify event logged
        verify(uiEventLogger).log(
                WiredChargingRippleController.WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    @Test
    fun testUpdateRippleColor() {
        val captor = ArgumentCaptor
                .forClass(ConfigurationController.ConfigurationListener::class.java)
        verify(configurationController).addCallback(captor.capture())

        reset(rippleView)
        captor.value.onThemeChanged()
        verify(rippleView).setColor(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())

        reset(rippleView)
        captor.value.onUiModeChanged()
        verify(rippleView).setColor(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())
    }

    @Test
    fun testDebounceRipple() {
        var time: Long = 0
        systemClock.setElapsedRealtime(time)

        controller.startRippleWithDebounce()
        verify(rippleView).addOnAttachStateChangeListener(ArgumentMatchers.any())

        reset(rippleView)
        // Wait a short while and trigger.
        time += 100
        systemClock.setElapsedRealtime(time)
        controller.startRippleWithDebounce()

        // Verify the ripple is debounced.
        verify(rippleView, never()).addOnAttachStateChangeListener(ArgumentMatchers.any())

        // Trigger many times.
        for (i in 0..100) {
            time += 100
            systemClock.setElapsedRealtime(time)
            controller.startRippleWithDebounce()
        }
        // Verify all attempts are debounced.
        verify(rippleView, never()).addOnAttachStateChangeListener(ArgumentMatchers.any())

        // Wait a long while and trigger.
        systemClock.setElapsedRealtime(time + 500000)
        controller.startRippleWithDebounce()
        // Verify that ripple is triggered.
        verify(rippleView).addOnAttachStateChangeListener(ArgumentMatchers.any())
    }

    @Test
    fun testRipple_whenDocked_doesNotPlayRipple() {
        `when`(batteryController.isChargingSourceDock).thenReturn(true)
        val captor = ArgumentCaptor
                .forClass(BatteryController.BatteryStateChangeCallback::class.java)
        verify(batteryController).addCallback(captor.capture())

        captor.value.onBatteryLevelChanged(
                /* unusedBatteryLevel= */ 0,
                /* plugged in= */ true,
                /* charging= */ false)

        val attachListenerCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener::class.java)
        verify(rippleView, never()).addOnAttachStateChangeListener(attachListenerCaptor.capture())
        verify(windowManager, never()).addView(eq(rippleView), any<WindowManager.LayoutParams>())
    }
}
