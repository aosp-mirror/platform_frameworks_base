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
import com.android.server.wm.flicker.dsl.runFlicker
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
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
 * Test Pip launch and exit.
 * To run this test: `atest WMShellFlickerTests:EnterExitPipTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterExitPipTest(
    rotationName: String,
    rotation: Int
) : AppTestBase(rotationName, rotation) {
    private val pipApp = PipAppHelper(instrumentation)
    private val testApp = FixedAppHelper(instrumentation)

    @Test
    fun testDisplayMetricsPinUnpin() {
        runFlicker(instrumentation) {
            withTestName { "testDisplayMetricsPinUnpin" }
            setup {
                test {
                    removeAllTasksButHome()
                    device.wakeUpAndGoToHomeScreen()
                    pipApp.launchViaIntent(stringExtras = mapOf(EXTRA_ENTER_PIP to "true"))
                    testApp.launchViaIntent()
                    waitForAnimationComplete()
                }
            }
            transitions {
                // This will bring PipApp to fullscreen
                pipApp.launchViaIntent()
                waitForAnimationComplete()
            }
            teardown {
                test {
                    removeAllTasksButHome()
                }
            }
            assertions {
                val displayBounds = WindowUtils.getDisplayBounds(rotation)
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

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}