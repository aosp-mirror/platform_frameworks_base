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

import android.os.Bundle
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.startRotation
import com.android.wm.shell.flicker.IME_WINDOW_NAME
import com.android.wm.shell.flicker.helpers.ImeAppHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest WMShellFlickerTests:PipKeyboardTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipKeyboardTest(testSpec: FlickerTestRunnerFactory.TestSpec) : FlickerTestRunner(testSpec) {
    companion object : PipTransitionBase(InstrumentationRegistry.getInstrumentation()) {
        private const val TAG_IME_VISIBLE = "imeIsVisible"

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val imeApp = ImeAppHelper(instrumentation)
            val baseConfig = getTransitionLaunch(eachRun = false)
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                setup {
                    test {
                        imeApp.launchViaIntent(wmHelper)
                        setRotation(configuration.startRotation)
                    }
                }
                teardown {
                    test {
                        imeApp.exit()
                        setRotation(Surface.ROTATION_0)
                    }
                }
                transitions {
                    // open the soft keyboard
                    imeApp.openIME(wmHelper)
                    createTag(TAG_IME_VISIBLE)

                    // then close it again
                    imeApp.closeIME(wmHelper)
                }
                assertions {
                    presubmit {
                        windowManagerTrace {
                            // Ensure the pip window remains visible throughout
                            // any keyboard interactions
                            all("pipInVisibleBounds") {
                                val displayBounds = WindowUtils.getDisplayBounds(
                                    configuration.startRotation)
                                coversAtMostRegion(pipApp.defaultWindowName, displayBounds)
                            }
                            // Ensure that the pip window does not obscure the keyboard
                            tag(TAG_IME_VISIBLE) {
                                isAboveWindow(IME_WINDOW_NAME, pipApp.defaultWindowName)
                            }
                        }
                    }
                }
            }

            return FlickerTestRunnerFactory.getInstance().buildTest(instrumentation,
                baseConfig, testSpec, supportedRotations = listOf(Surface.ROTATION_0),
                repetitions = 5)
        }
    }
}
