/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm.flicker.rotation

import android.platform.test.annotations.Presubmit
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test opening an app and cycling through app rotations
 *
 * Currently runs:
 *      0 -> 90 degrees
 *      90 -> 0 degrees
 *
 * Actions:
 *     Launch an app (via intent)
 *     Set initial device orientation
 *     Start tracing
 *     Change device orientation
 *     Stop tracing
 *
 * To run this test: `atest FlickerTests:ChangeAppRotationTest`
 *
 * To run only the presubmit assertions add: `--
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Presubmit`
 *
 * To run only the postsubmit assertions add: `--
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Postsubmit`
 *
 * To run only the flaky assertions add: `--
 *      --module-arg FlickerTests:include-annotation:androidx.test.filters.FlakyTest`
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [RotationTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group3
class ChangeAppRotationTest(
    testSpec: FlickerTestParameter
) : RotationTransition(testSpec) {
    override val testApp = SimpleAppHelper(instrumentation)
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = {
            super.transition(this, it)
            setup {
                test {
                    testApp.launchViaIntent(wmHelper)
                }
            }
        }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 190185577)
    @Test
    override fun focusDoesNotChange() {
        super.focusDoesNotChange()
    }

    /**
     * Checks that the [FlickerComponentName.ROTATION] layer appears during the transition,
     * doesn't flicker, and disappears before the transition is complete
     */
    @Presubmit
    @Test
    fun rotationLayerAppearsAndVanishes() {
        testSpec.assertLayers {
            this.isVisible(testApp.component)
                .then()
                .isVisible(FlickerComponentName.ROTATION)
                .then()
                .isVisible(testApp.component)
        }
    }

    /**
     * Checks that the status bar window is visible and above the app windows in all WM
     * trace entries
     */
    @Presubmit
    @Test
    fun statusBarWindowIsVisible() {
        testSpec.statusBarWindowIsVisible()
    }

    /**
     * Checks that the status bar layer is visible at the start and end of the transition
     */
    @Presubmit
    @Test
    fun statusBarLayerIsVisible() {
        testSpec.statusBarLayerIsVisible()
    }

    /**
     * Checks the position of the status bar at the start and end of the transition
     */
    @Presubmit
    @Test
    fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() {
        super.navBarLayerRotatesAndScales()
    }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigRotationTests(repetitions = 5)
        }
    }
}