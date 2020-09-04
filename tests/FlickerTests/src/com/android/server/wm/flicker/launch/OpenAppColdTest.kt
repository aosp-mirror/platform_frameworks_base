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
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.flicker
import com.android.server.wm.flicker.focusChanges
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
 * Test cold launch app from launcher.
 * To run this test: `atest FlickerTests:OpenAppColdTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppColdTest(
    rotationName: String,
    rotation: Int
) : OpenAppTestBase(rotationName, rotation) {
    @Test
    fun test() {
        flicker(instrumentation) {
            withTag { buildTestTag("openAppCold", testApp, rotation) }
            repeat { 1 }
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                }
                eachRun {
                    this.setRotation(rotation)
                }
            }
            transitions {
                testApp.open()
            }
            teardown {
                eachRun {
                    testApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                    appWindowReplacesLauncherAsTopWindow()
                    wallpaperWindowBecomesInvisible()
                }

                layersTrace {
                    // During testing the launcher is always in portrait mode
                    noUncoveredRegions(Surface.ROTATION_0, rotation, bugId = 141361128)
                    navBarLayerRotatesAndScales(Surface.ROTATION_0, rotation)
                    statusBarLayerRotatesScales(Surface.ROTATION_0, rotation)
                    navBarLayerIsAlwaysVisible(enabled = rotation == Surface.ROTATION_0)
                    statusBarLayerIsAlwaysVisible(enabled = false)
                    wallpaperLayerBecomesInvisible()
                }

                eventLog {
                    focusChanges("NexusLauncherActivity", testApp.`package`)
                }
            }
        }
    }
}