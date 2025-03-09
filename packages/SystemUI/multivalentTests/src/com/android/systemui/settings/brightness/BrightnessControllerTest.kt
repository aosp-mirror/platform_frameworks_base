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

package com.android.systemui.settings.brightness

import android.hardware.display.BrightnessInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.service.vr.IVrManager
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogBuffer
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class BrightnessControllerTest : SysuiTestCase() {
    @get:Rule
    public val mCheckFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    private val executor = FakeExecutor(FakeSystemClock())
    private val secureSettings = FakeSettings()
    @Mock private lateinit var toggleSlider: ToggleSlider
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var displayTracker: DisplayTracker
    @Mock private lateinit var displayManager: DisplayManager
    @Mock private lateinit var iVrManager: IVrManager
    @Mock private lateinit var logger: LogBuffer
    @Mock private lateinit var display: Display

    private lateinit var testableLooper: TestableLooper

    private lateinit var underTest: BrightnessController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        val contextSpy = spy(context)
        whenever(contextSpy.getDisplay()).thenReturn(display)
        underTest =
            BrightnessController(
                contextSpy,
                toggleSlider,
                userTracker,
                displayTracker,
                displayManager,
                secureSettings,
                logger,
                iVrManager,
                executor,
                mock(),
                Handler(testableLooper.looper),
            )
    }

    @Test
    fun registerCallbacksMultipleTimes_onlyOneRegistration() {
        val repeats = 100
        repeat(repeats) { underTest.registerCallbacks() }
        val messagesProcessed = testableLooper.processMessagesNonBlocking(repeats)

        verify(displayTracker).addBrightnessChangeCallback(any(), any())
        verify(iVrManager).registerListener(any())

        assertThat(messagesProcessed).isEqualTo(1)
    }

    @Test
    fun unregisterCallbacksMultipleTimes_onlyOneUnregistration() {
        val repeats = 100
        underTest.registerCallbacks()
        testableLooper.processAllMessages()

        repeat(repeats) { underTest.unregisterCallbacks() }
        val messagesProcessed = testableLooper.processMessagesNonBlocking(repeats)

        verify(displayTracker).removeCallback(any())
        verify(iVrManager).unregisterListener(any())

        assertThat(messagesProcessed).isEqualTo(1)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SHOW_TOAST_WHEN_APP_CONTROL_BRIGHTNESS)
    fun testOnChange_showToastWhenAppOverridesBrightness() {
        val brightnessInfo = BrightnessInfo(
            0.45f, 0.45f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
            1.0f /* highBrightnessTransitionPoint */,
            BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE,
            true /* isBrightnessOverrideByWindow */
        )
        whenever(display.brightnessInfo).thenReturn(brightnessInfo)
        underTest.registerCallbacks()
        testableLooper.processAllMessages()

        underTest.onChanged(true /* tracking */, 100 /* value */, false /* stopTracking */)
        verify(toggleSlider).showToast(any())
    }
}
