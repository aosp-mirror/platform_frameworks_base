/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.service.PlatformConsts
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test closing a pip window by swiping it to the bottom-center of the screen
 *
 * To run this test: `atest WMShellFlickerTests:ExitPipWithSwipeDownTest`
 *
 * Actions:
 * ```
 *     Launch an app in pip mode [pipApp],
 *     Swipe the pip window to the bottom-center of the screen and wait it disappear
 * ```
 * Notes:
 * ```
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [PipTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class ExitPipWithSwipeDownTest(flicker: FlickerTest) : ExitPipTransition(flicker) {
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            transitions {
                val pipRegion = wmHelper.getWindowRegion(pipApp).bounds
                val pipCenterX = pipRegion.centerX()
                val pipCenterY = pipRegion.centerY()
                val displayCenterX = device.displayWidth / 2
                val barComponent =
                    if (flicker.scenario.isTablet) {
                        ComponentNameMatcher.TASK_BAR
                    } else {
                        ComponentNameMatcher.NAV_BAR
                    }
                val barLayerHeight =
                    wmHelper.currentState.layerState
                        .getLayerWithBuffer(barComponent)
                        ?.visibleRegion
                        ?.height
                        ?: error("Couldn't find Nav or Task bar layer")
                // The dismiss button doesn't appear at the complete bottom of the screen,
                val displayY = device.displayHeight - barLayerHeight
                device.swipe(pipCenterX, pipCenterY, displayCenterX, displayY, 50)
                // Wait until the other app is no longer visible
                wmHelper
                    .StateSyncBuilder()
                    .withPipGone()
                    .withWindowSurfaceDisappeared(pipApp)
                    .withAppTransitionIdle()
                    .waitForAndVerify()
            }
        }

    /** Checks that the focus doesn't change between windows during the transition */
    @Presubmit
    @Test
    fun focusDoesNotChange() {
        flicker.assertEventLog { this.focusDoesNotChange() }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring repetitions, screen orientation
         * and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(PlatformConsts.Rotation.ROTATION_0)
            )
        }
    }
}
