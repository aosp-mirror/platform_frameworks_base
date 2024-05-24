/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;

import static java.util.concurrent.TimeUnit.HOURS;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RateEstimatorTest extends UiServiceTestCase {
    private long mTestStartTime;
    private RateEstimator mEstimator;

    @Before
    public void setUp() {
        mTestStartTime = 1225731600000L;
        mEstimator = new RateEstimator();
    }

    @Test
    public void testRunningTimeBackwardDoesntExplodeUpdate() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        updateAndVerifyRate(mTestStartTime - 1000L);
    }

    @Test
    public void testRunningTimeBackwardDoesntExplodeGet() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        final float rate = mEstimator.getRate(mTestStartTime - 1000L);
        assertThat(rate).isFinite();
    }

    @Test
    public void testInstantaneousEventsDontExplodeUpdate() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        updateAndVerifyRate(mTestStartTime);
    }

    @Test
    public void testInstantaneousEventsDontExplodeGet() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        updateAndVerifyRate(mTestStartTime);
        final float rate = mEstimator.getRate(mTestStartTime);
        assertThat(rate).isFinite();
    }

    @Test
    public void testInstantaneousBurstIsEstimatedUnderTwoPercent() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 0, 5); // five events at \inf
        final float rate = mEstimator.getRate(nextEventTime);
        assertThat(rate).isLessThan(20f);
    }

    @Test
    public void testCompactBurstIsEstimatedUnderTwoPercent() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 1, 5); // five events at 1000Hz
        final float rate = mEstimator.getRate(nextEventTime);
        assertThat(rate).isLessThan(20f);
    }

    @Test
    public void testSustained1000HzBurstIsEstimatedOverNinetyPercent() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 1, 100); // one hundred events at 1000Hz
        final float rate = mEstimator.getRate(nextEventTime);
        assertThat(rate).isGreaterThan(900f);
    }

    @Test
    public void testSustained100HzBurstIsEstimatedOverNinetyPercent() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 10, 100); // one hundred events at 100Hz
        final float rate = mEstimator.getRate(nextEventTime);

        assertThat(rate).isGreaterThan(90f);
    }

    @Test
    public void testRecoverQuicklyAfterSustainedBurst() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 10, 1000); // one thousand events at 100Hz
        final float rate = mEstimator.getRate(nextEventTime + 5000L); // five seconds later
        assertThat(rate).isLessThan(2f);
    }

    @Test
    public void testEstimateShouldNotOvershoot() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 1, 5000); // five thousand events at 1000Hz
        final float rate = mEstimator.getRate(nextEventTime);
        assertThat(rate).isAtMost(1000f);
    }

    @Test
    public void testGetRateWithoutUpdate() throws Exception {
        final float rate = mEstimator.getRate(mTestStartTime);
        assertThat(rate).isLessThan(0.1f);
    }

    @Test
    public void testGetRateWithOneUpdate() throws Exception {
        updateAndVerifyRate(mTestStartTime);
        final float rate = mEstimator.getRate(mTestStartTime+1);
        assertThat(rate).isLessThan(1f);
    }

    @Test
    public void testEstimateCatchesUpQuickly() {
        long nextEventTime = postEvents(mTestStartTime, 100, 30); // 30 events at 10Hz

        final float firstBurstRate = mEstimator.getRate(nextEventTime);
        assertThat(firstBurstRate).isWithin(2f).of(10);

        nextEventTime += HOURS.toMillis(3); // 3 hours later...
        nextEventTime = postEvents(nextEventTime, 100, 30); // same burst of 30 events at 10Hz

        // Catching up. Rate is not yet 10, since we had a long period of inactivity...
        float secondBurstRate = mEstimator.getRate(nextEventTime);
        assertThat(secondBurstRate).isWithin(1f).of(6);

        // ... but after a few more events, we are there.
        nextEventTime = postEvents(nextEventTime, 100, 10); // 10 more events at 10Hz
        secondBurstRate = mEstimator.getRate(nextEventTime);
        assertThat(secondBurstRate).isWithin(1f).of(10);
    }

    /** @return the next event time. */
    private long postEvents(long start, long dt, int num) {
        long time = start;
        for (int i = 0; i < num; i++) {
            mEstimator.update(time);
            time += dt;
        }
        return time;
    }

    private void updateAndVerifyRate(long time) {
        mEstimator.update(time);
        assertThat(mEstimator.getRate(time)).isFinite();
    }
}