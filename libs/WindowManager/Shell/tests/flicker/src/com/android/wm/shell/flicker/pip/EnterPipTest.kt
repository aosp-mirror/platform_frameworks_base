/*
 * Copyright (C) 2020 The Android Open Source Project
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
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.service.PlatformConsts
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test entering pip from an app by interacting with the app UI
 *
 * To run this test: `atest WMShellFlickerTests:EnterPipTest`
 *
 * Actions:
 * ```
 *     Launch an app in full screen
 *     Press an "enter pip" button to put [pipApp] in pip mode
 * ```
 * Notes:
 * ```
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited from [PipTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class EnterPipTest(flicker: FlickerTest) : PipTransition(flicker) {

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup { pipApp.launchViaIntent(wmHelper) }
            teardown { pipApp.exit(wmHelper) }
            transitions { pipApp.clickEnterPipButton(wmHelper) }
        }

    /** Checks [pipApp] window remains visible throughout the animation */
    @Presubmit
    @Test
    open fun pipAppWindowAlwaysVisible() {
        flicker.assertWm { this.isAppWindowVisible(pipApp) }
    }

    /** Checks [pipApp] layer remains visible throughout the animation */
    @Presubmit
    @Test
    open fun pipAppLayerAlwaysVisible() {
        flicker.assertLayers { this.isVisible(pipApp) }
    }

    /**
     * Checks that the pip app window remains inside the display bounds throughout the whole
     * animation
     */
    @Presubmit
    @Test
    fun pipWindowRemainInsideVisibleBounds() {
        flicker.assertWmVisibleRegion(pipApp) { coversAtMost(displayBounds) }
    }

    /**
     * Checks that the pip app layer remains inside the display bounds throughout the whole
     * animation
     */
    @Presubmit
    @Test
    open fun pipLayerRemainInsideVisibleBounds() {
        flicker.assertLayersVisibleRegion(pipApp) { coversAtMost(displayBounds) }
    }

    /** Checks that the visible region of [pipApp] always reduces during the animation */
    @Presubmit
    @Test
    open fun pipLayerReduces() {
        flicker.assertLayers {
            val pipLayerList = this.layers { pipApp.layerMatchesAnyOf(it) && it.isVisible }
            pipLayerList.zipWithNext { previous, current ->
                current.visibleRegion.notBiggerThan(previous.visibleRegion.region)
            }
        }
    }

    /** Checks that [pipApp] window becomes pinned */
    @Presubmit
    @Test
    fun pipWindowBecomesPinned() {
        flicker.assertWm {
            invoke("pipWindowIsNotPinned") { it.isNotPinned(pipApp) }
                .then()
                .invoke("pipWindowIsPinned") { it.isPinned(pipApp) }
        }
    }

    /** Checks [ComponentMatcher.LAUNCHER] layer remains visible throughout the animation */
    @Presubmit
    @Test
    fun launcherLayerBecomesVisible() {
        flicker.assertLayers {
            isInvisible(ComponentNameMatcher.LAUNCHER)
                .then()
                .isVisible(ComponentNameMatcher.LAUNCHER)
        }
    }

    /**
     * Checks that the focus changes between the [pipApp] window and the launcher when closing the
     * pip window
     */
    @Presubmit
    @Test
    open fun focusChanges() {
        flicker.assertEventLog { this.focusChanges(pipApp.`package`, "NexusLauncherActivity") }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring repetitions, screen orientation
         * and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(PlatformConsts.Rotation.ROTATION_0)
            )
        }
    }
}
