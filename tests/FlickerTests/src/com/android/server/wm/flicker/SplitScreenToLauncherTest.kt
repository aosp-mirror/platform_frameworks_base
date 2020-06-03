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
import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.android.server.wm.flicker.helpers.AutomationUtils
import org.junit.AfterClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Test open app to split screen.
 * To run this test: `atest FlickerTests:SplitScreenToLauncherTest`
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SplitScreenToLauncherTest : FlickerTestBase() {
    init {
        testApp = StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "SimpleApp")
    }

    override val transitionToRun: TransitionRunner
        get() = CommonTransitions.splitScreenToLauncher(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns().build()

    @Test
    fun checkCoveredRegion_noUncoveredRegions() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .coversRegion(WindowUtils.getDisplayBounds()).forAllEntries()
        }
    }

    @Test
    fun checkVisibility_dividerLayerBecomesInVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(DOCKED_STACK_DIVIDER)
                    .then()
                    .hidesLayer(DOCKED_STACK_DIVIDER)
                    .forAllEntries()
        }
    }

    @Test
    fun checkVisibility_appLayerBecomesInVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(testApp.getPackage())
                    .then()
                    .hidesLayer(testApp.getPackage())
                    .forAllEntries()
        }
    }

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

    companion object {
        @AfterClass
        @JvmStatic
        fun teardown() {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            if (AutomationUtils.isInSplitScreen(device)) {
                AutomationUtils.exitSplitScreen(device)
            }
        }
    }
}
