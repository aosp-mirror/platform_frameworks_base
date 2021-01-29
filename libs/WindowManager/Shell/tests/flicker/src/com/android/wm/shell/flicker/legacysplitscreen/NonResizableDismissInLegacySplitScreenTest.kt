/*
 * Copyright (C) 2021 The Android Open Source Project
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
import com.android.server.wm.flicker.dsl.runWithFlicker
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.wm.shell.flicker.dockedStackDividerIsInvisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest WMShellFlickerTests:NonResizableDismissInLegacySplitScreenTest`
 */
@Presubmit
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NonResizableDismissInLegacySplitScreenTest(
    rotationName: String,
    rotation: Int
) : SplitScreenTestBase(rotationName, rotation) {

    @Test
    fun testNonResizableDismissInLegacySplitScreenTest() {
        val testTag = "testNonResizableDismissInLegacySplitScreenTest"

        runWithFlicker(transitionSetup) {
            withTestName { testTag }
            repeat { SplitScreenHelper.TEST_REPETITIONS }
            transitions {
                nonResizeableApp.launchViaIntent(wmHelper)
                splitScreenApp.launchViaIntent(wmHelper)
                device.launchSplitScreen()
                nonResizeableApp.reopenAppFromOverview()
            }
            assertions {
                layersTrace {
                    dockedStackDividerIsInvisible()
                    end("appsEndingBounds", enabled = false) {
                        val displayBounds = WindowUtils.getDisplayBounds(rotation)
                        this.hasVisibleRegion(nonResizeableApp.defaultWindowName, displayBounds)
                    }
                    visibleLayersShownMoreThanOneConsecutiveEntry(
                            listOf(LAUNCHER_PACKAGE_NAME, splitScreenApp.defaultWindowName,
                                    nonResizeableApp.defaultWindowName, LETTER_BOX_NAME,
                                    TOAST_NAME, LIVE_WALLPAPER_PACKAGE_NAME),
                            bugId = 178447631
                    )
                }
                windowManagerTrace {
                    end {
                        isVisible(nonResizeableApp.defaultWindowName)
                            .isInvisible(splitScreenApp.defaultWindowName)
                    }
                }
            }
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0, Surface.ROTATION_90)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}