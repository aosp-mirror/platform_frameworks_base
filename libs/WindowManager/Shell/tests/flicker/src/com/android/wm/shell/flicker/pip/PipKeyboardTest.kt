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

import android.content.ComponentName
import android.graphics.Region
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.test.filters.RequiresDevice
import com.android.compatibility.common.util.SystemUtil
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.closePipWindow
import com.android.server.wm.flicker.helpers.hasPipWindow
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.TEST_APP_IME_ACTIVITY_COMPONENT_NAME
import com.android.wm.shell.flicker.IME_WINDOW_NAME
import com.android.wm.shell.flicker.TEST_APP_PIP_ACTIVITY_WINDOW_NAME
import com.android.wm.shell.flicker.helpers.ImeAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized
import java.io.IOException

/**
 * Test Pip launch.
 * To run this test: `atest WMShellFlickerTests:PipKeyboardTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipKeyboardTest(
    rotationName: String,
    rotation: Int
) : PipTestBase(rotationName, rotation) {
    private val windowManager: WindowManager =
            instrumentation.context.getSystemService(WindowManager::class.java)

    private val keyboardApp = ImeAppHelper(instrumentation)

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
                    launchActivity(TEST_APP_IME_ACTIVITY_COMPONENT_NAME)
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

                // then close it again
                keyboardApp.closeIME()
            }
            assertions {
                windowManagerTrace {
                    all("PiP window must remain inside visible bounds") {
                        coversAtMostRegion(
                                partialWindowTitle = "PipActivity",
                                region = Region(windowManager.maximumWindowMetrics.bounds)
                        )
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
            }
            teardown {
                eachRun {
                    // close the keyboard
                    keyboardApp.closeIME()
                }
            }
            assertions {
                windowManagerTrace {
                    end {
                        isAboveWindow(IME_WINDOW_NAME, TEST_APP_PIP_ACTIVITY_WINDOW_NAME)
                    }
                }
            }
        }
    }

    private fun launchActivity(
        activity: ComponentName? = null,
        action: String? = null,
        flags: Set<Int> = setOf(),
        boolExtras: Map<String, Boolean> = mapOf(),
        intExtras: Map<String, Int> = mapOf(),
        stringExtras: Map<String, String> = mapOf()
    ) {
        require(activity != null || !action.isNullOrBlank()) {
            "Cannot launch an activity with neither activity name nor action!"
        }
        val command = composeCommand(
                "start", activity, action, flags, boolExtras, intExtras, stringExtras)
        executeShellCommand(command)
    }

    private fun composeCommand(
        command: String,
        activity: ComponentName?,
        action: String?,
        flags: Set<Int>,
        boolExtras: Map<String, Boolean>,
        intExtras: Map<String, Int>,
        stringExtras: Map<String, String>
    ): String = buildString {
        append("am ")
        append(command)
        activity?.let {
            append(" -n ")
            append(it.flattenToShortString())
        }
        action?.let {
            append(" -a ")
            append(it)
        }
        flags.forEach {
            append(" -f ")
            append(it)
        }
        boolExtras.forEach {
            append(it.withFlag("ez"))
        }
        intExtras.forEach {
            append(it.withFlag("ei"))
        }
        stringExtras.forEach {
            append(it.withFlag("es"))
        }
    }

    private fun Map.Entry<String, *>.withFlag(flag: String): String = " --$flag $key $value"

    private fun executeShellCommand(cmd: String): String {
        try {
            return SystemUtil.runShellCommand(instrumentation, cmd)
        } catch (e: IOException) {
            Log.e("FlickerTests", "Error running shell command: $cmd")
            throw e
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