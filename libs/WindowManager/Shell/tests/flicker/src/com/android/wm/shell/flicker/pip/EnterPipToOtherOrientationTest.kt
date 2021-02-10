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
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.pip.PipTransitionBase.BroadcastActionTrigger.Companion.ORIENTATION_LANDSCAPE
import com.android.wm.shell.flicker.pip.PipTransitionBase.BroadcastActionTrigger.Companion.ORIENTATION_PORTRAIT
import com.android.wm.shell.flicker.testapp.Components.PipActivity.ACTION_ENTER_PIP
import com.android.wm.shell.flicker.testapp.Components.FixedActivity.EXTRA_FIXED_ORIENTATION
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip with orientation changes.
 * To run this test: `atest WMShellFlickerTests:PipOrientationTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterPipToOtherOrientationTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : PipTransitionBase(InstrumentationRegistry.getInstrumentation()) {
        private val testApp = FixedAppHelper(instrumentation)

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            return FlickerTestRunnerFactory.getInstance().buildTest(instrumentation,
                supportedRotations = listOf(Surface.ROTATION_0),
                repetitions = 5) { configuration ->
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
                        pipApp.exit()
                        testApp.exit()
                    }
                }
                transitions {
                    // Enter PiP, and assert that the PiP is within bounds now that the device is back
                    // in portrait
                    broadcastActionTrigger.doAction(ACTION_ENTER_PIP)
                    wmHelper.waitPipWindowShown()
                    wmHelper.waitForAppTransitionIdle()
                }
                assertions {
                    val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)
                    val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)

                    presubmit {
                        windowManagerTrace {
                            all("pipApp window is always on top") {
                                showsAppWindowOnTop(pipApp.defaultWindowName)
                            }
                            start("pipApp window hides testApp") {
                                isInvisible(testApp.defaultWindowName)
                            }
                            end("testApp windows is shown") {
                                isVisible(testApp.defaultWindowName)
                            }
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                        }

                        layersTrace {
                            start("pipApp layer hides testApp") {
                                hasVisibleRegion(pipApp.defaultWindowName, startingBounds)
                                isInvisible(testApp.defaultWindowName)
                            }
                        }
                    }

                    flaky {
                        layersTrace {
                            end("testApp layer covers fullscreen") {
                                hasVisibleRegion(testApp.defaultWindowName, endingBounds)
                            }
                            navBarLayerIsAlwaysVisible(bugId = 140855415)
                            statusBarLayerIsAlwaysVisible(bugId = 140855415)
                        }
                    }
                }
            }
        }
    }
}