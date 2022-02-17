/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.util.ArraySet;
import android.util.SparseLongArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.tare.Agent.ActionAffordabilityNote;
import com.android.server.tare.Agent.OngoingEvent;
import com.android.server.tare.Agent.TrendCalculator;
import com.android.server.tare.EconomyManagerInternal.ActionBill;
import com.android.server.tare.EconomyManagerInternal.AffordabilityChangeListener;
import com.android.server.tare.EconomyManagerInternal.AnticipatedAction;

import libcore.util.EmptyArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Tests the TrendCalculator in the Agent. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class AgentTrendCalculatorTest {

    private MockEconomicPolicy mEconomicPolicy;

    private static class MockEconomicPolicy extends EconomicPolicy {
        private final SparseLongArray mEventCosts = new SparseLongArray();

        MockEconomicPolicy(InternalResourceService irs) {
            super(irs);
        }

        @Override
        long getMinSatiatedBalance(int userId, String pkgName) {
            return 0;
        }

        @Override
        long getMaxSatiatedBalance() {
            return 0;
        }

        @Override
        long getMaxSatiatedCirculation() {
            return 0;
        }

        @Override
        int[] getCostModifiers() {
            return EmptyArray.INT;
        }

        @Override
        Action getAction(int actionId) {
            if (mEventCosts.indexOfKey(actionId) < 0) {
                return null;
            }
            return new Action(actionId, 0, mEventCosts.get(actionId));
        }

        @Override
        Reward getReward(int rewardId) {
            if (mEventCosts.indexOfKey(rewardId) < 0) {
                return null;
            }
            return new Reward(rewardId, mEventCosts.get(rewardId), mEventCosts.get(rewardId),
                    10 * mEventCosts.get(rewardId));
        }
    }

    @Before
    public void setUp() {
        mEconomicPolicy = new MockEconomicPolicy(mock(InternalResourceService.class));
    }

    @Test
    public void testNoOngoingEvents() {
        TrendCalculator trendCalculator = new TrendCalculator();
        mEconomicPolicy.mEventCosts.put(JobSchedulerEconomicPolicy.ACTION_JOB_TIMEOUT, 20);

        trendCalculator.reset(0, null);
        assertEquals("Expected not to cross lower threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals("Expected not to cross upper threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossUpperThresholdMs());

        ArraySet<ActionAffordabilityNote> affordabilityNotes = new ArraySet<>();
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(JobSchedulerEconomicPolicy.ACTION_JOB_TIMEOUT, 1, 0))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        for (ActionAffordabilityNote note : affordabilityNotes) {
            note.recalculateModifiedPrice(mEconomicPolicy, 0, "com.test.app");
        }

        trendCalculator.reset(1234, affordabilityNotes);
        assertEquals("Expected not to cross lower threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals("Expected not to cross upper threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossUpperThresholdMs());
    }

    @Test
    public void testNoAffordabilityNotes() {
        TrendCalculator trendCalculator = new TrendCalculator();

        OngoingEvent[] events = new OngoingEvent[]{
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, "1",
                        null, 1, 1),
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING, "2",
                        null, 2, 3),
                new OngoingEvent(EconomicPolicy.REWARD_TOP_ACTIVITY, "3",
                        null, 3, -3),
        };

        trendCalculator.reset(0, null);
        for (OngoingEvent event : events) {
            trendCalculator.accept(event);
        }
        assertEquals("Expected not to cross lower threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals("Expected not to cross upper threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossUpperThresholdMs());

        ArraySet<ActionAffordabilityNote> affordabilityNotes = new ArraySet<>();
        trendCalculator.reset(1234, affordabilityNotes);
        for (OngoingEvent event : events) {
            trendCalculator.accept(event);
        }
        assertEquals("Expected not to cross lower threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals("Expected not to cross upper threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossUpperThresholdMs());
    }

    @Test
    public void testNoTrendToThreshold() {
        TrendCalculator trendCalculator = new TrendCalculator();
        mEconomicPolicy.mEventCosts.put(JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING, 10);

        ArraySet<ActionAffordabilityNote> affordabilityNotes = new ArraySet<>();
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(JobSchedulerEconomicPolicy.ACTION_JOB_MAX_RUNNING, 0, 1000))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        for (ActionAffordabilityNote note : affordabilityNotes) {
            note.recalculateModifiedPrice(mEconomicPolicy, 0, "com.test.app");
        }

        // Balance is already above threshold and events are all positive delta.
        // There should be no time to report.
        trendCalculator.reset(1234, affordabilityNotes);
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, "1",
                        null, 1, 1));
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING, "2",
                        null, 2, 3));

        assertEquals("Expected not to cross lower threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals("Expected not to cross upper threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossUpperThresholdMs());

        // Balance is already below threshold and events are all negative delta.
        // There should be no time to report.
        trendCalculator.reset(1, affordabilityNotes);
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, "1",
                        null, 1, -1));
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING, "2",
                        null, 2, -3));

        assertEquals("Expected not to cross lower threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals("Expected not to cross upper threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossUpperThresholdMs());
    }

    @Test
    public void testSimpleTrendToThreshold() {
        TrendCalculator trendCalculator = new TrendCalculator();
        mEconomicPolicy.mEventCosts.put(JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 20);

        ArraySet<ActionAffordabilityNote> affordabilityNotes = new ArraySet<>();
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 1, 0))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        for (ActionAffordabilityNote note : affordabilityNotes) {
            note.recalculateModifiedPrice(mEconomicPolicy, 0, "com.test.app");
        }

        // Balance is below threshold and events are all positive delta.
        // Should report the correct time to the upper threshold.
        trendCalculator.reset(0, affordabilityNotes);
        trendCalculator.accept(
                new OngoingEvent(EconomicPolicy.REWARD_TOP_ACTIVITY, "1",
                        null, 1, 1));
        trendCalculator.accept(
                new OngoingEvent(EconomicPolicy.REWARD_OTHER_USER_INTERACTION, "2",
                        null, 2, 3));

        assertEquals("Expected not to cross lower threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals(5_000, trendCalculator.getTimeToCrossUpperThresholdMs());

        // Balance is above the threshold and events are all negative delta.
        // Should report the correct time to the lower threshold.
        trendCalculator.reset(40, affordabilityNotes);
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, "1",
                        null, 1, -1));
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_RUNNING, "2",
                        null, 2, -3));

        assertEquals(5_000, trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals("Expected not to cross upper threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossUpperThresholdMs());
    }

    @Test
    public void testSelectCorrectThreshold() {
        TrendCalculator trendCalculator = new TrendCalculator();
        mEconomicPolicy.mEventCosts.put(JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 20);
        mEconomicPolicy.mEventCosts.put(JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_START, 15);
        mEconomicPolicy.mEventCosts.put(JobSchedulerEconomicPolicy.ACTION_JOB_LOW_START, 10);
        mEconomicPolicy.mEventCosts.put(JobSchedulerEconomicPolicy.ACTION_JOB_MIN_START, 5);

        ArraySet<ActionAffordabilityNote> affordabilityNotes = new ArraySet<>();
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 1, 0))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(JobSchedulerEconomicPolicy.ACTION_JOB_HIGH_START, 1, 0))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(JobSchedulerEconomicPolicy.ACTION_JOB_LOW_START, 1, 0))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(JobSchedulerEconomicPolicy.ACTION_JOB_MIN_START, 1, 0))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        for (ActionAffordabilityNote note : affordabilityNotes) {
            note.recalculateModifiedPrice(mEconomicPolicy, 0, "com.test.app");
        }

        // Balance is below threshold and events are all positive delta.
        // Should report the correct time to the correct upper threshold.
        trendCalculator.reset(0, affordabilityNotes);
        trendCalculator.accept(
                new OngoingEvent(EconomicPolicy.REWARD_TOP_ACTIVITY, "1",
                        null, 1, 1));

        assertEquals("Expected not to cross lower threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals(5_000, trendCalculator.getTimeToCrossUpperThresholdMs());

        // Balance is above the threshold and events are all negative delta.
        // Should report the correct time to the correct lower threshold.
        trendCalculator.reset(30, affordabilityNotes);
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, "1",
                        null, 1, -1));

        assertEquals(10_000, trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals("Expected not to cross upper threshold",
                TrendCalculator.WILL_NOT_CROSS_THRESHOLD,
                trendCalculator.getTimeToCrossUpperThresholdMs());
    }

    @Test
    public void testTrendsToBothThresholds() {
        TrendCalculator trendCalculator = new TrendCalculator();
        mEconomicPolicy.mEventCosts.put(JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 20);
        mEconomicPolicy.mEventCosts.put(AlarmManagerEconomicPolicy.ACTION_ALARM_CLOCK, 50);

        ArraySet<ActionAffordabilityNote> affordabilityNotes = new ArraySet<>();
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(JobSchedulerEconomicPolicy.ACTION_JOB_MAX_START, 1, 0))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        affordabilityNotes.add(new ActionAffordabilityNote(new ActionBill(List.of(
                new AnticipatedAction(AlarmManagerEconomicPolicy.ACTION_ALARM_CLOCK, 1, 0))),
                mock(AffordabilityChangeListener.class), mEconomicPolicy));
        for (ActionAffordabilityNote note : affordabilityNotes) {
            note.recalculateModifiedPrice(mEconomicPolicy, 0, "com.test.app");
        }

        // Balance is between both thresholds and events are mixed positive/negative delta.
        // Should report the correct time to each threshold.
        trendCalculator.reset(35, affordabilityNotes);
        trendCalculator.accept(
                new OngoingEvent(EconomicPolicy.REWARD_TOP_ACTIVITY, "1",
                        null, 1, 3));
        trendCalculator.accept(
                new OngoingEvent(EconomicPolicy.REWARD_OTHER_USER_INTERACTION, "2",
                        null, 2, 2));
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_LOW_RUNNING, "3",
                        null, 3, -2));
        trendCalculator.accept(
                new OngoingEvent(JobSchedulerEconomicPolicy.ACTION_JOB_DEFAULT_RUNNING, "4",
                        null, 4, -3));

        assertEquals(3_000, trendCalculator.getTimeToCrossLowerThresholdMs());
        assertEquals(3_000, trendCalculator.getTimeToCrossUpperThresholdMs());
    }
}
