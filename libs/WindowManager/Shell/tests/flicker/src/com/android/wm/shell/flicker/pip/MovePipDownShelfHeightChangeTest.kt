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
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.service.PlatformConsts
import com.android.wm.shell.flicker.Direction
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip movement with Launcher shelf height change (increase).
 *
 * To run this test: `atest WMShellFlickerTests:MovePipUpShelfHeightChangeTest`
 *
 * Actions:
 * ```
 *     Launch [pipApp] in pip mode
 *     Press home
 *     Launch [testApp]
 *     Check if pip window moves down (visually)
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
class MovePipDownShelfHeightChangeTest(flicker: FlickerTest) :
    MovePipShelfHeightTransition(flicker) {
    /** Defines the transition used to run the test */
    override val transition: FlickerBuilder.() -> Unit
        get() = buildTransition {
            teardown {
                tapl.pressHome()
                testApp.exit(wmHelper)
            }
            transitions { testApp.launchViaIntent(wmHelper) }
        }

    /** Checks that the visible region of [pipApp] window always moves down during the animation. */
    @Presubmit @Test fun pipWindowMovesDown() = pipWindowMoves(Direction.DOWN)

    /** Checks that the visible region of [pipApp] layer always moves down during the animation. */
    @Presubmit @Test fun pipLayerMovesDown() = pipLayerMoves(Direction.DOWN)

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
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
