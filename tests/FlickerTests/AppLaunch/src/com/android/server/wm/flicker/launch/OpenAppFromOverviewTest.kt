/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.tools.Rotation
import android.tools.flicker.annotation.FlickerServiceCompatible
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.launch.common.OpenAppFromLauncherTransition
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an app from the recents app view (the overview)
 *
 * To run this test: `atest FlickerTestsAppLaunch:OpenAppFromOverviewTest`
 *
 * Actions:
 * ```
 *     Launch [testApp]
 *     Press recents
 *     Relaunch an app [testApp] by selecting it in the overview screen, and wait animation to
 *     complete (only this action is traced)
 * ```
 *
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
@FlickerServiceCompatible
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppFromOverviewTest(flicker: LegacyFlickerTest) : OpenAppFromLauncherTransition(flicker) {

    /** Defines the transition used to run the test */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                tapl.setExpectedRotationCheckEnabled(false)
                testApp.launchViaIntent(wmHelper)
                tapl.goHome()
                wmHelper.StateSyncBuilder().withHomeActivityVisible().waitForAndVerify()
                // By default, launcher doesn't rotate on phones, but rotates on tablets
                if (flicker.scenario.isTablet) {
                    tapl.setExpectedRotation(flicker.scenario.startRotation.value)
                } else {
                    tapl.setExpectedRotation(Rotation.ROTATION_0.value)
                }
                tapl.workspace.switchToOverview()
                wmHelper.StateSyncBuilder().withRecentsActivityVisible().waitForAndVerify()
                this.setRotation(flicker.scenario.startRotation)
            }
            transitions {
                tapl.overview.currentTask.open()
                wmHelper.StateSyncBuilder().withFullScreenApp(testApp).waitForAndVerify()
            }
        }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appLayerBecomesVisible() = super.appLayerBecomesVisible_warmStart()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appWindowBecomesVisible() = super.appWindowBecomesVisible_warmStart()

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
