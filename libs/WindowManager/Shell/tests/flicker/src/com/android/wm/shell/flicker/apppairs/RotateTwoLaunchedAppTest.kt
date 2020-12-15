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

package com.android.wm.shell.flicker.apppairs

import android.os.SystemClock
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.appPairsDividerIsVisible
import com.android.wm.shell.flicker.appPairsPrimaryBoundsIsVisible
import com.android.wm.shell.flicker.appPairsSecondaryBoundsIsVisible
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open apps to app pairs and rotate.
 * To run this test: `atest WMShellFlickerTests:RotateTwoLaunchedAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RotateTwoLaunchedAppTest(
    rotationName: String,
    rotation: Int
) : AppPairsTestBase(rotationName, rotation) {
    private val appPairsRotationSetup: FlickerBuilder
        get() = FlickerBuilder(instrumentation).apply {
            val testSetupRotation = "testSetupRotation"
            withTestName {
                testSetupRotation
            }
            setup {
                test {
                    uiDevice.wakeUpAndGoToHomeScreen()
                    primaryApp.launchViaIntent()
                    secondaryApp.launchViaIntent()
                    updateTasksId()
                }
            }
            teardown {
                eachRun {
                    executeShellCommand(composePairsCommand(
                            primaryTaskId, secondaryTaskId, false /* pair */))
                    primaryApp.exit()
                    secondaryApp.exit()
                }
            }
        }

    @Test
    fun testRotateInAppPairsMode() {
        val testTag = "testRotateInAppPairsMode"
        runWithFlicker(appPairsRotationSetup) {
            withTestName { testTag }
            repeat {
                SplitScreenHelper.TEST_REPETITIONS
            }
            transitions {
                executeShellCommand(composePairsCommand(
                        primaryTaskId, secondaryTaskId, true /* pair */))
                SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
                setRotation(rotation)
            }
            assertions {
                layersTrace {
                    navBarLayerRotatesAndScales(Surface.ROTATION_0, rotation)
                    statusBarLayerRotatesScales(Surface.ROTATION_0, rotation)
                    appPairsDividerIsVisible()
                    appPairsPrimaryBoundsIsVisible(
                            rotation, primaryApp.defaultWindowName, 172776659)
                    appPairsSecondaryBoundsIsVisible(
                            rotation, secondaryApp.defaultWindowName, 172776659)
                }
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                    end {
                        showsAppWindow(primaryApp.defaultWindowName)
                                .showsAppWindow(secondaryApp.defaultWindowName)
                    }
                }
            }
        }
    }

    @Test
    fun testRotateAndEnterAppPairsMode() {
        val testTag = "testRotateAndEnterAppPairsMode"
        runWithFlicker(appPairsRotationSetup) {
            withTestName { testTag }
            repeat {
                SplitScreenHelper.TEST_REPETITIONS
            }
            transitions {
                setRotation(rotation)
                executeShellCommand(composePairsCommand(
                        primaryTaskId, secondaryTaskId, true /* pair */))
                SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
            }
            assertions {
                layersTrace {
                    navBarLayerRotatesAndScales(Surface.ROTATION_0, rotation)
                    statusBarLayerRotatesScales(Surface.ROTATION_0, rotation)
                    appPairsDividerIsVisible()
                    appPairsPrimaryBoundsIsVisible(
                            rotation, primaryApp.defaultWindowName, 172776659)
                    appPairsSecondaryBoundsIsVisible(
                            rotation, secondaryApp.defaultWindowName, 172776659)
                }
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
                    end {
                        showsAppWindow(primaryApp.defaultWindowName)
                                .showsAppWindow(secondaryApp.defaultWindowName)
                    }
                }
            }
        }
    }

    fun updateTasksId() {
        if (primaryAppComponent != null) {
            primaryTaskId = getTaskIdForActivity(
                    primaryAppComponent.packageName, primaryAppComponent.className).toString()
        }
        if (secondaryAppComponent != null) {
            secondaryTaskId = getTaskIdForActivity(
                    secondaryAppComponent.packageName, secondaryAppComponent.className).toString()
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