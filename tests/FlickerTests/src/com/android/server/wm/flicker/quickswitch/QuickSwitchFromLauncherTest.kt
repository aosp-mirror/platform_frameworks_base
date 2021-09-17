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

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switching to last opened app from launcher
 *
 * To run this test: `atest FlickerTests:QuickSwitchFromLauncherTest`
 *
 * Actions:
 *     Launch an app
 *     Navigate home to show launcher
 *     Swipe right from the bottom of the screen to quick switch back to the app
 *
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
class QuickSwitchFromLauncherTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = SimpleAppHelper(instrumentation)
    private val startDisplayBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    testApp.launchViaIntent(wmHelper)
                    device.pressHome()
                    wmHelper.waitForHomeActivityVisible()
                    wmHelper.waitForWindowSurfaceDisappeared(testApp.component)
                }
            }
            transitions {
                // Swipe right from bottom to quick switch back
                // NOTE: We don't perform an edge-to-edge swipe but instead only swipe in the middle
                // as to not accidentally trigger a swipe back or forward action which would result
                // in the same behavior but not testing quick swap.
                device.swipe(
                        startDisplayBounds.bounds.right / 3,
                        startDisplayBounds.bounds.bottom,
                        2 * startDisplayBounds.bounds.right / 3,
                        startDisplayBounds.bounds.bottom,
                        50
                )

                wmHelper.waitForFullScreenApp(testApp.component)
                wmHelper.waitForAppTransitionIdle()
            }

            teardown {
                eachRun {
                    testApp.exit()
                }
            }
        }
    }

    /**
     * Checks that [testApp] windows fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppWindowsCoveringFullScreen() {
        testSpec.assertWmEnd {
            this.frameRegion(testApp.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp] layers fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppLayersCoveringFullScreen() {
        testSpec.assertLayersEnd {
            this.visibleRegion(testApp.component).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp] is the top window at the end of the transition once we have fully quick
     * switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppBeingOnTop() {
        testSpec.assertWmEnd {
            this.isAppWindowOnTop(testApp.component)
        }
    }

    /**
     * Checks that the transition starts with the home activity being tagged as visible.
     */
    @Presubmit
    @Test
    fun startsWithHomeActivityFlaggedVisible() {
        testSpec.assertWmStart {
            this.isHomeActivityVisible(true)
        }
    }

    /**
     * Checks that the transition starts with the launcher windows filling/covering exactly the
     * entirety of the display.
     */
    @Presubmit
    @Test
    fun startsWithLauncherWindowsCoverFullScreen() {
        testSpec.assertWmStart {
            this.frameRegion(LAUNCHER_COMPONENT).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with the launcher layers filling/covering exactly the
     * entirety of the display.
     */
    @Presubmit
    @Test
    fun startsWithLauncherLayersCoverFullScreen() {
        testSpec.assertLayersStart {
            this.visibleRegion(LAUNCHER_COMPONENT).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with the launcher being the top window.
     */
    @Presubmit
    @Test
    fun startsWithLauncherBeingOnTop() {
        testSpec.assertWmStart {
            this.isAppWindowOnTop(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that the transition ends with the home activity being flagged as not visible. By this
     * point we should have quick switched away from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithHomeActivityFlaggedInvisible() {
        testSpec.assertWmEnd {
            this.isHomeActivityVisible(false)
        }
    }

    /**
     * Checks that [testApp]'s window starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    fun appWindowBecomesAndStaysVisible() {
        testSpec.assertWm {
            this.isAppWindowInvisible(testApp.component, ignoreActivity = true)
                    .then()
                    .isAppWindowVisible(testApp.component, ignoreActivity = true)
        }
    }

    /**
     * Checks that [testApp]'s layer starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    fun appLayerBecomesAndStaysVisible() {
        testSpec.assertLayers {
            this.isInvisible(testApp.component)
                    .then()
                    .isVisible(testApp.component)
        }
    }

    /**
     * Checks that the launcher window starts off visible and becomes invisible at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Presubmit
    @Test
    fun launcherWindowBecomesAndStaysInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(LAUNCHER_COMPONENT)
                    .then()
                    .isAppWindowInvisible(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that the launcher layer starts off visible and becomes invisible at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Presubmit
    @Test
    fun launcherLayerBecomesAndStaysInvisible() {
        testSpec.assertLayers {
            this.isVisible(LAUNCHER_COMPONENT)
                    .then()
                    .isInvisible(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that the launcher window is visible at least until the app window is visible. Ensures
     * that at any point, either the launcher or [testApp] windows are at least partially visible.
     */
    @Presubmit
    @Test
    fun appWindowIsVisibleOnceLauncherWindowIsInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(LAUNCHER_COMPONENT)
                    .then()
                    .isAppWindowVisible(FlickerComponentName.SNAPSHOT)
                    .then()
                    .isAppWindowVisible(testApp.component)
        }
    }

    /**
     * Checks that the launcher layer is visible at least until the app layer is visible. Ensures
     * that at any point, either the launcher or [testApp] layers are at least partially visible.
     */
    @Presubmit
    @Test
    fun appLayerIsVisibleOnceLauncherLayerIsInvisible() {
        testSpec.assertLayers {
            this.isVisible(LAUNCHER_COMPONENT)
                    .then()
                    .isVisible(FlickerComponentName.SNAPSHOT)
                    .then()
                    .isVisible(testApp.component)
        }
    }

    /**
     * Checks that the navbar window is visible throughout the entire transition.
     */
    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsVisible()

    /**
     * Checks that the navbar layer is visible throughout the entire transition.
     */
    @Presubmit
    @Test
    fun navBarLayerAlwaysIsVisible() = testSpec.navBarLayerIsVisible()

    /**
     * Checks that the navbar is always in the right position and covers the expected region.
     *
     * NOTE: This doesn't check that the navbar is visible or not.
     */
    @Presubmit
    @Test
    fun navbarIsAlwaysInRightPosition() =
            testSpec.navBarLayerRotatesAndScales()

    /**
     * Checks that the status bar window is visible throughout the entire transition.
     */
    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsVisible()

    /**
     * Checks that the status bar layer is visible throughout the entire transition.
     */
    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsVisible()

    /**
     * Checks that the screen is always fully covered by visible layers throughout the transition.
     */
    @Presubmit
    @Test
    fun screenIsAlwaysFilled() = testSpec.entireScreenCovered()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(
                            repetitions = 5,
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            ),
                            // TODO: Test with 90 rotation
                            supportedRotations = listOf(Surface.ROTATION_0)
                    )
        }
    }
}