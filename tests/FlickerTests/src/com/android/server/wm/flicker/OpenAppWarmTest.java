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

import static com.android.server.wm.flicker.CommonTransitions.openAppWarm;
import static com.android.server.wm.flicker.WmTraceSubject.assertThat;

import android.view.Surface;

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
 * Test warm launch app.
 * To run this test: {@code atest FlickerTests:OpenAppWarmTest}
 */
@LargeTest
@RunWith(Parameterized.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class OpenAppWarmTest extends NonRotationTestBase {

    public OpenAppWarmTest(String beginRotationName, int beginRotation) {
        super(beginRotationName, beginRotation);

        this.mTestApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "SimpleApp");
    }

    @Override
    TransitionRunner getTransitionToRun() {
        return openAppWarm(mTestApp, mUiDevice, mBeginRotation)
                .includeJankyRuns().build();
    }

    @Test
    public void checkVisibility_wallpaperBecomesInvisible() {
        checkResults(result -> assertThat(result)
                .showsBelowAppWindow("Wallpaper")
                .then()
                .hidesBelowAppWindow("Wallpaper")
                .forAllEntries());
    }

    @FlakyTest(bugId = 140855415)
    @Ignore("Waiting bug feedback")
    @Test
    public void checkZOrder_appWindowReplacesLauncherAsTopWindow() {
        checkResults(result -> assertThat(result)
                .showsAppWindowOnTop(
                        "com.android.launcher3/.Launcher")
                .then()
                .showsAppWindowOnTop(mTestApp.getPackage())
                .forAllEntries());
    }

    @Ignore("Flaky. Pending debug")
    @Test
    public void checkVisibility_wallpaperLayerBecomesInvisible() {
        if (mBeginRotation == Surface.ROTATION_0) {
            checkResults(result -> LayersTraceSubject.assertThat(result)
                    .showsLayer("Wallpaper")
                    .then()
                    .replaceVisibleLayer("Wallpaper", mTestApp.getPackage())
                    .forAllEntries());
        } else {
            checkResults(result -> LayersTraceSubject.assertThat(result)
                    .showsLayer("Wallpaper")
                    .then()
                    .replaceVisibleLayer("Wallpaper", SCREENSHOT_LAYER)
                    .then()
                    .showsLayer(mTestApp.getPackage())
                    .forAllEntries());
        }
    }
}
