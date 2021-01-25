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

import androidx.test.filters.FlakyTest
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.isRotated
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
 * To run this test: `atest FlickerTests:CloseImeAutoOpenWindowToAppTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 178015460)
class CloseImeAutoOpenWindowToAppTest(
    testName: String,
    flickerProvider: () -> Flicker,
    cleanUp: Boolean
) : FlickerTestRunner(testName, flickerProvider, cleanUp) {

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            return FlickerTestRunnerFactory(instrumentation, repetitions = 5)
                .buildTest { configuration ->
                    val testApp = ImeAppAutoFocusHelper(instrumentation,
                        configuration.startRotation)
                    withTestName { buildTestTag("imeToAppAutoOpen", configuration) }
                    repeat { configuration.repetitions }
                    setup {
                        test {
                            device.wakeUpAndGoToHomeScreen()
                        }
                        eachRun {
                            testApp.launchViaIntent(wmHelper)
                            testApp.openIME(device, wmHelper)
                            this.setRotation(configuration.startRotation)
                        }
                    }
                    teardown {
                        test {
                            testApp.exit()
                            wmHelper.waitForAppTransitionIdle()
                            this.setRotation(Surface.ROTATION_0)
                        }
                    }
                    transitions {
                        testApp.closeIME(device, wmHelper)
                    }
                    assertions {
                        windowManagerTrace {
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                            visibleWindowsShownMoreThanOneConsecutiveEntry(listOf("InputMethod"))

                            imeAppWindowIsAlwaysVisible(testApp)
                        }

                        layersTrace {
                            navBarLayerIsAlwaysVisible()
                            statusBarLayerIsAlwaysVisible()
                            noUncoveredRegions(configuration.startRotation)
                            navBarLayerRotatesAndScales(configuration.startRotation,
                                enabled = !configuration.startRotation.isRotated())
                            statusBarLayerRotatesScales(configuration.startRotation,
                                enabled = !configuration.startRotation.isRotated())
                            visibleLayersShownMoreThanOneConsecutiveEntry()

                            imeLayerBecomesInvisible()
                            imeAppLayerIsAlwaysVisible(testApp)
                        }
                    }
                }
        }
    }
}