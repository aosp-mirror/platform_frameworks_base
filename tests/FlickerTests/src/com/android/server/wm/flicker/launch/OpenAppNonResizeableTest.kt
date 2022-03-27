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

import androidx.test.filters.FlakyTest
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.Assume
import org.junit.Before
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
open class OpenAppNonResizeableTest(testSpec: FlickerTestParameter)
    : OpenAppFromLockTransition(testSpec) {
    override val testApp = NonResizeableAppHelper(instrumentation)
    private val colorFadComponent = FlickerComponentName("", "ColorFade BLAST#")

    @Before
    open fun before() {
        Assume.assumeFalse(isShellTransitionsEnabled)
    }

    /**
     * Checks that the nav bar layer starts invisible, becomes visible during unlocking animation
     * and remains visible at the end
     */
    @FlakyTest(bugId = 227083463)
    @Test
    fun navBarLayerVisibilityChanges() {
        testSpec.assertLayers {
            this.isInvisible(FlickerComponentName.NAV_BAR)
                .then()
                .isVisible(FlickerComponentName.NAV_BAR)
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
     * Checks that the nav bar starts the transition invisible, then becomes visible during
     * the unlocking animation and remains visible at the end of the transition
     */
    @Postsubmit
    @Test
    fun navBarWindowsVisibilityChanges() {
        testSpec.assertWm {
            this.isNonAppWindowInvisible(FlickerComponentName.NAV_BAR)
                .then()
                .isAboveAppWindowVisible(FlickerComponentName.NAV_BAR)
        }
    }

    /**
     * Checks that the status bar layer is visible at the end of the trace
     *
     * It is not possible to check at the start because the animation is working differently
     * in devices with and without blur (b/202936526)
     */
    @Presubmit
    @Test
    override fun statusBarLayerIsVisible() {
        testSpec.assertLayersEnd {
            this.isVisible(FlickerComponentName.STATUS_BAR)
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 202936526)
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 206753786)
    @Test
    fun statusBarLayerPositionAtEnd() {
        testSpec.assertLayersEnd {
            val display = this.entry.displays.minByOrNull { it.id }
                ?: error("There is no display!")
            this.visibleRegion(FlickerComponentName.STATUS_BAR)
                .coversExactly(WindowUtils.getStatusBarPosition(display))
        }
    }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    @FlakyTest(bugId = 218470989)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

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
                            repetitions = 3,
                            supportedNavigationModes =
                            listOf(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY),
                            supportedRotations = listOf(Surface.ROTATION_0)
                    )
        }
    }
}
