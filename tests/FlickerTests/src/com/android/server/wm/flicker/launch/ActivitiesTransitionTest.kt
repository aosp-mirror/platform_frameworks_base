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

package com.android.server.wm.flicker.launch

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.TwoActivitiesAppHelper
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.parser.toFlickerComponent
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test the back and forward transition between 2 activities.
 *
 * To run this test: `atest FlickerTests:ActivitiesTransitionTest`
 *
 * Actions:
 * ```
 *     Launch an app
 *     Launch a secondary activity within the app
 *     Close the secondary activity back to the initial one
 * ```
 * Notes:
 * ```
 *     1. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class ActivitiesTransitionTest(flicker: FlickerTest) : BaseTest(flicker) {
    private val testApp: TwoActivitiesAppHelper = TwoActivitiesAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotation(flicker.scenario.startRotation.value)
            testApp.launchViaIntent(wmHelper)
        }
        teardown { testApp.exit(wmHelper) }
        transitions {
            testApp.openSecondActivity(device, wmHelper)
            tapl.pressBack()
            wmHelper.StateSyncBuilder().withFullScreenApp(testApp).waitForAndVerify()
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 206753786)
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /**
     * Checks that the [ActivityOptions.LaunchNewActivity] activity is visible at the start of the
     * transition, that [ActivityOptions.SimpleActivity] becomes visible during the transition, and
     * that [ActivityOptions.LaunchNewActivity] is again visible at the end
     */
    @Presubmit
    @Test
    fun finishSubActivity() {
        val buttonActivityComponent =
            ActivityOptions.LaunchNewActivity.COMPONENT.toFlickerComponent()
        val imeAutoFocusActivityComponent =
            ActivityOptions.SimpleActivity.COMPONENT.toFlickerComponent()
        flicker.assertWm {
            this.isAppWindowOnTop(buttonActivityComponent)
                .then()
                .isAppWindowOnTop(imeAutoFocusActivityComponent)
                .then()
                .isAppWindowOnTop(buttonActivityComponent)
        }
    }

    /**
     * Checks that the [ComponentNameMatcher.LAUNCHER] window is not on top. The launcher cannot be
     * asserted with `isAppWindowVisible` because it contains 2 windows with the exact same name,
     * and both are never simultaneously visible
     */
    @Presubmit
    @Test
    fun launcherWindowNotOnTop() {
        flicker.assertWm { this.isAppWindowNotOnTop(ComponentNameMatcher.LAUNCHER) }
    }

    /**
     * Checks that the [ComponentNameMatcher.LAUNCHER] layer is never visible during the transition
     */
    @Presubmit
    @Test
    fun launcherLayerNotVisible() {
        flicker.assertLayers { this.isInvisible(ComponentNameMatcher.LAUNCHER) }
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
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
