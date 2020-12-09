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
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.helpers.hasWindow
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
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

            return FlickerTestRunnerFactory(instrumentation, repetitions = 10)
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
                            device.hasWindow(testApp.getPackage())
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
                    }
        }
    }
}