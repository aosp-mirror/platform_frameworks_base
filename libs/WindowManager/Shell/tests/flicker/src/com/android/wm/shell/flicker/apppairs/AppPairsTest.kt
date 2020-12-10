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

import android.platform.test.annotations.Presubmit
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.compatibility.common.util.SystemUtil
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.AppPairsHelper.Companion.TEST_REPETITIONS
import com.android.wm.shell.flicker.appPairsDividerIsInvisible
import com.android.wm.shell.flicker.appPairsDividerIsVisible
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized
import java.io.IOException

/**
 * Test AppPairs launch.
 * To run this test: `atest WMShellFlickerTests:AppPairsTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppPairsTest(
    rotationName: String,
    rotation: Int
) : AppPairsTestBase(rotationName, rotation) {
    private val appPairsSetup: FlickerBuilder
        get() = FlickerBuilder(instrumentation).apply {
            val testLaunchActivity = "launch_appPairs_primary_secondary_activities"
            withTestName {
                testLaunchActivity
            }
            setup {
                eachRun {
                    uiDevice.wakeUpAndGoToHomeScreen()
                    primaryApp.open()
                    uiDevice.pressHome()
                    secondaryApp.open()
                    uiDevice.pressHome()
                    updateTaskId()
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
    fun testAppPairs_pairPrimaryAndSecondaryApps() {
        val testTag = "testAppPaired_pairPrimaryAndSecondary"
        runWithFlicker(appPairsSetup) {
            withTestName { testTag }
            repeat {
                TEST_REPETITIONS
            }
            transitions {
                // TODO pair apps through normal UX flow
                executeShellCommand(composePairsCommand(
                        primaryTaskId, secondaryTaskId, true /* pair */))
                SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
            }
            assertions {
                layersTrace {
                    appPairsDividerIsVisible()
                    end("appsEndingBounds", enabled = false) {
                        val entry = this.trace.entries.firstOrNull()
                                ?: throw IllegalStateException("Trace is empty")
                        val dividerRegion = entry.getVisibleBounds(SPLIT_DIVIDER)
                        this.hasVisibleRegion(primaryApp.defaultWindowName,
                                appPairsHelper.getPrimaryBounds(dividerRegion))
                                .and()
                                .hasVisibleRegion(secondaryApp.defaultWindowName,
                                        appPairsHelper.getSecondaryBounds(dividerRegion))
                    }
                }
                windowManagerTrace {
                    end {
                        showsAppWindow(primaryApp.defaultWindowName)
                                .and()
                                .showsAppWindow(secondaryApp.defaultWindowName)
                    }
                }
            }
        }
    }

    @Test
    fun testAppPairs_unpairPrimaryAndSecondary() {
        val testTag = "testAppPairs_unpairPrimaryAndSecondary"
        runWithFlicker(appPairsSetup) {
            withTestName { testTag }
            repeat {
                TEST_REPETITIONS
            }
            setup {
                executeShellCommand(composePairsCommand(
                        primaryTaskId, secondaryTaskId, true /* pair */))
                SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
            }
            transitions {
                // TODO pair apps through normal UX flow
                executeShellCommand(composePairsCommand(
                        primaryTaskId, secondaryTaskId, false /* pair */))
                SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
            }
            assertions {
                layersTrace {
                    appPairsDividerIsInvisible()
                    start("appsStartingBounds", enabled = false) {
                        val entry = this.trace.entries.firstOrNull()
                                ?: throw IllegalStateException("Trace is empty")
                        val dividerRegion = entry.getVisibleBounds(SPLIT_DIVIDER)
                        this.hasVisibleRegion(primaryApp.defaultWindowName,
                                appPairsHelper.getPrimaryBounds(dividerRegion))
                                .and()
                                .hasVisibleRegion(secondaryApp.defaultWindowName,
                                        appPairsHelper.getSecondaryBounds(dividerRegion))
                    }
                    end("appsEndingBounds", enabled = false) {
                        this.hasNotLayer(primaryApp.defaultWindowName)
                                .and()
                                .hasNotLayer(secondaryApp.defaultWindowName)
                    }
                }
                windowManagerTrace {
                    end {
                        hidesAppWindow(primaryApp.defaultWindowName)
                                .and()
                                .hidesAppWindow(secondaryApp.defaultWindowName)
                    }
                }
            }
        }
    }

    private fun composePairsCommand(
        primaryApp: String,
        secondaryApp: String,
        pair: Boolean
    ): String = buildString {
        // dumpsys activity service SystemUIService WMShell {pair|unpair} ${TASK_ID_1} ${TASK_ID_2}
        append("dumpsys activity service SystemUIService WMShell ")
        if (pair) {
            append("pair ")
        } else {
            append("unpair ")
        }
        append(primaryApp + " " + secondaryApp)
    }

    private fun executeShellCommand(cmd: String) {
        try {
            SystemUtil.runShellCommand(instrumentation, cmd)
        } catch (e: IOException) {
            Log.d("AppPairsTest", "executeShellCommand error!" + e)
        }
    }

    private fun updateTaskId() {
        val primaryAppComponent = primaryApp.openAppIntent.component
        val secondaryAppComponent = secondaryApp.openAppIntent.component
        if (primaryAppComponent != null) {
            primaryTaskId = appPairsHelper.getTaskIdForActivity(
                    primaryAppComponent.packageName, primaryAppComponent.className).toString()
        }
        if (secondaryAppComponent != null) {
            secondaryTaskId = appPairsHelper.getTaskIdForActivity(
                    secondaryAppComponent.packageName, secondaryAppComponent.className).toString()
        }
    }

    companion object {
        var primaryTaskId = ""
        var secondaryTaskId = ""
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}