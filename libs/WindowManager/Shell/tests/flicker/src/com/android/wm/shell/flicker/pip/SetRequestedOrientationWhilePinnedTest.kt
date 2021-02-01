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
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.pip.PipTransitionBase.BroadcastActionTrigger.Companion.ORIENTATION_LANDSCAPE
import com.android.wm.shell.flicker.testapp.Components.FixedActivity.EXTRA_FIXED_ORIENTATION
import com.android.wm.shell.flicker.testapp.Components.PipActivity.EXTRA_ENTER_PIP
import org.junit.Assert.assertEquals
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
class SetRequestedOrientationWhilePinnedTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : PipTransitionBase(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            return FlickerTestRunnerFactory.getInstance().buildTest(instrumentation,
                supportedRotations = listOf(Surface.ROTATION_0),
                repetitions = 1) { configuration ->
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
                        pipApp.exit()
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
                assertions {
                    val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)
                    val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)
                    presubmit {
                        windowManagerTrace {
                            start("PIP window must remain inside display") {
                                coversAtMostRegion(pipApp.defaultWindowName, startingBounds)
                            }
                            end("pipApp shows on top") {
                                showsAppWindowOnTop(pipApp.defaultWindowName)
                            }
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                        }
                        layersTrace {
                            start("PIP layer must remain inside display") {
                                coversAtMostRegion(startingBounds, pipApp.defaultWindowName)
                            }
                            end("pipApp layer covers fullscreen") {
                                hasVisibleRegion(pipApp.defaultWindowName, endingBounds)
                            }
                        }
                    }

                    flaky {
                        layersTrace {
                            navBarLayerIsAlwaysVisible(bugId = 140855415)
                            statusBarLayerIsAlwaysVisible(bugId = 140855415)
                        }
                    }
                }
            }
        }
    }
}