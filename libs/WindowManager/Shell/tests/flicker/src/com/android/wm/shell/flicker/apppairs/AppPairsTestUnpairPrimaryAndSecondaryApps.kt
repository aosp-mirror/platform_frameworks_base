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

import android.os.Bundle
import android.os.SystemClock
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.traces.layers.getVisibleBounds
import com.android.wm.shell.flicker.FlickerTestBase.Companion.APP_PAIR_SPLIT_DIVIDER
import com.android.wm.shell.flicker.appPairsDividerIsInvisible
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launch app from launcher.
 * To run this test: `atest WMShellFlickerTests:AppPairsTestUnpairPrimaryAndSecondaryApps`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppPairsTestUnpairPrimaryAndSecondaryApps(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : AppPairsTransition(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<Array<Any>> {
            val testTag = "testAppPairs_unpairPrimaryAndSecondaryApps"
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                withTestName {
                    buildTestTag(testTag, configuration)
                }
                setup {
                    executeShellCommand(
                        composePairsCommand(primaryTaskId, secondaryTaskId, pair = true))
                    SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
                }
                transitions {
                    // TODO pair apps through normal UX flow
                    executeShellCommand(
                        composePairsCommand(primaryTaskId, secondaryTaskId, pair = false))
                    SystemClock.sleep(AppPairsHelper.TIMEOUT_MS)
                }
                assertions {
                    layersTrace {
                        appPairsDividerIsInvisible()
                        start("appsStartingBounds", enabled = false) {
                            val dividerRegion = entry.getVisibleBounds(APP_PAIR_SPLIT_DIVIDER)
                            this.hasVisibleRegion(primaryApp.defaultWindowName,
                                appPairsHelper.getPrimaryBounds(dividerRegion))
                                .hasVisibleRegion(secondaryApp.defaultWindowName,
                                    appPairsHelper.getSecondaryBounds(dividerRegion))
                        }
                        end("appsEndingBounds", enabled = false) {
                            this.notExists(primaryApp.defaultWindowName)
                                .notExists(secondaryApp.defaultWindowName)
                        }
                    }
                    windowManagerTrace {
                        end("bothAppWindowsInvisible") {
                            isInvisible(primaryApp.defaultWindowName)
                            isInvisible(secondaryApp.defaultWindowName)
                        }
                    }
                }
            }
            return FlickerTestRunnerFactory.getInstance().buildTest(instrumentation, transition,
                testSpec, repetitions = AppPairsHelper.TEST_REPETITIONS)
        }
    }
}
