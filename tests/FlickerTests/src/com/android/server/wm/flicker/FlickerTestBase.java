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

import static com.android.server.wm.flicker.helpers.AutomationUtils.setDefaultWait;

import static com.google.common.truth.Truth.assertWithMessage;

import android.platform.helpers.IAppHelper;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.server.wm.flicker.TransitionRunner.TransitionResult;

import org.junit.After;
import org.junit.AfterClass;

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
    static final String NAVIGATION_BAR_WINDOW_TITLE = "NavigationBar";
    static final String STATUS_BAR_WINDOW_TITLE = "StatusBar";
    static final String DOCKED_STACK_DIVIDER = "DockedStackDivider";
    private static HashMap<String, List<TransitionResult>> transitionResults =
            new HashMap<>();
    IAppHelper testApp;
    UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private List<TransitionResult> results;
    private TransitionResult lastResult = null;

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
                                    .screenCaptureVideo.toString());
                        }
                    }
                });
    }

    /**
     * Runs a transition, returns a cached result if the transition has run before.
     */
    void runTransition(TransitionRunner transition) {
        if (transitionResults.containsKey(transition.getTestTag())) {
            results = transitionResults.get(transition.getTestTag());
            return;
        }
        results = transition.run().getResults();
        /* Fail if we don't have any results due to jank */
        assertWithMessage("No results to test because all transition runs were invalid because "
                + "of Jank").that(results).isNotEmpty();
        transitionResults.put(transition.getTestTag(), results);
    }

    /**
     * Goes through a list of transition results and checks assertions on each result.
     */
    void checkResults(Consumer<TransitionResult> assertion) {

        for (TransitionResult result : results) {
            lastResult = result;
            assertion.accept(result);
        }
        lastResult = null;
    }

    /**
     * Kludge to mark a file for saving. If {@code checkResults} fails, the last result is not
     * cleared. This indicates the assertion failed for the result, so mark it for saving.
     */
    @After
    public void markArtifactsForSaving() {
        if (lastResult != null) {
            lastResult.flagForSaving();
        }
    }
}
