/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.flicker.legacysplitscreen

import android.os.Bundle
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.DOCKED_STACK_DIVIDER
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.appWindowBecomesInVisible
import com.android.server.wm.flicker.appWindowBecomesVisible
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.layerBecomesInvisible
import com.android.server.wm.flicker.layerBecomesVisible
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launch non resizable activity in split screen mode will trigger exit split screen mode
 * (Non resizable activity launch via recent overview)
 * To run this test: `atest WMShellFlickerTests:NonResizableDismissInLegacySplitScreen`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NonResizableDismissInLegacySplitScreen(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : LegacySplitScreenTransition(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                withTestName {
                    buildTestTag("testNonResizableDismissInLegacySplitScreen", configuration)
                }
                repeat { SplitScreenHelper.TEST_REPETITIONS }
                transitions {
                    nonResizeableApp.launchViaIntent(wmHelper)
                    splitScreenApp.launchViaIntent(wmHelper)
                    device.launchSplitScreen()
                    nonResizeableApp.reopenAppFromOverview()
                    wmHelper.waitForAppTransitionIdle()
                }
                assertions {
                    layersTrace {
                        layerBecomesVisible(nonResizeableApp.defaultWindowName)
                        layerBecomesInvisible(splitScreenApp.defaultWindowName)
                        visibleLayersShownMoreThanOneConsecutiveEntry(
                            listOf(DOCKED_STACK_DIVIDER, LAUNCHER_PACKAGE_NAME,
                                LETTERBOX_NAME, TOAST_NAME,
                                splitScreenApp.defaultWindowName,
                                nonResizeableApp.defaultWindowName),
                            bugId = 178447631
                        )
                    }
                    windowManagerTrace {
                        appWindowBecomesVisible(nonResizeableApp.defaultWindowName)
                        appWindowBecomesInVisible(splitScreenApp.defaultWindowName)
                        visibleWindowsShownMoreThanOneConsecutiveEntry(
                            listOf(DOCKED_STACK_DIVIDER, LAUNCHER_PACKAGE_NAME,
                                LETTERBOX_NAME, TOAST_NAME,
                                splitScreenApp.defaultWindowName,
                                nonResizeableApp.defaultWindowName),
                            bugId = 178447631
                        )
                    }
                }
            }
            return FlickerTestRunnerFactory.getInstance().buildTest(
                instrumentation, cleanSetup, testSpec,
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0 /* bugId = 178685668 */))
        }
    }
}