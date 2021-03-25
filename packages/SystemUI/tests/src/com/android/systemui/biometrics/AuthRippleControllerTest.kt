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

package com.android.systemui.biometrics

import android.hardware.biometrics.BiometricSourceType
import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.ConfigurationController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AuthRippleControllerTest : SysuiTestCase() {
    private lateinit var controller: AuthRippleController
    @Mock private lateinit var commandRegistry: CommandRegistry
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var rippleView: AuthRippleView
    @Mock private lateinit var viewHost: ViewGroup

    @Before

    fun setUp() {
        MockitoAnnotations.initMocks(this)
        controller = AuthRippleController(
            commandRegistry, configurationController, context, keyguardUpdateMonitor)
        controller.rippleView = rippleView // Replace the real ripple view with a mock instance
        controller.setViewHost(viewHost)
    }

    @Test
    fun testAddRippleView() {
        val listenerCaptor = ArgumentCaptor.forClass(View.OnAttachStateChangeListener::class.java)
        verify(viewHost).addOnAttachStateChangeListener(listenerCaptor.capture())

        // Fake attach to window
        listenerCaptor.value.onViewAttachedToWindow(viewHost)
        verify(viewHost).addView(rippleView)
    }

    @Test
    fun testTriggerRipple() {
        // Fake attach to window
        val listenerCaptor = ArgumentCaptor.forClass(View.OnAttachStateChangeListener::class.java)
        verify(viewHost).addOnAttachStateChangeListener(listenerCaptor.capture())
        listenerCaptor.value.onViewAttachedToWindow(viewHost)

        val captor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(keyguardUpdateMonitor).registerCallback(captor.capture())

        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FACE /* type */,
            false /* isStrongBiometric */)
        verify(rippleView, never()).startRipple()

        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FINGERPRINT /* type */,
            false /* isStrongBiometric */)
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

    @Test
    fun testForwardsSensorLocation() {
        controller.setSensorLocation(5f, 5f)
        verify(rippleView).setSensorLocation(5f, 5f)
    }
}
