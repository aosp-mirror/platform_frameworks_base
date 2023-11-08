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

import android.tools.common.NavBar
import android.tools.common.datatypes.Rect
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switching back to previous app from last opened app
 *
 * To run this test: `atest FlickerTests:QuickSwitchBetweenTwoAppsForwardTest`
 *
 * Actions:
 * ```
 *     Launch an app [testApp1]
 *     Launch another app [testApp2]
 *     Swipe right from the bottom of the screen to quick switch back to the first app [testApp1]
 *     Swipe left from the bottom of the screen to quick switch forward to the second app [testApp2]
 * ```
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QuickSwitchBetweenTwoAppsForwardTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val testApp1 = SimpleAppHelper(instrumentation)
    private val testApp2 = NonResizeableAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotation(flicker.scenario.startRotation.value)

            testApp1.launchViaIntent(wmHelper)
            testApp2.launchViaIntent(wmHelper)
            tapl.launchedAppState.quickSwitchToPreviousApp()
            wmHelper
                .StateSyncBuilder()
                .withFullScreenApp(testApp1)
                .withNavOrTaskBarVisible()
                .withStatusBarVisible()
                .waitForAndVerify()
            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds ?: error("Display not found")
        }
        transitions {
            tapl.launchedAppState.quickSwitchToPreviousAppSwipeLeft()
            wmHelper
                .StateSyncBuilder()
                .withFullScreenApp(testApp2)
                .withNavOrTaskBarVisible()
                .withStatusBarVisible()
                .waitForAndVerify()
        }

        teardown {
            testApp1.exit(wmHelper)
            testApp2.exit(wmHelper)
        }
    }

    /**
     * Checks that the transition starts with [testApp1]'s windows filling/covering exactly the
     * entirety of the display.
     */
    @FlakyTest(bugId = 298544839)
    @Test
    open fun startsWithApp1WindowsCoverFullScreen() {
        flicker.assertWmStart {
            this.visibleRegion(testApp1.or(ComponentNameMatcher.LETTERBOX))
                .coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that the transition starts with [testApp1]'s layers filling/covering exactly the
     * entirety of the display.
     */
    @FlakyTest(bugId = 250522691)
    @Test
    open fun startsWithApp1LayersCoverFullScreen() {
        flicker.assertLayersStart { this.visibleRegion(testApp1).coversExactly(startDisplayBounds) }
    }

    /** Checks that the transition starts with [testApp1] being the top window. */
    @Test
    open fun startsWithApp1WindowBeingOnTop() {
        flicker.assertWmStart { this.isAppWindowOnTop(testApp1) }
    }

    /**
     * Checks that [testApp2] windows fill the entire screen (i.e. is "fullscreen") at the end of
     * the transition once we have fully quick switched from [testApp1] back to the [testApp2].
     */
    @FlakyTest(bugId = 298544839)
    @Test
    open fun endsWithApp2WindowsCoveringFullScreen() {
        flicker.assertWmEnd { this.visibleRegion(testApp2).coversExactly(startDisplayBounds) }
    }

    /**
     * Checks that [testApp2] layers fill the entire screen (i.e. is "fullscreen") at the end of the
     * transition once we have fully quick switched from [testApp1] back to the [testApp2].
     */
    @FlakyTest(bugId = 298544839)
    @Test
    open fun endsWithApp2LayersCoveringFullScreen() {
        flicker.assertLayersEnd {
            this.visibleRegion(testApp2.or(ComponentNameMatcher.LETTERBOX))
                .coversExactly(startDisplayBounds)
        }
    }

    /**
     * Checks that [testApp2] is the top window at the end of the transition once we have fully
     * quick switched from [testApp1] back to the [testApp2].
     */
    @FlakyTest(bugId = 298544839)
    @Test
    open fun endsWithApp2BeingOnTop() {
        flicker.assertWmEnd { this.isAppWindowOnTop(testApp2) }
    }

    /**
     * Checks that [testApp2]'s window starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @Test
    open fun app2WindowBecomesAndStaysVisible() {
        flicker.assertWm {
            this.isAppWindowInvisible(testApp2)
                .then()
                .isAppWindowVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isAppWindowVisible(testApp2)
        }
    }

    /**
     * Checks that [testApp2]'s layer starts off invisible and becomes visible at some point before
     * the end of the transition and then stays visible until the end of the transition.
     */
    @FlakyTest(bugId = 298544839)
    @Test
    open fun app2LayerBecomesAndStaysVisible() {
        flicker.assertLayers { this.isInvisible(testApp2).then().isVisible(testApp2) }
    }

    /**
     * Checks that [testApp1]'s window starts off visible and becomes invisible at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @FlakyTest(bugId = 298544839)
    @Test
    open fun app1WindowBecomesAndStaysInvisible() {
        flicker.assertWm { this.isAppWindowVisible(testApp1).then().isAppWindowInvisible(testApp1) }
    }

    /**
     * Checks that [testApp1]'s layer starts off visible and becomes invisible at some point before
     * the end of the transition and then stays invisible until the end of the transition.
     */
    @Test
    open fun app1LayerBecomesAndStaysInvisible() {
        flicker.assertLayers { this.isVisible(testApp1).then().isInvisible(testApp1) }
    }

    /**
     * Checks that [testApp1]'s window is visible at least until [testApp2]'s window is visible.
     * Ensures that at any point, either [testApp2] or [testApp1]'s windows are at least partially
     * visible.
     */
    @FlakyTest(bugId = 298544839)
    @Test
    open fun app2WindowIsVisibleOnceApp1WindowIsInvisible() {
        flicker.assertWm {
            this.isAppWindowVisible(testApp1)
                .then()
                .isAppWindowVisible(ComponentNameMatcher.LAUNCHER, isOptional = true)
                .then()
                .isAppWindowVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isAppWindowVisible(testApp2)
        }
    }

    /**
     * Checks that [testApp1]'s layer is visible at least until [testApp2]'s window is visible.
     * Ensures that at any point, either [testApp2] or [testApp1]'s windows are at least partially
     * visible.
     */
    @FlakyTest(bugId = 298544839)
    @Test
    open fun app2LayerIsVisibleOnceApp1LayerIsInvisible() {
        flicker.assertLayers {
            this.isVisible(testApp1)
                .then()
                .isVisible(ComponentNameMatcher.LAUNCHER, isOptional = true)
                .then()
                .isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(testApp2)
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 298544839)
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Ignore("Nav bar window becomes invisible during quick switch")
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun navBarWindowIsVisibleAtStartAndEnd() = super.navBarWindowIsVisibleAtStartAndEnd()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() = super.statusBarLayerPositionAtStartAndEnd()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun taskBarWindowIsAlwaysVisible() = super.taskBarWindowIsAlwaysVisible()

    @FlakyTest(bugId = 298544839)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        private var startDisplayBounds = Rect.EMPTY

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedNavigationModes = listOf(NavBar.MODE_GESTURAL)
            )
    }
}
