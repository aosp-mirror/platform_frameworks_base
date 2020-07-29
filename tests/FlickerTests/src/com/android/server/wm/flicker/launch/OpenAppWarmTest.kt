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

package com.android.server.wm.flicker.launch

import android.view.Surface
import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.StandardAppHelper
import com.android.server.wm.flicker.dsl.flicker
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
 * Test warm launch app.
 * To run this test: `atest FlickerTests:OpenAppWarmTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppWarmTest(
    rotationName: String,
    rotation: Int
) : OpenAppTestBase(rotationName, rotation) {
    @Test
    fun test() {
        val testApp = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")

        flicker(instrumentation) {
            withTag { buildTestTag("openAppWarm", testApp, rotation) }
            repeat { 1 }
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    testApp.open()
                }
                eachRun {
                    device.pressHome()
                    this.setRotation(rotation)
                }
            }
            transitions {
                testApp.open()
            }
            teardown {
                eachRun {
                    this.setRotation(Surface.ROTATION_0)
                }
                test {
                    testApp.exit()
                }
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                    appWindowReplacesLauncherAsTopWindow(bugId = 141361128)
                    wallpaperWindowBecomesInvisible(enabled = false)
                }

                layersTrace {
                    noUncoveredRegions(rotation, bugId = 141361128)
                    // During testing the launcher is always in portrait mode
                    navBarLayerRotatesAndScales(Surface.ROTATION_0, rotation)
                    statusBarLayerRotatesScales(Surface.ROTATION_0, rotation)
                    navBarLayerIsAlwaysVisible(bugId = 141361128)
                    statusBarLayerIsAlwaysVisible(bugId = 141361128)
                    wallpaperLayerBecomesInvisible(bugId = 141361128)
                }
            }
        }
    }
}