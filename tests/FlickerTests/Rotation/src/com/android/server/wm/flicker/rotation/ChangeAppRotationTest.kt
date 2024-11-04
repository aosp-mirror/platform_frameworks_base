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

import android.platform.test.annotations.PlatinumTest
import android.platform.test.annotations.Presubmit
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.traces.component.ComponentNameMatcher
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test opening an app and cycling through app rotations
 *
 * Currently, runs:
 * ```
 *      0 -> 90 degrees
 *      90 -> 0 degrees
 * ```
 *
 * Actions:
 * ```
 *     Launch an app (via intent)
 *     Set initial device orientation
 *     Start tracing
 *     Change device orientation
 *     Stop tracing
 * ```
 *
 * To run this test: `atest FlickerTestsRotation:ChangeAppRotationTest`
 *
 * To run only the presubmit assertions add: `--
 *
 * ```
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Presubmit`
 * ```
 *
 * To run only the postsubmit assertions add: `--
 *
 * ```
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Postsubmit`
 * ```
 *
 * To run only the flaky assertions add: `--
 *
 * ```
 *      --module-arg FlickerTests:include-annotation:androidx.test.filters.FlakyTest`
 * ```
 *
 * Notes:
 * ```
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [RotationTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 * ```
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChangeAppRotationTest(flicker: LegacyFlickerTest) : RotationTransition(flicker) {
    override val testApp = SimpleAppHelper(instrumentation)
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup { testApp.launchViaIntent(wmHelper) }
        }

    /**
     * Windows maybe recreated when rotated. Checks that the focus does not change or if it does,
     * focus returns to [testApp]
     */
    @Presubmit
    @Test
    fun focusChanges() {
        flicker.assertEventLog { this.focusChanges(testApp.packageName) }
    }

    /**
     * Checks that the [ComponentNameMatcher.ROTATION] layer appears during the transition, doesn't
     * flicker, and disappears before the transition is complete
     */
    fun rotationLayerAppearsAndVanishesAssertion() {
        flicker.assertLayers {
            this.isVisible(testApp)
                .then()
                .isVisible(ComponentNameMatcher.ROTATION)
                .then()
                .isVisible(testApp)
                .isInvisible(ComponentNameMatcher.ROTATION)
        }
    }

    /**
     * Checks that the [ComponentNameMatcher.ROTATION] layer appears during the transition, doesn't
     * flicker, and disappears before the transition is complete
     */
    @Presubmit
    @Test
    fun rotationLayerAppearsAndVanishes() {
        rotationLayerAppearsAndVanishesAssertion()
    }

    @Test
    @PlatinumTest(focusArea = "framework")
    override fun cujCompleted() {
        super.cujCompleted()
        focusChanges()
        rotationLayerAppearsAndVanishes()
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.rotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.rotationTests()
    }
}
