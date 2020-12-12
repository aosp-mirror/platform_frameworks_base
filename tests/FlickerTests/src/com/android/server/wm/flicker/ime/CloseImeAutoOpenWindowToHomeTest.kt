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

package com.android.server.wm.flicker.ime

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing back to app window transitions.
 * To run this test: `atest FlickerTests:CloseImeAutoOpenWindowToHomeTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CloseImeAutoOpenWindowToHomeTest(
    testName: String,
    flickerSpec: Flicker
) : FlickerTestRunner(testName, flickerSpec) {

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testApp = ImeAppAutoFocusHelper(instrumentation)

            return FlickerTestRunnerFactory(instrumentation)
                .buildTest { configuration ->
                    withTestName {
                        buildTestTag("imeToHomeAutoOpen", testApp, configuration)
                    }
                    repeat { configuration.repetitions }
                    setup {
                        test {
                            device.wakeUpAndGoToHomeScreen()
                        }
                        eachRun {
                            this.setRotation(configuration.startRotation)
                            testApp.open()
                            testApp.openIME(device)
                        }
                    }
                    teardown {
                        eachRun {
                            testApp.exit()
                            this.setRotation(Surface.ROTATION_0)
                        }
                    }
                    transitions {
                        device.pressHome()
                        device.waitForIdle()
                    }
                    assertions {
                        windowManagerTrace {
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                            visibleWindowsShownMoreThanOneConsecutiveEntry(listOf(IME_WINDOW_TITLE))

                            imeWindowBecomesInvisible(bugId = 141458352)
                            imeAppWindowBecomesInvisible(testApp, bugId = 157449248)
                        }

                        layersTrace {
                            noUncoveredRegions(configuration.startRotation, Surface.ROTATION_0,
                                allStates = false)
                            navBarLayerRotatesAndScales(configuration.startRotation,
                                Surface.ROTATION_0, bugId = 140855415)
                            statusBarLayerRotatesScales(configuration.startRotation,
                                Surface.ROTATION_0)
                            navBarLayerIsAlwaysVisible(
                                    enabled = Surface.ROTATION_0 == configuration.startRotation)
                            statusBarLayerIsAlwaysVisible(
                                    enabled = Surface.ROTATION_0 == configuration.startRotation)
                            visibleLayersShownMoreThanOneConsecutiveEntry(listOf(IME_WINDOW_TITLE))

                            imeLayerBecomesInvisible(bugId = 141458352)
                            imeAppLayerBecomesInvisible(testApp)
                        }
                    }
                }
        }
    }
}
