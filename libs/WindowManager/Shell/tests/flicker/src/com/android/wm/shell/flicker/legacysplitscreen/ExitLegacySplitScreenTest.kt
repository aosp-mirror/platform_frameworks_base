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

package com.android.wm.shell.flicker.legacysplitscreen

import android.platform.test.annotations.Presubmit
import android.util.Rational
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.layerBecomesInvisible
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.openQuickStepAndClearRecentAppsFromOverview
import com.android.server.wm.flicker.helpers.resizeSplitScreen
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.dockedStackDividerIsInvisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper.Companion.TEST_REPETITIONS
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test exit SplitScreen mode.
 * To run this test: `atest WMShellFlickerTests:ExitLegacySplitScreenTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ExitLegacySplitScreenTest(
    rotationName: String,
    rotation: Int
) : SplitScreenTestBase(rotationName, rotation) {
    private val splitScreenSetup: FlickerBuilder
        get() = FlickerBuilder(instrumentation).apply {
            val testLaunchActivity = "launch_splitScreen_test_activity"
            withTestName {
                testLaunchActivity
            }
            setup {
                eachRun {
                    uiDevice.wakeUpAndGoToHomeScreen()
                    uiDevice.openQuickStepAndClearRecentAppsFromOverview()
                    secondaryApp.launchViaIntent()
                    splitScreenApp.launchViaIntent()
                    uiDevice.launchSplitScreen()
                }
            }
            teardown {
                eachRun {
                    splitScreenApp.exit()
                    secondaryApp.exit()
                }
            }
            assertions {
                windowManagerTrace {
                    visibleWindowsShownMoreThanOneConsecutiveEntry()
                }
                layersTrace {
                    visibleLayersShownMoreThanOneConsecutiveEntry(
                            listOf(launcherPackageName))
                }
            }
        }

    @Test
    fun testEnterSplitScreen_exitPrimarySplitScreenMode() {
        val testTag = "testEnterSplitScreen_exitPrimarySplitScreenMode"
        runWithFlicker(splitScreenSetup) {
            withTestName { testTag }
            repeat {
                TEST_REPETITIONS
            }
            transitions {
                uiDevice.exitSplitScreen()
            }
            assertions {
                layersTrace {
                    dockedStackDividerIsInvisible()
                    layerBecomesInvisible(splitScreenApp.defaultWindowName)
                }
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                    end {
                        hidesAppWindow(splitScreenApp.defaultWindowName)
                    }
                }
            }
        }
    }

    @Test
    @FlakyTest(bugId = 172811376)
    fun testEnterSplitScreen_exitPrimary_showSecondaryAppFullScreen() {
        val testTag = "testEnterSplitScreen_exitPrimary_showSecondaryAppFullScreen"
        runWithFlicker(splitScreenSetup) {
            withTestName { testTag }
            repeat {
                TEST_REPETITIONS
            }
            transitions {
                splitScreenApp.reopenAppFromOverview()
                uiDevice.resizeSplitScreen(startRatio)
            }
            assertions {
                layersTrace {
                    dockedStackDividerIsInvisible()
                }
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                    end {
                        showsAppWindow(splitScreenApp.defaultWindowName)
                    }
                }
            }
        }
    }

    companion object {
        private val startRatio = Rational(1, 3)

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}
