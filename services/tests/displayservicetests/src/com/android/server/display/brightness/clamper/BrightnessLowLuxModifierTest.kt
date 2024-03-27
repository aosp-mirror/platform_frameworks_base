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
package com.android.server.display.brightness.clamper

import android.os.UserHandle
import android.platform.test.annotations.RequiresFlagsEnabled
import android.provider.Settings
import android.testing.TestableContext
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED
import com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED
import com.android.server.display.DisplayDeviceConfig
import com.android.server.display.brightness.BrightnessReason
import com.android.server.display.feature.flags.Flags
import com.android.server.testutils.TestHandler
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

private const val USER_ID = UserHandle.USER_CURRENT

class BrightnessLowLuxModifierTest {

    private var mockClamperChangeListener =
        mock<BrightnessClamperController.ClamperChangeListener>()

    val context = TestableContext(
        InstrumentationRegistry.getInstrumentation().getContext())

    private val testHandler = TestHandler(null)
    private lateinit var modifier: BrightnessLowLuxModifier

    private var mockDisplayDeviceConfig = mock<DisplayDeviceConfig>()

    private val LOW_LUX_BRIGHTNESS = 0.1f
    private val TRANSITION_POINT = 0.25f
    private val NORMAL_RANGE_BRIGHTNESS = 0.3f

    @Before
    fun setUp() {
        modifier =
            BrightnessLowLuxModifier(testHandler,
                mockClamperChangeListener,
                context,
                mockDisplayDeviceConfig)

        // values below transition point (even dimmer range)
        // nits: 0.1 -> backlight 0.02 -> brightness -> 0.1
        whenever(mockDisplayDeviceConfig.getBacklightFromNits(/* nits= */ 1.0f))
                .thenReturn(0.02f)
        whenever(mockDisplayDeviceConfig.getBrightnessFromBacklight(/* backlight = */ 0.02f))
                .thenReturn(LOW_LUX_BRIGHTNESS)

        // values above transition point (noraml range)
        // nits: 10 -> backlight 0.2 -> brightness -> 0.3
        whenever(mockDisplayDeviceConfig.getBacklightFromNits(/* nits= */ 2f))
                .thenReturn(0.15f)
        whenever(mockDisplayDeviceConfig.getBrightnessFromBacklight(/* backlight = */ 0.15f))
                .thenReturn(0.24f)

        // values above transition point (normal range)
        // nits: 10 -> backlight 0.2 -> brightness -> 0.3
        whenever(mockDisplayDeviceConfig.getBacklightFromNits(/* nits= */ 10f))
                .thenReturn(0.2f)
        whenever(mockDisplayDeviceConfig.getBrightnessFromBacklight(/* backlight = */ 0.2f))
                .thenReturn(NORMAL_RANGE_BRIGHTNESS)

        // min nits when lux of 400
        whenever(mockDisplayDeviceConfig.getMinNitsFromLux(/* lux= */ 400f))
                .thenReturn(1.0f)


        whenever(mockDisplayDeviceConfig.evenDimmerTransitionPoint).thenReturn(TRANSITION_POINT)

        testHandler.flush()
    }

    @Test
    fun testSettingOffDisablesModifier() {
        // test transition point ensures brightness doesn't drop when setting is off.
        Settings.Secure.putIntForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_ACTIVATED, 0, USER_ID)
        modifier.setAutoBrightnessState(AUTO_BRIGHTNESS_ENABLED)
        modifier.recalculateLowerBound()
        testHandler.flush()
        assertThat(modifier.brightnessLowerBound).isEqualTo(TRANSITION_POINT)
        assertThat(modifier.brightnessReason).isEqualTo(0) // no reason - ie off
        modifier.onAmbientLuxChange(3000.0f)
        testHandler.flush()
        assertThat(modifier.isActive).isFalse()
        assertThat(modifier.brightnessLowerBound).isEqualTo(TRANSITION_POINT)
        assertThat(modifier.brightnessReason).isEqualTo(0) // no reason - ie off
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun testLuxRestrictsBrightnessRange() {
        // test that high lux prevents low brightness range.
        Settings.Secure.putIntForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_ACTIVATED, 1, USER_ID)
        Settings.Secure.putFloatForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_MIN_NITS, 0.1f, USER_ID)
        modifier.setAutoBrightnessState(AUTO_BRIGHTNESS_ENABLED)
        modifier.onAmbientLuxChange(400.0f)
        testHandler.flush()

        assertThat(modifier.isActive).isTrue()
        // Test restriction from lux setting
        assertThat(modifier.brightnessReason).isEqualTo(BrightnessReason.MODIFIER_MIN_LUX)
        assertThat(modifier.brightnessLowerBound).isEqualTo(LOW_LUX_BRIGHTNESS)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun testUserRestrictsBrightnessRange() {
        // test that user minimum nits setting prevents low brightness range.
        Settings.Secure.putIntForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_ACTIVATED, 1, USER_ID)
        Settings.Secure.putFloatForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_MIN_NITS, 10.0f, USER_ID)
        modifier.setAutoBrightnessState(AUTO_BRIGHTNESS_ENABLED)
        modifier.recalculateLowerBound()
        testHandler.flush()

        // Test restriction from user setting
        assertThat(modifier.isActive).isTrue()
        assertThat(modifier.brightnessReason)
                .isEqualTo(BrightnessReason.MODIFIER_MIN_USER_SET_LOWER_BOUND)
        assertThat(modifier.brightnessLowerBound).isEqualTo(NORMAL_RANGE_BRIGHTNESS)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun testOnToOff() {
        // test that high lux prevents low brightness range.
        Settings.Secure.putIntForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_ACTIVATED, 1, USER_ID) // on
        Settings.Secure.putFloatForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_MIN_NITS, 1.0f, USER_ID)
        modifier.setAutoBrightnessState(AUTO_BRIGHTNESS_ENABLED)
        modifier.onAmbientLuxChange(400.0f)
        testHandler.flush()

        assertThat(modifier.isActive).isTrue()
        // Test restriction from lux setting
        assertThat(modifier.brightnessReason).isEqualTo(BrightnessReason.MODIFIER_MIN_LUX)
        assertThat(modifier.brightnessLowerBound).isEqualTo(LOW_LUX_BRIGHTNESS)

        Settings.Secure.putIntForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_ACTIVATED, 0, USER_ID) // off

        modifier.recalculateLowerBound()
        testHandler.flush()

        assertThat(modifier.isActive).isFalse()
        assertThat(modifier.brightnessLowerBound).isEqualTo(TRANSITION_POINT)
        assertThat(modifier.brightnessReason).isEqualTo(0) // no reason - ie off
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun testOffToOn() {
        // test that high lux prevents low brightness range.
        Settings.Secure.putIntForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_ACTIVATED, 0, USER_ID) // off
        Settings.Secure.putFloatForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_MIN_NITS, 1.0f, USER_ID)
        modifier.setAutoBrightnessState(AUTO_BRIGHTNESS_ENABLED)
        modifier.onAmbientLuxChange(400.0f)
        testHandler.flush()

        assertThat(modifier.isActive).isFalse()
        assertThat(modifier.brightnessLowerBound).isEqualTo(TRANSITION_POINT)
        assertThat(modifier.brightnessReason).isEqualTo(0) // no reason - ie off



        Settings.Secure.putIntForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_ACTIVATED, 1, USER_ID) // on
        modifier.recalculateLowerBound()
        testHandler.flush()

        assertThat(modifier.isActive).isTrue()
        // Test restriction from lux setting
        assertThat(modifier.brightnessReason).isEqualTo(BrightnessReason.MODIFIER_MIN_LUX)
        assertThat(modifier.brightnessLowerBound).isEqualTo(LOW_LUX_BRIGHTNESS)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_EVEN_DIMMER)
    fun testDisabledWhenAutobrightnessIsOff() {
        // test that high lux prevents low brightness range.
        Settings.Secure.putIntForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_ACTIVATED, 1, USER_ID) // on
        Settings.Secure.putFloatForUser(context.contentResolver,
            Settings.Secure.EVEN_DIMMER_MIN_NITS, 1.0f, USER_ID)

        modifier.setAutoBrightnessState(AUTO_BRIGHTNESS_ENABLED)
        modifier.onAmbientLuxChange(400.0f)
        testHandler.flush()

        assertThat(modifier.isActive).isTrue()
        // Test restriction from lux setting
        assertThat(modifier.brightnessReason).isEqualTo(BrightnessReason.MODIFIER_MIN_LUX)
        assertThat(modifier.brightnessLowerBound).isEqualTo(LOW_LUX_BRIGHTNESS)


        modifier.setAutoBrightnessState(AUTO_BRIGHTNESS_DISABLED)
        modifier.onAmbientLuxChange(400.0f)
        testHandler.flush()

        assertThat(modifier.isActive).isFalse()
        // Test restriction from lux setting
        assertThat(modifier.brightnessReason).isEqualTo(0)
        assertThat(modifier.brightnessLowerBound).isEqualTo(TRANSITION_POINT)
    }
}

