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

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.traces.common.FlickerComponentName
import com.google.common.truth.Truth
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an app while the device is locked
 *
 * To run this test: `atest FlickerTests:OpenAppNonResizeableTest`
 *
 * Actions:
 *     Lock the device.
 *     Launch an app on top of the lock screen [testApp] and wait animation to complete
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
class OpenAppNonResizeableTest(testSpec: FlickerTestParameter) : OpenAppTransition(testSpec) {
    override val testApp = NonResizeableAppHelper(instrumentation)
    private val colorFadComponent = FlickerComponentName("", "ColorFade BLAST#")

    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { args ->
            super.transition(this, args)
            setup {
                eachRun {
                    device.sleep()
                    wmHelper.waitFor("noAppWindowsOnTop") {
                        it.wmState.topVisibleAppWindow.isEmpty()
                    }
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

    /**
     * Checks that the nav bar layer starts visible, becomes invisible during unlocking animation
     * and becomes visible at the end
     */
    @Postsubmit
    @Test
    fun navBarLayerVisibilityChanges() {
        testSpec.assertLayers {
            this.isVisible(FlickerComponentName.NAV_BAR)
                .then()
                .isInvisible(FlickerComponentName.NAV_BAR)
                .then()
                .isVisible(FlickerComponentName.NAV_BAR)
        }
    }

    /**
     * Checks that the app layer doesn't exist at the start of the transition, that it is
     * created (invisible) and becomes visible during the transition
     */
    @Presubmit
    @Test
    fun appLayerBecomesVisible() {
        testSpec.assertLayers {
            this.notContains(testApp.component)
                    .then()
                    .isInvisible(testApp.component)
                    .then()
                    .isVisible(testApp.component)
        }
    }

    /**
     * Checks that the app window doesn't exist at the start of the transition, that it is
     * created (invisible - optional) and becomes visible during the transition
     *
     * The `isAppWindowInvisible` step is optional because we log once per frame, upon logging,
     * the window may be visible or not depending on what was processed until that moment.
     */
    @Presubmit
    @Test
    fun appWindowBecomesVisible() {
        testSpec.assertWm {
            this.notContains(testApp.component)
                    .then()
                    .isAppWindowInvisible(testApp.component, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp.component)
        }
    }

    /**
     * Checks if [testApp] is visible at the end of the transition
     */
    @Presubmit
    @Test
    fun appWindowBecomesVisibleAtEnd() {
        testSpec.assertWmEnd {
            this.isAppWindowVisible(testApp.component)
        }
    }

    /**
     * Checks that the nav bar starts the transition visible, then becomes invisible during
     * then unlocking animation and becomes visible at the end of the transition
     */
    @Postsubmit
    @Test
    fun navBarWindowsVisibilityChanges() {
        testSpec.assertWm {
            this.isAboveAppWindowVisible(FlickerComponentName.NAV_BAR)
                .then()
                .isNonAppWindowInvisible(FlickerComponentName.NAV_BAR)
                .then()
                .isAboveAppWindowVisible(FlickerComponentName.NAV_BAR)
        }
    }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    /**
     * Checks that the focus changes from the launcher to [testApp]
     */
    @FlakyTest
    @Test
    override fun focusChanges() = super.focusChanges()

    /**
     * Checks that the screen is locked at the start of the transition ([colorFadComponent])
     * layer is visible
     */
    @Postsubmit
    @Test
    fun screenLockedStart() {
        testSpec.assertLayersStart {
            isVisible(colorFadComponent)
        }
    }

    /**
     * This test checks if the launcher is visible at the start and the app at the end,
     * it cannot use the regular assertion (check over time), because on lock screen neither
     * the app not the launcher are visible, and there is no top visible window.
     */
    @Postsubmit
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() {
        testSpec.assertWm {
            this.invoke("noAppWindowsOnTop") {
                    Truth.assertWithMessage("Should not have any app window on top " +
                        "when the screen is locked")
                        .that(it.wmState.topVisibleAppWindow)
                        .isEmpty()
                }.then()
                .isAppWindowOnTop(testApp.component)
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
                    .getConfigNonRotationTests(
                            repetitions = 5,
                            supportedNavigationModes =
                            listOf(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY),
                            supportedRotations = listOf(Surface.ROTATION_0)
                    )
        }
    }
}