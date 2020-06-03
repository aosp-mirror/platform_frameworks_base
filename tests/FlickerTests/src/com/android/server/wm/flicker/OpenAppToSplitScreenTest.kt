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

import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest FlickerTests:OpenAppToSplitScreenTest`
 */
@LargeTest
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppToSplitScreenTest(
    beginRotationName: String,
    beginRotation: Int
) : NonRotationTestBase(beginRotationName, beginRotation) {
    init {
        testApp = StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "SimpleApp")
    }

    override val transitionToRun: TransitionRunner
        get() = CommonTransitions.appToSplitScreen(testApp, uiDevice, beginRotation)
                .includeJankyRuns()
                .build()

    @Test
    fun checkVisibility_navBarWindowIsAlwaysVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAboveAppWindow(NAVIGATION_BAR_WINDOW_TITLE).forAllEntries()
        }
    }

    @Test
    fun checkVisibility_statusBarWindowIsAlwaysVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAboveAppWindow(STATUS_BAR_WINDOW_TITLE).forAllEntries()
        }
    }

    @Test
    fun checkVisibility_dividerLayerBecomesVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .hidesLayer(DOCKED_STACK_DIVIDER)
                    .then()
                    .showsLayer(DOCKED_STACK_DIVIDER)
                    .forAllEntries()
        }
    }
}
