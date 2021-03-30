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
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.helpers.ImeAppHelper
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.server.wm.flicker.repetitions
import com.android.wm.shell.flicker.removeAllTasksButHome
import com.android.wm.shell.flicker.testapp.Components.PipActivity.EXTRA_ENTER_PIP
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip with split-screen.
 * To run this test: `atest WMShellFlickerTests:PipLegacySplitScreenTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 161435597)
class PipLegacySplitScreenTest(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    private val imeApp = ImeAppHelper(instrumentation)
    private val testApp = FixedAppHelper(instrumentation)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = {
            withTestName { testSpec.name }
            repeat { testSpec.config.repetitions }
            setup {
                test {
                    removeAllTasksButHome()
                    device.wakeUpAndGoToHomeScreen()
                    pipApp.launchViaIntent(stringExtras = mapOf(EXTRA_ENTER_PIP to "true"),
                        wmHelper = wmHelper)
                }
            }
            transitions {
                testApp.launchViaIntent(wmHelper)
                device.launchSplitScreen(wmHelper)
                imeApp.launchViaIntent(wmHelper)
            }
            teardown {
                eachRun {
                    imeApp.exit(wmHelper)
                    testApp.exit(wmHelper)
                }
                test {
                    removeAllTasksButHome()
                }
            }
        }

    @Presubmit
    @Test
    fun pipWindowInsideDisplayBounds() {
        testSpec.assertWm {
            coversAtMost(displayBounds, pipApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    fun bothAppWindowsVisible() {
        testSpec.assertWmEnd {
            isVisible(testApp.defaultWindowName)
            isVisible(imeApp.defaultWindowName)
            noWindowsOverlap(testApp.defaultWindowName, imeApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun pipLayerInsideDisplayBounds() {
        testSpec.assertLayers {
            coversAtMost(displayBounds, pipApp.defaultWindowName)
        }
    }

    @Presubmit
    @Test
    fun bothAppLayersVisible() {
        testSpec.assertLayersEnd {
            visibleRegion(testApp.defaultWindowName).coversAtMost(displayBounds)
            visibleRegion(imeApp.defaultWindowName).coversAtMost(displayBounds)
        }
    }

    @Presubmit
    @Test
    override fun navBarLayerIsAlwaysVisible() = super.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    override fun statusBarLayerIsAlwaysVisible() = super.statusBarLayerIsAlwaysVisible()

    companion object {
        const val TEST_REPETITIONS = 2

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                supportedRotations = listOf(Surface.ROTATION_0),
                repetitions = TEST_REPETITIONS
            )
        }
    }
}
