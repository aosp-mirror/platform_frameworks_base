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
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.WALLPAPER_TITLE
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.canSplitScreen
import com.android.server.wm.flicker.helpers.openQuickstep
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.wm.shell.flicker.dockedStackDividerIsInvisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.Assert
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open non-resizable activity will auto exit split screen mode
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenNonResizableNotDock`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 173875043)
class EnterSplitScreenNonResizableNotDock(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : LegacySplitScreenTransition(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                withTestName {
                    buildTestTag("testLegacySplitScreenNonResizeableActivityNotDock", configuration)
                }
                repeat { SplitScreenHelper.TEST_REPETITIONS }
                transitions {
                    nonResizeableApp.launchViaIntent(wmHelper)
                    device.openQuickstep()
                    if (device.canSplitScreen()) {
                        Assert.fail("Non-resizeable app should not enter split screen")
                    }
                }
                assertions {
                    layersTrace {
                        dockedStackDividerIsInvisible()
                        visibleLayersShownMoreThanOneConsecutiveEntry(
                            listOf(LAUNCHER_PACKAGE_NAME,
                                SPLASH_SCREEN_NAME,
                                nonResizeableApp.defaultWindowName,
                                splitScreenApp.defaultWindowName),
                            bugId = 178447631
                        )
                    }
                    windowManagerTrace {
                        visibleWindowsShownMoreThanOneConsecutiveEntry(
                            listOf(WALLPAPER_TITLE,
                                LAUNCHER_PACKAGE_NAME,
                                SPLASH_SCREEN_NAME,
                                nonResizeableApp.defaultWindowName,
                                splitScreenApp.defaultWindowName)
                        )
                        end("appWindowIsVisible") {
                            isInvisible(nonResizeableApp.defaultWindowName)
                        }
                    }
                }
            }
            return FlickerTestRunnerFactory.getInstance().buildTest(
                instrumentation, defaultTransitionSetup, testSpec,
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0 /* bugId = 178685668 */))
        }
    }
}