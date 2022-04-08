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

import static com.android.server.wm.flicker.CommonTransitions.resizeSplitScreen;
import static com.android.server.wm.flicker.WindowUtils.getDisplayBounds;
import static com.android.server.wm.flicker.WindowUtils.getDockedStackDividerInset;
import static com.android.server.wm.flicker.WindowUtils.getNavigationBarHeight;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Rect;
import android.util.Rational;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.LargeTest;

import com.android.server.wm.flicker.helpers.ImeAppHelper;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;

/**
 * Test split screen resizing window transitions.
 * To run this test: {@code atest FlickerTests:ResizeSplitScreenTest}
 */
@LargeTest
@RunWith(Parameterized.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 140854698)
@Ignore("Waiting bug feedback")
public class ResizeSplitScreenTest extends NonRotationTestBase {

    private static String sSimpleActivity = "SimpleActivity";
    private static String sImeActivity = "ImeActivity";

    public ResizeSplitScreenTest(String beginRotationName, int beginRotation) {
        super(beginRotationName, beginRotation);

        this.mTestApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "SimpleApp");
    }

    @Before
    public void runTransition() {
        ImeAppHelper bottomApp = new ImeAppHelper(InstrumentationRegistry.getInstrumentation());
        run(resizeSplitScreen(mTestApp, bottomApp, mUiDevice, mBeginRotation,
                new Rational(1, 3), new Rational(2, 3))
                .includeJankyRuns().build());
    }

    @Test
    public void checkVisibility_topAppLayerIsAlwaysVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(sSimpleActivity)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_bottomAppLayerIsAlwaysVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(sImeActivity)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_dividerLayerIsAlwaysVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(DOCKED_STACK_DIVIDER)
                .forAllEntries());
    }

    @Test
    public void checkPosition_appsStartingBounds() {
        Rect displayBounds = getDisplayBounds();
        checkResults(result -> {
            LayersTrace entries = LayersTrace.parseFrom(result.getLayersTrace(),
                    result.getLayersTracePath());

            assertThat(entries.getEntries()).isNotEmpty();
            Rect startingDividerBounds = entries.getEntries().get(0).getVisibleBounds
                    (DOCKED_STACK_DIVIDER);

            Rect startingTopAppBounds = new Rect(0, 0, startingDividerBounds.right,
                    startingDividerBounds.top + getDockedStackDividerInset());

            Rect startingBottomAppBounds = new Rect(0,
                    startingDividerBounds.bottom - getDockedStackDividerInset(),
                    displayBounds.right,
                    displayBounds.bottom - getNavigationBarHeight());

            LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion("SimpleActivity", startingTopAppBounds)
                    .inTheBeginning();

            LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion("ImeActivity", startingBottomAppBounds)
                    .inTheBeginning();
        });
    }

    @Test
    public void checkPosition_appsEndingBounds() {
        Rect displayBounds = getDisplayBounds();
        checkResults(result -> {
            LayersTrace entries = LayersTrace.parseFrom(result.getLayersTrace(),
                    result.getLayersTracePath());

            assertThat(entries.getEntries()).isNotEmpty();
            Rect endingDividerBounds = entries.getEntries().get(
                    entries.getEntries().size() - 1).getVisibleBounds(
                    DOCKED_STACK_DIVIDER);

            Rect startingTopAppBounds = new Rect(0, 0, endingDividerBounds.right,
                    endingDividerBounds.top + getDockedStackDividerInset());

            Rect startingBottomAppBounds = new Rect(0,
                    endingDividerBounds.bottom - getDockedStackDividerInset(),
                    displayBounds.right,
                    displayBounds.bottom - getNavigationBarHeight());

            LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(sSimpleActivity, startingTopAppBounds)
                    .atTheEnd();

            LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(sImeActivity, startingBottomAppBounds)
                    .atTheEnd();
        });
    }

    @Test
    public void checkVisibility_navBarWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAboveAppWindow(NAVIGATION_BAR_WINDOW_TITLE)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_statusBarWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAboveAppWindow(STATUS_BAR_WINDOW_TITLE)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_topAppWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAppWindow(sSimpleActivity)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_bottomAppWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAppWindow(sImeActivity)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_dividerWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAboveAppWindow(DOCKED_STACK_DIVIDER)
                .forAllEntries());
    }
}
