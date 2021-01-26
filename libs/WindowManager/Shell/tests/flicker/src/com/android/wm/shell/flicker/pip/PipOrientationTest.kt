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

import android.content.Intent
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.runFlicker
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.wm.shell.flicker.helpers.PipAppHelper
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.testapp.Components.PipActivity.ACTION_ENTER_PIP
import com.android.wm.shell.flicker.testapp.Components.PipActivity.ACTION_SET_REQUESTED_ORIENTATION
import com.android.wm.shell.flicker.testapp.Components.PipActivity.EXTRA_ENTER_PIP
import com.android.wm.shell.flicker.testapp.Components.PipActivity.EXTRA_PIP_ORIENTATION
import com.android.wm.shell.flicker.testapp.Components.FixedActivity.EXTRA_FIXED_ORIENTATION
import org.junit.Assert.assertEquals
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
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipOrientationTest(
    rotationName: String,
    rotation: Int
) : AppTestBase(rotationName, rotation) {
    // Helper class to process test actions by broadcast.
    private inner class BroadcastActionTrigger {
        private fun createIntentWithAction(broadcastAction: String): Intent {
            return Intent(broadcastAction).setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        fun doAction(broadcastAction: String) {
            instrumentation.getContext().sendBroadcast(createIntentWithAction(broadcastAction))
        }
        fun requestOrientationForPip(orientation: Int) {
            instrumentation.getContext()
                    .sendBroadcast(createIntentWithAction(ACTION_SET_REQUESTED_ORIENTATION)
                    .putExtra(EXTRA_PIP_ORIENTATION, orientation.toString()))
        }
    }
    private val broadcastActionTrigger = BroadcastActionTrigger()

    // Corresponds to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    private val ORIENTATION_LANDSCAPE = 0
    // Corresponds to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private val ORIENTATION_PORTRAIT = 1

    private val testApp = FixedAppHelper(instrumentation)
    private val pipApp = PipAppHelper(instrumentation)

    @Test
    fun testEnterPipToOtherOrientation() {
        runFlicker(instrumentation) {
            withTestName { "testEnterPipToOtherOrientation" }
            setup {
                test {
                    removeAllTasksButHome()
                    device.wakeUpAndGoToHomeScreen()
                    // Launch a portrait only app on the fullscreen stack
                    testApp.launchViaIntent(stringExtras = mapOf(
                            EXTRA_FIXED_ORIENTATION to ORIENTATION_PORTRAIT.toString()))
                    waitForAnimationComplete()
                    // Launch the PiP activity fixed as landscape
                    pipApp.launchViaIntent(stringExtras = mapOf(
                            EXTRA_FIXED_ORIENTATION to ORIENTATION_LANDSCAPE.toString()))
                    waitForAnimationComplete()
                }
            }
            transitions {
                // Enter PiP, and assert that the PiP is within bounds now that the device is back
                // in portrait
                broadcastActionTrigger.doAction(ACTION_ENTER_PIP)
                waitForAnimationComplete()
            }
            teardown {
                test {
                    removeAllTasksButHome()
                }
            }
            assertions {
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
                    val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)
                    val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)
                    start("pipApp layer hides testApp") {
                        hasVisibleRegion(pipApp.defaultWindowName, startingBounds)
                        isInvisible(testApp.defaultWindowName)
                    }
                    end("testApp layer covers fullscreen") {
                        hasVisibleRegion(testApp.defaultWindowName, endingBounds)
                    }
                    navBarLayerIsAlwaysVisible(bugId = 140855415)
                    statusBarLayerIsAlwaysVisible(bugId = 140855415)
                }
            }
        }
    }

    @Test
    fun testSetRequestedOrientationWhilePinned() {
        runFlicker(instrumentation) {
            withTestName { "testSetRequestedOrientationWhilePinned" }
            setup {
                test {
                    removeAllTasksButHome()
                    device.wakeUpAndGoToHomeScreen()
                    // Launch the PiP activity fixed as landscape
                    pipApp.launchViaIntent(stringExtras = mapOf(
                            EXTRA_FIXED_ORIENTATION to ORIENTATION_LANDSCAPE.toString(),
                            EXTRA_ENTER_PIP to "true"))
                    waitForAnimationComplete()
                    assertEquals(Surface.ROTATION_0, device.displayRotation)
                }
            }
            transitions {
                // Request that the orientation is set to landscape
                broadcastActionTrigger.requestOrientationForPip(ORIENTATION_LANDSCAPE)

                // Launch the activity back into fullscreen and ensure that it is now in landscape
                pipApp.launchViaIntent()
                waitForAnimationComplete()
                assertEquals(Surface.ROTATION_90, device.displayRotation)
            }
            teardown {
                test {
                    removeAllTasksButHome()
                }
            }
            assertions {
                val startingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_0)
                val endingBounds = WindowUtils.getDisplayBounds(Surface.ROTATION_90)
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
                    navBarLayerIsAlwaysVisible(bugId = 140855415)
                    statusBarLayerIsAlwaysVisible(bugId = 140855415)
                }
            }
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}