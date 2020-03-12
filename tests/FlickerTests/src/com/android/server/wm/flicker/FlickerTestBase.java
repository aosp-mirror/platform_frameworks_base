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

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static com.android.server.wm.flicker.helpers.AutomationUtils.setDefaultWait;

import static com.google.common.truth.Truth.assertWithMessage;

import android.os.Bundle;
import android.platform.helpers.IAppHelper;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;

import com.android.server.wm.flicker.TransitionRunner.TransitionResult;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * Base class of all Flicker test that performs common functions for all flicker tests:
 * <p>
 * - Caches transitions so that a transition is run once and the transition results are used by
 * tests multiple times. This is needed for parameterized tests which call the BeforeClass methods
 * multiple times.
 * - Keeps track of all test artifacts and deletes ones which do not need to be reviewed.
 * - Fails tests if results are not available for any test due to jank.
 */
public class FlickerTestBase {
    public static final String TAG = "FLICKER";
    static final String SCREENSHOT_LAYER = "RotationLayer";
    static final String NAVIGATION_BAR_WINDOW_TITLE = "NavigationBar";
    static final String STATUS_BAR_WINDOW_TITLE = "StatusBar";
    static final String DOCKED_STACK_DIVIDER = "DockedStackDivider";
    private static HashMap<String, List<TransitionResult>> transitionResults =
            new HashMap<>();
    IAppHelper mTestApp;
    UiDevice mUiDevice;
    private List<TransitionResult> mResults;
    private TransitionResult mLastResult = null;

    @Before
    public void setUp() {
        InstrumentationRegistry.registerInstance(getInstrumentation(), new Bundle());
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    /**
     * Teardown any system settings and clean up test artifacts from the file system.
     *
     * Note: test artifacts for failed tests will remain on the device.
     */
    @AfterClass
    public static void teardown() {
        setDefaultWait();
        transitionResults.values().stream()
                .flatMap(List::stream)
                .forEach(result -> {
                    if (result.canDelete()) {
                        result.delete();
                    } else {
                        if (result.layersTraceExists()) {
                            Log.e(TAG, "Layers trace saved to " + result.getLayersTracePath());
                        }
                        if (result.windowManagerTraceExists()) {
                            Log.e(TAG, "WindowManager trace saved to " + result
                                    .getWindowManagerTracePath
                                            ());
                        }
                        if (result.screenCaptureVideoExists()) {
                            Log.e(TAG, "Screen capture video saved to " + result
                                    .screenCaptureVideoPath().toString());
                        }
                    }
                });
    }

    /**
     * Runs a transition, returns a cached result if the transition has run before.
     */
    void run(TransitionRunner transition) {
        if (transitionResults.containsKey(transition.getTestTag())) {
            mResults = transitionResults.get(transition.getTestTag());
            return;
        }
        mResults = transition.run().getResults();
        /* Fail if we don't have any results due to jank */
        assertWithMessage("No results to test because all transition runs were invalid because "
                + "of Jank").that(mResults).isNotEmpty();
        transitionResults.put(transition.getTestTag(), mResults);
    }

    /**
     * Runs a transition, returns a cached result if the transition has run before.
     */
    void runTransition(TransitionRunner transition) {
        run(transition);
    }

    /**
     * Goes through a list of transition results and checks assertions on each result.
     */
    void checkResults(Consumer<TransitionResult> assertion) {

        for (TransitionResult result : mResults) {
            mLastResult = result;
            assertion.accept(result);
        }
        mLastResult = null;
    }

    /**
     * Kludge to mark a file for saving. If {@code checkResults} fails, the last result is not
     * cleared. This indicates the assertion failed for the result, so mark it for saving.
     */
    @After
    public void markArtifactsForSaving() {
        if (mLastResult != null) {
            mLastResult.flagForSaving();
        }
    }
}
