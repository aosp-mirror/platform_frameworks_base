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
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.WALLPAPER_TITLE
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.wm.shell.flicker.dockedStackDividerBecomesVisible
import com.android.wm.shell.flicker.dockedStackPrimaryBoundsIsVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open activity and dock to primary split screen
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenDockActivity`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterSplitScreenDockActivity(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : LegacySplitScreenTransition(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                withTestName {
                    buildTestTag("testLegacySplitScreenDockActivity", configuration)
                }
                repeat { SplitScreenHelper.TEST_REPETITIONS }
                transitions {
                    device.launchSplitScreen()
                }
                assertions {
                    layersTrace {
                        dockedStackPrimaryBoundsIsVisible(
                            configuration.startRotation,
                            splitScreenApp.defaultWindowName, bugId = 169271943)
                        dockedStackDividerBecomesVisible()
                        visibleLayersShownMoreThanOneConsecutiveEntry(
                            listOf(LAUNCHER_PACKAGE_NAME,
                                WALLPAPER_TITLE, LIVE_WALLPAPER_PACKAGE_NAME,
                                splitScreenApp.defaultWindowName),
                            bugId = 178531736
                        )
                    }
                    windowManagerTrace {
                        navBarWindowIsAlwaysVisible()
                        statusBarWindowIsAlwaysVisible()
                        visibleWindowsShownMoreThanOneConsecutiveEntry(
                            listOf(LAUNCHER_PACKAGE_NAME,
                                WALLPAPER_TITLE, LIVE_WALLPAPER_PACKAGE_NAME,
                                splitScreenApp.defaultWindowName),
                            bugId = 178531736
                        )
                        end("appWindowIsVisible") {
                            isVisible(splitScreenApp.defaultWindowName)
                        }
                    }
                }
            }
            return FlickerTestRunnerFactory.getInstance().buildTest(
                instrumentation, defaultTransitionSetup, testSpec,
                repetitions = SplitScreenHelper.TEST_REPETITIONS)
        }
    }
}