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

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.closePipWindow
import com.android.server.wm.flicker.helpers.hasPipWindow
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.IME_WINDOW_NAME
import com.android.wm.shell.flicker.helpers.ImeAppHelper
import com.android.wm.shell.flicker.testapp.Components
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest WMShellFlickerTests:PipKeyboardTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipKeyboardTest(
    rotationName: String,
    rotation: Int
) : PipTestBase(rotationName, rotation) {
    private val keyboardApp = ImeAppHelper(instrumentation)
    private val keyboardComponent = Components.ImeActivity().componentName
    private val helper = WindowManagerStateHelper()

    private val keyboardScenario: FlickerBuilder
        get() = FlickerBuilder(instrumentation).apply {
            repeat { TEST_REPETITIONS }
            // disable layer tracing
            withLayerTracing { null }
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    device.pressHome()
                    // launch our target pip app
                    testApp.open()
                    this.setRotation(rotation)
                    testApp.clickEnterPipButton()
                    // open an app with an input field and a keyboard
                    // UiAutomator doesn't support to launch the multiple Activities in a task.
                    // So use launchActivity() for the Keyboard Activity.
                    keyboardApp.launchViaIntent()
                    helper.waitForAppTransitionIdle()
                    helper.waitForFullScreenApp(keyboardComponent)
                }
            }
            teardown {
                test {
                    keyboardApp.exit()

                    if (device.hasPipWindow()) {
                        device.closePipWindow()
                    }
                    testApp.exit()
                    this.setRotation(Surface.ROTATION_0)
                }
            }
        }

    /** Ensure the pip window remains visible throughout any keyboard interactions. */
    @Test
    fun pipWindow_doesNotLeaveTheScreen_onKeyboardOpenClose() {
        val testTag = "pipWindow_doesNotLeaveTheScreen_onKeyboardOpenClose"
        runWithFlicker(keyboardScenario) {
            withTestName { testTag }
            transitions {
                // open the soft keyboard
                keyboardApp.openIME()
                helper.waitImeWindowShown()

                // then close it again
                keyboardApp.closeIME()
                helper.waitImeWindowGone()
            }
            assertions {
                windowManagerTrace {
                    all("PiP window must remain inside visible bounds") {
                        val displayBounds = WindowUtils.getDisplayBounds(rotation)
                        coversAtMostRegion(testApp.defaultWindowName, displayBounds)
                    }
                }
            }
        }
    }

    /** Ensure the pip window does not obscure the keyboard. */
    @Test
    fun pipWindow_doesNotObscure_keyboard() {
        val testTag = "pipWindow_doesNotObscure_keyboard"
        runWithFlicker(keyboardScenario) {
            withTestName { testTag }
            transitions {
                // open the soft keyboard
                keyboardApp.openIME()
                helper.waitImeWindowShown()
            }
            teardown {
                eachRun {
                    // close the keyboard
                    keyboardApp.closeIME()
                    helper.waitImeWindowGone()
                }
            }
            assertions {
                windowManagerTrace {
                    end {
                        isAboveWindow(IME_WINDOW_NAME, testApp.defaultWindowName)
                    }
                }
            }
        }
    }

    companion object {
        private const val TEST_REPETITIONS = 10

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}
