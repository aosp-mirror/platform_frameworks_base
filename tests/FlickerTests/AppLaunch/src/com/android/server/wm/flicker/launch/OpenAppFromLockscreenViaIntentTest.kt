/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.platform.test.annotations.Presubmit
import android.tools.common.NavBar
import android.tools.common.Rotation
import android.tools.common.flicker.annotation.FlickerServiceCompatible
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.launch.common.OpenAppFromLockscreenTransition
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an app while the device is locked
 *
 * This test assumes the device doesn't have AOD enabled
 *
 * To run this test: `atest FlickerTests:OpenAppNonResizeableTest`
 *
 * Actions:
 * ```
 *     Lock the device.
 *     Launch an app on top of the lock screen [testApp] and wait animation to complete
 * ```
 *
 * Notes:
 * ```
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [OpenAppTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 * ```
 */
@RequiresDevice
@FlickerServiceCompatible
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class OpenAppFromLockscreenViaIntentTest(flicker: LegacyFlickerTest) :
    OpenAppFromLockscreenTransition(flicker) {
    override val testApp = NonResizeableAppHelper(instrumentation)

    /**
     * Checks that the [ComponentNameMatcher.NAV_BAR] layer starts invisible, becomes visible during
     * unlocking animation and remains visible at the end
     */
    @FlakyTest(bugId = 288341660)
    @Test
    fun navBarLayerVisibilityChanges() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.assertLayers {
            this.isInvisible(ComponentNameMatcher.NAV_BAR)
                .then()
                .isVisible(ComponentNameMatcher.NAV_BAR)
        }
    }

    /** Checks if [testApp] is visible at the end of the transition */
    @Presubmit
    @Test
    fun appWindowBecomesVisibleAtEnd() {
        flicker.assertWmEnd { this.isAppWindowVisible(testApp) }
    }

    /**
     * Checks that the [ComponentNameMatcher.NAV_BAR] starts the transition invisible, then becomes
     * visible during the unlocking animation and remains visible at the end of the transition
     */
    @FlakyTest(bugId = 293581770)
    @Test
    fun navBarWindowsVisibilityChanges() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.assertWm {
            this.isNonAppWindowInvisible(ComponentNameMatcher.NAV_BAR)
                .then()
                .isAboveAppWindowVisible(ComponentNameMatcher.NAV_BAR)
        }
    }

    /**
     * Checks that the [ComponentNameMatcher.TASK_BAR] starts the transition invisible, then becomes
     * visible during the unlocking animation and remains visible at the end of the transition
     */
    @Presubmit
    @Test
    fun taskBarLayerIsVisibleAtEnd() {
        Assume.assumeTrue(flicker.scenario.isTablet)
        flicker.assertLayersEnd { this.isVisible(ComponentNameMatcher.TASK_BAR) }
    }

    /**
     * Checks that the [ComponentNameMatcher.STATUS_BAR] layer is visible at the end of the trace
     *
     * It is not possible to check at the start because the screen is off
     */
    @Presubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() {
        flicker.assertLayersEnd { this.isVisible(ComponentNameMatcher.STATUS_BAR) }
    }

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun taskBarLayerIsVisibleAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun navBarLayerIsVisibleAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun taskBarWindowIsAlwaysVisible() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun navBarWindowIsAlwaysVisible() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts locked and app is full screen at the end")
    override fun navBarWindowIsVisibleAtStartAndEnd() = super.navBarWindowIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun statusBarWindowIsAlwaysVisible() {}

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appWindowBecomesFirstAndOnlyTopWindow() =
        super.appWindowBecomesFirstAndOnlyTopWindow()

    /** {@inheritDoc} */
    @Presubmit @Test override fun appWindowBecomesVisible() = super.appWindowBecomesVisible()

    /** Checks the [ComponentNameMatcher.NAV_BAR] is visible at the end of the transition */
    @Presubmit
    @Test
    fun navBarLayerIsVisibleAtEnd() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.assertLayersEnd { this.isVisible(ComponentNameMatcher.NAV_BAR) }
    }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appLayerBecomesVisible() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        super.appLayerBecomesVisible()
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 227143265)
    @Test
    fun appLayerBecomesVisibleTablet() {
        Assume.assumeTrue(flicker.scenario.isTablet)
        super.appLayerBecomesVisible()
    }

    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @FlakyTest(bugId = 285980483)
    @Test
    override fun focusChanges() {
        super.focusChanges()
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
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedNavigationModes = listOf(NavBar.MODE_GESTURAL),
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
    }
}
