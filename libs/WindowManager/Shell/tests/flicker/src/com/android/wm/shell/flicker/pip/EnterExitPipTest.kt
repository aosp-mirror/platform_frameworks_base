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

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.wm.shell.flicker.helpers.FixedAppHelper
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
@Group3
class EnterExitPipTest(
    testSpec: FlickerTestParameter
) : PipTransition(testSpec) {
    private val testApp = FixedAppHelper(instrumentation)

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

    @Postsubmit
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    @Postsubmit
    @Test
    override fun statusBarLayerIsVisible() = super.statusBarLayerIsVisible()

    @Presubmit
    @Test
    fun pipAppRemainInsideVisibleBounds() {
        testSpec.assertWm {
            coversAtMost(displayBounds, pipApp.component)
        }
    }

    @Presubmit
    @Test
    fun showBothAppWindowsThenHidePip() {
        testSpec.assertWm {
            // when the activity is STOPPING, sometimes it becomes invisible in an entry before
            // the window, sometimes in the same entry. This occurs because we log 1x per frame
            // thus we ignore activity here
            isAppWindowVisible(testApp.component, ignoreActivity = true)
                .isAppWindowOnTop(pipApp.component)
                .then()
                .isAppWindowInvisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    fun showBothAppLayersThenHidePip() {
        testSpec.assertLayers {
            isVisible(testApp.component)
                .isVisible(pipApp.component)
                .then()
                .isInvisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    fun testAppCoversFullScreenWithPipOnDisplay() {
        testSpec.assertLayersStart {
            visibleRegion(testApp.component).coversExactly(displayBounds)
            visibleRegion(pipApp.component).coversAtMost(displayBounds)
        }
    }

    @Presubmit
    @Test
    fun pipAppCoversFullScreen() {
        testSpec.assertLayersEnd {
            visibleRegion(pipApp.component).coversExactly(displayBounds)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                supportedRotations = listOf(Surface.ROTATION_0), repetitions = 5)
        }
    }
}
