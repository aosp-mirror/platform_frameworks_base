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
import com.android.server.wm.flicker.appWindowBecomesVisible
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.layerBecomesVisible
import com.android.server.wm.flicker.focusChanges
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.appPairsDividerBecomesVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest WMShellFlickerTests:OpenAppToLegacySplitScreenTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppToLegacySplitScreenTest(
    rotationName: String,
    rotation: Int
) : SplitScreenTestBase(rotationName, rotation) {
    @Test
    fun OpenAppToLegacySplitScreenTest() {
        val testTag = "OpenAppToLegacySplitScreenTest"
        val helper = WindowManagerStateHelper()
        runWithFlicker(transitionSetup) {
            withTestName { testTag }
            repeat { SplitScreenHelper.TEST_REPETITIONS }
            setup {
                eachRun {
                    splitScreenApp.launchViaIntent(wmHelper)
                    device.pressHome()
                    this.setRotation(rotation)
                }
            }
            transitions {
                device.launchSplitScreen()
                helper.waitForAppTransitionIdle()
            }
            assertions {
                windowManagerTrace {
                    visibleWindowsShownMoreThanOneConsecutiveEntry(
                            listOf(LAUNCHER_PACKAGE_NAME, splitScreenApp.defaultWindowName,
                                    LETTER_BOX_NAME)
                    )
                    appWindowBecomesVisible(splitScreenApp.getPackage())
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    noUncoveredRegions(rotation, enabled = false)
                    statusBarLayerIsAlwaysVisible()
                    visibleLayersShownMoreThanOneConsecutiveEntry(
                            listOf(LAUNCHER_PACKAGE_NAME, splitScreenApp.defaultWindowName,
                                    LETTER_BOX_NAME))
                    appPairsDividerBecomesVisible()
                    layerBecomesVisible(splitScreenApp.getPackage())
                }

                eventLog {
                    focusChanges(splitScreenApp.`package`,
                            "recents_animation_input_consumer", "NexusLauncherActivity",
                            bugId = 151179149)
                }
            }
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            // TODO(b/161435597) causes the test not to work on 90 degrees
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}
