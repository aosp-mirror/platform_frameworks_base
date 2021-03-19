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

import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroupOverlay
import android.view.ViewRootImpl
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
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
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var rippleView: ChargingRippleView
    @Mock private lateinit var viewHost: View
    @Mock private lateinit var viewHostRootImpl: ViewRootImpl
    @Mock private lateinit var viewGroupOverlay: ViewGroupOverlay

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(viewHost.viewRootImpl).thenReturn(viewHostRootImpl)
        `when`(viewHostRootImpl.view).thenReturn(viewHost)
        `when`(viewHost.overlay).thenReturn(viewGroupOverlay)
        `when`(featureFlags.isChargingRippleEnabled).thenReturn(true)
        `when`(keyguardStateController.isShowing).thenReturn(true)
        controller = WiredChargingRippleController(
                commandRegistry, batteryController, configurationController,
                featureFlags, context, keyguardStateController)
        controller.rippleView = rippleView // Replace the real ripple view with a mock instance
        controller.setViewHost(viewHost)
    }

    @Test
    fun testSetRippleViewAsOverlay() {
        val listenerCaptor = ArgumentCaptor.forClass(View.OnAttachStateChangeListener::class.java)
        verify(viewHost).addOnAttachStateChangeListener(listenerCaptor.capture())

        // Fake attach to window
        listenerCaptor.value.onViewAttachedToWindow(viewHost)
        verify(viewGroupOverlay).add(rippleView)
    }

    @Test
    fun testTriggerRipple() {
        val captor = ArgumentCaptor
                .forClass(BatteryController.BatteryStateChangeCallback::class.java)
        verify(batteryController).addCallback(captor.capture())

        val unusedBatteryLevel = 0
        captor.value.onBatteryLevelChanged(
                unusedBatteryLevel,
                false /* plugged in */,
                false /* charging */)
        verify(rippleView, never()).startRipple()

        captor.value.onBatteryLevelChanged(
                unusedBatteryLevel,
                false /* plugged in */,
                true /* charging */)
        verify(rippleView).startRipple()
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
