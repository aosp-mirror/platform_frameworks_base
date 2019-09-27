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

import static android.view.Surface.rotationToString;

import static com.android.server.wm.flicker.WindowUtils.getDisplayBounds;

import android.graphics.Rect;
import android.view.Surface;

import androidx.test.filters.FlakyTest;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Collection;

public abstract class NonRotationTestBase extends FlickerTestBase {

    int mBeginRotation;

    public NonRotationTestBase(String beginRotationName, int beginRotation) {
        this.mBeginRotation = beginRotation;
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> getParams() {
        int[] supportedRotations =
                {Surface.ROTATION_0, Surface.ROTATION_90};
        Collection<Object[]> params = new ArrayList<>();

        for (int begin : supportedRotations) {
            params.add(new Object[]{rotationToString(begin), begin});
        }

        return params;
    }

    @FlakyTest(bugId = 141361128)
    @Ignore("Waiting bug feedback")
    @Test
    public void checkCoveredRegion_noUncoveredRegions() {
        Rect displayBounds = getDisplayBounds(mBeginRotation);
        checkResults(result -> LayersTraceSubject.assertThat(result).coversRegion(
                displayBounds).forAllEntries());
    }

    @FlakyTest(bugId = 141361128)
    @Ignore("Waiting bug feedback")
    @Test
    public void checkVisibility_navBarLayerIsAlwaysVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(NAVIGATION_BAR_WINDOW_TITLE).forAllEntries());
    }

    @FlakyTest(bugId = 141361128)
    @Ignore("Waiting bug feedback")
    @Test
    public void checkVisibility_statusBarLayerIsAlwaysVisible() {
        checkResults(result -> LayersTraceSubject.assertThat(result)
                .showsLayer(STATUS_BAR_WINDOW_TITLE).forAllEntries());
    }
}
