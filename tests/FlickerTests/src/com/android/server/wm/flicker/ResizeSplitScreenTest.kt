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

import android.graphics.Region
import android.util.Rational
import android.view.Surface
import androidx.test.InstrumentationRegistry
import androidx.test.filters.FlakyTest
import androidx.test.filters.LargeTest
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.android.server.wm.flicker.helpers.AutomationUtils
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.google.common.truth.Truth
import org.junit.AfterClass
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Test split screen resizing window transitions.
 * To run this test: `atest FlickerTests:ResizeSplitScreenTest`
 *
 * Currently it runs only in 0 degrees because of b/156100803
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 159096424)
@Ignore("Waiting bug feedback")
class ResizeSplitScreenTest : FlickerTestBase() {
    init {
        testApp = StandardAppHelper(instrumentation,
                "com.android.server.wm.flicker.testapp", "SimpleApp")
    }

    override val transitionToRun: TransitionRunner
        get() {
            val bottomApp = ImeAppHelper(instrumentation)
            return CommonTransitions.resizeSplitScreen(testApp, bottomApp, instrumentation,
                    uiDevice, Surface.ROTATION_0,
                    Rational(1, 3), Rational(2, 3))
                    .includeJankyRuns().build()
        }

    @Test
    fun checkVisibility_topAppLayerIsAlwaysVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(sSimpleActivity)
                    .forAllEntries()
        }
    }

    @Test
    fun checkVisibility_bottomAppLayerIsAlwaysVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(sImeActivity)
                    .forAllEntries()
        }
    }

    @Test
    fun checkVisibility_dividerLayerIsAlwaysVisible() {
        checkResults {
            LayersTraceSubject.assertThat(it)
                    .showsLayer(DOCKED_STACK_DIVIDER)
                    .forAllEntries()
        }
    }

    @Test
    @Ignore("Waiting feedback")
    fun checkPosition_appsStartingBounds() {
        val displayBounds = WindowUtils.getDisplayBounds()
        checkResults { result: TransitionResult ->
            val entries = LayersTrace.parseFrom(result.layersTrace,
                    result.layersTracePath, result.layersTraceChecksum)
            Truth.assertThat(entries.entries).isNotEmpty()
            val startingDividerBounds = entries.entries[0].getVisibleBounds(
                    DOCKED_STACK_DIVIDER).bounds
            val startingTopAppBounds = Region(0, 0, startingDividerBounds.right,
                    startingDividerBounds.top + WindowUtils.getDockedStackDividerInset())
            val startingBottomAppBounds = Region(0,
                    startingDividerBounds.bottom - WindowUtils.getDockedStackDividerInset(),
                    displayBounds.right,
                    displayBounds.bottom - WindowUtils.getNavigationBarHeight())
            LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion("SimpleActivity", startingTopAppBounds)
                    .inTheBeginning()
            LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion("ImeActivity", startingBottomAppBounds)
                    .inTheBeginning()
        }
    }

    @Test
    @Ignore("Waiting feedback")
    fun checkPosition_appsEndingBounds() {
        val displayBounds = WindowUtils.getDisplayBounds()
        checkResults { result: TransitionResult ->
            val entries = LayersTrace.parseFrom(result.layersTrace,
                    result.layersTracePath, result.layersTraceChecksum)
            Truth.assertThat(entries.entries).isNotEmpty()
            val endingDividerBounds = entries.entries[entries.entries.size - 1].getVisibleBounds(
                    DOCKED_STACK_DIVIDER).bounds
            val startingTopAppBounds = Region(0, 0, endingDividerBounds.right,
                    endingDividerBounds.top + WindowUtils.getDockedStackDividerInset())
            val startingBottomAppBounds = Region(0,
                    endingDividerBounds.bottom - WindowUtils.getDockedStackDividerInset(),
                    displayBounds.right,
                    displayBounds.bottom - WindowUtils.getNavigationBarHeight())
            LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(sSimpleActivity, startingTopAppBounds)
                    .atTheEnd()
            LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(sImeActivity, startingBottomAppBounds)
                    .atTheEnd()
        }
    }

    @Test
    fun checkVisibility_navBarWindowIsAlwaysVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAboveAppWindow(NAVIGATION_BAR_WINDOW_TITLE)
                    .forAllEntries()
        }
    }

    @Test
    fun checkVisibility_statusBarWindowIsAlwaysVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAboveAppWindow(STATUS_BAR_WINDOW_TITLE)
                    .forAllEntries()
        }
    }

    @Test
    @FlakyTest(bugId = 156223549)
    @Ignore("Waiting bug feedback")
    fun checkVisibility_topAppWindowIsAlwaysVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAppWindow(sSimpleActivity)
                    .forAllEntries()
        }
    }

    @Test
    @FlakyTest(bugId = 156223549)
    @Ignore("Waiting bug feedback")
    fun checkVisibility_bottomAppWindowIsAlwaysVisible() {
        checkResults {
            WmTraceSubject.assertThat(it)
                    .showsAppWindow(sImeActivity)
                    .forAllEntries()
        }
    }

    companion object {
        private const val sSimpleActivity = "SimpleActivity"
        private const val sImeActivity = "ImeActivity"

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
