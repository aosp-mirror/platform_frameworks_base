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
import androidx.test.filters.RequiresDevice
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.google.common.truth.Truth
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip movement with Launcher shelf height change.
 * To run this test: `atest WMShellFlickerTests:PipShelfHeightTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group3
class PipShelfHeightTest(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    private val taplInstrumentation = LauncherInstrumentation()
    private val testApp = FixedAppHelper(instrumentation)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = buildTransition(eachRun = false) {
            teardown {
                eachRun {
                    taplInstrumentation.pressHome()
                }
                test {
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                testApp.launchViaIntent(wmHelper)
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
    fun pipAlwaysVisible() = testSpec.assertWm { this.showsAppWindow(pipApp.windowName) }

    @Presubmit
    @Test
    fun pipLayerInsideDisplay() {
        testSpec.assertLayersStart {
            visibleRegion(pipApp.defaultWindowName).coversAtMost(displayBounds)
        }
    }

    @Presubmit
    @Test
    fun pipWindowMovesUp() = testSpec.assertWmEnd {
        val initialState = this.trace?.first()?.wmState
            ?: error("Trace should not be empty")
        val startPos = initialState.pinnedWindows.first().frame
        val currPos = this.wmState.pinnedWindows.first().frame
        val subject = Truth.assertWithMessage("Pip should have moved up")
        subject.that(currPos.top).isGreaterThan(startPos.top)
        subject.that(currPos.bottom).isGreaterThan(startPos.bottom)
        subject.that(currPos.left).isEqualTo(startPos.left)
        subject.that(currPos.right).isEqualTo(startPos.right)
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
