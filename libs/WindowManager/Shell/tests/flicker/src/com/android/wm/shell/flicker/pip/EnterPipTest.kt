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
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
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
 *     Launch an app in full screen
 *     Press an "enter pip" button to put [pipApp] in pip mode
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [PipTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group3
class EnterPipTest(testSpec: FlickerTestParameter) : PipTransition(testSpec) {

    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setupAndTeardown(this)
            setup {
                eachRun {
                    pipApp.launchViaIntent(wmHelper)
                }
            }
            teardown {
                eachRun {
                    pipApp.exit(wmHelper)
                }
            }
            transitions {
                pipApp.clickEnterPipButton(wmHelper)
            }
        }

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 206753786)
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    /**
     * Checks [pipApp] window remains visible throughout the animation
     */
    @Presubmit
    @Test
    fun pipAppWindowAlwaysVisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(pipApp.component)
        }
    }

    /**
     * Checks [pipApp] layer remains visible throughout the animation
     */
    @Presubmit
    @Test
    fun pipAppLayerAlwaysVisible() {
        testSpec.assertLayers {
            this.isVisible(pipApp.component)
        }
    }

    /**
     * Checks that the pip app window remains inside the display bounds throughout the whole
     * animation
     */
    @Presubmit
    @Test
    fun pipWindowRemainInsideVisibleBounds() {
        testSpec.assertWmVisibleRegion(pipApp.component) {
            coversAtMost(displayBounds)
        }
    }

    /**
     * Checks that the pip app layer remains inside the display bounds throughout the whole
     * animation
     */
    @Presubmit
    @Test
    fun pipLayerRemainInsideVisibleBounds() {
        testSpec.assertLayersVisibleRegion(pipApp.component) {
            coversAtMost(displayBounds)
        }
    }

    /**
     * Checks that the visible region of [pipApp] always reduces during the animation
     */
    @Presubmit
    @Test
    fun pipLayerReduces() {
        val layerName = pipApp.component.toLayerName()
        testSpec.assertLayers {
            val pipLayerList = this.layers { it.name.contains(layerName) && it.isVisible }
            pipLayerList.zipWithNext { previous, current ->
                current.visibleRegion.coversAtMost(previous.visibleRegion.region)
            }
        }
    }

    /**
     * Checks that [pipApp] window becomes pinned
     */
    @Presubmit
    @Test
    fun pipWindowBecomesPinned() {
        testSpec.assertWm {
            invoke("pipWindowIsNotPinned") { it.isNotPinned(pipApp.component) }
                .then()
                .invoke("pipWindowIsPinned") { it.isPinned(pipApp.component) }
        }
    }

    /**
     * Checks [LAUNCHER_COMPONENT] layer remains visible throughout the animation
     */
    @Presubmit
    @Test
    fun launcherLayerBecomesVisible() {
        testSpec.assertLayers {
            isInvisible(LAUNCHER_COMPONENT)
                .then()
                .isVisible(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that the focus changes between the [pipApp] window and the launcher when
     * closing the pip window
     */
    @Presubmit
    @Test
    fun focusChanges() {
        testSpec.assertEventLog {
            this.focusChanges(pipApp.`package`, "NexusLauncherActivity")
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
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                    repetitions = 3)
        }
    }
}
