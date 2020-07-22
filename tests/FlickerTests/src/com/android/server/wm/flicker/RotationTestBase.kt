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

package com.android.server.wm.flicker

import android.view.Surface
import androidx.test.filters.FlakyTest
import org.junit.Test
import org.junit.runners.Parameterized

abstract class RotationTestBase(
    beginRotationName: String,
    endRotationName: String,
    protected val beginRotation: Int,
    protected val endRotation: Int
) : FlickerTestBase() {
    @FlakyTest(bugId = 140855415)
    @Test
    fun checkVisibility_navBarWindowIsAlwaysVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAboveAppWindow(NAVIGATION_BAR_WINDOW_TITLE).forAllEntries()
        }
    }

    @FlakyTest(bugId = 140855415)
    @Test
    fun checkVisibility_statusBarWindowIsAlwaysVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAboveAppWindow(STATUS_BAR_WINDOW_TITLE).forAllEntries()
        }
    }

    @Test
    fun checkPosition_navBarLayerRotatesAndScales() {
        val startingPos = WindowUtils.getNavigationBarPosition(beginRotation)
        val endingPos = WindowUtils.getNavigationBarPosition(endRotation)
        if (startingPos == endingPos) {
            checkResults {
                LayersTraceSubject.assertThat(it)
                        .hasVisibleRegion(NAVIGATION_BAR_WINDOW_TITLE, startingPos)
                        .forAllEntries()
            }
        } else {
            checkResults {
                LayersTraceSubject.assertThat(it)
                        .hasVisibleRegion(NAVIGATION_BAR_WINDOW_TITLE, startingPos)
                        .inTheBeginning()
            }
            checkResults {
                LayersTraceSubject.assertThat(it)
                        .hasVisibleRegion(NAVIGATION_BAR_WINDOW_TITLE, endingPos)
                        .atTheEnd()
            }
        }
    }

    @Test
    fun checkPosition_statusBarLayerRotatesScales() {
        val startingPos = WindowUtils.getStatusBarPosition(beginRotation)
        val endingPos = WindowUtils.getStatusBarPosition(endRotation)
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .hasVisibleRegion(STATUS_BAR_WINDOW_TITLE, startingPos)
                    .inTheBeginning()
            LayersTraceSubject.assertThat(it)
                    .hasVisibleRegion(STATUS_BAR_WINDOW_TITLE, endingPos).atTheEnd()
        }
    }

    @FlakyTest(bugId = 140855415)
    @Test
    fun checkVisibility_navBarLayerIsAlwaysVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(NAVIGATION_BAR_WINDOW_TITLE).forAllEntries()
        }
    }

    @FlakyTest(bugId = 140855415)
    @Test
    fun checkVisibility_statusBarLayerIsAlwaysVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(STATUS_BAR_WINDOW_TITLE).forAllEntries()
        }
    }

    companion object {
        const val SCREENSHOT_LAYER = "RotationLayer"

        @Parameterized.Parameters(name = "{0}-{1}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0, Surface.ROTATION_90)
            val params: MutableCollection<Array<Any>> = mutableListOf()
            for (begin in supportedRotations) {
                for (end in supportedRotations) {
                    if (begin != end) {
                        params.add(arrayOf(
                                Surface.rotationToString(begin),
                                Surface.rotationToString(end),
                                begin,
                                end
                        ))
                    }
                }
            }
            return params
        }
    }
}
