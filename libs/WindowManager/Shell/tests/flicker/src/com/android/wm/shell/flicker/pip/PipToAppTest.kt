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

package com.android.wm.shell.flicker.pip

import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.focusChanges
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.closePipWindow
import com.android.server.wm.flicker.helpers.expandPipWindow
import com.android.server.wm.flicker.helpers.hasPipWindow
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
import com.android.wm.shell.flicker.helpers.PipAppHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest WMShellFlickerTests:PipToAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 152738416)
class PipToAppTest(
    testName: String,
    flickerProvider: () -> Flicker,
    cleanUp: Boolean
) : FlickerTestRunner(testName, flickerProvider, cleanUp) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testApp = PipAppHelper(instrumentation)
            return FlickerTestRunnerFactory(instrumentation, listOf(Surface.ROTATION_0))
                .buildTest { configuration ->
                    withTestName { buildTestTag("exitPipModeToApp", testApp, configuration) }
                    repeat { configuration.repetitions }
                    setup {
                        test {
                            device.wakeUpAndGoToHomeScreen()
                            device.pressHome()
                            testApp.launchViaIntent(wmHelper)
                        }
                        eachRun {
                            this.setRotation(configuration.startRotation)
                            testApp.clickEnterPipButton()
                            device.hasPipWindow()
                        }
                    }
                    teardown {
                        eachRun {
                            this.setRotation(Surface.ROTATION_0)
                        }
                        test {
                            if (device.hasPipWindow()) {
                                device.closePipWindow()
                            }
                            testApp.exit()
                        }
                    }
                    transitions {
                        device.expandPipWindow()
                        device.waitForIdle()
                    }
                    assertions {
                        windowManagerTrace {
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()

                            all("appReplacesPipWindow") {
                                this.showsAppWindow(PIP_WINDOW_TITLE)
                                    .then()
                                    .showsAppWindowOnTop(testApp.launcherName)
                            }
                        }

                        layersTrace {
                            navBarLayerIsAlwaysVisible(bugId = 140855415)
                            statusBarLayerIsAlwaysVisible()
                            noUncoveredRegions(configuration.startRotation, Surface.ROTATION_0,
                                enabled = false)
                            navBarLayerRotatesAndScales(configuration.startRotation,
                                Surface.ROTATION_0, bugId = 140855415)
                            statusBarLayerRotatesScales(configuration.startRotation,
                                Surface.ROTATION_0)

                            all("appReplacesPipLayer") {
                                this.showsLayer(PIP_WINDOW_TITLE)
                                    .then()
                                    .showsLayer(testApp.launcherName)
                            }
                        }

                        eventLog {
                            focusChanges(
                                "NexusLauncherActivity", testApp.launcherName,
                                "NexusLauncherActivity", bugId = 151179149)
                        }
                    }
                }
        }
    }
}
