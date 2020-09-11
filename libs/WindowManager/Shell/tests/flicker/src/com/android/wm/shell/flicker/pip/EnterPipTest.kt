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
import com.android.server.wm.flicker.dsl.flicker
import com.android.server.wm.flicker.helpers.closePipWindow
import com.android.server.wm.flicker.helpers.expandPipWindow
import com.android.server.wm.flicker.helpers.hasPipWindow
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.navBarLayerIsAlwaysVisible
import com.android.wm.shell.flicker.navBarLayerRotatesAndScales
import com.android.wm.shell.flicker.navBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.noUncoveredRegions
import com.android.wm.shell.flicker.statusBarLayerIsAlwaysVisible
import com.android.wm.shell.flicker.statusBarLayerRotatesScales
import com.android.wm.shell.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest FlickerTests:PipToAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 152738416)
class EnterPipTest(
    rotationName: String,
    rotation: Int
) : PipTestBase(rotationName, rotation) {
    @Test
    fun test() {
        flicker(instrumentation) {
            withTag { buildTestTag("enterPip", testApp, rotation) }
            repeat { 1 }
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                }
                eachRun {
                    device.pressHome()
                    testApp.open()
                    this.setRotation(rotation)
                }
            }
            teardown {
                eachRun {
                    if (device.hasPipWindow()) {
                        device.closePipWindow()
                    }
                    testApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
                test {
                    if (device.hasPipWindow()) {
                        device.closePipWindow()
                    }
                }
            }
            transitions {
                testApp.clickEnterPipButton(device)
                device.expandPipWindow()
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                    all("pipWindowBecomesVisible") {
                        this.showsAppWindow(testApp.`package`)
                                .then()
                                .showsAppWindow(sPipWindowTitle)
                    }
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                    noUncoveredRegions(rotation, Surface.ROTATION_0, allStates = false)
                    navBarLayerRotatesAndScales(rotation, Surface.ROTATION_0)
                    statusBarLayerRotatesScales(rotation, Surface.ROTATION_0)

                    all("pipLayerBecomesVisible") {
                        this.showsLayer(testApp.launcherName)
                                .then()
                                .showsLayer(sPipWindowTitle)
                    }
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
