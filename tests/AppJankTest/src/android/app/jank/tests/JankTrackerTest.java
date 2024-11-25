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
import static org.junit.Assert.assertNotNull;

import android.app.jank.Flags;
import android.app.jank.JankTracker;
import android.app.jank.StateTracker;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.Choreographer;
import android.view.View;

import androidx.test.annotation.UiThreadTest;
import androidx.test.core.app.ActivityScenario;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.policy.DecorView;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class JankTrackerTest {
    private Choreographer mChoreographer;
    private JankTracker mJankTracker;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    /**
     * Start an empty activity so decore view is not null when creating the JankTracker instance.
     */
    private static ActivityScenario<EmptyActivity> sEmptyActivityRule;

    private static String sActivityName;

    private static View sActivityDecorView;

    @BeforeClass
    public static void classSetup() {
        sEmptyActivityRule = ActivityScenario.launch(EmptyActivity.class);
        sEmptyActivityRule.onActivity(activity -> {
            sActivityDecorView = activity.getWindow().getDecorView();
            sActivityName = activity.toString();
        });
    }

    @AfterClass
    public static void classTearDown() {
        sEmptyActivityRule.close();
    }

    @Before
    @UiThreadTest
    public void setup() {
        mChoreographer = Choreographer.getInstance();
        mJankTracker = new JankTracker(mChoreographer, sActivityDecorView);
        mJankTracker.setActivityName(sActivityName);
    }

    /**
     * When jank tracking is enabled the activity name should be added as a state to associate
     * frames to it.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void jankTracking_WhenEnabled_ActivityAdded() {
        mJankTracker.enableAppJankTracking();

        ArrayList<StateTracker.StateData> stateData = new ArrayList<>();
        mJankTracker.getAllUiStates(stateData);

        assertEquals(1, stateData.size());

        StateTracker.StateData firstState = stateData.getFirst();

        assertEquals(sActivityName, firstState.mWidgetId);
    }

    /**
     * No states should be added when tracking is disabled.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void jankTrackingDisabled_StatesShouldNot_BeAddedToTracker() {
        mJankTracker.disableAppJankTracking();

        mJankTracker.addUiState("FAKE_CATEGORY", "FAKE_ID",
                "FAKE_STATE");

        ArrayList<StateTracker.StateData> stateData = new ArrayList<>();
        mJankTracker.getAllUiStates(stateData);

        assertEquals(0, stateData.size());
    }

    /**
     * The activity name as well as the test state should be added for frame association.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void jankTrackingEnabled_StatesShould_BeAddedToTracker() {
        mJankTracker.forceListenerRegistration();

        mJankTracker.enableAppJankTracking();
        mJankTracker.addUiState("FAKE_CATEGORY", "FAKE_ID",
                "FAKE_STATE");

        ArrayList<StateTracker.StateData> stateData = new ArrayList<>();
        mJankTracker.getAllUiStates(stateData);

        assertEquals(2, stateData.size());
    }

    /**
     * Activity state should only be added once even if jank tracking is enabled multiple times.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void jankTrackingEnabled_EnabledCalledTwice_ActivityStateOnlyAddedOnce() {
        mJankTracker.enableAppJankTracking();

        ArrayList<StateTracker.StateData> stateData = new ArrayList<>();
        mJankTracker.getAllUiStates(stateData);

        assertEquals(1, stateData.size());

        stateData.clear();

        mJankTracker.enableAppJankTracking();
        mJankTracker.getAllUiStates(stateData);

        assertEquals(1, stateData.size());
    }

    /**
     * Test confirms a JankTracker object is retrieved from the activity.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_LOGGING_ENABLED)
    public void jankTracker_NotNull_WhenRetrievedFromDecorView() {
        DecorView decorView  = (DecorView) sActivityDecorView;
        JankTracker jankTracker = decorView.getJankTracker();

        assertNotNull(jankTracker);
    }
}
