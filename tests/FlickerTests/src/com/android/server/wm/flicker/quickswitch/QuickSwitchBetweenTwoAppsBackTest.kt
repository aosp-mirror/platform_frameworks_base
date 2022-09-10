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

package com.android.server.wm.flicker.quickswitch

import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.Rect
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switching back to previous app from last opened app
 *
 * To run this test: `atest FlickerTests:QuickSwitchBetweenTwoAppsBackTest`
 *
 * Actions:
 *     Launch an app [testApp1]
 *     Launch another app [testApp2]
 *     Swipe right from the bottom of the screen to quick switch back to the first app [testApp1]
 *
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
open class QuickSwitchBetweenTwoAppsBackTest(
    testSpec: FlickerTestParameter
) : BaseTest(testSpec) {
    private val testApp1 = SimpleAppHelper(instrumentation)
    private val testApp2 = NonResizeableAppHelper(instrumentation)

    @Before
    open fun before() {
        Assume.assumeFalse(isShellTransitionsEnabled)
    }

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            test {
                tapl.setExpectedRotation(testSpec.startRotation)
            }
            eachRun {
                testApp1.launchViaIntent(wmHelper)
                testApp2.launchViaIntent(wmHelper)
                startDisplayBounds = wmHelper.currentState.layerState
                    .physicalDisplayBounds ?: error("Display not found")
            }
        }
        transitions {
            tapl.launchedAppState.quickSwitchToPreviousApp()
            wmHelper.StateSyncBuilder()
                .withFullScreenApp(testApp1)
                .withNavOrTaskBarVisible()
                .withStatusBarVisible()
                .waitForAndVerify()
        }

        teardown {
            test {
                testApp1.exit(wmHelper)
                testApp2.exit(wmHelper)
            }
        }
    }

    /**
     * Checks that the transition starts with [testApp2]'s windows filling/covering exactly the
     * entirety of the display.
     */
    @Presubmit
    @Test
    open fun startsWithApp2WindowsCoverFullScreen() {
        testSpec.assertWmStart {
            this.visibleRegion(testApp2).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with [testApp2]'s layers filling/covering exactly the
     * entirety of the display.
     */
    @Presubmit
    @Test
    open fun startsWithApp2LayersCoverFullScreen() {
        testSpec.assertLayersStart {
            this.visibleRegion(testApp2).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with [testApp2] being the top window.
     */
    @Presubmit
    @Test
    open fun startsWithApp2WindowBeingOnTop() {
        testSpec.assertWmStart {
            this.isAppWindowOnTop(testApp2)
        }
    }

    /**
     * Checks that [testApp1] windows fill the entire screen (i.e. is "fullscreen") at the end of
     * the transition once we have fully quick switched from [testApp2] back to the [testApp1].
     */
    @Presubmit
    @Test
    open fun endsWithApp1WindowsCoveringFullScreen() {
        testSpec.assertWmEnd {
            this.visibleRegion(testApp1).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp1] layers fill the entire screen (i.e. is "fullscreen") at the end of
     * the transition once we have fully quick switched from [testApp2] back to the [testApp1].
     */
    @Presubmit
    @Test
    fun endsWithApp1LayersCoveringFullScreen() {
        testSpec.assertLayersEnd {
            this.visibleRegion(testApp1).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp1] is the top window at the end of the transition once we have fully
     * quick switched from [testApp2] back to the [testApp1].
     */
    @Presubmit
    @Test
    open fun endsWithApp1BeingOnTop() {
        testSpec.assertWmEnd {
            this.isAppWindowOnTop(testApp1)
        }
    }

    /**
     * Checks that [testApp1]'s window starts off invisible and becomes visible at some point
     * before the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    open fun app1WindowBecomesAndStaysVisible() {
        testSpec.assertWm {
            this.isAppWindowInvisible(testApp1)
                .then()
                .isAppWindowVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isAppWindowVisible(testApp1)
        }
    }

    /**
     * Checks that [testApp1]'s layer starts off invisible and becomes visible at some point
     * before the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    open fun app1LayerBecomesAndStaysVisible() {
        testSpec.assertLayers {
            this.isInvisible(testApp1)
                .then()
                .isVisible(testApp1)
        }
    }

    /**
     * Checks that [testApp2]'s window starts off visible and becomes invisible at some point
     * before the end of the transition and then stays invisible until the end of the transition.
     */
    @Presubmit
    @Test
    open fun app2WindowBecomesAndStaysInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(testApp2)
                .then()
                .isAppWindowInvisible(testApp2)
        }
    }

    /**
     * Checks that [testApp2]'s layer starts off visible and becomes invisible at some point
     * before the end of the transition and then stays invisible until the end of the transition.
     */
    @Presubmit
    @Test
    open fun app2LayerBecomesAndStaysInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp2)
                .then()
                .isInvisible(testApp2)
        }
    }

    /**
     * Checks that [testApp2]'s window is visible at least until [testApp1]'s window is visible.
     * Ensures that at any point, either [testApp1] or [testApp2]'s windows are at least partially
     * visible.
     */
    @Presubmit
    @Test
    open fun app1WindowIsVisibleOnceApp2WindowIsInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(testApp2)
                .then()
                // TODO: Do we actually want to test this? Seems too implementation specific...
                .isAppWindowVisible(ComponentNameMatcher.LAUNCHER, isOptional = true)
                .then()
                .isAppWindowVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isAppWindowVisible(testApp1)
        }
    }

    /**
     * Checks that [testApp2]'s layer is visible at least until [testApp1]'s window is visible.
     * Ensures that at any point, either [testApp1] or [testApp2]'s windows are at least partially
     * visible.
     */
    @Presubmit
    @Test
    open fun app1LayerIsVisibleOnceApp2LayerIsInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp2)
                .then()
                .isVisible(ComponentNameMatcher.LAUNCHER, isOptional = true)
                .then()
                .isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(testApp1)
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    companion object {
        private var startDisplayBounds = Rect.EMPTY

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(
                                        supportedNavigationModes = listOf(
                        WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                    ),
                    supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90)
                )
        }
    }
}
