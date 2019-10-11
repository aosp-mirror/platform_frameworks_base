/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker;

import static com.android.server.wm.flicker.CommonTransitions.appToSplitScreen;
import static com.android.server.wm.flicker.WindowUtils.getDisplayBounds;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/**
 * Test open app to split screen.
 * To run this test: {@code atest FlickerTests:OpenAppToSplitScreenTest}
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class OpenAppToSplitScreenTest extends FlickerTestBase {

    public OpenAppToSplitScreenTest() {
        this.mTestApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "SimpleApp");
    }

    @Before
    public void runTransition() {
        super.runTransition(appToSplitScreen(mTestApp, mUiDevice).includeJankyRuns().build());
    }

    @Test
    public void checkVisibility_navBarWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAboveAppWindow(NAVIGATION_BAR_WINDOW_TITLE).forAllEntries());
    }

    @Test
    public void checkVisibility_statusBarWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAboveAppWindow(STATUS_BAR_WINDOW_TITLE).forAllEntries());
    }

    @Test
    public void checkVisibility_dividerWindowBecomesVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .hidesAboveAppWindow(DOCKED_STACK_DIVIDER)
                .then()
                .showsAboveAppWindow(DOCKED_STACK_DIVIDER)
                .forAllEntries());
    }

    @Test
    public void checkCoveredRegion_noUncoveredRegions() {
        checkResults(result ->
                LayersTraceSubject.assertThat(result)
                        .coversRegion(getDisplayBounds()).forAllEntries());
    }

    @Test
    public void checkVisibility_navBarLayerIsAlwaysVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(NAVIGATION_BAR_WINDOW_TITLE).forAllEntries());
    }

    @Test
    public void checkVisibility_statusBarLayerIsAlwaysVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(STATUS_BAR_WINDOW_TITLE).forAllEntries());
    }

    @Test
    public void checkVisibility_dividerLayerBecomesVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .hidesLayer(DOCKED_STACK_DIVIDER)
                .then()
                .showsLayer(DOCKED_STACK_DIVIDER)
                .forAllEntries());
    }
}
