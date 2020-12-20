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

import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.isInSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.openQuickStepAndClearRecentAppsFromOverview
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.dockedStackDividerIsVisible
import com.android.wm.shell.flicker.dockedStackPrimaryBoundsIsVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest WMShellFlickerTests:RotateOneLaunchedAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RotateOneLaunchedAppTest(
    rotationName: String,
    rotation: Int
) : SplitScreenTestBase(rotationName, rotation) {
    private val splitScreenRotationSetup: FlickerBuilder
        get() = FlickerBuilder(instrumentation).apply {
            val testSetupRotation = "testSetupRotation"
            withTestName {
                testSetupRotation
            }
            setup {
                test {
                    uiDevice.wakeUpAndGoToHomeScreen()
                    uiDevice.openQuickStepAndClearRecentAppsFromOverview()
                }
            }
            teardown {
                eachRun {
                    if (uiDevice.isInSplitScreen()) {
                        uiDevice.exitSplitScreen()
                    }
                    setRotation(Surface.ROTATION_0)
                    splitScreenApp.exit()
                    secondaryApp.exit()
                }
            }
        }

    @Test
    fun testRotateInSplitScreenMode() {
        val testTag = "testEnterSplitScreen_launchToSide"
        runWithFlicker(splitScreenRotationSetup) {
            withTestName { testTag }
            repeat {
                SplitScreenHelper.TEST_REPETITIONS
            }
            transitions {
                splitScreenApp.launchViaIntent()
                uiDevice.launchSplitScreen()
                setRotation(rotation)
            }
            assertions {
                layersTrace {
                    navBarLayerRotatesAndScales(Surface.ROTATION_0, rotation, 169271943)
                    statusBarLayerRotatesScales(Surface.ROTATION_0, rotation, 169271943)
                    dockedStackDividerIsVisible()
                    dockedStackPrimaryBoundsIsVisible(
                            rotation, splitScreenApp.defaultWindowName, 169271943)
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

    @Test
    fun testRotateAndEnterSplitScreenMode() {
        val testTag = "testRotateAndEnterSplitScreenMode"
        runWithFlicker(splitScreenRotationSetup) {
            withTestName { testTag }
            repeat {
                SplitScreenHelper.TEST_REPETITIONS
            }
            transitions {
                splitScreenApp.launchViaIntent()
                setRotation(rotation)
                uiDevice.launchSplitScreen()
            }
            assertions {
                layersTrace {
                    navBarLayerRotatesAndScales(Surface.ROTATION_0, rotation, 169271943)
                    statusBarLayerRotatesScales(Surface.ROTATION_0, rotation, 169271943)
                    dockedStackDividerIsVisible()
                    dockedStackPrimaryBoundsIsVisible(
                            rotation, splitScreenApp.defaultWindowName, 169271943)
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
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_90, Surface.ROTATION_270)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}