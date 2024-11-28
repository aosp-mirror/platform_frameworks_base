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
import static org.junit.Assert.assertTrue;

import android.app.jank.Flags;
import android.app.jank.StateTracker;
import android.app.jank.StateTracker.StateData;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.Choreographer;

import androidx.test.annotation.UiThreadTest;
import androidx.test.core.app.ActivityScenario;
import androidx.test.runner.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;


@RunWith(AndroidJUnit4.class)
public class StateTrackerTest {

    private static final String WIDGET_CATEGORY_NONE = "None";
    private static final String WIDGET_CATEGORY_SCROLL = "Scroll";
    private static final String WIDGET_STATE_IDLE = "Idle";
    private static final String WIDGET_STATE_SCROLLING = "Scrolling";
    private StateTracker mStateTracker;
    private Choreographer mChoreographer;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    /**
     * Start an empty activity so choreographer won't return -1 for vsyncid.
     */
    private static ActivityScenario<EmptyActivity> sEmptyActivityRule;

    @BeforeClass
    public static void classSetup() {
        sEmptyActivityRule = ActivityScenario.launch(EmptyActivity.class);
    }

    @AfterClass
    public static void classTearDown() {
        sEmptyActivityRule.close();
    }

    @Before
    @UiThreadTest
    public void setup() {
        mChoreographer = Choreographer.getInstance();
        mStateTracker = new StateTracker(mChoreographer);
    }

    /**
     * Check that the start vsyncid is added when the state is first added and end vsyncid is
     * set to the default value, indicating it has not been updated.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void addWidgetState_VerifyStateHasStartVsyncId() {
        mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                "addWidgetState_VerifyStateHasStartVsyncId");

        ArrayList<StateData> stateList = new ArrayList<StateData>();
        mStateTracker.retrieveAllStates(stateList);
        StateData stateData = stateList.get(0);

        assertTrue(stateData.mVsyncIdStart > 0);
        assertTrue(stateData.mVsyncIdEnd == Long.MAX_VALUE);
    }

    /**
     * Check that the end vsyncid is added when the state is removed.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void removeWidgetState_VerifyStateHasEndVsyncId() {

        mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                "removeWidgetState_VerifyStateHasEndVsyncId");
        mStateTracker.removeState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                "removeWidgetState_VerifyStateHasEndVsyncId");

        ArrayList<StateData> stateList = new ArrayList<StateData>();
        mStateTracker.retrieveAllStates(stateList);
        StateData stateData = stateList.get(0);

        assertTrue(stateData.mVsyncIdStart > 0);
        assertTrue(stateData.mVsyncIdEnd != Long.MAX_VALUE);
    }

    /**
     * Check that duplicate states are aggregated into only one active instance.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void addDuplicateStates_ConfirmStateCountOnlyOne() {
        mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                "addDuplicateStates_ConfirmStateCountOnlyOne");

        ArrayList<StateData> stateList = new ArrayList<>();
        mStateTracker.retrieveAllStates(stateList);

        assertEquals(stateList.size(), 1);

        mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                "addDuplicateStates_ConfirmStateCountOnlyOne");

        stateList.clear();

        mStateTracker.retrieveAllStates(stateList);

        assertEquals(stateList.size(), 1);
    }

    /**
     * Check that correct distinct states are returned when all states are retrieved.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void addThreeStateChanges_ConfirmThreeStatesReturned() {
        mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                "addThreeStateChanges_ConfirmThreeStatesReturned");
        mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                "addThreeStateChanges_ConfirmThreeStatesReturned_01");
        mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                "addThreeStateChanges_ConfirmThreeStatesReturned_02");

        ArrayList<StateData> stateList = new ArrayList<>();
        mStateTracker.retrieveAllStates(stateList);

        assertEquals(stateList.size(), 3);
    }

    /**
     * Confirm when states are added and removed the removed states are moved to the previousStates
     * list and returned when retrieveAllStates is called.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void simulateAddingSeveralStates() {
        for (int i = 0; i < 20; i++) {
            mStateTracker.removeState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                    String.format("simulateAddingSeveralStates_%s", i - 1));
            mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                    String.format("simulateAddingSeveralStates_%s", i));
        }

        ArrayList<StateData> stateList = new ArrayList<>();
        mStateTracker.retrieveAllStates(stateList);

        int countStatesWithEndVsync = 0;
        for (int i = 0; i < stateList.size(); i++) {
            if (stateList.get(i).mVsyncIdEnd != Long.MAX_VALUE) {
                countStatesWithEndVsync++;
            }
        }

        // The last state that was added would be an active state and should not have an associated
        // end vsyncid.
        assertEquals(19, countStatesWithEndVsync);
    }

    /**
     * Confirm once a state has been attributed to a frame it has been removed from the previous
     * state list.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void confirmProcessedStates_RemovedFromPreviousStateList() {
        for (int i = 0; i < 20; i++) {
            mStateTracker.removeState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                    String.format("simulateAddingSeveralStates_%s", i - 1));
            mStateTracker.putState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                    String.format("simulateAddingSeveralStates_%s", i));

            if (i == 19) {
                mStateTracker.removeState(WIDGET_CATEGORY_SCROLL, WIDGET_STATE_SCROLLING,
                        String.format("simulateAddingSeveralStates_%s", i));
            }
        }

        ArrayList<StateData> stateList = new ArrayList<>();
        mStateTracker.retrieveAllStates(stateList);

        assertEquals(20, stateList.size());

        // Simulate processing all the states.
        for (int i = 0; i < stateList.size(); i++) {
            stateList.get(i).mProcessed = true;
        }
        // Clear out all processed states.
        mStateTracker.stateProcessingComplete();

        stateList.clear();

        mStateTracker.retrieveAllStates(stateList);

        assertEquals(0, stateList.size());
    }
}
