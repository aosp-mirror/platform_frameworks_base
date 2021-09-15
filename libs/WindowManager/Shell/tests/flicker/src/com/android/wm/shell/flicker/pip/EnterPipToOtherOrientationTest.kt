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
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.wm.shell.flicker.pip.PipTransition.BroadcastActionTrigger.Companion.ORIENTATION_LANDSCAPE
import com.android.wm.shell.flicker.pip.PipTransition.BroadcastActionTrigger.Companion.ORIENTATION_PORTRAIT
import com.android.wm.shell.flicker.testapp.Components.PipActivity.ACTION_ENTER_PIP
import com.android.wm.shell.flicker.testapp.Components.FixedActivity.EXTRA_FIXED_ORIENTATION
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test entering pip while changing orientation (from app in landscape to pip window in portrait)
 *
 * To run this test: `atest EnterPipToOtherOrientationTest:EnterPipToOtherOrientationTest`
 *
 * Actions:
 *     Launch [testApp] on a fixed portrait orientation
 *     Launch [pipApp] on a fixed landscape orientation
 *     Broadcast action [ACTION_ENTER_PIP] to enter pip mode
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [PipTransition]
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
class EnterPipToOtherOrientationTest(
    testSpec: FlickerTestParameter
) : PipTransition(testSpec) {
    private val testApp = FixedAppHelper(instrumentation)
    private val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)
    private val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)

    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            setupAndTeardown(this, configuration)

            setup {
                eachRun {
                    // Launch a portrait only app on the fullscreen stack
                    testApp.launchViaIntent(wmHelper, stringExtras = mapOf(
                        EXTRA_FIXED_ORIENTATION to ORIENTATION_PORTRAIT.toString()))
                    // Launch the PiP activity fixed as landscape
                    pipApp.launchViaIntent(wmHelper, stringExtras = mapOf(
                        EXTRA_FIXED_ORIENTATION to ORIENTATION_LANDSCAPE.toString()))
                }
            }
            teardown {
                eachRun {
                    pipApp.exit(wmHelper)
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                // Enter PiP, and assert that the PiP is within bounds now that the device is back
                // in portrait
                broadcastActionTrigger.doAction(ACTION_ENTER_PIP)
                wmHelper.waitFor { it.wmState.hasPipWindow() }
                wmHelper.waitForAppTransitionIdle()
                // during rotation the status bar becomes invisible and reappears at the end
                wmHelper.waitForNavBarStatusBarVisible()
            }
        }

    /**
     * Checks that the [FlickerComponentName.NAV_BAR] has the correct position at
     * the start and end of the transition
     */
    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    /**
     * Checks that the [FlickerComponentName.STATUS_BAR] has the correct position at
     * the start and end of the transition
     */
    @Presubmit
    @Test
    override fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

    /**
     * Checks that all parts of the screen are covered at the start and end of the transition
     *
     * TODO b/197726599 Prevents all states from being checked
     */
    @Presubmit
    @Test
    override fun entireScreenCovered() = testSpec.entireScreenCovered(allStates = false)

    /**
     * Checks [pipApp] window remains visible and on top throughout the transition
     */
    @Presubmit
    @Test
    fun pipAppWindowIsAlwaysOnTop() {
        testSpec.assertWm {
            isAppWindowOnTop(pipApp.component)
        }
    }

    /**
     * Checks that [testApp] window is not visible at the start
     */
    @Presubmit
    @Test
    fun testAppWindowInvisibleOnStart() {
        testSpec.assertWmStart {
            isInvisible(testApp.component)
        }
    }

    /**
     * Checks that [testApp] window is visible at the end
     */
    @Presubmit
    @Test
    fun testAppWindowVisibleOnEnd() {
        testSpec.assertWmEnd {
            isVisible(testApp.component)
        }
    }

    /**
     * Checks that [testApp] layer is not visible at the start
     */
    @Presubmit
    @Test
    fun testAppLayerInvisibleOnStart() {
        testSpec.assertLayersStart {
            isInvisible(testApp.component)
        }
    }

    /**
     * Checks that [testApp] layer is visible at the end
     */
    @Presubmit
    @Test
    fun testAppLayerVisibleOnEnd() {
        testSpec.assertLayersEnd {
            isVisible(testApp.component)
        }
    }

    /**
     * Checks that the visible region of [pipApp] covers the full display area at the start of
     * the transition
     */
    @Presubmit
    @Test
    fun pipAppLayerCoversFullScreenOnStart() {
        testSpec.assertLayersStart {
            visibleRegion(pipApp.component).coversExactly(startingBounds)
        }
    }

    /**
     * Checks that the visible region of [testApp] plus the visible region of [pipApp]
     * cover the full display area at the end of the transition
     */
    @Presubmit
    @Test
    fun testAppPlusPipLayerCoversFullScreenOnEnd() {
        testSpec.assertLayersEnd {
            val pipRegion = visibleRegion(pipApp.component).region
            visibleRegion(testApp.component)
                .plus(pipRegion)
                .coversExactly(endingBounds)
        }
    }

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
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                    repetitions = 5)
        }
    }
}
