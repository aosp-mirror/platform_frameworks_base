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

package com.android.server.wm.flicker.pip

import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.dsl.flicker
import com.android.server.wm.flicker.helpers.closePipWindow
import com.android.server.wm.flicker.helpers.hasPipWindow
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest FlickerTests:PipToHomeTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 152738416)
class PipToHomeTest(
    rotationName: String,
    rotation: Int
) : PipTestBase(rotationName, rotation) {
    @Test
    fun test() {
        flicker(instrumentation) {
            withTag { buildTestTag("exitPipModeToApp", testApp, rotation) }
            repeat { 1 }
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    device.pressHome()
                    this.setRotation(rotation)
                    testApp.open()
                }
            }
            teardown {
                eachRun {
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
                testApp.closePipWindow(device)
                device.waitForIdle()
                testApp.exit()
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                    pipWindowBecomesVisible()

                    all {
                        this.showsAppWindowOnTop(sPipWindowTitle)
                                .and()
                                .showsBelowAppWindow("Wallpaper")
                                .then()
                                .showsAboveAppWindow("Wallpaper")
                    }
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                    noUncoveredRegions(rotation)
                    navBarLayerRotatesAndScales(rotation)
                    statusBarLayerRotatesScales(rotation)
                    pipLayerBecomesVisible()
                }
            }
        }
    }
}