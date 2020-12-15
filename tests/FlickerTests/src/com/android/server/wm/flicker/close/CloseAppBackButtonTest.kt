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

package com.android.server.wm.flicker.close

import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.launcherReplacesAppWindowAsTopWindow
import com.android.server.wm.flicker.wallpaperWindowBecomesVisible
import com.android.server.wm.flicker.wallpaperLayerReplacesAppLayer
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.isRotated
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test app closes by pressing back button
 * To run this test: `atest FlickerTests:CloseAppBackButtonTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CloseAppBackButtonTest(
    testName: String,
    flickerProvider: () -> Flicker,
    cleanUp: Boolean
) : FlickerTestRunner(testName, flickerProvider, cleanUp) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testApp = SimpleAppHelper(instrumentation)
            return FlickerTestRunnerFactory(instrumentation, repetitions = 5)
                .buildTest { configuration ->
                    withTestName { buildTestTag("closeAppBackButton", configuration) }
                    repeat { configuration.repetitions }
                    setup {
                        test {
                            device.wakeUpAndGoToHomeScreen()
                        }
                        eachRun {
                            this.setRotation(configuration.startRotation)
                            testApp.launchViaIntent(wmHelper)
                        }
                    }
                    transitions {
                        device.pressBack()
                        wmHelper.waitForHomeActivityVisible()
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
                            visibleWindowsShownMoreThanOneConsecutiveEntry(bugId = 174635878)

                            launcherReplacesAppWindowAsTopWindow(testApp)
                            wallpaperWindowBecomesVisible()
                        }

                        layersTrace {
                            noUncoveredRegions(configuration.startRotation,
                                Surface.ROTATION_0)
                            navBarLayerRotatesAndScales(configuration.startRotation,
                                Surface.ROTATION_0,
                                enabled = !configuration.startRotation.isRotated())
                            statusBarLayerRotatesScales(configuration.startRotation,
                                Surface.ROTATION_0,
                                enabled = !configuration.startRotation.isRotated())
                            navBarLayerIsAlwaysVisible()
                            statusBarLayerIsAlwaysVisible()
                            visibleLayersShownMoreThanOneConsecutiveEntry(bugId = 174635878)

                            wallpaperLayerReplacesAppLayer(testApp)
                        }
                    }
                }
        }
    }
}