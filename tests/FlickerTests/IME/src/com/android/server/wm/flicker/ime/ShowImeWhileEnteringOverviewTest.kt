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

import android.platform.test.annotations.Presubmit
import android.tools.common.traces.ConditionsFactory
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import android.tools.device.traces.parsers.WindowManagerStateHelper
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeShownOnAppStartHelper
import com.android.server.wm.flicker.navBarLayerIsVisibleAtStartAndEnd
import com.android.server.wm.flicker.statusBarLayerIsVisibleAtStartAndEnd
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window layer will be associated with the app task when going to the overview screen. To
 * run this test: `atest FlickerTests:OpenImeWindowToOverViewTest`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ShowImeWhileEnteringOverviewTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val imeTestApp =
        ImeShownOnAppStartHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup { imeTestApp.launchViaIntent(wmHelper) }
        transitions {
            device.pressRecentApps()
            val builder = wmHelper.StateSyncBuilder().withRecentsActivityVisible()
            waitNavStatusBarVisibility(builder)
            builder.waitForAndVerify()
        }
        teardown {
            device.pressHome()
            wmHelper.StateSyncBuilder().withHomeActivityVisible().waitForAndVerify()
            imeTestApp.exit(wmHelper)
        }
    }

    /**
     * The bars (including [ComponentNameMatcher.STATUS_BAR] and [ComponentNameMatcher.NAV_BAR]) are
     * expected to be hidden while entering overview in landscape if launcher is set to portrait
     * only. Because "showing portrait overview (launcher) in landscape display" is an intermediate
     * state depending on the touch-up to decide the intention of gesture, the display may keep in
     * landscape if return to app, or change to portrait if the gesture is to swipe-to-home.
     *
     * So instead of showing landscape bars with portrait launcher at the same time (especially
     * return-to-home that launcher workspace becomes visible), hide the bars until leave overview
     * to have cleaner appearance.
     *
     * b/227189877
     */
    private fun waitNavStatusBarVisibility(stateSync: WindowManagerStateHelper.StateSyncBuilder) {
        when {
            flicker.scenario.isLandscapeOrSeascapeAtStart && !flicker.scenario.isTablet ->
                stateSync.add(ConditionsFactory.isStatusBarVisible().negate())
            else -> stateSync.withNavOrTaskBarVisible().withStatusBarVisible()
        }
    }

    @Presubmit
    @Test
    fun imeWindowIsAlwaysVisible() {
        flicker.imeWindowIsAlwaysVisible()
    }

    @Presubmit
    @Test
    fun navBarLayerIsVisibleAtStartAndEnd3Button() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        Assume.assumeFalse(flicker.scenario.isGesturalNavigation)
        flicker.navBarLayerIsVisibleAtStartAndEnd()
    }

    /**
     * In the legacy transitions, the nav bar is not marked as invisible. In the new transitions
     * this is fixed and the nav bar shows as invisible
     */
    @Presubmit
    @Test
    fun navBarLayerIsInvisibleInLandscapeGestural() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        Assume.assumeTrue(flicker.scenario.isLandscapeOrSeascapeAtStart)
        Assume.assumeTrue(flicker.scenario.isGesturalNavigation)
        flicker.assertLayersStart { this.isVisible(ComponentNameMatcher.NAV_BAR) }
        flicker.assertLayersEnd { this.isInvisible(ComponentNameMatcher.NAV_BAR) }
    }

    /**
     * In the legacy transitions, the nav bar is not marked as invisible. In the new transitions
     * this is fixed and the nav bar shows as invisible
     */
    @Presubmit
    @Test
    fun statusBarLayerIsInvisibleInLandscapePhone() {
        Assume.assumeTrue(flicker.scenario.isLandscapeOrSeascapeAtStart)
        Assume.assumeTrue(flicker.scenario.isGesturalNavigation)
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.assertLayersStart { this.isVisible(ComponentNameMatcher.STATUS_BAR) }
        flicker.assertLayersEnd { this.isInvisible(ComponentNameMatcher.STATUS_BAR) }
    }

    /**
     * In the legacy transitions, the nav bar is not marked as invisible. In the new transitions
     * this is fixed and the nav bar shows as invisible
     */
    @Presubmit
    @Test
    fun statusBarLayerIsInvisibleInLandscapeTablet() {
        Assume.assumeTrue(flicker.scenario.isLandscapeOrSeascapeAtStart)
        Assume.assumeTrue(flicker.scenario.isGesturalNavigation)
        Assume.assumeTrue(flicker.scenario.isTablet)
        flicker.statusBarLayerIsVisibleAtStartAndEnd()
    }

    /** {@inheritDoc} */
    @Test
    @Ignore("Visibility changes depending on orientation and navigation mode")
    override fun navBarLayerIsVisibleAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Visibility changes depending on orientation and navigation mode")
    override fun navBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Visibility changes depending on orientation and navigation mode")
    override fun statusBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Visibility changes depending on orientation and navigation mode")
    override fun statusBarLayerIsVisibleAtStartAndEnd() {}

    @Presubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    @Presubmit
    @Test
    fun statusBarLayerIsVisibleInPortrait() {
        Assume.assumeFalse(flicker.scenario.isLandscapeOrSeascapeAtStart)
        flicker.statusBarLayerIsVisibleAtStartAndEnd()
    }

    @Presubmit
    @Test
    fun statusBarLayerIsInvisibleInLandscape() {
        Assume.assumeTrue(flicker.scenario.isLandscapeOrSeascapeAtStart)
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.assertLayersStart { this.isVisible(ComponentNameMatcher.STATUS_BAR) }
        flicker.assertLayersEnd { this.isInvisible(ComponentNameMatcher.STATUS_BAR) }
    }

    @Presubmit
    @Test
    fun imeLayerIsVisibleAndAssociatedWithAppWidow() {
        flicker.assertLayersStart {
            isVisible(ComponentNameMatcher.IME)
                .visibleRegion(ComponentNameMatcher.IME)
                .coversAtMost(isVisible(imeTestApp).visibleRegion(imeTestApp).region)
        }
        flicker.assertLayers {
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
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
