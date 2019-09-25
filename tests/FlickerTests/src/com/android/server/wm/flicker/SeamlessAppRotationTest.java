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

import static android.view.Surface.rotationToString;

import static com.android.server.wm.flicker.CommonTransitions.changeAppRotation;
import static com.android.server.wm.flicker.WindowUtils.getAppPosition;
import static com.android.server.wm.flicker.WindowUtils.getDisplayBounds;
import static com.android.server.wm.flicker.WindowUtils.getNavigationBarPosition;
import static com.android.server.wm.flicker.testapp.ActivityOptions.EXTRA_STARVE_UI_THREAD;
import static com.android.server.wm.flicker.testapp.ActivityOptions.SEAMLESS_ACTIVITY_COMPONENT_NAME;

import android.content.Intent;
import android.graphics.Rect;
import android.view.Surface;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Cycle through supported app rotations using seamless rotations.
 * To run this test: {@code atest FlickerTests:SeamlessAppRotationTest}
 */
@LargeTest
@RunWith(Parameterized.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SeamlessAppRotationTest extends FlickerTestBase {
    private int mBeginRotation;
    private int mEndRotation;
    private Intent mIntent;

    public SeamlessAppRotationTest(String testId, Intent intent, int beginRotation,
            int endRotation) {
        this.mIntent = intent;
        this.mBeginRotation = beginRotation;
        this.mEndRotation = endRotation;
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> getParams() {
        int[] supportedRotations =
                {Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_270};
        Collection<Object[]> params = new ArrayList<>();

        ArrayList<Intent> testIntents = new ArrayList<>();

        // launch test activity that supports seamless rotation
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(SEAMLESS_ACTIVITY_COMPONENT_NAME);
        testIntents.add(intent);

        // launch test activity that supports seamless rotation with a busy UI thread to miss frames
        // when the app is asked to redraw
        intent = new Intent(intent);
        intent.putExtra(EXTRA_STARVE_UI_THREAD, true);
        testIntents.add(intent);

        for (Intent testIntent : testIntents) {
            for (int begin : supportedRotations) {
                for (int end : supportedRotations) {
                    if (begin != end) {
                        String testId = rotationToString(begin) + "_" + rotationToString(end);
                        if (testIntent.getExtras() != null &&
                                testIntent.getExtras().getBoolean(EXTRA_STARVE_UI_THREAD)) {
                            testId += "_" + "BUSY_UI_THREAD";
                        }
                        params.add(new Object[]{testId, testIntent, begin, end});
                    }
                }
            }
        }
        return params;
    }

    @Before
    public void runTransition() {
        String intentId = "";
        if (mIntent.getExtras() != null &&
                mIntent.getExtras().getBoolean(EXTRA_STARVE_UI_THREAD)) {
            intentId = "BUSY_UI_THREAD";
        }

        super.runTransition(
                changeAppRotation(mIntent, intentId, InstrumentationRegistry.getContext(),
                        mUiDevice, mBeginRotation, mEndRotation).repeat(5).build());
    }

    @Test
    public void checkVisibility_navBarWindowIsAlwaysVisible() {
        checkResults(result -> WmTraceSubject.assertThat(result)
                .showsAboveAppWindow(NAVIGATION_BAR_WINDOW_TITLE).forAllEntries());
    }

    @Test
    public void checkPosition_navBarLayerRotatesAndScales() {
        Rect startingPos = getNavigationBarPosition(mBeginRotation);
        Rect endingPos = getNavigationBarPosition(mEndRotation);
        if (startingPos.equals(endingPos)) {
            checkResults(result -> LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(NAVIGATION_BAR_WINDOW_TITLE, startingPos)
                    .forAllEntries());
        } else {
            checkResults(result -> LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(NAVIGATION_BAR_WINDOW_TITLE, startingPos)
                    .inTheBeginning());
            checkResults(result -> LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(NAVIGATION_BAR_WINDOW_TITLE, endingPos)
                    .atTheEnd());
        }
    }

    @Test
    public void checkPosition_appLayerRotates() {
        Rect startingPos = getAppPosition(mBeginRotation);
        Rect endingPos = getAppPosition(mEndRotation);
        if (startingPos.equals(endingPos)) {
            checkResults(result -> LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(mIntent.getComponent().getPackageName(), startingPos)
                    .forAllEntries());
        } else {
            checkResults(result -> LayersTraceSubject.assertThat(result)
                    .hasVisibleRegion(mIntent.getComponent().getPackageName(), startingPos)
                    .then()
                    .hasVisibleRegion(mIntent.getComponent().getPackageName(), endingPos)
                    .forAllEntries());
        }
    }

    @Test
    public void checkCoveredRegion_noUncoveredRegions() {
        Rect startingBounds = getDisplayBounds(mBeginRotation);
        Rect endingBounds = getDisplayBounds(mEndRotation);
        if (startingBounds.equals(endingBounds)) {
            checkResults(result ->
                    LayersTraceSubject.assertThat(result)
                            .coversRegion(startingBounds)
                            .forAllEntries());
        } else {
            checkResults(result ->
                    LayersTraceSubject.assertThat(result)
                            .coversRegion(startingBounds)
                            .then()
                            .coversRegion(endingBounds)
                            .forAllEntries());
        }
    }
}
