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
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.wallpaperWindowBecomesInvisible
import com.android.server.wm.flicker.appLayerReplacesWallpaperLayer
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window opening transitions.
 * To run this test: `atest FlickerTests:ReOpenImeWindowTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 175027130)
class ReOpenImeWindowTest(
    testName: String,
    flickerSpec: Flicker
) : FlickerTestRunner(testName, flickerSpec) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testApp = ImeAppAutoFocusHelper(instrumentation)
            val testAppComponentName = ActivityOptions.IME_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME

            return FlickerTestRunnerFactory(instrumentation, repetitions = 5)
                    .buildTest { configuration ->
                        withTestName { buildTestTag("reOpenImeAutoFocus", testApp, configuration) }
                        repeat { configuration.repetitions }
                        setup {
                            test {
                                device.wakeUpAndGoToHomeScreen()
                            }
                            eachRun {
                                testApp.open()
                                testApp.openIME(device)
                                device.pressHome()
                                device.pressRecentApps()
                                this.setRotation(configuration.startRotation)
                            }
                        }
                        transitions {
                            device.reopenAppFromOverview()
                            WindowManagerStateHelper().waitForFullScreenApp(testAppComponentName)
                        }
                        teardown {
                            eachRun {
                                testApp.closeIME(device)
                                testApp.exit()
                            }
                            test {
                                this.setRotation(Surface.ROTATION_0)
                            }
                        }
                        assertions {
                            windowManagerTrace {
                                navBarWindowIsAlwaysVisible()
                                statusBarWindowIsAlwaysVisible()
                                visibleWindowsShownMoreThanOneConsecutiveEntry()

                                imeWindowBecomesVisible()
                                imeAppWindowBecomesVisible(testAppComponentName.className)
                                wallpaperWindowBecomesInvisible()
                            }

                            layersTrace {
                                noUncoveredRegions(Surface.ROTATION_0, configuration.endRotation,
                                        bugId = 141361128)
                                navBarLayerRotatesAndScales(Surface.ROTATION_0,
                                        configuration.endRotation)
                                statusBarLayerRotatesScales(Surface.ROTATION_0,
                                        configuration.endRotation)
                                statusBarLayerIsAlwaysVisible(
                                        enabled = Surface.ROTATION_0 == configuration.endRotation)
                                navBarLayerIsAlwaysVisible(
                                        enabled = Surface.ROTATION_0 == configuration.endRotation)
                                visibleLayersShownMoreThanOneConsecutiveEntry(
                                        enabled = Surface.ROTATION_0 == configuration.endRotation)

                                imeLayerBecomesVisible()
                                appLayerReplacesWallpaperLayer(testAppComponentName.className)
                            }
                        }
                    }
        }
    }
}