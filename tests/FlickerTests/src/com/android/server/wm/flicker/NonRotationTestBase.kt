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

abstract class NonRotationTestBase(
    beginRotationName: String,
    protected val beginRotation: Int
) : FlickerTestBase() {
    @FlakyTest(bugId = 141361128)
    @Test
    fun checkCoveredRegion_noUncoveredRegions() {
        val displayBounds = WindowUtils.getDisplayBounds(beginRotation)
        checkResults {
            LayersTraceSubject.assertThat(it).coversRegion(
                    displayBounds).forAllEntries()
        }
    }

    @FlakyTest(bugId = 141361128)
    @Test
    fun checkVisibility_navBarLayerIsAlwaysVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(NAVIGATION_BAR_WINDOW_TITLE).forAllEntries()
        }
    }

    @FlakyTest(bugId = 141361128)
    @Test
    fun checkVisibility_statusBarLayerIsAlwaysVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(STATUS_BAR_WINDOW_TITLE).forAllEntries()
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0, Surface.ROTATION_90)
            val params: MutableCollection<Array<Any>> = ArrayList()
            for (begin in supportedRotations) {
                params.add(arrayOf(Surface.rotationToString(begin), begin))
            }
            return params
        }
    }
}
