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

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from launcher
 *
 * To run this test: `atest FlickerTests:OpenAppColdFromIcon`
 *
 * Actions:
 *     Make sure no apps are running on the device
 *     Launch an app [testApp] by clicking it's icon on all apps and wait animation to complete
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [OpenAppTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class OpenAppColdFromIcon(testSpec: FlickerTestParameter) :
    OpenAppFromLauncherTransition(testSpec) {
    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                eachRun {
                    tapl.setExpectedRotation(Surface.ROTATION_0)
                    RemoveAllTasksButHomeRule.removeAllTasksButHome()
                    this.setRotation(testSpec.startRotation)
                }
            }
            teardown {
                eachRun {
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                tapl.goHome()
                    .switchToAllApps()
                    .getAppIcon(testApp.launcherName)
                    .launch(testApp.`package`)
            }
        }

    @Postsubmit
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() =
        super.appWindowReplacesLauncherAsTopWindow()

    @Postsubmit
    @Test
    override fun appLayerBecomesVisible() =
        super.appLayerBecomesVisible()

    @Postsubmit
    @Test
    override fun appLayerReplacesLauncher() =
        super.appLayerReplacesLauncher()

    @Postsubmit
    @Test
    override fun appWindowBecomesTopWindow() =
        super.appWindowBecomesTopWindow()

    @Postsubmit
    @Test
    override fun appWindowBecomesVisible() =
        super.appWindowBecomesVisible()

    @Postsubmit
    @Test
    override fun entireScreenCovered() =
        super.entireScreenCovered()

    @Postsubmit
    @Test
    override fun focusChanges() =
        super.focusChanges()

    @Postsubmit
    @Test
    override fun navBarLayerIsVisible() =
        super.navBarLayerIsVisible()

    @Postsubmit
    @Test
    override fun navBarLayerRotatesAndScales() =
        super.navBarLayerRotatesAndScales()

    @Postsubmit
    @Test
    override fun navBarWindowIsVisible() =
        super.navBarWindowIsVisible()

    @Postsubmit
    @Test
    override fun statusBarLayerRotatesScales() =
        super.statusBarLayerRotatesScales()

    @Postsubmit
    @Test
    override fun statusBarLayerIsVisible() =
        super.statusBarLayerIsVisible()

    @Postsubmit
    @Test
    override fun statusBarWindowIsVisible() =
        super.statusBarWindowIsVisible()

    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Postsubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Postsubmit
    @Test
    override fun appWindowIsTopWindowAtEnd() =
        super.appWindowIsTopWindowAtEnd()

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigNonRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests()
        }
    }
}
