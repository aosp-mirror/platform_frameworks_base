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
import com.android.wm.shell.flicker.DesktopModeFlickerScenarios.Companion.SNAP_RESIZE_LEFT_WITH_DRAG
import com.android.wm.shell.scenarios.SnapResizeAppWindowWithDrag
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Snap resize app window by dragging it to the left edge of the screen.
 *
 * Assert that the app window fills the left half the display after being snap resized.
 */
@RunWith(FlickerServiceJUnit4ClassRunner::class)
class SnapResizeAppWindowLeftWithDrag : SnapResizeAppWindowWithDrag(toLeft = true) {
    @ExpectedScenarios(["SNAP_RESIZE_LEFT_WITH_DRAG"])
    @Test
    override fun snapResizeAppWindowWithDrag() = super.snapResizeAppWindowWithDrag()

    companion object {
        @JvmStatic
        @FlickerConfigProvider
        fun flickerConfigProvider(): FlickerConfig =
            FlickerConfig().use(FlickerServiceConfig.DEFAULT).use(SNAP_RESIZE_LEFT_WITH_DRAG)
    }
}