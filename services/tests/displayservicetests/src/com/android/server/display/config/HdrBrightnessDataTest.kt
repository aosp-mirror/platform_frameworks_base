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

package com.android.server.display.config

import android.os.PowerManager
import android.util.Spline.createSpline
import androidx.test.filters.SmallTest
import com.android.server.display.DisplayBrightnessState
import com.android.server.display.config.HighBrightnessModeData.HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class HdrBrightnessDataTest {

    @Test
    fun testHdrBrightnessData_defaultConfiguration() {
        val displayConfiguration = createDisplayConfiguration {
            hdrBrightnessConfig(
                brightnessDecreaseDebounceMillis = "3000",
                screenBrightnessRampDecrease = "0.05",
                brightnessIncreaseDebounceMillis = "2000",
                screenBrightnessRampIncrease = "0.03",
                brightnessMap = listOf(Pair("500", "0.6"), Pair("600", "0.7")),
                minimumHdrPercentOfScreenForNbm = null,
                minimumHdrPercentOfScreenForHbm = null,
                allowInLowPowerMode = null,
                sdrHdrRatioMap = null,
            )
        }

        val hdrBrightnessData = HdrBrightnessData.loadConfig(displayConfiguration) { 0.6f }
        assertThat(hdrBrightnessData).isNotNull()

        assertThat(hdrBrightnessData!!.brightnessDecreaseDebounceMillis).isEqualTo(3000)
        assertThat(hdrBrightnessData.screenBrightnessRampDecrease).isEqualTo(0.05f)
        assertThat(hdrBrightnessData.brightnessIncreaseDebounceMillis).isEqualTo(2000)
        assertThat(hdrBrightnessData.screenBrightnessRampIncrease).isEqualTo(0.03f)

        assertThat(hdrBrightnessData.maxBrightnessLimits).hasSize(2)
        assertThat(hdrBrightnessData.maxBrightnessLimits).containsEntry(500f, 0.6f)
        assertThat(hdrBrightnessData.maxBrightnessLimits).containsEntry(600f, 0.7f)

        assertThat(hdrBrightnessData.hbmTransitionPoint).isEqualTo(PowerManager.BRIGHTNESS_MAX)
        assertThat(hdrBrightnessData.minimumHdrPercentOfScreenForNbm).isEqualTo(
            HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT
        )
        assertThat(hdrBrightnessData.minimumHdrPercentOfScreenForHbm).isEqualTo(
            HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT
        )
        assertThat(hdrBrightnessData.allowInLowPowerMode).isFalse()
        assertThat(hdrBrightnessData.sdrToHdrRatioSpline).isNull()
        assertThat(hdrBrightnessData.highestHdrSdrRatio).isEqualTo(1)
    }

    @Test
    fun testHdrBrightnessData_fallbackConfiguration() {
        val displayConfiguration = createDisplayConfiguration {
            hdrBrightnessConfig(
                minimumHdrPercentOfScreenForNbm = null,
                minimumHdrPercentOfScreenForHbm = null,
                allowInLowPowerMode = null,
                sdrHdrRatioMap = null,
            )
            highBrightnessMode(
                minimumHdrPercentOfScreen = "0.2",
                sdrHdrRatioMap = listOf(Pair("2.0", "4.0"), Pair("5.0", "7.0"))
            )
        }

        val transitionPoint = 0.6f
        val hdrBrightnessData =
            HdrBrightnessData.loadConfig(displayConfiguration) { transitionPoint }
        assertThat(hdrBrightnessData).isNotNull()

        assertThat(hdrBrightnessData!!.hbmTransitionPoint).isEqualTo(transitionPoint)
        assertThat(hdrBrightnessData.minimumHdrPercentOfScreenForNbm).isEqualTo(0.2f)
        assertThat(hdrBrightnessData.minimumHdrPercentOfScreenForHbm).isEqualTo(0.2f)
        assertThat(hdrBrightnessData.allowInLowPowerMode).isFalse()

        val expectedSpline = createSpline(floatArrayOf(2.0f, 5.0f), floatArrayOf(4.0f, 7.0f))
        assertThat(hdrBrightnessData.sdrToHdrRatioSpline.toString())
            .isEqualTo(expectedSpline.toString())
        assertThat(hdrBrightnessData.highestHdrSdrRatio).isEqualTo(7)
    }

    @Test
    fun testHdrBrightnessData_fallbackConfiguration_noHdrBrightnessConfig() {
        val displayConfiguration = createDisplayConfiguration {
            highBrightnessMode(
                minimumHdrPercentOfScreen = "0.2",
                sdrHdrRatioMap = listOf(Pair("2.0", "4.0"), Pair("5.0", "7.0"))
            )
        }

        val transitionPoint = 0.6f
        val hdrBrightnessData =
            HdrBrightnessData.loadConfig(displayConfiguration) { transitionPoint }
        assertThat(hdrBrightnessData).isNotNull()

        assertThat(hdrBrightnessData!!.brightnessDecreaseDebounceMillis).isEqualTo(0)
        assertThat(hdrBrightnessData.screenBrightnessRampDecrease)
            .isEqualTo(DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET)
        assertThat(hdrBrightnessData.brightnessIncreaseDebounceMillis).isEqualTo(0)
        assertThat(hdrBrightnessData.screenBrightnessRampIncrease)
            .isEqualTo(DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET)

        assertThat(hdrBrightnessData.maxBrightnessLimits).hasSize(0)

        assertThat(hdrBrightnessData.hbmTransitionPoint).isEqualTo(transitionPoint)
        assertThat(hdrBrightnessData.minimumHdrPercentOfScreenForNbm).isEqualTo(0.2f)
        assertThat(hdrBrightnessData.minimumHdrPercentOfScreenForHbm).isEqualTo(0.2f)
        assertThat(hdrBrightnessData.allowInLowPowerMode).isFalse()

        val expectedSpline = createSpline(floatArrayOf(2.0f, 5.0f), floatArrayOf(4.0f, 7.0f))
        assertThat(hdrBrightnessData.sdrToHdrRatioSpline.toString())
            .isEqualTo(expectedSpline.toString())
        assertThat(hdrBrightnessData.highestHdrSdrRatio).isEqualTo(7)
    }

    @Test
    fun testHdrBrightnessData_emptyConfiguration() {
        val displayConfiguration = createDisplayConfiguration()

        val hdrBrightnessData = HdrBrightnessData.loadConfig(displayConfiguration) { 0.6f }
        assertThat(hdrBrightnessData).isNull()
    }

    @Test
    fun testHdrBrightnessData_realConfiguration() {
        val displayConfiguration = createDisplayConfiguration {
            hdrBrightnessConfig(
                minimumHdrPercentOfScreenForNbm = "0.3",
                minimumHdrPercentOfScreenForHbm = "0.6",
                allowInLowPowerMode = "true",
                sdrHdrRatioMap = listOf(Pair("3.0", "5.0"), Pair("6.0", "7.0"))
            )
            highBrightnessMode(
                minimumHdrPercentOfScreen = "0.2",
                sdrHdrRatioMap = listOf(Pair("2.0", "4.0"), Pair("5.0", "7.5"))
            )
        }

        val transitionPoint = 0.6f
        val hdrBrightnessData =
            HdrBrightnessData.loadConfig(displayConfiguration) { transitionPoint }
        assertThat(hdrBrightnessData).isNotNull()

        assertThat(hdrBrightnessData!!.hbmTransitionPoint).isEqualTo(transitionPoint)
        assertThat(hdrBrightnessData.minimumHdrPercentOfScreenForNbm).isEqualTo(0.3f)
        assertThat(hdrBrightnessData.minimumHdrPercentOfScreenForHbm).isEqualTo(0.6f)
        assertThat(hdrBrightnessData.allowInLowPowerMode).isTrue()

        val expectedSpline = createSpline(floatArrayOf(3.0f, 6.0f), floatArrayOf(5.0f, 7.0f))
        assertThat(hdrBrightnessData.sdrToHdrRatioSpline.toString())
            .isEqualTo(expectedSpline.toString())
        assertThat(hdrBrightnessData.highestHdrSdrRatio).isEqualTo(7)
    }
}