/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.keyguard

import android.animation.ValueAnimator
import android.content.res.Resources
import android.hardware.biometrics.BiometricSourceType
import android.os.Handler
import android.provider.Settings.System.SCREEN_BRIGHTNESS_FLOAT
import android.testing.AndroidTestingRunner
import android.util.TypedValue
import android.view.View
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dumpable
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SystemSettings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

const val INITIAL_BRIGHTNESS = 0.5f

@RunWith(AndroidTestingRunner::class)
@SmallTest
class FaceAuthScreenBrightnessControllerTest : SysuiTestCase() {

    @Mock
    lateinit var whiteOverlay: View
    @Mock
    lateinit var dumpManager: DumpManager
    @Mock
    lateinit var resources: Resources
    @Mock
    lateinit var mainHandler: Handler
    @Mock
    lateinit var globalSettings: GlobalSettings
    @Mock
    lateinit var systemSettings: SystemSettings
    @Mock
    lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock
    lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock
    lateinit var animator: ValueAnimator
    @Captor
    lateinit var keyguardUpdateCallback: ArgumentCaptor<KeyguardUpdateMonitorCallback>
    lateinit var faceAuthScreenBrightnessController: FaceAuthScreenBrightnessController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        faceAuthScreenBrightnessController = object : FaceAuthScreenBrightnessController(
                notificationShadeWindowController, keyguardUpdateMonitor, resources, globalSettings,
                systemSettings, mainHandler, dumpManager, true) {
            override fun createAnimator(start: Float, end: Float) = animator
        }
        `when`(systemSettings.getFloat(eq(SCREEN_BRIGHTNESS_FLOAT))).thenReturn(INITIAL_BRIGHTNESS)
        `when`(systemSettings.getFloat(eq(SCREEN_BRIGHTNESS_FLOAT), eq(1f)))
                .thenReturn(INITIAL_BRIGHTNESS)
        faceAuthScreenBrightnessController.attach(whiteOverlay)
        verify(keyguardUpdateMonitor).registerCallback(capture(keyguardUpdateCallback))
    }

    @Test
    fun init_registersDumpManager() {
        verify(dumpManager).registerDumpable(anyString(), any(Dumpable::class.java))
    }

    @Test
    fun init_registersKeyguardCallback() {
        verify(keyguardUpdateMonitor)
                .registerCallback(any(KeyguardUpdateMonitorCallback::class.java))
    }

    @Test
    fun onBiometricRunningChanged_animatesBrightness() {
        clearInvocations(whiteOverlay)
        keyguardUpdateCallback.value
                .onBiometricRunningStateChanged(true, BiometricSourceType.FACE)
        verify(whiteOverlay).visibility = eq(View.VISIBLE)
        verify(animator).start()
    }

    @Test
    fun faceAuthWallpaper_whenFaceIsDisabledForUser() {
        faceAuthScreenBrightnessController.useFaceAuthWallpaper = true
        faceAuthScreenBrightnessController.faceAuthWallpaper
        verify(resources, never()).openRawResource(anyInt(), any(TypedValue::class.java))
    }

    @Test
    fun faceAuthWallpaper_whenFaceFlagIsDisabled() {
        faceAuthScreenBrightnessController.useFaceAuthWallpaper = true
        faceAuthScreenBrightnessController.faceAuthWallpaper
        verify(resources, never()).openRawResource(anyInt(), any(TypedValue::class.java))
    }

    @Test
    fun faceAuthWallpaper_whenFaceIsEnabledForUser() {
        faceAuthScreenBrightnessController.useFaceAuthWallpaper = true
        `when`(keyguardUpdateMonitor.isFaceAuthEnabledForUser(anyInt())).thenReturn(true)
        faceAuthScreenBrightnessController.faceAuthWallpaper
        verify(resources).openRawResource(anyInt(), any(TypedValue::class.java))
    }
}