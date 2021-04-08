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

package com.android.systemui.statusbar.charging

import android.content.Context
import android.testing.AndroidTestingRunner
import android.view.View
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.mockito.capture
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class WiredChargingRippleControllerTest : SysuiTestCase() {
    private lateinit var controller: WiredChargingRippleController
    @Mock private lateinit var commandRegistry: CommandRegistry
    @Mock private lateinit var batteryController: BatteryController
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var rippleView: ChargingRippleView
    @Mock private lateinit var windowManager: WindowManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(featureFlags.isChargingRippleEnabled).thenReturn(true)
        controller = WiredChargingRippleController(
                commandRegistry, batteryController, configurationController,
                featureFlags, context)
        controller.rippleView = rippleView // Replace the real ripple view with a mock instance
        context.addMockSystemService(Context.WINDOW_SERVICE, windowManager)
    }

    @Test
    fun testTriggerRipple_UnlockedState() {
        val captor = ArgumentCaptor
                .forClass(BatteryController.BatteryStateChangeCallback::class.java)
        verify(batteryController).addCallback(captor.capture())

        // Verify ripple added to window manager.
        captor.value.onBatteryLevelChanged(
                0 /* unusedBatteryLevel */,
                false /* plugged in */,
                true /* charging */)
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
    }

    @Test
    fun testUpdateRippleColor() {
        val captor = ArgumentCaptor
                .forClass(ConfigurationController.ConfigurationListener::class.java)
        verify(configurationController).addCallback(captor.capture())

        reset(rippleView)
        captor.value.onThemeChanged()
        verify(rippleView).setColor(ArgumentMatchers.anyInt())

        reset(rippleView)
        captor.value.onUiModeChanged()
        verify(rippleView).setColor(ArgumentMatchers.anyInt())
    }
}
