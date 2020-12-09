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

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusChanges
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.wallpaperWindowBecomesInvisible
import com.android.server.wm.flicker.appLayerReplacesWallpaperLayer
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test warm launch app.
 * To run this test: `atest FlickerTests:OpenAppWarmTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppWarmTest(
    testName: String,
    flickerSpec: Flicker
) : FlickerTestRunner(testName, flickerSpec) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testApp = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")
            return FlickerTestRunnerFactory(instrumentation)
                .buildTest { configuration ->
                    withTag { buildTestTag("openAppWarm", testApp, configuration) }
                    repeat { configuration.repetitions }
                    setup {
                        test {
                            device.wakeUpAndGoToHomeScreen()
                            testApp.open()
                        }
                        eachRun {
                            device.pressHome()
                            this.setRotation(configuration.startRotation)
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
                            visibleWindowsShownMoreThanOneConsecutiveEntry()

                            appWindowReplacesLauncherAsTopWindow(testApp)
                            wallpaperWindowBecomesInvisible()
                        }

                        layersTrace {
                            // During testing the launcher is always in portrait mode
                            noUncoveredRegions(Surface.ROTATION_0, configuration.endRotation,
                                bugId = 141361128)
                            navBarLayerRotatesAndScales(Surface.ROTATION_0,
                                configuration.endRotation)
                            statusBarLayerRotatesScales(Surface.ROTATION_0,
                                configuration.endRotation)
                            navBarLayerIsAlwaysVisible(enabled = false)
                            statusBarLayerIsAlwaysVisible(enabled = false)
                            visibleLayersShownMoreThanOneConsecutiveEntry(
                                    enabled = Surface.ROTATION_0 == configuration.endRotation)

                            appLayerReplacesWallpaperLayer(testApp.`package`)
                        }

                        eventLog {
                            focusChanges("NexusLauncherActivity", testApp.`package`)
                        }
                    }
                }
        }
    }
}