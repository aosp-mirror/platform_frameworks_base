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

import android.graphics.Region
import android.util.Rational
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.LargeTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.android.server.wm.flicker.FlickerTestBase
import com.android.server.wm.flicker.StandardAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.dsl.flicker
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.clearRecents
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.isInSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.resizeSplitScreen
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
 * Test split screen resizing window transitions.
 * To run this test: `atest FlickerTests:ResizeSplitScreenTest`
 *
 * Currently it runs only in 0 degrees because of b/156100803
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 159096424)
class ResizeSplitScreenTest : FlickerTestBase() {
    @Test
    fun test() {
        val testAppTop = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")
        val testAppBottom = ImeAppHelper(instrumentation)

        flicker(instrumentation) {
            withTag {
                val description = (startRatio.toString().replace("/", "-") + "_to_" +
                        stopRatio.toString().replace("/", "-"))
                buildTestTag("resizeSplitScreen", testAppTop, rotation,
                        rotation, testAppBottom, description)
            }
            repeat { 1 }
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    this.setRotation(rotation)
                    clearRecents(instrumentation)
                    testAppBottom.open()
                    device.pressHome()
                    testAppTop.open()
                    device.waitForIdle()
                    device.launchSplitScreen()
                    val snapshot = device.findObject(By.res(device.launcherPackageName, "snapshot"))
                    snapshot.click()
                    testAppBottom.openIME(device)
                    device.pressBack()
                    device.resizeSplitScreen(startRatio)
                }
            }
            teardown {
                eachRun {
                    device.exitSplitScreen()
                    device.pressHome()
                    testAppTop.exit()
                    testAppBottom.exit()
                }
                test {
                    if (device.isInSplitScreen()) {
                        device.exitSplitScreen()
                    }
                }
            }
            transitions {
                device.resizeSplitScreen(stopRatio)
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()

                    all("topAppWindowIsAlwaysVisible", bugId = 156223549) {
                        this.showsAppWindow(sSimpleActivity)
                    }

                    all("bottomAppWindowIsAlwaysVisible", bugId = 156223549) {
                        this.showsAppWindow(sImeActivity)
                    }
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                    noUncoveredRegions(rotation)
                    navBarLayerRotatesAndScales(rotation)
                    statusBarLayerRotatesScales(rotation)

                    all("topAppLayerIsAlwaysVisible") {
                        this.showsLayer(sSimpleActivity)
                    }

                    all("bottomAppLayerIsAlwaysVisible") {
                        this.showsLayer(sImeActivity)
                    }

                    all("dividerLayerIsAlwaysVisible") {
                        this.showsLayer(DOCKED_STACK_DIVIDER)
                    }

                    start("appsStartingBounds", enabled = false) {
                        val displayBounds = WindowUtils.displayBounds
                        val entry = this.trace.entries.firstOrNull()
                                ?: throw IllegalStateException("Trace is empty")
                        val dividerBounds = entry.getVisibleBounds(DOCKED_STACK_DIVIDER).bounds

                        val topAppBounds = Region(0, 0, dividerBounds.right,
                                dividerBounds.top + WindowUtils.dockedStackDividerInset)
                        val bottomAppBounds = Region(0,
                                dividerBounds.bottom - WindowUtils.dockedStackDividerInset,
                                displayBounds.right,
                                displayBounds.bottom - WindowUtils.navigationBarHeight)
                        this.hasVisibleRegion("SimpleActivity", topAppBounds)
                                .and()
                                .hasVisibleRegion("ImeActivity", bottomAppBounds)
                    }

                    end("appsEndingBounds", enabled = false) {
                        val displayBounds = WindowUtils.displayBounds
                        val entry = this.trace.entries.lastOrNull()
                                ?: throw IllegalStateException("Trace is empty")
                        val dividerBounds = entry.getVisibleBounds(DOCKED_STACK_DIVIDER).bounds

                        val topAppBounds = Region(0, 0, dividerBounds.right,
                                dividerBounds.top + WindowUtils.dockedStackDividerInset)
                        val bottomAppBounds = Region(0,
                                dividerBounds.bottom - WindowUtils.dockedStackDividerInset,
                                displayBounds.right,
                                displayBounds.bottom - WindowUtils.navigationBarHeight)

                        this.hasVisibleRegion(sSimpleActivity, topAppBounds)
                                .and()
                                .hasVisibleRegion(sImeActivity, bottomAppBounds)
                    }
                }
            }
        }
    }

    companion object {
        private const val sSimpleActivity = "SimpleActivity"
        private const val sImeActivity = "ImeActivity"
        private val rotation = Surface.ROTATION_0
        private val startRatio = Rational(1, 3)
        private val stopRatio = Rational(2, 3)
    }
}
