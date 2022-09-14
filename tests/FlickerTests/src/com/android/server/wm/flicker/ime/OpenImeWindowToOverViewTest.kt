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

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.navBarLayerIsVisibleAtStartAndEnd
import com.android.server.wm.flicker.statusBarLayerIsVisibleAtStartAndEnd
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.WindowManagerConditionsFactory
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Ignore
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
class OpenImeWindowToOverViewTest(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            imeTestApp.launchViaIntent(wmHelper)
        }
        transitions {
            device.pressRecentApps()
            val builder = wmHelper.StateSyncBuilder()
                .withRecentsActivityVisible()
            waitNavStatusBarVisibility(builder)
            builder.waitForAndVerify()
        }
        teardown {
            device.pressHome()
            wmHelper.StateSyncBuilder()
                .withHomeActivityVisible()
                .waitForAndVerify()
            imeTestApp.exit(wmHelper)
        }
    }

    /**
     * The bars (including [ComponentMatcher.STATUS_BAR] and [ComponentMatcher.NAV_BAR]) are
     * expected to be hidden while entering overview in landscape if launcher is set to portrait
     * only. Because "showing portrait overview (launcher) in landscape display" is an intermediate
     * state depending on the touch-up to decide the intention of gesture, the display may keep in
     * landscape if return to app, or change to portrait if the gesture is to swipe-to-home.
     *
     * So instead of showing landscape bars with portrait launcher at the same time
     * (especially return-to-home that launcher workspace becomes visible), hide the bars until
     * leave overview to have cleaner appearance.
     *
     * b/227189877
     */
    private fun waitNavStatusBarVisibility(stateSync: WindowManagerStateHelper.StateSyncBuilder) {
        when {
            testSpec.isLandscapeOrSeascapeAtStart && !testSpec.isTablet ->
                stateSync.add(WindowManagerConditionsFactory.isStatusBarVisible().negate())
            else ->
                stateSync.withNavOrTaskBarVisible().withStatusBarVisible()
        }
    }

    @Presubmit
    @Test
    fun imeWindowIsAlwaysVisible() {
        testSpec.imeWindowIsAlwaysVisible()
    }

    @Presubmit
    @Test
    fun navBarLayerIsVisibleAtStartAndEnd3Button() {
        Assume.assumeFalse(testSpec.isTablet)
        Assume.assumeFalse(testSpec.isGesturalNavigation)
        testSpec.navBarLayerIsVisibleAtStartAndEnd()
    }

    /**
     * Bars are expected to be hidden while entering overview in landscape (b/227189877)
     */
    @Presubmit
    @Test
    fun navBarLayerIsVisibleAtStartAndEndGestural() {
        Assume.assumeFalse(testSpec.isTablet)
        Assume.assumeTrue(testSpec.isGesturalNavigation)
        Assume.assumeFalse(isShellTransitionsEnabled)
        testSpec.navBarLayerIsVisibleAtStartAndEnd()
    }

    /**
     * In the legacy transitions, the nav bar is not marked as invisible.
     * In the new transitions this is fixed and the nav bar shows as invisible
     */
    @Presubmit
    @Test
    fun navBarLayerIsInvisibleInLandscapeGestural() {
        Assume.assumeFalse(testSpec.isTablet)
        Assume.assumeTrue(testSpec.isLandscapeOrSeascapeAtStart)
        Assume.assumeTrue(testSpec.isGesturalNavigation)
        Assume.assumeTrue(isShellTransitionsEnabled)
        testSpec.assertLayersStart {
            this.isVisible(ComponentNameMatcher.NAV_BAR)
        }
        testSpec.assertLayersEnd {
            this.isInvisible(ComponentNameMatcher.NAV_BAR)
        }
    }

    /**
     * In the legacy transitions, the nav bar is not marked as invisible.
     * In the new transitions this is fixed and the nav bar shows as invisible
     */
    @Presubmit
    @Test
    fun statusBarLayerIsInvisibleInLandscapePhone() {
        Assume.assumeTrue(testSpec.isLandscapeOrSeascapeAtStart)
        Assume.assumeTrue(testSpec.isGesturalNavigation)
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.assertLayersStart {
            this.isVisible(ComponentNameMatcher.STATUS_BAR)
        }
        testSpec.assertLayersEnd {
            this.isInvisible(ComponentNameMatcher.STATUS_BAR)
        }
    }

    /**
     * In the legacy transitions, the nav bar is not marked as invisible.
     * In the new transitions this is fixed and the nav bar shows as invisible
     */
    @Presubmit
    @Test
    fun statusBarLayerIsInvisibleInLandscapeTablet() {
        Assume.assumeTrue(testSpec.isLandscapeOrSeascapeAtStart)
        Assume.assumeTrue(testSpec.isGesturalNavigation)
        Assume.assumeTrue(testSpec.isTablet)
        testSpec.statusBarLayerIsVisibleAtStartAndEnd()
    }

    /** {@inheritDoc} */
    @Test
    @Ignore("Visibility changes depending on orientation and navigation mode")
    override fun navBarLayerIsVisibleAtStartAndEnd() { }

    /** {@inheritDoc} */
    @Test
    @Ignore("Visibility changes depending on orientation and navigation mode")
    override fun navBarLayerPositionAtStartAndEnd() { }

    /** {@inheritDoc} */
    @Test
    @Ignore("Visibility changes depending on orientation and navigation mode")
    override fun statusBarLayerPositionAtStartAndEnd() { }

    /** {@inheritDoc} */
    @Test
    @Ignore("Visibility changes depending on orientation and navigation mode")
    override fun statusBarLayerIsVisibleAtStartAndEnd() { }

    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() =
        super.taskBarLayerIsVisibleAtStartAndEnd()

    @Presubmit
    @Test
    fun statusBarLayerIsVisibleInPortrait() {
        Assume.assumeFalse(testSpec.isLandscapeOrSeascapeAtStart)
        testSpec.statusBarLayerIsVisibleAtStartAndEnd()
    }

    @Presubmit
    @Test
    fun statusBarLayerIsInvisibleInLandscapeShell() {
        Assume.assumeTrue(testSpec.isLandscapeOrSeascapeAtStart)
        Assume.assumeFalse(testSpec.isTablet)
        Assume.assumeTrue(isShellTransitionsEnabled)
        testSpec.assertLayersStart {
            this.isVisible(ComponentNameMatcher.STATUS_BAR)
        }
        testSpec.assertLayersEnd {
            this.isInvisible(ComponentNameMatcher.STATUS_BAR)
        }
    }

    @Presubmit
    @Test
    fun statusBarLayerIsVisibleInLandscapeLegacy() {
        Assume.assumeTrue(testSpec.isLandscapeOrSeascapeAtStart)
        Assume.assumeTrue(testSpec.isTablet)
        Assume.assumeFalse(isShellTransitionsEnabled)
        testSpec.statusBarLayerIsVisibleAtStartAndEnd()
    }

    @Presubmit
    @Test
    fun imeLayerIsVisibleAndAssociatedWithAppWidow() {
        testSpec.assertLayersStart {
            isVisible(ComponentNameMatcher.IME).visibleRegion(ComponentNameMatcher.IME)
                .coversAtMost(
                    isVisible(imeTestApp)
                        .visibleRegion(imeTestApp).region
                )
        }
        testSpec.assertLayers {
            this.invoke("imeLayerIsVisibleAndAlignAppWidow") {
                val imeVisibleRegion = it.visibleRegion(ComponentNameMatcher.IME)
                val appVisibleRegion = it.visibleRegion(imeTestApp)
                if (imeVisibleRegion.region.isNotEmpty) {
                    it.isVisible(ComponentNameMatcher.IME)
                    imeVisibleRegion.coversAtMost(appVisibleRegion.region)
                }
            }
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
                    supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90),
                    supportedNavigationModes = listOf(
                        WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                        WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                    )
                )
        }
    }
}
