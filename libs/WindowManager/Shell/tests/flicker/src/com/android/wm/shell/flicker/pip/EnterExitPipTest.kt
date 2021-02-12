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

import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch and exit.
 * To run this test: `atest WMShellFlickerTests:EnterExitPipTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterExitPipTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : PipTransitionBase(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val testApp = FixedAppHelper(instrumentation)
            val testSpec = getTransition(eachRun = true) { configuration ->
                setup {
                    eachRun {
                        testApp.launchViaIntent(wmHelper)
                    }
                }
                transitions {
                    // This will bring PipApp to fullscreen
                    pipApp.launchViaIntent(wmHelper)
                }
                assertions {
                    val displayBounds = WindowUtils.getDisplayBounds(configuration.startRotation)
                    presubmit {
                        windowManagerTrace {
                            all("pipApp must remain inside visible bounds") {
                                coversAtMostRegion(pipApp.defaultWindowName, displayBounds)
                            }
                            all("Initially shows both app windows then pipApp hides testApp") {
                                showsAppWindow(testApp.defaultWindowName)
                                    .showsAppWindowOnTop(pipApp.defaultWindowName)
                                    .then()
                                    .hidesAppWindow(testApp.defaultWindowName)
                            }
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                        }
                        layersTrace {
                            all("Initially shows both app layers then pipApp hides testApp") {
                                showsLayer(testApp.defaultWindowName)
                                    .showsLayer(pipApp.defaultWindowName)
                                    .then()
                                    .hidesLayer(testApp.defaultWindowName)
                            }
                            start("testApp covers the fullscreen, pipApp remains inside display") {
                                hasVisibleRegion(testApp.defaultWindowName, displayBounds)
                                coversAtMostRegion(displayBounds, pipApp.defaultWindowName)
                            }
                            end("pipApp covers the fullscreen") {
                                hasVisibleRegion(pipApp.defaultWindowName, displayBounds)
                            }
                            navBarLayerIsAlwaysVisible()
                            statusBarLayerIsAlwaysVisible()
                        }
                    }
                }
            }
            return FlickerTestRunnerFactory.getInstance().buildTest(instrumentation,
                testSpec, supportedRotations = listOf(Surface.ROTATION_0),
                repetitions = 5)
        }
    }
}