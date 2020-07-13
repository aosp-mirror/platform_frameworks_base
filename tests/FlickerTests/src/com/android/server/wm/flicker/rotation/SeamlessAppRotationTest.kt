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
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.RotationTestBase
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.dsl.flicker
import com.android.server.wm.flicker.helpers.stopPackage
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
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
    testId: String,
    private val intent: Intent,
    beginRotationName: String,
    endRotationName: String,
    beginRotation: Int,
    endRotation: Int
) : RotationTestBase(beginRotationName, endRotationName, beginRotation, endRotation) {
    @Test
    fun test() {
        var intentId = ""
        if (intent.extras?.getBoolean(ActivityOptions.EXTRA_STARVE_UI_THREAD) == true) {
            intentId = "BUSY_UI_THREAD"
        }

        flicker(instrumentation) {
            withTag {
                "changeAppRotation_" + intentId + "_" +
                        Surface.rotationToString(beginRotation) + "_" +
                        Surface.rotationToString(endRotation)
            }
            repeat { 1 }
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    instrumentation.targetContext.startActivity(intent)
                    device.wait(Until.hasObject(By.pkg(intent.component?.packageName)
                            .depth(0)), APP_LAUNCH_TIMEOUT)
                    this.setRotation(beginRotation)
                }
            }
            teardown {
                eachRun {
                    stopPackage(
                            instrumentation.targetContext,
                            intent.component?.packageName
                                    ?: error("Unable to determine package name for intent"))
                    this.setRotation(Surface.ROTATION_0)
                }
            }
            transitions {
                this.setRotation(endRotation)
            }
            assertions {
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible(bugId = 140855415)
                    statusBarWindowIsAlwaysVisible(bugId = 140855415)
                }

                layersTrace {
                    navBarLayerIsAlwaysVisible(bugId = 140855415)
                    statusBarLayerIsAlwaysVisible(bugId = 140855415)
                    noUncoveredRegions(beginRotation, endRotation, allStates = true)
                    navBarLayerRotatesAndScales(beginRotation, endRotation)
                    statusBarLayerRotatesScales(beginRotation, endRotation, enabled = false)
                }

                layersTrace {
                    all("appLayerRotates"/*, bugId = 147659548*/) {
                        val startingPos = WindowUtils.getDisplayBounds(beginRotation)
                        val endingPos = WindowUtils.getDisplayBounds(endRotation)

                        if (startingPos == endingPos) {
                            this.hasVisibleRegion(
                                    intent.component?.packageName ?: "",
                                    startingPos)
                        } else {
                            this.hasVisibleRegion(intent.component?.packageName ?: "", startingPos)
                                    .then()
                                    .hasVisibleRegion(intent.component?.packageName
                                            ?: "", endingPos)
                        }
                    }

                    all("noUncoveredRegions"/*, bugId = 147659548*/) {
                        val startingBounds = WindowUtils.getDisplayBounds(beginRotation)
                        val endingBounds = WindowUtils.getDisplayBounds(endRotation)
                        if (startingBounds == endingBounds) {
                            this.coversRegion(startingBounds)
                        } else {
                            this.coversRegion(startingBounds)
                                    .then()
                                    .coversRegion(endingBounds)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val APP_LAUNCH_TIMEOUT: Long = 10000

        // launch test activity that supports seamless rotation with a busy UI thread to miss frames
        // when the app is asked to redraw
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0, Surface.ROTATION_90)
            val params = mutableListOf<Array<Any>>()
            val testIntents = mutableListOf<Intent>()

            // launch test activity that supports seamless rotation
            var intent = Intent(Intent.ACTION_MAIN)
            intent.component = ActivityOptions.SEAMLESS_ACTIVITY_COMPONENT_NAME
            intent.flags = FLAG_ACTIVITY_NEW_TASK
            testIntents.add(intent)

            // launch test activity that supports seamless rotation with a busy UI thread to miss frames
            // when the app is asked to redraw
            intent = Intent(intent)
            intent.putExtra(ActivityOptions.EXTRA_STARVE_UI_THREAD, true)
            intent.flags = FLAG_ACTIVITY_NEW_TASK
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
                            params.add(arrayOf(
                                    testId,
                                    testIntent,
                                    Surface.rotationToString(begin),
                                    Surface.rotationToString(end),
                                    begin,
                                    end))
                        }
                    }
                }
            }
            return params
        }
    }
}