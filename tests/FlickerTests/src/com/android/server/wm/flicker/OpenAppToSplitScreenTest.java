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

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.LargeTest;

import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;

/**
 * Test open app to split screen.
 * To run this test: {@code atest FlickerTests:OpenAppToSplitScreenTest}
 */
@LargeTest
@RunWith(Parameterized.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 151632128)
@Ignore("Waiting bug feedback")
public class OpenAppToSplitScreenTest extends NonRotationTestBase {

    public OpenAppToSplitScreenTest(String beginRotationName, int beginRotation) {
        super(beginRotationName, beginRotation);

        this.mTestApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "SimpleApp");
    }

    @Override
    TransitionRunner getTransitionToRun() {
        return appToSplitScreen(mTestApp, mUiDevice, mBeginRotation)
                .includeJankyRuns()
                .build();
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
    public void checkVisibility_dividerLayerBecomesVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .hidesLayer(DOCKED_STACK_DIVIDER)
                .then()
                .showsLayer(DOCKED_STACK_DIVIDER)
                .forAllEntries());
    }
}
