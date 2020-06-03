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

import android.util.Log
import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Cycle through supported app rotations.
 * To run this test: `atest FlickerTest:ChangeAppRotationTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChangeAppRotationTest(
    beginRotationName: String,
    endRotationName: String,
    beginRotation: Int,
    endRotation: Int
) : RotationTestBase(beginRotationName, endRotationName, beginRotation, endRotation) {
    init {
        testApp = StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "SimpleApp")
    }

    override val transitionToRun: TransitionRunner
        get() = CommonTransitions.changeAppRotation(testApp, uiDevice,
                beginRotation, endRotation)
                .includeJankyRuns().build()

    @Test
    fun checkPosition_appLayerRotates() {
        val startingPos = WindowUtils.getAppPosition(beginRotation)
        val endingPos = WindowUtils.getAppPosition(endRotation)
        Log.e(TAG, "startingPos=$startingPos endingPos=$endingPos")
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .hasVisibleRegion(testApp.getPackage(), startingPos).inTheBeginning()
            LayersTraceSubject.assertThat(it)
                    .hasVisibleRegion(testApp.getPackage(), endingPos).atTheEnd()
        }
    }

    @Ignore("Flaky. Pending debug")
    @Test
    fun checkVisibility_screenshotLayerBecomesInvisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(testApp.getPackage())
                    .then()
                    .replaceVisibleLayer(testApp.getPackage(), SCREENSHOT_LAYER)
                    .then()
                    .showsLayer(testApp.getPackage()).and().showsLayer(SCREENSHOT_LAYER)
                    .then()
                    .replaceVisibleLayer(SCREENSHOT_LAYER, testApp.getPackage())
                    .forAllEntries()
        }
    }
}
