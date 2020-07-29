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

package com.android.server.wm.flicker.splitscreen

import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.FlickerTestBase
import com.android.server.wm.flicker.StandardAppHelper
import com.android.server.wm.flicker.dsl.flicker
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.isInSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
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

/**
 * Test open app to split screen.
 * To run this test: `atest FlickerTests:SplitScreenToLauncherTest`
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SplitScreenToLauncherTest : FlickerTestBase() {
    private val rotation: Int = Surface.ROTATION_0
    @Test
    fun test() {
        val testApp = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")

        flicker(instrumentation) {
            withTag { buildTestTag("splitScreenToLauncher", testApp, rotation) }
            repeat { 1 }
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    testApp.open()
                    this.setRotation(rotation)
                    device.launchSplitScreen()
                    device.waitForIdle()
                }
            }
            teardown {
                eachRun {
                    testApp.exit()
                }
                test {
                    if (device.isInSplitScreen()) {
                        device.exitSplitScreen()
                    }
                }
            }
            transitions {
                device.exitSplitScreen()
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                    noUncoveredRegions(rotation)
                    navBarLayerRotatesAndScales(rotation)
                    statusBarLayerRotatesScales(rotation)

                    // b/161435597 causes the test not to work on 90 degrees
                    all("dividerLayerBecomesInvisible") {
                        this.showsLayer(DOCKED_STACK_DIVIDER)
                                .then()
                                .hidesLayer(DOCKED_STACK_DIVIDER)
                    }

                    all("appLayerBecomesInvisible") {
                        this.showsLayer(testApp.getPackage())
                            .then()
                            .hidesLayer(testApp.getPackage())
                    }
                }
            }
        }
    }
}
