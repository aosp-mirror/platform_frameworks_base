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
import android.graphics.Region
import android.util.Rational
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.android.server.wm.flicker.DOCKED_STACK_DIVIDER
import com.android.server.wm.flicker.FlickerTestRunner
import com.android.server.wm.flicker.FlickerTestRunnerFactory
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.focusDoesNotChange
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.isInSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.resizeSplitScreen
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.traces.layers.getVisibleBounds
import com.android.wm.shell.flicker.helpers.SimpleAppHelper
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test split screen resizing window transitions.
 * To run this test: `atest WMShellFlickerTests:ResizeLegacySplitScreenTest`
 *
 * Currently it runs only in 0 degrees because of b/156100803
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 159096424)
class ResizeLegacySplitScreenTest(
    testSpec: FlickerTestRunnerFactory.TestSpec
) : FlickerTestRunner(testSpec) {
    companion object {
        private const val sSimpleActivity = "SimpleActivity"
        private const val sImeActivity = "ImeActivity"
        private val startRatio = Rational(1, 3)
        private val stopRatio = Rational(2, 3)

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testAppTop = SimpleAppHelper(instrumentation)
            val testAppBottom = ImeAppHelper(instrumentation)

            return FlickerTestRunnerFactory.getInstance().buildTest(instrumentation,
                supportedRotations = listOf(Surface.ROTATION_0)) { configuration ->
                    withTestName {
                        val description = (startRatio.toString().replace("/", "-") + "_to_" +
                            stopRatio.toString().replace("/", "-"))
                        buildTestTag("resizeSplitScreen", configuration, description)
                    }
                    repeat { configuration.repetitions }
                    setup {
                        eachRun {
                            device.wakeUpAndGoToHomeScreen()
                            this.setRotation(configuration.startRotation)
                            this.launcherStrategy.clearRecentAppsFromOverview()
                            testAppBottom.launchViaIntent(wmHelper)
                            device.pressHome()
                            testAppTop.launchViaIntent(wmHelper)
                            device.waitForIdle()
                            device.launchSplitScreen()
                            val snapshot =
                                device.findObject(By.res(device.launcherPackageName, "snapshot"))
                            snapshot.click()
                            testAppBottom.openIME(device)
                            device.pressBack()
                            device.resizeSplitScreen(startRatio)
                        }
                    }
                    teardown {
                        eachRun {
                            if (device.isInSplitScreen()) {
                                device.exitSplitScreen()
                            }
                            device.pressHome()
                            testAppTop.exit()
                            testAppBottom.exit()
                        }
                        test {
                            if (device.isInSplitScreen()) {
                                device.exitSplitScreen()
                            }
                        }
                    }
                    transitions {
                        device.resizeSplitScreen(stopRatio)
                    }
                    assertions {
                        windowManagerTrace {
                            navBarWindowIsAlwaysVisible()
                            statusBarWindowIsAlwaysVisible()
                            visibleWindowsShownMoreThanOneConsecutiveEntry()

                            all("topAppWindowIsAlwaysVisible", bugId = 156223549) {
                                this.showsAppWindow(sSimpleActivity)
                            }

                            all("bottomAppWindowIsAlwaysVisible", bugId = 156223549) {
                                this.showsAppWindow(sImeActivity)
                            }
                        }

                        layersTrace {
                            navBarLayerIsAlwaysVisible()
                            statusBarLayerIsAlwaysVisible()
                            noUncoveredRegions(configuration.endRotation)
                            navBarLayerRotatesAndScales(configuration.endRotation)
                            statusBarLayerRotatesScales(configuration.endRotation)
                            visibleLayersShownMoreThanOneConsecutiveEntry()

                            all("topAppLayerIsAlwaysVisible") {
                                this.showsLayer(sSimpleActivity)
                            }

                            all("bottomAppLayerIsAlwaysVisible") {
                                this.showsLayer(sImeActivity)
                            }

                            all("dividerLayerIsAlwaysVisible") {
                                this.showsLayer(DOCKED_STACK_DIVIDER)
                            }

                            start("appsStartingBounds", enabled = false) {
                                val displayBounds = WindowUtils.displayBounds
                                val dividerBounds =
                                    entry.getVisibleBounds(DOCKED_STACK_DIVIDER).bounds

                                val topAppBounds = Region(0, 0, dividerBounds.right,
                                    dividerBounds.top + WindowUtils.dockedStackDividerInset)
                                val bottomAppBounds = Region(0,
                                    dividerBounds.bottom - WindowUtils.dockedStackDividerInset,
                                    displayBounds.right,
                                    displayBounds.bottom - WindowUtils.navigationBarHeight)
                                this.hasVisibleRegion("SimpleActivity", topAppBounds)
                                    .hasVisibleRegion("ImeActivity", bottomAppBounds)
                            }

                            end("appsEndingBounds", enabled = false) {
                                val displayBounds = WindowUtils.displayBounds
                                val dividerBounds =
                                    entry.getVisibleBounds(DOCKED_STACK_DIVIDER).bounds

                                val topAppBounds = Region(0, 0, dividerBounds.right,
                                    dividerBounds.top + WindowUtils.dockedStackDividerInset)
                                val bottomAppBounds = Region(0,
                                    dividerBounds.bottom - WindowUtils.dockedStackDividerInset,
                                    displayBounds.right,
                                    displayBounds.bottom - WindowUtils.navigationBarHeight)

                                this.hasVisibleRegion(sSimpleActivity, topAppBounds)
                                    .hasVisibleRegion(sImeActivity, bottomAppBounds)
                            }
                        }

                        eventLog {
                            focusDoesNotChange()
                        }
                    }
                }
        }
    }
}
