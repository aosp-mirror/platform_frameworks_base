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
import android.tools.common.NavBar
import android.tools.common.Rotation
import android.tools.common.datatypes.Rect
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import org.junit.FixMethodOrder
import org.junit.Ignore
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
 * ```
 *     Launch an app
 *     Navigate home to show launcher
 *     Swipe right from the bottom of the screen to quick switch back to the app
 * ```
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QuickSwitchFromLauncherTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val testApp = SimpleAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)

            tapl.setExpectedRotation(flicker.scenario.startRotation.value)

            testApp.launchViaIntent(wmHelper)
            tapl.goHome()
            wmHelper
                .StateSyncBuilder()
                .withHomeActivityVisible()
                .withWindowSurfaceDisappeared(testApp)
                .waitForAndVerify()

            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds ?: error("Display not found")
        }
        transitions {
            tapl.workspace.quickSwitchToPreviousApp()
            wmHelper
                .StateSyncBuilder()
                .withFullScreenApp(testApp)
                .withNavOrTaskBarVisible()
                .withStatusBarVisible()
                .waitForAndVerify()
        }
        teardown { testApp.exit(wmHelper) }
    }

    /**
     * Checks that [testApp] windows fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppWindowsCoveringFullScreen() {
        flicker.assertWmEnd { this.visibleRegion(testApp).coversExactly(startDisplayBounds) }
    }

    /**
     * Checks that [testApp] layers fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppLayersCoveringFullScreen() {
        flicker.assertLayersEnd { this.visibleRegion(testApp).coversExactly(startDisplayBounds) }
    }

    /**
     * Checks that [testApp] is the top window at the end of the transition once we have fully quick
     * switched from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithAppBeingOnTop() {
        flicker.assertWmEnd { this.isAppWindowOnTop(testApp) }
    }

    /** Checks that the transition starts with the home activity being tagged as visible. */
    @Presubmit
    @Test
    fun startsWithHomeActivityFlaggedVisible() {
        flicker.assertWmStart { this.isHomeActivityVisible() }
    }

    /**
     * Checks that the transition starts with the [ComponentNameMatcher.LAUNCHER] windows
     * filling/covering exactly display size
     */
    @Presubmit
    @Test
    fun startsWithLauncherWindowsCoverFullScreen() {
        flicker.assertWmStart {
            this.visibleRegion(ComponentNameMatcher.LAUNCHER).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with the [ComponentNameMatcher.LAUNCHER] layers
     * filling/covering exactly the display size.
     */
    @Presubmit
    @Test
    fun startsWithLauncherLayersCoverFullScreen() {
        flicker.assertLayersStart {
            this.visibleRegion(ComponentNameMatcher.LAUNCHER).coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with the [ComponentNameMatcher.LAUNCHER] being the top
     * window.
     */
    @Presubmit
    @Test
    fun startsWithLauncherBeingOnTop() {
        flicker.assertWmStart { this.isAppWindowOnTop(ComponentNameMatcher.LAUNCHER) }
    }

    /**
     * Checks that the transition ends with the home activity being flagged as not visible. By this
     * point we should have quick switched away from the launcher back to the [testApp].
     */
    @Presubmit
    @Test
    fun endsWithHomeActivityFlaggedInvisible() {
        flicker.assertWmEnd { this.isHomeActivityInvisible() }
    }

    /**
     * Checks that [testApp]'s window starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    fun appWindowBecomesAndStaysVisible() {
        flicker.assertWm { this.isAppWindowInvisible(testApp).then().isAppWindowVisible(testApp) }
    }

    /**
     * Checks that [testApp]'s layer starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Presubmit
    @Test
    fun appLayerBecomesAndStaysVisible() {
        flicker.assertLayers { this.isInvisible(testApp).then().isVisible(testApp) }
    }

    /**
     * Checks that the [ComponentNameMatcher.LAUNCHER] window starts off visible and becomes
     * invisible at some point before the end of the transition and then stays invisible until the
     * end of the transition.
     */
    @Presubmit
    @Test
    fun launcherWindowBecomesAndStaysInvisible() {
        flicker.assertWm {
            this.isAppWindowOnTop(ComponentNameMatcher.LAUNCHER)
                .then()
                .isAppWindowNotOnTop(ComponentNameMatcher.LAUNCHER)
        }
    }

    /**
     * Checks that the [ComponentNameMatcher.LAUNCHER] layer starts off visible and becomes
     * invisible at some point before the end of the transition and then stays invisible until the
     * end of the transition.
     */
    @Presubmit
    @Test
    fun launcherLayerBecomesAndStaysInvisible() {
        flicker.assertLayers {
            this.isVisible(ComponentNameMatcher.LAUNCHER)
                .then()
                .isInvisible(ComponentNameMatcher.LAUNCHER)
        }
    }

    /**
     * Checks that the [ComponentNameMatcher.LAUNCHER] window is visible at least until the app
     * window is visible. Ensures that at any point, either the launcher or [testApp] windows are at
     * least partially visible.
     */
    @Presubmit
    @Test
    fun appWindowIsVisibleOnceLauncherWindowIsInvisible() {
        flicker.assertWm {
            this.isAppWindowOnTop(ComponentNameMatcher.LAUNCHER)
                .then()
                .isAppWindowVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isAppWindowVisible(testApp)
        }
    }

    /**
     * Checks that the [ComponentNameMatcher.LAUNCHER] layer is visible at least until the app layer
     * is visible. Ensures that at any point, either the launcher or [testApp] layers are at least
     * partially visible.
     */
    @Presubmit
    @Test
    fun appLayerIsVisibleOnceLauncherLayerIsInvisible() {
        flicker.assertLayers {
            this.isVisible(ComponentNameMatcher.LAUNCHER)
                .then()
                .isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(testApp)
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Ignore("Nav bar window becomes invisible during quick switch")
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    @FlakyTest(bugId = 246285528)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        super.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    companion object {
        /** {@inheritDoc} */
        private var startDisplayBounds = Rect.EMPTY

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedNavigationModes = listOf(NavBar.MODE_GESTURAL),
                // TODO: Test with 90 rotation
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
    }
}
