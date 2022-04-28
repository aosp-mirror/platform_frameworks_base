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

package com.android.server.wm.flicker.ime

import android.app.Instrumentation
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.FlakyTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.common.WindowManagerConditionsFactory
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeFalse
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window layer will be associated with the app task when going to the overview screen.
 * To run this test: `atest FlickerTests:OpenImeWindowToOverViewTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
class OpenImeWindowToOverViewTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)
    private val statusBarInvisible = WindowManagerConditionsFactory.isStatusBarVisible().negate()
    private val navBarInvisible = WindowManagerConditionsFactory.isNavBarVisible().negate()

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    imeTestApp.launchViaIntent(wmHelper)
                }
            }
            transitions {
                device.pressRecentApps()
                waitForRecentsActivityVisible(wmHelper)
                waitNavStatusBarVisibility(wmHelper)
            }
            teardown {
                test {
                    device.pressHome()
                    imeTestApp.exit(wmHelper)
                }
            }
        }
    }

    /**
     * The bars (including status bar and navigation bar) are expected to be hidden while
     * entering overview in landscape if launcher is set to portrait only. Because
     * "showing portrait overview (launcher) in landscape display" is an intermediate state
     * depending on the touch-up to decide the intention of gesture, the display may keep in
     * landscape if return to app, or change to portrait if the gesture is to swipe-to-home.
     *
     * So instead of showing landscape bars with portrait launcher at the same time
     * (especially return-to-home that launcher workspace becomes visible), hide the bars until
     * leave overview to have cleaner appearance.
     *
     * b/227189877
     */
    private fun waitNavStatusBarVisibility(wmHelper: WindowManagerStateHelper) {
        when {
            testSpec.isLandscapeOrSeascapeAtStart && !testSpec.isGesturalNavigation ->
                wmHelper.waitFor(statusBarInvisible)
            testSpec.isLandscapeOrSeascapeAtStart ->
                wmHelper.waitFor(statusBarInvisible, navBarInvisible)
        }
    }

    @Presubmit
    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    @Presubmit
    @Test
    fun imeWindowIsAlwaysVisible() {
        testSpec.imeWindowIsAlwaysVisible()
    }

    @Presubmit
    @Test
    fun navBarLayerIsVisible3Button() {
        assumeFalse(testSpec.isGesturalNavigation)
        testSpec.navBarLayerIsVisible()
    }

    /**
     * Bars are expected to be hidden while entering overview in landscape (b/227189877)
     */
    @Presubmit
    @Test
    fun navBarLayerIsVisibleInPortraitGestural() {
        assumeFalse(testSpec.isLandscapeOrSeascapeAtStart)
        assumeTrue(testSpec.isGesturalNavigation)
        testSpec.navBarLayerIsVisible()
    }

    /**
     * In the legacy transitions, the nav bar is not marked as invisible.
     * In the new transitions this is fixed and the nav bar shows as invisible
     */
    @Postsubmit
    @Test
    fun navBarLayerIsInvisibleInLandscapeGestural() {
        assumeTrue(testSpec.isLandscapeOrSeascapeAtStart)
        assumeTrue(testSpec.isGesturalNavigation)
        assumeTrue(isShellTransitionsEnabled)
        testSpec.assertLayersStart {
            this.isVisible(FlickerComponentName.NAV_BAR)
        }
        testSpec.assertLayersEnd {
            this.isInvisible(FlickerComponentName.NAV_BAR)
        }
    }

    @Postsubmit
    @Test
    fun statusBarLayerIsVisibleInPortrait() {
        assumeFalse(testSpec.isLandscapeOrSeascapeAtStart)
        testSpec.statusBarLayerIsVisible()
    }

    @Presubmit
    @Test
    fun statusBarLayerIsInvisibleInLandscape() {
        assumeTrue(testSpec.isLandscapeOrSeascapeAtStart)
        testSpec.assertLayersStart {
            this.isVisible(FlickerComponentName.STATUS_BAR)
        }
        testSpec.assertLayersEnd {
            this.isInvisible(FlickerComponentName.STATUS_BAR)
        }
    }

    @FlakyTest(bugId = 228011606)
    @Test
    fun imeLayerIsVisibleAndAssociatedWithAppWidow() {
        testSpec.assertLayersStart {
            isVisible(FlickerComponentName.IME).visibleRegion(FlickerComponentName.IME)
                    .coversAtMost(isVisible(imeTestApp.component)
                            .visibleRegion(imeTestApp.component).region)
        }
        testSpec.assertLayers {
            this.invoke("imeLayerIsVisibleAndAlignAppWidow") {
                val imeVisibleRegion = it.visibleRegion(FlickerComponentName.IME)
                val appVisibleRegion = it.visibleRegion(imeTestApp.component)
                if (imeVisibleRegion.region.isNotEmpty) {
                    it.isVisible(FlickerComponentName.IME)
                    imeVisibleRegion.coversAtMost(appVisibleRegion.region)
                }
            }
        }
    }

    private fun waitForRecentsActivityVisible(
        wmHelper: WindowManagerStateHelper
    ) {
        val waitMsg = "state of Recents activity to be visible"
        require(
                wmHelper.waitFor(waitMsg) {
                    it.wmState.homeActivity?.let { act ->
                        it.wmState.isActivityVisible(act.name)
                    } == true ||
                            it.wmState.recentsActivity?.let { act ->
                                it.wmState.isActivityVisible(act.name)
                            } == true
                }
        ) { "Recents activity should be visible" }
        wmHelper.waitForAppTransitionIdle()
        // Ensure WindowManagerService wait until all animations have completed
        instrumentation.uiAutomation.syncInputTransactions()
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
                            repetitions = 1,
                            supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90),
                            supportedNavigationModes = listOf(
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                                    WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                            )
                    )
        }
    }
}