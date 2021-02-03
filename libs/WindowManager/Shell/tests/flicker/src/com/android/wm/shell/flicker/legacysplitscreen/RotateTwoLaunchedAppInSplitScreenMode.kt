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

import android.os.Bundle
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.appWindowBecomesVisible
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.dockedStackDividerIsVisible
import com.android.wm.shell.flicker.dockedStackPrimaryBoundsIsVisible
import com.android.wm.shell.flicker.dockedStackSecondaryBoundsIsVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest WMShellFlickerTests:RotateTwoLaunchedAppInSplitScreenMode`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RotateTwoLaunchedAppInSplitScreenMode(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object : LegacySplitScreenTransition(InstrumentationRegistry.getInstrumentation()) {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val testSpec: FlickerBuilder.(Bundle) -> Unit = { configuration ->
                withTestName {
                    buildTestTag("testRotateTwoLaunchedAppInSplitScreenMode", configuration)
                }
                repeat { SplitScreenHelper.TEST_REPETITIONS }
                setup {
                    eachRun {
                        device.launchSplitScreen()
                        splitScreenApp.reopenAppFromOverview()
                        this.setRotation(configuration.startRotation)
                    }
                }
                transitions {
                    this.setRotation(configuration.startRotation)
                }
                assertions {
                    layersTrace {
                        dockedStackDividerIsVisible(bugId = 175687842)
                        dockedStackPrimaryBoundsIsVisible(
                            configuration.startRotation,
                            splitScreenApp.defaultWindowName, bugId = 175687842)
                        dockedStackSecondaryBoundsIsVisible(
                            configuration.startRotation,
                            secondaryApp.defaultWindowName, bugId = 175687842)
                        navBarLayerRotatesAndScales(
                            configuration.startRotation,
                            configuration.endRotation, bugId = 169271943)
                        statusBarLayerRotatesScales(
                            configuration.startRotation,
                            configuration.endRotation, bugId = 169271943)
                    }
                    windowManagerTrace {
                        appWindowBecomesVisible(secondaryApp.defaultWindowName)
                        navBarWindowIsAlwaysVisible()
                        statusBarWindowIsAlwaysVisible()
                    }
                }
            }
            return FlickerTestRunnerFactory.getInstance().buildTest(
                instrumentation, customRotateSetup, testSpec,
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0 /* bugId = 178685668 */))
        }
    }
}