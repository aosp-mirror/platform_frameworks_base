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

package com.android.wm.shell.flicker.splitscreen

import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.dockedStackDividerIsVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper.Companion.TEST_REPETITIONS
import com.android.wm.shell.flicker.navBarLayerIsAlwaysVisible
import com.android.wm.shell.flicker.navBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.statusBarLayerIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test SplitScreen launch.
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterSplitScreenTest(
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
                    splitScreenApp.open()
                    uiDevice.pressHome()
                }
            }
            teardown {
                eachRun {
                    splitScreenApp.exit()
                    secondaryApp.exit()
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
                uiDevice.launchSplitScreen()
            }
            assertions {
                layersTrace {
                    dockedStackDividerIsVisible()
                    end("appsEndingBounds", enabled = false) {
                        val entry = this.trace.entries.firstOrNull()
                                ?: throw IllegalStateException("Trace is empty")
                        this.hasVisibleRegion(splitScreenApp.defaultWindowName,
                                splitScreenApp.getPrimaryBounds(
                                        entry.getVisibleBounds(DOCKED_STACK_DIVIDER)))
                    }
                }
                windowManagerTrace {
                    end {
                        showsAppWindow(splitScreenApp.defaultWindowName)
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
                secondaryApp.open()
                uiDevice.pressHome()
                splitScreenApp.open()
                uiDevice.pressHome()
                uiDevice.launchSplitScreen()
                splitScreenApp.reopenAppFromOverview()
            }
            assertions {
                layersTrace {
                    dockedStackDividerIsVisible()
                    end("appsEndingBounds", enabled = false) {
                        val entry = this.trace.entries.firstOrNull()
                                ?: throw IllegalStateException("Trace is empty")
                        this.hasVisibleRegion(splitScreenApp.defaultWindowName,
                                splitScreenApp.getPrimaryBounds(
                                        entry.getVisibleBounds(DOCKED_STACK_DIVIDER)))
                                .and()
                                .hasVisibleRegion(secondaryApp.defaultWindowName,
                                        splitScreenApp.getSecondaryBounds(
                                        entry.getVisibleBounds(DOCKED_STACK_DIVIDER)))
                    }
                }
                windowManagerTrace {
                    end {
                        showsAppWindow(splitScreenApp.defaultWindowName)
                                .and().showsAppWindow(secondaryApp.defaultWindowName)
                    }
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