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
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.wm.shell.flicker.pip.PipTransition.BroadcastActionTrigger.Companion.ORIENTATION_LANDSCAPE
import com.android.wm.shell.flicker.testapp.Components.FixedActivity.EXTRA_FIXED_ORIENTATION
import com.android.wm.shell.flicker.testapp.Components.PipActivity.EXTRA_ENTER_PIP
import org.junit.Assert.assertEquals
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
@Group3
class SetRequestedOrientationWhilePinnedTest(
    testSpec: FlickerTestParameter
) : PipTransition(testSpec) {
    private val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)
    private val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            setupAndTeardown(this, configuration)

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
                assertEquals(Surface.ROTATION_90, device.displayRotation)
            }
        }

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    @FlakyTest
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    @Presubmit
    @Test
    fun pipWindowInsideDisplay() {
        testSpec.assertWmStart {
            frameRegion(pipApp.defaultWindowName).coversAtMost(startingBounds)
        }
    }

    @Presubmit
    @Test
    fun pipAppShowsOnTop() {
        testSpec.assertWmEnd {
            showsAppWindowOnTop(pipApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    fun pipLayerInsideDisplay() {
        testSpec.assertLayersStart {
            visibleRegion(pipApp.defaultWindowName).coversAtMost(startingBounds)
        }
    }

    @Presubmit
    @Test
    fun pipAlwaysVisible() = testSpec.assertWm {
        this.showsAppWindow(pipApp.windowName)
    }

    @Presubmit
    @Test
    fun pipAppLayerCoversFullScreen() {
        testSpec.assertLayersEnd {
            visibleRegion(pipApp.defaultWindowName).coversExactly(endingBounds)
        }
    }

    @FlakyTest
    @Test
    override fun noUncoveredRegions() {
        super.noUncoveredRegions()
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
