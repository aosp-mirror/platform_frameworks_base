/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.server.wm.flicker.CommonTransitions.exitPipModeToHome;

import android.view.Surface;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.wm.flicker.helpers.PipAppHelper;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/**
 * Test Pip launch.
 * To run this test: {@code atest FlickerTests:PipToHomeTest}
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PipToHomeTest extends FlickerTestBase {

    static final String sPipWindowTitle = "PipActivity";

    // public PipToHomeTest(String beginRotationName, int beginRotation) {
    public PipToHomeTest() {
        // super(beginRotationName, beginRotation);

        this.mTestApp = new PipAppHelper(InstrumentationRegistry.getInstrumentation());
    }

    @Before
    public void runTransition() {
        // run(exitPipModeToHome((PipAppHelper) mTestApp, mUiDevice, mBeginRotation)
        run(exitPipModeToHome((PipAppHelper) mTestApp, mUiDevice, Surface.ROTATION_0)
                .includeJankyRuns().build());
    }

    @Ignore
    @Test
    public void checkVisibility_pipWindowBecomesVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .skipUntilFirstAssertion()
                .showsAppWindowOnTop(sPipWindowTitle)
                .then()
                .hidesAppWindow(sPipWindowTitle)
                .forAllEntries());
    }

    @Test
    public void checkVisibility_pipLayerBecomesVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .skipUntilFirstAssertion()
                .showsLayer(sPipWindowTitle)
                .then()
                .hidesLayer(sPipWindowTitle)
                .forAllEntries());
    }

    @Ignore
    @Test
    public void checkVisibility_backgroundWindowVisibleBehindPipLayer() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAppWindowOnTop(sPipWindowTitle)
                .then()
                .showsBelowAppWindow("Wallpaper")
                .then()
                .showsAppWindowOnTop("Wallpaper")
                .forAllEntries());
    }
}
