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
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.canSplitScreen
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.isInSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.openQuickstep
import com.android.server.wm.flicker.helpers.openQuickStepAndClearRecentAppsFromOverview
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.dockedStackDividerIsInvisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper.Companion.TEST_REPETITIONS
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.dockedStackPrimaryBoundsIsVisible
import com.android.wm.shell.flicker.dockedStackSecondaryBoundsIsVisible
import org.junit.Assert
import com.android.wm.shell.flicker.dockedStackDividerBecomesVisible
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test SplitScreen launch.
 * To run this test: `atest WMShellFlickerTests:EnterLegacySplitScreenTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterLegacySplitScreenTest(
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
                }
            }
            teardown {
                eachRun {
                    if (uiDevice.isInSplitScreen()) {
                        uiDevice.exitSplitScreen()
                    }
                    splitScreenApp.exit()
                    secondaryApp.exit()
                    nonResizeableApp.exit()
                }
            }
            assertions {
                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                }
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                }
            }
        }

    @Test
    fun testEnterSplitScreen_dockActivity() {
        val testTag = "testEnterSplitScreen_dockActivity"
        runWithFlicker(splitScreenSetup) {
            withTestName { testTag }
            repeat {
                TEST_REPETITIONS
            }
            transitions {
                splitScreenApp.launchViaIntent()
                uiDevice.launchSplitScreen()
            }
            assertions {
                layersTrace {
                    dockedStackPrimaryBoundsIsVisible(
                            rotation, splitScreenApp.defaultWindowName, 169271943)
                    dockedStackDividerBecomesVisible()
                    visibleLayersShownMoreThanOneConsecutiveEntry(
                            listOf(LAUNCHER_PACKAGE_NAME, splitScreenApp.defaultWindowName,
                                    LIVE_WALLPAPER_PACKAGE_NAME)
                    )
                }
                windowManagerTrace {
                    end {
                        isVisible(splitScreenApp.defaultWindowName)
                    }
                }
            }
        }
    }

    @Test
    fun testEnterSplitScreen_launchToSide() {
        val testTag = "testEnterSplitScreen_launchToSide"
        runWithFlicker(splitScreenSetup) {
            withTestName { testTag }
            repeat {
                TEST_REPETITIONS
            }
            transitions {
                secondaryApp.launchViaIntent()
                splitScreenApp.launchViaIntent()
                uiDevice.launchSplitScreen()
                splitScreenApp.reopenAppFromOverview()
            }
            assertions {
                layersTrace {
                    dockedStackPrimaryBoundsIsVisible(
                        rotation, splitScreenApp.defaultWindowName, 169271943)
                    dockedStackSecondaryBoundsIsVisible(
                        rotation, secondaryApp.defaultWindowName, 169271943)
                    dockedStackDividerBecomesVisible()
                    visibleLayersShownMoreThanOneConsecutiveEntry(
                        listOf(LAUNCHER_PACKAGE_NAME, splitScreenApp.defaultWindowName,
                            secondaryApp.defaultWindowName)
                    )
                }
                windowManagerTrace {
                    end {
                        isVisible(splitScreenApp.defaultWindowName)
                            .isVisible(secondaryApp.defaultWindowName)
                    }
                    visibleWindowsShownMoreThanOneConsecutiveEntry(
                        listOf(LAUNCHER_PACKAGE_NAME, splitScreenApp.defaultWindowName,
                            secondaryApp.defaultWindowName))
                }
            }
        }
    }

    @Test
    fun testNonResizeableNotDocked() {
        val testTag = "testNonResizeableNotDocked"
        runWithFlicker(splitScreenSetup) {
            withTestName { testTag }
            repeat {
                TEST_REPETITIONS
            }
            transitions {
                nonResizeableApp.launchViaIntent()
                uiDevice.openQuickstep()
                if (uiDevice.canSplitScreen()) {
                    Assert.fail("Non-resizeable app should not enter split screen")
                }
            }
            assertions {
                layersTrace {
                    dockedStackDividerIsInvisible()
                    visibleLayersShownMoreThanOneConsecutiveEntry(
                        listOf(LAUNCHER_PACKAGE_NAME, nonResizeableApp.defaultWindowName)
                    )
                }
                windowManagerTrace {
                    end {
                        isInvisible(nonResizeableApp.defaultWindowName)
                    }
                    visibleWindowsShownMoreThanOneConsecutiveEntry(
                        listOf(LAUNCHER_PACKAGE_NAME, nonResizeableApp.defaultWindowName))
                }
            }
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}