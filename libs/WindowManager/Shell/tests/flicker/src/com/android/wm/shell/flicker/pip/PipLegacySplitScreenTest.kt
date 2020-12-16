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
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.runFlicker
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.isInSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.helpers.ImeAppHelper
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import com.android.wm.shell.flicker.helpers.PipAppHelper
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.testapp.Components.PipActivity.EXTRA_ENTER_PIP
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip with split-screen.
 * To run this test: `atest WMShellFlickerTests:PipSplitScreenTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 161435597)
class PipLegacySplitScreenTest(
    rotationName: String,
    rotation: Int
) : AppTestBase(rotationName, rotation) {
    private val pipApp = PipAppHelper(instrumentation)
    private val imeApp = ImeAppHelper(instrumentation)
    private val testApp = FixedAppHelper(instrumentation)

    @Test
    fun testShowsPipLaunchingToSplitScreen() {
        runFlicker(instrumentation) {
            withTestName { "testShowsPipLaunchingToSplitScreen" }
            repeat { TEST_REPETITIONS }
            setup {
                test {
                    removeAllTasksButHome()
                    device.wakeUpAndGoToHomeScreen()
                    pipApp.launchViaIntent(stringExtras = mapOf(EXTRA_ENTER_PIP to "true"))
                    waitForAnimationComplete()
                }
            }
            transitions {
                testApp.launchViaIntent()
                device.launchSplitScreen()
                imeApp.launchViaIntent()
                waitForAnimationComplete()
            }
            teardown {
                eachRun {
                    imeApp.exit()
                    if (device.isInSplitScreen()) {
                        device.exitSplitScreen()
                    }
                    testApp.exit()
                }
                test {
                    removeAllTasksButHome()
                }
            }
            assertions {
                val displayBounds = WindowUtils.getDisplayBounds(rotation)
                windowManagerTrace {
                    all("PIP window must remain inside visible bounds") {
                        coversAtMostRegion(pipApp.defaultWindowName, displayBounds)
                    }
                    end("Both app windows should be visible") {
                        showsAppWindow(testApp.defaultWindowName)
                        showsAppWindow(imeApp.defaultWindowName)
                        noWindowsOverlap(testApp.defaultWindowName, imeApp.defaultWindowName)
                    }
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                }
                layersTrace {
                    all("PIP layer must remain inside visible bounds") {
                        coversAtMostRegion(displayBounds, pipApp.defaultWindowName)
                    }
                    end("Both app layers should be visible") {
                        coversAtMostRegion(displayBounds, testApp.defaultWindowName)
                        coversAtMostRegion(displayBounds, imeApp.defaultWindowName)
                    }
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                }
            }
        }
    }

    companion object {
        const val TEST_REPETITIONS = 2
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}