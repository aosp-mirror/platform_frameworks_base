/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.battery

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatterySpecs.BATTERY_HEIGHT
import com.android.systemui.battery.BatterySpecs.BATTERY_HEIGHT_WITH_SHIELD
import com.android.systemui.battery.BatterySpecs.BATTERY_WIDTH
import com.android.systemui.battery.BatterySpecs.BATTERY_WIDTH_WITH_SHIELD
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class BatterySpecsTest : SysuiTestCase() {
    @Test
    fun getFullBatteryHeight_shieldFalse_returnsMainHeight() {
        val fullHeight = BatterySpecs.getFullBatteryHeight(56f, displayShield = false)

        assertThat(fullHeight).isEqualTo(56f)
    }

    @Test
    fun getFullBatteryHeight_shieldTrue_returnsMainHeightPlusShield() {
        val mainHeight = BATTERY_HEIGHT * 5
        val fullHeight = BatterySpecs.getFullBatteryHeight(mainHeight, displayShield = true)

        // Since the main battery was scaled 5x, the output height should also be scaled 5x
        val expectedFullHeight = BATTERY_HEIGHT_WITH_SHIELD * 5

        assertThat(fullHeight).isWithin(.0001f).of(expectedFullHeight)
    }

    @Test
    fun getFullBatteryWidth_shieldFalse_returnsMainWidth() {
        val fullWidth = BatterySpecs.getFullBatteryWidth(33f, displayShield = false)

        assertThat(fullWidth).isEqualTo(33f)
    }

    @Test
    fun getFullBatteryWidth_shieldTrue_returnsMainWidthPlusShield() {
        val mainWidth = BATTERY_WIDTH * 3.3f

        val fullWidth = BatterySpecs.getFullBatteryWidth(mainWidth, displayShield = true)

        // Since the main battery was scaled 3.3x, the output width should also be scaled 5x
        val expectedFullWidth = BATTERY_WIDTH_WITH_SHIELD * 3.3f
        assertThat(fullWidth).isWithin(.0001f).of(expectedFullWidth)
    }

    @Test
    fun getMainBatteryHeight_shieldFalse_returnsFullHeight() {
        val mainHeight = BatterySpecs.getMainBatteryHeight(89f, displayShield = false)

        assertThat(mainHeight).isEqualTo(89f)
    }

    @Test
    fun getMainBatteryHeight_shieldTrue_returnsNotFullHeight() {
        val fullHeight = BATTERY_HEIGHT_WITH_SHIELD * 7.7f

        val mainHeight = BatterySpecs.getMainBatteryHeight(fullHeight, displayShield = true)

        // Since the full height was scaled 7.7x, the main height should also be scaled 7.7x.
        val expectedHeight = BATTERY_HEIGHT * 7.7f
        assertThat(mainHeight).isWithin(.0001f).of(expectedHeight)
    }

    @Test
    fun getMainBatteryWidth_shieldFalse_returnsFullWidth() {
        val mainWidth = BatterySpecs.getMainBatteryWidth(2345f, displayShield = false)

        assertThat(mainWidth).isEqualTo(2345f)
    }

    @Test
    fun getMainBatteryWidth_shieldTrue_returnsNotFullWidth() {
        val fullWidth = BATTERY_WIDTH_WITH_SHIELD * 0.6f

        val mainWidth = BatterySpecs.getMainBatteryWidth(fullWidth, displayShield = true)

        // Since the full width was scaled 0.6x, the main height should also be scaled 0.6x.
        val expectedWidth = BATTERY_WIDTH * 0.6f
        assertThat(mainWidth).isWithin(.0001f).of(expectedWidth)
    }
}
