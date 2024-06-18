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

import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.testing.TestableContext
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.display.brightness.BrightnessReason
import com.android.server.testutils.TestHandler
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

private const val userId = UserHandle.USER_CURRENT

class BrightnessLowLuxModifierTest {

    private var mockClamperChangeListener =
            mock<BrightnessClamperController.ClamperChangeListener>()

    val context = TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext())

    private val testHandler = TestHandler(null)
    private lateinit var modifier: BrightnessLowLuxModifier

    @Before
    fun setUp() {
        modifier = BrightnessLowLuxModifier(testHandler, mockClamperChangeListener, context)
        testHandler.flush()
    }

    @Test
    fun testThrottlingBounds() {
        Settings.Secure.putIntForUser(context.contentResolver,
                Settings.Secure.EVEN_DIMMER_ACTIVATED, 1, userId) // true
        Settings.Secure.putFloatForUser(context.contentResolver,
                Settings.Secure.EVEN_DIMMER_MIN_NITS, 0.7f, userId)
        modifier.recalculateLowerBound()
        testHandler.flush()
        assertThat(modifier.isActive).isTrue()

        // TODO: code currently returns MIN/MAX; update with lux values
        assertThat(modifier.brightnessLowerBound).isEqualTo(PowerManager.BRIGHTNESS_MIN)
    }

    @Test
    fun testGetReason_UserSet() {
        Settings.Secure.putIntForUser(context.contentResolver,
                Settings.Secure.EVEN_DIMMER_ACTIVATED, 1, userId)
        Settings.Secure.putFloatForUser(context.contentResolver,
                Settings.Secure.EVEN_DIMMER_MIN_NITS, 0.7f, userId)
        modifier.recalculateLowerBound()
        testHandler.flush()
        assertThat(modifier.isActive).isTrue()

        // Test restriction from user setting
        assertThat(modifier.brightnessReason)
                .isEqualTo(BrightnessReason.MODIFIER_MIN_USER_SET_LOWER_BOUND)
    }

    @Test
    fun testGetReason_Lux() {
        Settings.Secure.putIntForUser(context.contentResolver,
                Settings.Secure.EVEN_DIMMER_ACTIVATED, 1, userId)
        Settings.Secure.putFloatForUser(context.contentResolver,
                Settings.Secure.EVEN_DIMMER_MIN_NITS, 0.0f, userId)
        modifier.recalculateLowerBound()
        testHandler.flush()
        assertThat(modifier.isActive).isTrue()

        // Test restriction from lux setting
        assertThat(modifier.brightnessReason).isEqualTo(BrightnessReason.MODIFIER_MIN_LUX)
    }
}
