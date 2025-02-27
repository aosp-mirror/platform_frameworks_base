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

import android.app.jank.AppJankStats;
import android.app.jank.Flags;
import android.app.jank.JankDataProcessor;
import android.app.jank.StateTracker;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.Choreographer;
import android.view.SurfaceControl;

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
import java.util.HashMap;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class JankDataProcessorTest {

    private Choreographer mChoreographer;
    private StateTracker mStateTracker;
    private JankDataProcessor mJankDataProcessor;
    private static final int NANOS_PER_MS = 1_000_000;
    private static String sActivityName;
    private static ActivityScenario<EmptyActivity> sEmptyActivityActivityScenario;
    private static final int APP_ID = 25;

    @BeforeClass
    public static void classSetup() {
        sEmptyActivityActivityScenario = ActivityScenario.launch(EmptyActivity.class);
        sActivityName = sEmptyActivityActivityScenario.toString();
    }

    @AfterClass
    public static void classTearDown() {
        sEmptyActivityActivityScenario.close();
    }

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    @UiThreadTest
    public void setup() {
        mChoreographer = Choreographer.getInstance();
        mStateTracker = new StateTracker(mChoreographer);
        mJankDataProcessor = new JankDataProcessor(mStateTracker);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void processJankData_multipleFramesAndStates_attributesTotalFramesCorrectly() {
        List<SurfaceControl.JankData> jankData = getMockJankData_vsyncId_inRange();
        mStateTracker.addPendingStateData(getMockStateData_vsyncId_inRange());

        mJankDataProcessor.processJankData(jankData, sActivityName, APP_ID);

        long totalFramesAttributed = getTotalFramesCounted();

        // Each state is active for each frame that is passed in, there are two states being tested
        // which is why jankData.size is multiplied by 2.
        assertEquals(jankData.size() * 2, totalFramesAttributed);
    }

    /**
     * Each JankData frame has an associated vsyncid, only frames that have vsyncids between the
     * StatData start and end vsyncids should be counted.  This test confirms that if JankData
     * does not share any frames with the states then no jank stats are added.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void processJankData_outOfRangeVsyncId_skipOutOfRangeVsyncIds() {
        List<SurfaceControl.JankData> jankData = getMockJankData_vsyncId_inRange();
        mStateTracker.addPendingStateData(getMockStateData_vsyncId_outOfRange());

        mJankDataProcessor.processJankData(jankData, sActivityName, APP_ID);

        assertEquals(0, mJankDataProcessor.getPendingJankStats().size());
    }

    /**
     * It's expected to see many duplicate widget states, if a user is scrolling then
     * pauses and resumes scrolling again, we may get three widget states two of which are the same.
     * State 1: {Scroll,WidgetId,Scrolling} State 2: {Scroll,WidgetId,None}
     * State 3: {Scroll,WidgetId,Scrolling}
     * These duplicate states should coalesce into only one Jank stat. This test confirms that
     * behavior.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void processJankData_duplicateStates_confirmDuplicatesCoalesce() {
        // getMockStateData will return 10 states 5 of which are set to none and 5 of which are
        // scrolling.
        mStateTracker.addPendingStateData(getMockStateData_vsyncId_inRange());

        mJankDataProcessor.processJankData(getMockJankData_vsyncId_inRange(), sActivityName,
                APP_ID);

        // Confirm the duplicate states are coalesced down to 2 stats 1 for the scrolling state
        // another for the none state.
        assertEquals(2, mJankDataProcessor.getPendingJankStats().size());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void processJankData_inRangeVsyncIds_confirmOnlyInRangeFramesCounted() {
        List<SurfaceControl.JankData> jankData = getMockJankData_vsyncId_inRange();
        int inRangeFrameCount = jankData.size();

        mStateTracker.addPendingStateData(getMockStateData_vsyncId_inRange());
        mJankDataProcessor.processJankData(jankData, sActivityName, APP_ID);

        // Two states are active for each frame which is why inRangeFrameCount is multiplied by 2.
        assertEquals(inRangeFrameCount * 2, getTotalFramesCounted());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void processJankData_inRangeVsyncIds_confirmHistogramCountMatchesFrameCount() {
        List<SurfaceControl.JankData> jankData = getMockJankData_vsyncId_inRange();
        mStateTracker.addPendingStateData(getMockStateData_vsyncId_inRange());
        mJankDataProcessor.processJankData(jankData, sActivityName, APP_ID);

        long totalFrames = getTotalFramesCounted();
        long histogramFrames = getHistogramFrameCount();

        assertEquals(totalFrames, histogramFrames);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void mergeAppJankStats_confirmStatAddedToPendingStats() {
        HashMap<String, JankDataProcessor.PendingJankStat> pendingStats =
                mJankDataProcessor.getPendingJankStats();

        assertEquals(pendingStats.size(), 0);

        AppJankStats jankStats = JankUtils.getAppJankStats();
        mJankDataProcessor.mergeJankStats(jankStats, sActivityName);

        pendingStats = mJankDataProcessor.getPendingJankStats();

        assertEquals(pendingStats.size(), 1);
    }

    /**
     * This test confirms matching states are combined into one pending stat.  When JankStats are
     * merged from outside the platform they will contain widget category, widget id and widget
     * state. If an incoming JankStats matches a pending stat on all those fields the incoming
     * JankStat will be merged into the existing stat.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void mergeAppJankStats_confirmStatsWithMatchingStatesAreCombinedIntoOnePendingStat() {
        AppJankStats jankStats = JankUtils.getAppJankStats();
        mJankDataProcessor.mergeJankStats(jankStats, sActivityName);

        HashMap<String, JankDataProcessor.PendingJankStat> pendingStats =
                mJankDataProcessor.getPendingJankStats();
        assertEquals(pendingStats.size(), 1);

        AppJankStats secondJankStat = JankUtils.getAppJankStats();
        mJankDataProcessor.mergeJankStats(secondJankStat, sActivityName);

        pendingStats = mJankDataProcessor.getPendingJankStats();

        assertEquals(pendingStats.size(), 1);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
    public void mergeAppJankStats_whenStatsWithMatchingStatesMerge_confirmFrameCountsAdded() {
        AppJankStats jankStats = JankUtils.getAppJankStats();
        mJankDataProcessor.mergeJankStats(jankStats, sActivityName);
        mJankDataProcessor.mergeJankStats(jankStats, sActivityName);

        HashMap<String, JankDataProcessor.PendingJankStat> pendingStats =
                mJankDataProcessor.getPendingJankStats();

        String statKey = pendingStats.keySet().iterator().next();
        JankDataProcessor.PendingJankStat pendingStat = pendingStats.get(statKey);

        assertEquals(pendingStats.size(), 1);
        // The same jankStats objects are merged twice, this should result in the frame counts being
        // doubled.
        assertEquals(jankStats.getJankyFrameCount() * 2, pendingStat.getJankyFrames());
        assertEquals(jankStats.getTotalFrameCount() * 2, pendingStat.getTotalFrames());

        int[] originalHistogramBuckets = jankStats.getFrameOverrunHistogram().getBucketCounters();
        int[] frameOverrunBuckets = pendingStat.getFrameOverrunBuckets();

        for (int i = 0; i < frameOverrunBuckets.length; i++) {
            assertEquals(originalHistogramBuckets[i] * 2, frameOverrunBuckets[i]);
        }
    }

    // TODO b/375005277 add tests that cover logging and releasing resources back to pool.

    private long getTotalFramesCounted() {
        return mJankDataProcessor.getPendingJankStats().values()
                .stream().mapToLong(stat -> stat.getTotalFrames()).sum();
    }

    private long getHistogramFrameCount() {
        long totalHistogramFrames = 0;

        for (JankDataProcessor.PendingJankStat stats :
                mJankDataProcessor.getPendingJankStats().values()) {
            int[] overrunHistogram = stats.getFrameOverrunBuckets();

            for (int i = 0; i < overrunHistogram.length; i++) {
                totalHistogramFrames += overrunHistogram[i];
            }
        }

        return totalHistogramFrames;
    }

    /**
     * Out of range data will have a mVsyncIdStart and mVsyncIdEnd values set to below 25.
     */
    private List<StateTracker.StateData> getMockStateData_vsyncId_outOfRange() {
        ArrayList<StateTracker.StateData> stateData = new ArrayList<StateTracker.StateData>();
        StateTracker.StateData newStateData = new StateTracker.StateData();
        newStateData.mVsyncIdEnd = 20;
        newStateData.mStateDataKey = "Test1_OutBand";
        newStateData.mVsyncIdStart = 1;
        newStateData.mWidgetState = "scrolling";
        newStateData.mWidgetId = "widgetId";
        newStateData.mWidgetCategory = "Scroll";
        stateData.add(newStateData);

        newStateData = new StateTracker.StateData();
        newStateData.mVsyncIdEnd = 24;
        newStateData.mStateDataKey = "Test1_InBand";
        newStateData.mVsyncIdStart = 20;
        newStateData.mWidgetState = "Idle";
        newStateData.mWidgetId = "widgetId";
        newStateData.mWidgetCategory = "Scroll";
        stateData.add(newStateData);

        newStateData = new StateTracker.StateData();
        newStateData.mVsyncIdEnd = 20;
        newStateData.mStateDataKey = "Test1_OutBand";
        newStateData.mVsyncIdStart = 12;
        newStateData.mWidgetState = "Idle";
        newStateData.mWidgetId = "widgetId";
        newStateData.mWidgetCategory = "Scroll";
        stateData.add(newStateData);

        return stateData;
    }

    /**
     * This method returns two unique states, one state is set to scrolling the other is set
     * to none. Both states will have the same startvsyncid to ensure each state is counted the same
     * number of times. This keeps logic in asserts easier to reason about. Both states will have
     * a startVsyncId between 25 and 35.
     */
    private List<StateTracker.StateData> getMockStateData_vsyncId_inRange() {
        ArrayList<StateTracker.StateData> stateData = new ArrayList<StateTracker.StateData>();

        for (int i = 0; i < 10; i++) {
            StateTracker.StateData newStateData = new StateTracker.StateData();
            newStateData.mVsyncIdEnd = Long.MAX_VALUE;
            newStateData.mStateDataKey = "Test1_" + (i % 2 == 0 ? "scrolling" : "none");
            // Divide i by two to ensure both the scrolling and none states get the same vsyncid
            // This makes asserts in tests easier to reason about as each state should be counted
            // the same number of times.
            newStateData.mVsyncIdStart = 25 + (i / 2);
            newStateData.mWidgetState = i % 2 == 0 ? "scrolling" : "none";
            newStateData.mWidgetId = "widgetId";
            newStateData.mWidgetCategory = "Scroll";

            stateData.add(newStateData);
        }

        return stateData;
    }

    /**
     * In range data will have a frameVsyncId value between 25 and 35.
     */
    private List<SurfaceControl.JankData> getMockJankData_vsyncId_inRange() {
        ArrayList<SurfaceControl.JankData> mockData = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            mockData.add(new SurfaceControl.JankData(
                    /*frameVsyncId*/25 + i,
                    SurfaceControl.JankData.JANK_NONE,
                    NANOS_PER_MS * ((long) i),
                    NANOS_PER_MS * ((long) i),
                    NANOS_PER_MS * ((long) i)));

        }

        return mockData;
    }

    /**
     * Out of range data will have frameVsyncId values below 25.
     */
    private List<SurfaceControl.JankData> getMockJankData_vsyncId_outOfRange() {
        ArrayList<SurfaceControl.JankData> mockData = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            mockData.add(new SurfaceControl.JankData(
                    /*frameVsyncId*/i,
                    SurfaceControl.JankData.JANK_NONE,
                    NANOS_PER_MS * ((long) i),
                    NANOS_PER_MS * ((long) i),
                    NANOS_PER_MS * ((long) i)));

        }

        return mockData;
    }
}
