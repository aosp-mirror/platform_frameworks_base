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
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.traces.layers.getVisibleBounds
import com.android.wm.shell.flicker.appPairsDividerIsInvisible
import com.android.wm.shell.flicker.appPairsDividerIsVisible
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.AppPairsHelper.Companion.TEST_REPETITIONS
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

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
                    primaryApp.launchViaIntent()
                    secondaryApp.launchViaIntent()
                    nonResizeableApp.launchViaIntent()
                    updateTasksId()
                }
            }
            teardown {
                eachRun {
                    executeShellCommand(composePairsCommand(
                            primaryTaskId, secondaryTaskId, false /* pair */))
                    executeShellCommand(composePairsCommand(
                            primaryTaskId, nonResizeableTaskId, false /* pair */))
                    primaryApp.exit()
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
    fun testAppPairs_pairPrimaryAndSecondaryApps() {
        val testTag = "testAppPairs_pairPrimaryAndSecondaryApps"
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
                        val dividerRegion = entry.getVisibleBounds(APP_PAIR_SPLIT_DIVIDER)
                        this.hasVisibleRegion(primaryApp.defaultWindowName,
                                appPairsHelper.getPrimaryBounds(dividerRegion))
                                .hasVisibleRegion(secondaryApp.defaultWindowName,
                                        appPairsHelper.getSecondaryBounds(dividerRegion))
                    }
                }
                windowManagerTrace {
                    end {
                        showsAppWindow(primaryApp.defaultWindowName)
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
                        val dividerRegion = entry.getVisibleBounds(APP_PAIR_SPLIT_DIVIDER)
                        this.hasVisibleRegion(primaryApp.defaultWindowName,
                                appPairsHelper.getPrimaryBounds(dividerRegion))
                                .hasVisibleRegion(secondaryApp.defaultWindowName,
                                        appPairsHelper.getSecondaryBounds(dividerRegion))
                    }
                    end("appsEndingBounds", enabled = false) {
                        this.hasNotLayer(primaryApp.defaultWindowName)
                                .hasNotLayer(secondaryApp.defaultWindowName)
                    }
                }
                windowManagerTrace {
                    end {
                        hidesAppWindow(primaryApp.defaultWindowName)
                                .hidesAppWindow(secondaryApp.defaultWindowName)
                    }
                }
            }
        }
    }

    @Test
    fun testAppPairs_canNotPairNonResizeableApps() {
        val testTag = "testAppPairs_canNotPairNonResizeableApps"
        runWithFlicker(appPairsSetup) {
            withTestName { testTag }
            repeat {
                TEST_REPETITIONS
            }
            transitions {
                // TODO pair apps through normal UX flow
                executeShellCommand(composePairsCommand(
                    primaryTaskId, nonResizeableTaskId, true /* pair */))
                SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
            }

            assertions {
                layersTrace {
                    appPairsDividerIsInvisible()
                }
                windowManagerTrace {
                    end {
                        showsAppWindow(nonResizeableApp.defaultWindowName)
                            .hidesAppWindow(primaryApp.defaultWindowName)
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
        if (nonResizeableAppComponent != null) {
            nonResizeableTaskId = getTaskIdForActivity(
                    nonResizeableAppComponent.packageName,
                    nonResizeableAppComponent.className).toString()
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