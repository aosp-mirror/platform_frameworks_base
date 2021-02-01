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
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.isRotated
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
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReOpenImeWindowTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testAppComponentName = ActivityOptions.IME_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME
            return FlickerTestRunnerFactory.getInstance()
                .buildTest(instrumentation, repetitions = 1) { configuration ->
                        val testApp = ImeAppAutoFocusHelper(instrumentation,
                            configuration.startRotation)
                        withTestName { buildTestTag("reOpenImeAutoFocus", configuration) }
                        repeat { configuration.repetitions }
                        setup {
                            test {
                                device.wakeUpAndGoToHomeScreen()
                                testApp.launchViaIntent(wmHelper)
                                testApp.openIME(device, wmHelper)
                            }
                            eachRun {
                                device.pressRecentApps()
                                wmHelper.waitImeWindowGone()
                                wmHelper.waitForAppTransitionIdle()
                                this.setRotation(configuration.startRotation)
                            }
                        }
                        transitions {
                            device.reopenAppFromOverview()
                            wmHelper.waitImeWindowShown()
                        }
                        teardown {
                            test {
                                this.setRotation(Surface.ROTATION_0)
                                testApp.exit()
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
                                noUncoveredRegions(Surface.ROTATION_0, configuration.endRotation)
                                navBarLayerRotatesAndScales(Surface.ROTATION_0,
                                        configuration.endRotation,
                                        enabled = !configuration.startRotation.isRotated())
                                statusBarLayerRotatesScales(Surface.ROTATION_0,
                                        configuration.endRotation,
                                        enabled = !configuration.startRotation.isRotated())
                                statusBarLayerIsAlwaysVisible()
                                navBarLayerIsAlwaysVisible()
                                visibleLayersShownMoreThanOneConsecutiveEntry(enabled = false)

                                imeLayerBecomesVisible()
                                appLayerReplacesWallpaperLayer(testAppComponentName.className)
                            }
                        }
                    }
        }
    }
}