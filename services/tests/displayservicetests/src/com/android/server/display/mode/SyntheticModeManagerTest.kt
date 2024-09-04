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

package com.android.server.display.mode

import android.view.Display.Mode
import com.android.server.display.DisplayDeviceConfig
import com.android.server.display.feature.DisplayManagerFlags
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private val DISPLAY_MODES = arrayOf(
    Mode(1, 100, 100, 60f),
    Mode(2, 100, 100, 120f)
)

@SmallTest
@RunWith(TestParameterInjector::class)
class SyntheticModeManagerTest {

    private val mockFlags = mock<DisplayManagerFlags>()
    private val mockConfig = mock<DisplayDeviceConfig>()

    @Test
    fun testAppSupportedModes(@TestParameter testCase: AppSupportedModesTestCase) {
        whenever(mockFlags.isSynthetic60HzModesEnabled).thenReturn(testCase.syntheticModesEnabled)
        whenever(mockConfig.isVrrSupportEnabled).thenReturn(testCase.vrrSupported)
        val syntheticModeManager = SyntheticModeManager(mockFlags)

        val result = syntheticModeManager.createAppSupportedModes(
            mockConfig, testCase.supportedModes)

        assertThat(result).isEqualTo(testCase.expectedAppModes)
    }

    enum class AppSupportedModesTestCase(
        val syntheticModesEnabled: Boolean,
        val vrrSupported: Boolean,
        val supportedModes: Array<Mode>,
        val expectedAppModes: Array<Mode>
    ) {
        SYNTHETIC_MODES_NOT_SUPPORTED(false, true, DISPLAY_MODES, DISPLAY_MODES),
        VRR_NOT_SUPPORTED(true, false, DISPLAY_MODES, DISPLAY_MODES),
        VRR_SYNTHETIC_NOT_SUPPORTED(false, false, DISPLAY_MODES, DISPLAY_MODES),
        SINGLE_RESOLUTION_MODES(true, true, DISPLAY_MODES, arrayOf(
            Mode(2, 100, 100, 120f),
            Mode(3, 100, 100, 60f, 60f, true, floatArrayOf(), intArrayOf())
        )),
        NO_60HZ_MODES(true, true, arrayOf(Mode(2, 100, 100, 120f)),
            arrayOf(
                Mode(2, 100, 100, 120f),
                Mode(3, 100, 100, 60f, 60f, true, floatArrayOf(), intArrayOf())
            )
        ),
        MULTI_RESOLUTION_MODES(true, true,
            arrayOf(
                Mode(1, 100, 100, 120f),
                Mode(2, 200, 200, 60f),
                Mode(3, 300, 300, 60f),
                Mode(4, 300, 300, 90f),
                ),
            arrayOf(
                Mode(1, 100, 100, 120f),
                Mode(4, 300, 300, 90f),
                Mode(5, 100, 100, 60f, 60f, true, floatArrayOf(), intArrayOf()),
                Mode(6, 200, 200, 60f, 60f, true, floatArrayOf(), intArrayOf()),
                Mode(7, 300, 300, 60f, 60f, true, floatArrayOf(), intArrayOf())
            )
        ),
        WITH_HDR_TYPES(true, true,
            arrayOf(
                Mode(1, 100, 100, 120f, 120f, false, floatArrayOf(), intArrayOf(1, 2)),
                Mode(2, 200, 200, 60f, 120f, false, floatArrayOf(), intArrayOf(3, 4)),
                Mode(3, 200, 200, 120f, 120f, false, floatArrayOf(), intArrayOf(5, 6)),
            ),
            arrayOf(
                Mode(1, 100, 100, 120f, 120f, false, floatArrayOf(), intArrayOf(1, 2)),
                Mode(3, 200, 200, 120f, 120f, false, floatArrayOf(), intArrayOf(5, 6)),
                Mode(4, 100, 100, 60f, 60f, true, floatArrayOf(), intArrayOf(1, 2)),
                Mode(5, 200, 200, 60f, 60f, true, floatArrayOf(), intArrayOf(5, 6)),
            )
        ),
        UNACHIEVABLE_60HZ(true, true,
            arrayOf(
                Mode(1, 100, 100, 90f),
            ),
            arrayOf(
                Mode(1, 100, 100, 90f),
            )
        ),
        MULTI_RESOLUTION_MODES_WITH_UNACHIEVABLE_60HZ(true, true,
            arrayOf(
                Mode(1, 100, 100, 120f),
                Mode(2, 200, 200, 90f),
            ),
            arrayOf(
                Mode(1, 100, 100, 120f),
                Mode(2, 200, 200, 90f),
                Mode(3, 100, 100, 60f, 60f, true, floatArrayOf(), intArrayOf()),
            )
        ),
        LOWER_THAN_60HZ_MODES(true, true,
            arrayOf(
                Mode(1, 100, 100, 30f),
                Mode(2, 100, 100, 45f),
                Mode(3, 100, 100, 90f),
            ),
            arrayOf(
                Mode(3, 100, 100, 90f),
            )
        ),
    }
}