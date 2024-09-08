/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display

import android.os.IBinder
import androidx.test.filters.SmallTest
import com.android.server.display.brightness.clamper.HdrClamper
import com.android.server.display.feature.DisplayManagerFlags
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val MAX_BRIGHTNESS = 1.0f
private const val TRANSITION_POINT = 0.7f
private const val NORMAL_BRIGHTNESS_HIGH = 0.8f
private const val NORMAL_BRIGHTNESS_LOW = 0.6f

@SmallTest
class BrightnessRangeControllerTest {

    private val mockHbmController = mock<HighBrightnessModeController>()
    private val mockCallback = mock<Runnable>()
    private val mockConfig = mock<DisplayDeviceConfig>()
    private val mockNormalBrightnessController = mock<NormalBrightnessModeController>()
    private val mockHdrClamper = mock<HdrClamper>()
    private val mockFlags = mock<DisplayManagerFlags>()
    private val mockToken = mock<IBinder>()

    @Test
    fun testMaxBrightness_HbmSupportedAndOn() {
        val controller = createController()
        assertThat(controller.currentBrightnessMax).isEqualTo(MAX_BRIGHTNESS)
    }

    @Test
    fun testMaxBrightness_HbmNotSupported() {
        val controller = createController(hbmSupported = false)
        assertThat(controller.currentBrightnessMax).isEqualTo(NORMAL_BRIGHTNESS_LOW)
    }

    @Test
    fun testMaxBrightness_HbmNotAllowed() {
        val controller = createController(hbmAllowed = false)
        assertThat(controller.currentBrightnessMax).isEqualTo(NORMAL_BRIGHTNESS_LOW)
    }

    @Test
    fun testMaxBrightness_HbmDisabledAndNotAllowed() {
        val controller = createController(nbmEnabled = false, hbmAllowed = false)
        assertThat(controller.currentBrightnessMax).isEqualTo(MAX_BRIGHTNESS)
    }

    @Test
    fun testMaxBrightness_transitionPointLessThanCurrentNbmLimit() {
        val controller = createController(
            hbmAllowed = false,
            hbmMaxBrightness = TRANSITION_POINT,
            nbmMaxBrightness = NORMAL_BRIGHTNESS_HIGH
        )
        assertThat(controller.currentBrightnessMax).isEqualTo(TRANSITION_POINT)
    }

    private fun createController(
        nbmEnabled: Boolean = true,
        hbmSupported: Boolean = true,
        hbmAllowed: Boolean = true,
        hbmMaxBrightness: Float = MAX_BRIGHTNESS,
        nbmMaxBrightness: Float = NORMAL_BRIGHTNESS_LOW
    ): BrightnessRangeController {
        whenever(mockFlags.isNbmControllerEnabled).thenReturn(nbmEnabled)
        whenever(mockHbmController.deviceSupportsHbm()).thenReturn(hbmSupported)
        whenever(mockHbmController.isHbmCurrentlyAllowed).thenReturn(hbmAllowed)
        whenever(mockHbmController.currentBrightnessMax).thenReturn(hbmMaxBrightness)
        whenever(mockNormalBrightnessController.currentBrightnessMax).thenReturn(nbmMaxBrightness)

        return BrightnessRangeController(mockHbmController, mockCallback, mockConfig,
            mockNormalBrightnessController, mockHdrClamper, mockFlags, mockToken,
            DisplayDeviceInfo())
    }
}