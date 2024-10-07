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

import android.tools.flicker.FlickerConfig
import android.tools.flicker.annotation.ExpectedScenarios
import android.tools.flicker.annotation.FlickerConfigProvider
import android.tools.flicker.config.FlickerConfig
import android.tools.flicker.config.FlickerServiceConfig
import android.tools.flicker.junit.FlickerServiceJUnit4ClassRunner
import com.android.wm.shell.flicker.DesktopModeFlickerScenarios.Companion.CORNER_RESIZE_TO_MAXIMUM_SIZE
import com.android.wm.shell.scenarios.ResizeAppWithCornerResize
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resize app window using corner resize to the greatest possible height and width in
 * portrait mode.
 *
 * Assert that the maximum window size constraint is maintained.
 */
@RunWith(FlickerServiceJUnit4ClassRunner::class)
class ResizeAppToMaximumWindowSizePortrait : ResizeAppWithCornerResize() {
    @ExpectedScenarios(["CORNER_RESIZE_TO_MAXIMUM_SIZE"])
    @Test
    override fun resizeAppWithCornerResizeToMaximumSize() =
        super.resizeAppWithCornerResizeToMaximumSize()

    companion object {
        @JvmStatic
        @FlickerConfigProvider
        fun flickerConfigProvider(): FlickerConfig =
            FlickerConfig().use(FlickerServiceConfig.DEFAULT).use(CORNER_RESIZE_TO_MAXIMUM_SIZE)
    }
}
