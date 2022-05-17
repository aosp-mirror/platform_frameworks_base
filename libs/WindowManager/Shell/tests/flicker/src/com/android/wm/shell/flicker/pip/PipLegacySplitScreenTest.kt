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
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule.Companion.removeAllTasksButHome
import com.android.wm.shell.flicker.helpers.BaseAppHelper.Companion.isShellTransitionsEnabled
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.wm.shell.flicker.helpers.ImeAppHelper
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import com.android.wm.shell.flicker.testapp.Components.PipActivity.EXTRA_ENTER_PIP
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
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
@Group4
class PipLegacySplitScreenTest(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    private val imeApp = ImeAppHelper(instrumentation)
    private val testApp = FixedAppHelper(instrumentation)

    @Before
    open fun setup() {
        // Only run legacy split tests when the system is using legacy split screen.
        assumeTrue(SplitScreenHelper.isUsingLegacySplit())
        // Legacy split is having some issue with Shell transition, and will be deprecated soon.
        assumeFalse(isShellTransitionsEnabled())
    }

    override val transition: FlickerBuilder.() -> Unit
        get() = {
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

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 206753786)
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    @FlakyTest(bugId = 161435597)
    @Test
    fun pipWindowInsideDisplayBounds() {
        testSpec.assertWmVisibleRegion(pipApp.component) {
            coversAtMost(displayBounds)
        }
    }

    @Presubmit
    @Test
    fun bothAppWindowsVisible() {
        testSpec.assertWmEnd {
            isAppWindowVisible(testApp.component)
            isAppWindowVisible(imeApp.component)
            doNotOverlap(testApp.component, imeApp.component)
        }
    }

    @FlakyTest(bugId = 161435597)
    @Test
    fun pipLayerInsideDisplayBounds() {
        testSpec.assertLayersVisibleRegion(pipApp.component) {
            coversAtMost(displayBounds)
        }
    }

    @Presubmit
    @Test
    fun bothAppLayersVisible() {
        testSpec.assertLayersEnd {
            visibleRegion(testApp.component).coversAtMost(displayBounds)
            visibleRegion(imeApp.component).coversAtMost(displayBounds)
        }
    }

    @FlakyTest(bugId = 161435597)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

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
