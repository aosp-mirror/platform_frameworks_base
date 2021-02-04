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

package com.android.wm.shell.flicker.legacysplitscreen

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.exitSplitScreenFromBottom
import com.android.server.wm.flicker.helpers.isInSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.openQuickStepAndClearRecentAppsFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.repetitions
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest WMShellFlickerTests:ExitLegacySplitScreenFromBottomTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ExitLegacySplitScreenFromBottomTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val splitScreenApp = SplitScreenHelper.getPrimary(instrumentation)
            // TODO(b/162923992) Use of multiple segments of flicker spec for testing
            return FlickerTestRunnerFactory.getInstance().buildTest(instrumentation,
                supportedRotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90)) {
                configuration ->
                        withTestName {
                            buildTestTag("exitSplitScreenFromBottom", configuration)
                        }
                        repeat { configuration.repetitions }
                        setup {
                            eachRun {
                                device.wakeUpAndGoToHomeScreen()
                                device.openQuickStepAndClearRecentAppsFromOverview()
                                splitScreenApp.launchViaIntent(wmHelper)
                                device.launchSplitScreen()
                                device.waitForIdle()
                                this.setRotation(configuration.endRotation)
                            }
                        }
                        teardown {
                            eachRun {
                                if (device.isInSplitScreen()) {
                                    device.exitSplitScreen()
                                }
                                splitScreenApp.exit()
                            }
                        }
                        transitions {
                            device.exitSplitScreenFromBottom()
                        }
                        assertions {
                            windowManagerTrace {
                                all("isNotEmpty") { isNotEmpty() }
                            }
                        }
                    }
        }
    }
}