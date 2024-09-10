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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatterySpecs.BATTERY_HEIGHT
import com.android.systemui.battery.BatterySpecs.BATTERY_HEIGHT_WITH_SHIELD
import com.android.systemui.battery.BatterySpecs.BATTERY_WIDTH
import com.android.systemui.battery.BatterySpecs.BATTERY_WIDTH_WITH_SHIELD
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AccessorizedBatteryDrawableTest : SysuiTestCase() {
    @Test
    fun intrinsicSize_shieldFalse_isBatterySize() {
        val drawable = AccessorizedBatteryDrawable(context, frameColor = 0)
        drawable.displayShield = false

        val density = context.resources.displayMetrics.density
        assertThat(drawable.intrinsicHeight).isEqualTo((BATTERY_HEIGHT * density).toInt())
        assertThat(drawable.intrinsicWidth).isEqualTo((BATTERY_WIDTH * density).toInt())
    }

    @Test
    fun intrinsicSize_shieldTrue_isBatteryPlusShieldSize() {
        val drawable = AccessorizedBatteryDrawable(context, frameColor = 0)
        drawable.displayShield = true

        val density = context.resources.displayMetrics.density
        assertThat(drawable.intrinsicHeight)
            .isEqualTo((BATTERY_HEIGHT_WITH_SHIELD * density).toInt())
        assertThat(drawable.intrinsicWidth).isEqualTo((BATTERY_WIDTH_WITH_SHIELD * density).toInt())
    }

    // TODO(b/255625888): Screenshot tests for this drawable would be amazing!
}
