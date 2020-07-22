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

package com.android.server.wm.flicker.rotation

import android.content.Intent
import android.view.Surface
import androidx.test.InstrumentationRegistry
import androidx.test.filters.FlakyTest
import androidx.test.filters.LargeTest
import com.android.server.wm.flicker.CommonTransitions
import com.android.server.wm.flicker.LayersTraceSubject
import com.android.server.wm.flicker.RotationTestBase
import com.android.server.wm.flicker.TransitionRunner
import com.android.server.wm.flicker.WindowUtils
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Cycle through supported app rotations using seamless rotations.
 * To run this test: `atest FlickerTests:SeamlessAppRotationTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 147659548)
class SeamlessAppRotationTest(
    private val intent: Intent,
    beginRotationName: String,
    endRotationName: String,
    beginRotation: Int,
    endRotation: Int
) : RotationTestBase(beginRotationName, endRotationName, beginRotation, endRotation) {
    override val transitionToRun: TransitionRunner
        get() {
            var intentId = ""
            if (intent.extras?.getBoolean(ActivityOptions.EXTRA_STARVE_UI_THREAD) == true) {
                intentId = "BUSY_UI_THREAD"
            }
            return CommonTransitions.changeAppRotation(intent, intentId,
                    InstrumentationRegistry.getContext(), instrumentation, uiDevice,
                    beginRotation, endRotation).build()
        }

    @Test
    fun checkPosition_appLayerRotates() {
        val startingPos = WindowUtils.getAppPosition(beginRotation)
        val endingPos = WindowUtils.getAppPosition(endRotation)
        if (startingPos == endingPos) {
            checkResults {
                LayersTraceSubject.assertThat(it)
                        .hasVisibleRegion(intent.component?.packageName ?: "", startingPos)
                        .forAllEntries()
            }
        } else {
            checkResults {
                LayersTraceSubject.assertThat(it)
                        .hasVisibleRegion(intent.component?.packageName ?: "", startingPos)
                        .then()
                        .hasVisibleRegion(intent.component?.packageName ?: "", endingPos)
                        .forAllEntries()
            }
        }
    }

    @Test
    fun checkCoveredRegion_noUncoveredRegions() {
        val startingBounds = WindowUtils.getDisplayBounds(beginRotation)
        val endingBounds = WindowUtils.getDisplayBounds(endRotation)
        if (startingBounds == endingBounds) {
            checkResults {
                LayersTraceSubject.assertThat(it)
                        .coversRegion(startingBounds)
                        .forAllEntries()
            }
        } else {
            checkResults {
                LayersTraceSubject.assertThat(it)
                        .coversRegion(startingBounds)
                        .then()
                        .coversRegion(endingBounds)
                        .forAllEntries()
            }
        }
    }

    companion object {
        // launch test activity that supports seamless rotation

        // launch test activity that supports seamless rotation with a busy UI thread to miss frames
        // when the app is asked to redraw
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0, Surface.ROTATION_90)
            val params: MutableCollection<Array<Any>> = ArrayList()
            val testIntents = ArrayList<Intent>()

            // launch test activity that supports seamless rotation
            var intent = Intent(Intent.ACTION_MAIN)
            intent.component = ActivityOptions.SEAMLESS_ACTIVITY_COMPONENT_NAME
            testIntents.add(intent)

            // launch test activity that supports seamless rotation with a busy UI thread to miss frames
            // when the app is asked to redraw
            intent = Intent(intent)
            intent.putExtra(ActivityOptions.EXTRA_STARVE_UI_THREAD, true)
            testIntents.add(intent)
            for (testIntent in testIntents) {
                for (begin in supportedRotations) {
                    for (end in supportedRotations) {
                        if (begin != end) {
                            var testId: String = Surface.rotationToString(begin) +
                                    "_" + Surface.rotationToString(end)
                            if (testIntent.extras?.getBoolean(
                                            ActivityOptions.EXTRA_STARVE_UI_THREAD) == true) {
                                testId += "_" + "BUSY_UI_THREAD"
                            }
                            params.add(arrayOf(testId, testIntent, begin, end))
                        }
                    }
                }
            }
            return params
        }
    }
}
