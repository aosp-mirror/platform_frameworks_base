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
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.wm.shell.flicker.pip.PipTransition.BroadcastActionTrigger.Companion.ORIENTATION_LANDSCAPE
import com.android.wm.shell.flicker.testapp.Components.FixedActivity.EXTRA_FIXED_ORIENTATION
import com.android.wm.shell.flicker.testapp.Components.PipActivity.EXTRA_ENTER_PIP
import org.junit.Assume.assumeFalse
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip with orientation changes.
 * To run this test: `atest WMShellFlickerTests:PipOrientationTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
class SetRequestedOrientationWhilePinnedTest(
    testSpec: FlickerTestParameter
) : PipTransition(testSpec) {
    private val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)
    private val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setupAndTeardown(this)

            setup {
                eachRun {
                    // Launch the PiP activity fixed as landscape
                    pipApp.launchViaIntent(wmHelper, stringExtras = mapOf(
                        EXTRA_FIXED_ORIENTATION to ORIENTATION_LANDSCAPE.toString(),
                        EXTRA_ENTER_PIP to "true"))
                }
            }
            teardown {
                eachRun {
                    pipApp.exit(wmHelper)
                }
            }
            transitions {
                // Request that the orientation is set to landscape
                broadcastActionTrigger.requestOrientationForPip(ORIENTATION_LANDSCAPE)

                // Launch the activity back into fullscreen and
                // ensure that it is now in landscape
                pipApp.launchViaIntent(wmHelper)
                wmHelper.waitForFullScreenApp(pipApp.component)
                wmHelper.waitForRotation(Surface.ROTATION_90)
            }
        }

    @Presubmit
    @Test
    fun displayEndsAt90Degrees() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        testSpec.assertWmEnd {
            hasRotation(Surface.ROTATION_90)
        }
    }

    @Presubmit
    @Test
    override fun navBarLayerIsVisible() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        super.navBarLayerIsVisible()
    }

    @Presubmit
    @Test
    override fun statusBarLayerIsVisible() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        super.statusBarLayerIsVisible()
    }

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        super.navBarLayerRotatesAndScales()
    }

    @FlakyTest(bugId = 206753786)
    @Test
    override fun statusBarLayerRotatesScales() {
        // This test doesn't work in shell transitions because of b/206753786
        assumeFalse(isShellTransitionsEnabled)
        super.statusBarLayerRotatesScales()
    }

    @Presubmit
    @Test
    fun pipWindowInsideDisplay() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        testSpec.assertWmStart {
            frameRegion(pipApp.component).coversAtMost(startingBounds)
        }
    }

    @Presubmit
    @Test
    fun pipAppShowsOnTop() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        testSpec.assertWmEnd {
            isAppWindowOnTop(pipApp.component)
        }
    }

    @Presubmit
    @Test
    fun pipLayerInsideDisplay() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        testSpec.assertLayersStart {
            visibleRegion(pipApp.component).coversAtMost(startingBounds)
        }
    }

    @Presubmit
    @Test
    fun pipAlwaysVisible() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        testSpec.assertWm {
            this.isAppWindowVisible(pipApp.component)
        }
    }

    @Presubmit
    @Test
    fun pipAppLayerCoversFullScreen() {
        // This test doesn't work in shell transitions because of b/208576418
        assumeFalse(isShellTransitionsEnabled)
        testSpec.assertLayersEnd {
            visibleRegion(pipApp.component).coversExactly(endingBounds)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                    repetitions = 1)
        }
    }
}
