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

package com.android.server.wm.flicker.launch

import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule
import com.android.server.wm.traces.common.service.PlatformConsts
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from launcher
 *
 * To run this test: `atest FlickerTests:OpenAppColdFromIcon`
 *
 * Actions:
 * ```
 *     Make sure no apps are running on the device
 *     Launch an app [testApp] by clicking it's icon on all apps and wait animation to complete
 * ```
 * Notes:
 * ```
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [OpenAppTransition]
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
class OpenAppColdFromIcon(flicker: FlickerTest) : OpenAppFromLauncherTransition(flicker) {
    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                if (flicker.scenario.isTablet) {
                    tapl.setExpectedRotation(flicker.scenario.startRotation.value)
                } else {
                    tapl.setExpectedRotation(PlatformConsts.Rotation.ROTATION_0.value)
                }
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
            }
            transitions {
                tapl
                    .goHome()
                    .switchToAllApps()
                    .getAppIcon(testApp.launcherName)
                    .launch(testApp.`package`)
            }
            teardown { testApp.exit(wmHelper) }
        }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            // TAPL fails on landscape mode b/240916028
            return FlickerTestFactory.nonRotationTests(
                supportedNavigationModes = listOf(PlatformConsts.NavBar.MODE_3BUTTON)
            )
        }
    }
}
