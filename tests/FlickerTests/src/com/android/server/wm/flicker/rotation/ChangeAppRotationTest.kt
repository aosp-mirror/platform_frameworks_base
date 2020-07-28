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

package com.android.server.wm.flicker.rotation

import android.view.Surface
import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.NonRotationTestBase.Companion.SCREENSHOT_LAYER
import com.android.server.wm.flicker.RotationTestBase
import com.android.server.wm.flicker.StandardAppHelper
import com.android.server.wm.flicker.dsl.flicker
import com.android.server.wm.flicker.helpers.WindowUtils
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
 * Cycle through supported app rotations.
 * To run this test: `atest FlickerTest:ChangeAppRotationTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChangeAppRotationTest(
    beginRotationName: String,
    endRotationName: String,
    beginRotation: Int,
    endRotation: Int
) : RotationTestBase(beginRotationName, endRotationName, beginRotation, endRotation) {
    @Test
    fun test() {
        val testApp = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")

        flicker(instrumentation) {
            withTag {
                buildTestTag("changeAppRotation", testApp, beginRotation, endRotation)
            }
            repeat { 1 }
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    testApp.open()
                    this.setRotation(beginRotation)
                }
            }
            teardown {
                eachRun {
                    testApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
            transitions {
                this.setRotation(endRotation)
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible(bugId = 140855415)
                    statusBarWindowIsAlwaysVisible(bugId = 140855415)
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible(bugId = 140855415)
                    statusBarLayerIsAlwaysVisible(bugId = 140855415)
                    noUncoveredRegions(beginRotation, endRotation, allStates = false)
                    navBarLayerRotatesAndScales(beginRotation, endRotation)
                    statusBarLayerRotatesScales(beginRotation, endRotation)
                }

                layersTrace {
                    val startingPos = WindowUtils.getDisplayBounds(beginRotation)
                    val endingPos = WindowUtils.getDisplayBounds(endRotation)

                    start("appLayerRotates_StartingPos") {
                        this.hasVisibleRegion(testApp.getPackage(), startingPos)
                    }

                    end("appLayerRotates_EndingPos") {
                        this.hasVisibleRegion(testApp.getPackage(), endingPos)
                    }

                    all("screenshotLayerBecomesInvisible", enabled = false) {
                        this.showsLayer(testApp.getPackage())
                                .then()
                                .replaceVisibleLayer(
                                        testApp.getPackage(),
                                        SCREENSHOT_LAYER)
                                .then()
                                .showsLayer(testApp.getPackage())
                                .and()
                                .showsLayer(SCREENSHOT_LAYER)
                                .then()
                                .replaceVisibleLayer(
                                        SCREENSHOT_LAYER,
                                        testApp.getPackage()
                                )
                    }
                }
            }
        }
    }
}