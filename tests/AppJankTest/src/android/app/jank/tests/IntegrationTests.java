/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.jank.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.jank.AppJankStats;
import android.app.jank.Flags;
import android.app.jank.JankDataProcessor;
import android.app.jank.JankTracker;
import android.app.jank.StateTracker;
import android.content.Intent;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * This file contains tests that verify the proper functionality of the Jank Tracking feature.
 * All tests should obtain references to necessary objects through View type interfaces, rather
 * than direct instantiation. When operating outside of a testing environment, the expected
 * behavior is to retrieve the necessary objects using View type interfaces. This approach ensures
 * that calls are correctly routed down to the activity level. Any modifications to the call
 * routing should result in test failures, which might happen with direct instantiations.
 */
@RunWith(AndroidJUnit4.class)
public class IntegrationTests {
    public static final int WAIT_FOR_TIMEOUT_MS = 5000;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private Activity mEmptyActivity;

    public UiDevice mDevice;
    private Instrumentation mInstrumentation;
    private ActivityTestRule<JankTrackerActivity> mJankTrackerActivityRule =
            new ActivityTestRule<>(
                    JankTrackerActivity.class,
                    false,
                    false);

    private ActivityTestRule<EmptyActivity> mEmptyActivityRule =
            new ActivityTestRule<>(EmptyActivity.class, false , true);

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mDevice = UiDevice.getInstance(mInstrumentation);
    }


    /**
     * Get a JankTracker object from a view and confirm it's not null.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void getJankTacker_confirmNotNull() {
        Activity jankTrackerActivity = mJankTrackerActivityRule.launchActivity(null);
        EditText editText = jankTrackerActivity.findViewById(R.id.edit_text);

        mDevice.wait(Until.findObject(By.text("Edit Text")), WAIT_FOR_TIMEOUT_MS);

        JankTracker jankTracker = editText.getJankTracker();
        assertTrue(jankTracker != null);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void reportJankStats_confirmPendingStatsIncreases() {
        Activity jankTrackerActivity = mJankTrackerActivityRule.launchActivity(null);
        EditText editText = jankTrackerActivity.findViewById(R.id.edit_text);
        JankTracker jankTracker = editText.getJankTracker();

        HashMap<String, JankDataProcessor.PendingJankStat> pendingStats =
                jankTracker.getPendingJankStats();
        assertEquals(0, pendingStats.size());

        editText.reportAppJankStats(JankUtils.getAppJankStats());

        // reportAppJankStats performs the work on a background thread, check periodically to see
        // if the work is complete.
        for (int i = 0; i < 10; i++) {
            try {
                Thread.sleep(100);
                if (jankTracker.getPendingJankStats().size() > 0) {
                    break;
                }
            } catch (InterruptedException exception) {
                //do nothing and continue
            }
        }

        pendingStats = jankTracker.getPendingJankStats();

        assertEquals(1, pendingStats.size());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void simulateWidgetStateChanges_confirmStateChangesAreTracked() {
        JankTrackerActivity jankTrackerActivity =
                mJankTrackerActivityRule.launchActivity(null);
        TestWidget testWidget = jankTrackerActivity.findViewById(R.id.jank_tracker_widget);
        JankTracker jankTracker = testWidget.getJankTracker();
        jankTracker.forceListenerRegistration();

        ArrayList<StateTracker.StateData> uiStates = new ArrayList<>();
        // Get the current UI states, at this point only the activity name should be in the UI
        // states list.
        jankTracker.getAllUiStates(uiStates);

        assertEquals(1, uiStates.size());

        // This should add a UI state to be tracked.
        testWidget.simulateAnimationStarting();
        uiStates.clear();
        jankTracker.getAllUiStates(uiStates);

        assertEquals(2, uiStates.size());

        // Stop the animation
        testWidget.simulateAnimationEnding();
        uiStates.clear();
        jankTracker.getAllUiStates(uiStates);

        assertEquals(2, uiStates.size());

        // Confirm the Animation state has a VsyncIdEnd that is not default, indicating the end
        // of that state.
        for (int i = 0; i < uiStates.size(); i++) {
            StateTracker.StateData stateData = uiStates.get(i);
            if (stateData.mWidgetCategory.equals(AppJankStats.ANIMATION)) {
                assertNotEquals(Long.MAX_VALUE, stateData.mVsyncIdEnd);
            }
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void jankTrackingPaused_whenActivityNoLongerVisible() {
        JankTrackerActivity jankTrackerActivity =
                mJankTrackerActivityRule.launchActivity(null);
        TestWidget testWidget = jankTrackerActivity.findViewById(R.id.jank_tracker_widget);
        JankTracker jankTracker = testWidget.getJankTracker();
        jankTracker.forceListenerRegistration();

        assertTrue(jankTracker.shouldTrack());

        // Send jankTrackerActivity to the background
        mDevice.pressHome();
        mDevice.waitForIdle(WAIT_FOR_TIMEOUT_MS);

        assertFalse(jankTracker.shouldTrack());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void jankTrackingResumed_whenActivityBecomesVisibleAgain() {
        mEmptyActivityRule.launchActivity(null);
        mEmptyActivity = mEmptyActivityRule.getActivity();
        JankTrackerActivity jankTrackerActivity =
                mJankTrackerActivityRule.launchActivity(null);
        TestWidget testWidget = jankTrackerActivity.findViewById(R.id.jank_tracker_widget);
        JankTracker jankTracker = testWidget.getJankTracker();
        jankTracker.forceListenerRegistration();

        // Send jankTrackerActivity to the background
        mDevice.pressHome();
        mDevice.waitForIdle(WAIT_FOR_TIMEOUT_MS);

        assertFalse(jankTracker.shouldTrack());

        Intent resumeJankTracker = new Intent(mInstrumentation.getContext(),
                JankTrackerActivity.class);
        resumeJankTracker.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        mEmptyActivity.startActivity(resumeJankTracker);
        mDevice.wait(Until.findObject(By.text("Edit Text")), WAIT_FOR_TIMEOUT_MS);

        assertTrue(jankTracker.shouldTrack());
    }
}
