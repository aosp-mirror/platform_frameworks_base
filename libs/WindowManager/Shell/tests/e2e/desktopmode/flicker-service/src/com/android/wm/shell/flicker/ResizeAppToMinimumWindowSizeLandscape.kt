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

package com.android.wm.shell.flicker

import android.tools.Rotation
import android.tools.flicker.FlickerConfig
import android.tools.flicker.annotation.ExpectedScenarios
import android.tools.flicker.annotation.FlickerConfigProvider
import android.tools.flicker.config.FlickerConfig
import android.tools.flicker.config.FlickerServiceConfig
import android.tools.flicker.junit.FlickerServiceJUnit4ClassRunner
import com.android.wm.shell.flicker.DesktopModeFlickerScenarios.Companion.CORNER_RESIZE_TO_MINIMUM_SIZE
import com.android.wm.shell.scenarios.ResizeAppWithCornerResize
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resize app window using corner resize to the smallest possible height and width in
 * landscape mode.
 *
 * Assert that the minimum window size constraint is maintained.
 */
@RunWith(FlickerServiceJUnit4ClassRunner::class)
class ResizeAppToMinimumWindowSizeLandscape : ResizeAppWithCornerResize(
    rotation = Rotation.ROTATION_90,
    horizontalChange = -1500,
    verticalChange = 1500) {
    @ExpectedScenarios(["CORNER_RESIZE_TO_MINIMUM_SIZE"])
    @Test
    override fun resizeAppWithCornerResize() = super.resizeAppWithCornerResize()

    companion object {
        @JvmStatic
        @FlickerConfigProvider
        fun flickerConfigProvider(): FlickerConfig =
            FlickerConfig().use(FlickerServiceConfig.DEFAULT).use(CORNER_RESIZE_TO_MINIMUM_SIZE)
    }
}
