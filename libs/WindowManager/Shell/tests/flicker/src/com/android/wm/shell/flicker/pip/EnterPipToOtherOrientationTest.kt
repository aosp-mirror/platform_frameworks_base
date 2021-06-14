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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.Postsubmit
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
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.wm.shell.flicker.pip.PipTransition.BroadcastActionTrigger.Companion.ORIENTATION_LANDSCAPE
import com.android.wm.shell.flicker.pip.PipTransition.BroadcastActionTrigger.Companion.ORIENTATION_PORTRAIT
import com.android.wm.shell.flicker.testapp.Components.PipActivity.ACTION_ENTER_PIP
import com.android.wm.shell.flicker.testapp.Components.FixedActivity.EXTRA_FIXED_ORIENTATION
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
class EnterPipToOtherOrientationTest(
    testSpec: FlickerTestParameter
) : PipTransition(testSpec) {
    private val testApp = FixedAppHelper(instrumentation)
    private val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)
    private val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            setupAndTeardown(this, configuration)

            setup {
                eachRun {
                    // Launch a portrait only app on the fullscreen stack
                    testApp.launchViaIntent(wmHelper, stringExtras = mapOf(
                        EXTRA_FIXED_ORIENTATION to ORIENTATION_PORTRAIT.toString()))
                    // Launch the PiP activity fixed as landscape
                    pipApp.launchViaIntent(wmHelper, stringExtras = mapOf(
                        EXTRA_FIXED_ORIENTATION to ORIENTATION_LANDSCAPE.toString()))
                }
            }
            teardown {
                eachRun {
                    pipApp.exit(wmHelper)
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                // Enter PiP, and assert that the PiP is within bounds now that the device is back
                // in portrait
                broadcastActionTrigger.doAction(ACTION_ENTER_PIP)
                wmHelper.waitFor { it.wmState.hasPipWindow() }
                wmHelper.waitForAppTransitionIdle()
            }
        }

    @Postsubmit
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    @Postsubmit
    @Test
    override fun statusBarLayerIsVisible() = super.statusBarLayerIsVisible()

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    @FlakyTest
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    @FlakyTest
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    @Presubmit
    @Test
    fun pipAppWindowIsAlwaysOnTop() {
        testSpec.assertWm {
            isAppWindowOnTop(pipApp.component)
        }
    }

    @Presubmit
    @Test
    fun pipAppHidesTestApp() {
        testSpec.assertWmStart {
            isInvisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    fun testAppWindowIsVisible() {
        testSpec.assertWmEnd {
            isVisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    fun pipAppLayerHidesTestApp() {
        testSpec.assertLayersStart {
            visibleRegion(pipApp.component).coversExactly(startingBounds)
            isInvisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    fun testAppLayerCoversFullScreen() {
        testSpec.assertLayersEnd {
            visibleRegion(testApp.component).coversExactly(endingBounds)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                    repetitions = 5)
        }
    }
}
