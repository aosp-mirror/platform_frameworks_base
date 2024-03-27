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

import androidx.test.filters.SmallTest
import com.android.server.display.brightness.createHysteresisLevels
import kotlin.test.assertEquals
import org.junit.Test

private const val FLOAT_TOLERANCE = 0.001f
@SmallTest
class HysteresisLevelsTest {
    @Test
    fun `test hysteresis levels`() {
        val hysteresisLevels = createHysteresisLevels(
            brighteningThresholdsPercentages = floatArrayOf(50f, 100f),
            darkeningThresholdsPercentages = floatArrayOf(10f, 20f),
            brighteningThresholdLevels = floatArrayOf(0f, 500f),
            darkeningThresholdLevels = floatArrayOf(0f, 500f),
            minDarkeningThreshold = 3f,
            minBrighteningThreshold = 1.5f
        )

        // test low, activate minimum change thresholds.
        assertEquals(1.5f, hysteresisLevels.getBrighteningThreshold(0.0f), FLOAT_TOLERANCE)
        assertEquals(0f, hysteresisLevels.getDarkeningThreshold(0.0f), FLOAT_TOLERANCE)
        assertEquals(1f, hysteresisLevels.getDarkeningThreshold(4.0f), FLOAT_TOLERANCE)

        // test max
        // epsilon is x2 here, since the next floating point value about 20,000 is 0.0019531 greater
        assertEquals(
            20000f, hysteresisLevels.getBrighteningThreshold(10000.0f), FLOAT_TOLERANCE * 2)
        assertEquals(8000f, hysteresisLevels.getDarkeningThreshold(10000.0f), FLOAT_TOLERANCE)

        // test just below threshold
        assertEquals(748.5f, hysteresisLevels.getBrighteningThreshold(499f), FLOAT_TOLERANCE)
        assertEquals(449.1f, hysteresisLevels.getDarkeningThreshold(499f), FLOAT_TOLERANCE)

        // test at (considered above) threshold
        assertEquals(1000f, hysteresisLevels.getBrighteningThreshold(500f), FLOAT_TOLERANCE)
        assertEquals(400f, hysteresisLevels.getDarkeningThreshold(500f), FLOAT_TOLERANCE)
    }
}