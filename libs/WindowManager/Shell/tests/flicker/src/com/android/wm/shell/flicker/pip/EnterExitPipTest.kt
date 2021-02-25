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
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch and exit.
 * To run this test: `atest WMShellFlickerTests:EnterExitPipTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterExitPipTest(
    testSpec: FlickerTestParameter
) : PipTransition(testSpec) {
    private val testApp = FixedAppHelper(instrumentation)
    private val displayBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = buildTransition(eachRun = true) {
            setup {
                eachRun {
                    testApp.launchViaIntent(wmHelper)
                }
            }
            transitions {
                // This will bring PipApp to fullscreen
                pipApp.launchViaIntent(wmHelper)
            }
        }

    @Presubmit
    @Test
    fun pipAppRemainInsideVisibleBounds() {
        testSpec.assertWm {
            coversAtMost(displayBounds, pipApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    fun showBothAppWindowsThenHidePip() {
        testSpec.assertWm {
            showsAppWindow(testApp.defaultWindowName)
                .showsAppWindowOnTop(pipApp.defaultWindowName)
                .then()
                .hidesAppWindow(testApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun showBothAppLayersThenHidePip() {
        testSpec.assertLayers {
            isVisible(testApp.defaultWindowName)
                .isVisible(pipApp.defaultWindowName)
                .then()
                .isInvisible(testApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    fun testAppCoversFullScreenWithPipOnDisplay() {
        testSpec.assertLayersStart {
            coversExactly(displayBounds, testApp.defaultWindowName)
            coversAtMost(displayBounds, pipApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    fun pipAppCoversFullScreen() {
        testSpec.assertLayersEnd {
            coversExactly(displayBounds, pipApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsAlwaysVisible()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                supportedRotations = listOf(Surface.ROTATION_0), repetitions = 5)
        }
    }
}
