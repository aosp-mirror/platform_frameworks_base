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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.test.suitebuilder.annotation.SmallTest;

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
        assertUpdateTime(mTestStartTime);
        assertUpdateTime(mTestStartTime - 1000L);
    }

    @Test
    public void testRunningTimeBackwardDoesntExplodeGet() throws Exception {
        assertUpdateTime(mTestStartTime);
        final float rate = mEstimator.getRate(mTestStartTime - 1000L);
        assertFalse(Float.isInfinite(rate));
        assertFalse(Float.isNaN(rate));
    }

    @Test
    public void testInstantaneousEventsDontExplodeUpdate() throws Exception {
        assertUpdateTime(mTestStartTime);
        assertUpdateTime(mTestStartTime);
    }

    @Test
    public void testInstantaneousEventsDontExplodeGet() throws Exception {
        assertUpdateTime(mTestStartTime);
        assertUpdateTime(mTestStartTime);
        final float rate = mEstimator.getRate(mTestStartTime);
        assertFalse(Float.isInfinite(rate));
        assertFalse(Float.isNaN(rate));
    }

    @Test
    public void testInstantaneousBurstIsEstimatedUnderTwoPercent() throws Exception {
        assertUpdateTime(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 0, 5); // five events at \inf
        final float rate = mEstimator.getRate(nextEventTime);
        assertLessThan("Rate", rate, 20f);
    }

    @Test
    public void testCompactBurstIsEstimatedUnderTwoPercent() throws Exception {
        assertUpdateTime(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 1, 5); // five events at 1000Hz
        final float rate = mEstimator.getRate(nextEventTime);
        assertLessThan("Rate", rate, 20f);
    }

    @Test
    public void testSustained1000HzBurstIsEstimatedOverNinetyPercent() throws Exception {
        assertUpdateTime(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 1, 100); // one hundred events at 1000Hz
        final float rate = mEstimator.getRate(nextEventTime);
        assertGreaterThan("Rate", rate, 900f);
    }

    @Test
    public void testSustained100HzBurstIsEstimatedOverNinetyPercent() throws Exception {
        assertUpdateTime(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 10, 100); // one hundred events at 100Hz
        final float rate = mEstimator.getRate(nextEventTime);

        assertGreaterThan("Rate", rate, 90f);
    }

    @Test
    public void testRecoverQuicklyAfterSustainedBurst() throws Exception {
        assertUpdateTime(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 10, 1000); // one hundred events at 100Hz
        final float rate = mEstimator.getRate(nextEventTime + 5000L); // two seconds later
        assertLessThan("Rate", rate, 2f);
    }

    @Test
    public void testEstimateShouldNotOvershoot() throws Exception {
        assertUpdateTime(mTestStartTime);
        long eventStart = mTestStartTime + 1000; // start event a long time after initialization
        long nextEventTime = postEvents(eventStart, 1, 1000); // one thousand events at 1000Hz
        final float rate = mEstimator.getRate(nextEventTime);
        assertLessThan("Rate", rate, 1000f);
    }

    @Test
    public void testGetRateWithoutUpdate() throws Exception {
        final float rate = mEstimator.getRate(mTestStartTime);
        assertLessThan("Rate", rate, 0.1f);
    }

    @Test
    public void testGetRateWithOneUpdate() throws Exception {
        assertUpdateTime(mTestStartTime);
        final float rate = mEstimator.getRate(mTestStartTime+1);
        assertLessThan("Rate", rate, 1f);
    }

    private void assertLessThan(String label, float a, float b)  {
        assertTrue(String.format("%s was %f, but should be less than %f", label, a, b), a <= b);
    }

    private void assertGreaterThan(String label, float a, float b)  {
        assertTrue(String.format("%s was %f, but should be more than %f", label, a, b), a >= b);
    }

    /** @returns the next event time. */
    private long postEvents(long start, long dt, int num) {
        long time = start;
        for (int i = 0; i < num; i++) {
            mEstimator.update(time);
            time += dt;
        }
        return time;
    }

    private void assertUpdateTime(long time) {
        final float rate = mEstimator.update(time);
        assertFalse(Float.isInfinite(rate));
        assertFalse(Float.isNaN(rate));
    }
}
