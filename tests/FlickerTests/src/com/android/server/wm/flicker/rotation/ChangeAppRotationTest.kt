/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm.flicker.rotation

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusDoesNotChange
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Cycle through supported app rotations.
 * To run this test: `atest FlickerTests:ChangeAppRotationTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChangeAppRotationTest(
    testName: String,
    flickerSpec: Flicker
) : FlickerTestRunner(testName, flickerSpec) {
    companion object {
        private const val SCREENSHOT_LAYER = "RotationLayer"

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testApp = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")
            return FlickerTestRunnerFactory(instrumentation)
                .buildRotationTest { configuration ->
                    withTestName {
                        buildTestTag(
                            "changeAppRotation", testApp, configuration)
                    }
                    repeat { configuration.repetitions }
                    setup {
                        test {
                            device.wakeUpAndGoToHomeScreen()
                            testApp.open()
                        }
                        eachRun {
                            this.setRotation(configuration.startRotation)
                        }
                    }
                    teardown {
                        eachRun {
                            this.setRotation(Surface.ROTATION_0)
                        }
                        test {
                            testApp.exit()
                        }
                    }
                    transitions {
                        this.setRotation(configuration.endRotation)
                    }
                    assertions {
                        windowManagerTrace {
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                            visibleWindowsShownMoreThanOneConsecutiveEntry()
                        }

                        layersTrace {
                            navBarLayerIsAlwaysVisible(bugId = 140855415)
                            statusBarLayerIsAlwaysVisible(bugId = 140855415)
                            noUncoveredRegions(configuration.startRotation,
                                configuration.endRotation, allStates = false)
                            navBarLayerRotatesAndScales(configuration.startRotation,
                                configuration.endRotation)
                            statusBarLayerRotatesScales(configuration.startRotation,
                                configuration.endRotation)
                            visibleLayersShownMoreThanOneConsecutiveEntry(bugId = 140855415)
                        }

                        layersTrace {
                            val startingPos = WindowUtils.getDisplayBounds(
                                configuration.startRotation)
                            val endingPos = WindowUtils.getDisplayBounds(
                                configuration.endRotation)

                            start("appLayerRotates_StartingPos") {
                                this.hasVisibleRegion(testApp.getPackage(), startingPos)
                            }

                            end("appLayerRotates_EndingPos") {
                                this.hasVisibleRegion(testApp.getPackage(), endingPos)
                            }

                            all("screenshotLayerBecomesInvisible") {
                                this.showsLayer(testApp.getPackage())
                                        .then()
                                        .showsLayer(SCREENSHOT_LAYER)
                                        .then()
                                        .showsLayer(testApp.getPackage())
                            }
                        }

                        eventLog {
                            focusDoesNotChange(bugId = 151179149)
                        }
                    }
                }
        }
    }
}