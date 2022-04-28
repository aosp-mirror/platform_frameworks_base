/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test warm launching an app from launcher
 *
 * To run this test: `atest FlickerTests:OpenAppWarmTest`
 *
 * Actions:
 *     Launch [testApp]
 *     Press home
 *     Relaunch an app [testApp] and wait animation to complete (only this action is traced)
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
open class OpenAppWarmTest(testSpec: FlickerTestParameter)
    : OpenAppFromLauncherTransition(testSpec) {
    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                test {
                    testApp.launchViaIntent(wmHelper)
                }
                eachRun {
                    device.pressHome()
                    wmHelper.waitForHomeActivityVisible()
                    this.setRotation(testSpec.startRotation)
                }
            }
            teardown {
                eachRun {
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                testApp.launchViaIntent(wmHelper)
                wmHelper.waitForFullScreenApp(testApp.component)
            }
        }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 206753786)
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() =
            super.appWindowReplacesLauncherAsTopWindow()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appLayerReplacesLauncher() = super.appLayerReplacesLauncher()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarWindowIsVisible() = super.navBarWindowIsVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appLayerBecomesVisible() = super.appLayerBecomesVisible_warmStart()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appWindowBecomesVisible() = super.appWindowBecomesVisible_warmStart()

    @FlakyTest(bugId = 229735718)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

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
