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
 * limitations under the License
 */

package com.android.server.job.controllers;

import static org.junit.Assert.assertEquals;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.os.SystemClock;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.job.JobSchedulerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.ZoneOffset;

@RunWith(AndroidJUnit4.class)
public class JobStatusTest {
    private static final double DELTA = 0.00001;

    @Before
    public void setUp() throws Exception {
        // Freeze the clocks at this moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sUptimeMillisClock =
                Clock.fixed(SystemClock.uptimeMillisClock().instant(), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);
    }

    @Test
    public void testFraction() throws Exception {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();

        assertEquals(1, createJobStatus(0, Long.MAX_VALUE).getFractionRunTime(), DELTA);

        assertEquals(1, createJobStatus(0, now - 1000).getFractionRunTime(), DELTA);
        assertEquals(0, createJobStatus(0, now + 1000).getFractionRunTime(), DELTA);

        assertEquals(1, createJobStatus(now - 1000, Long.MAX_VALUE).getFractionRunTime(), DELTA);
        assertEquals(0, createJobStatus(now + 1000, Long.MAX_VALUE).getFractionRunTime(), DELTA);

        assertEquals(0, createJobStatus(now, now + 2000).getFractionRunTime(), DELTA);
        assertEquals(0.25, createJobStatus(now - 500, now + 1500).getFractionRunTime(), DELTA);
        assertEquals(0.5, createJobStatus(now - 1000, now + 1000).getFractionRunTime(), DELTA);
        assertEquals(0.75, createJobStatus(now - 1500, now + 500).getFractionRunTime(), DELTA);
        assertEquals(1, createJobStatus(now - 2000, now).getFractionRunTime(), DELTA);
    }

    private static JobStatus createJobStatus(long earliestRunTimeElapsedMillis,
            long latestRunTimeElapsedMillis) {
        final JobInfo job = new JobInfo.Builder(101, new ComponentName("foo", "bar"))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build();
        return new JobStatus(job, 0, null, -1, 0, 0, null, earliestRunTimeElapsedMillis,
                latestRunTimeElapsedMillis, 0, 0, null, 0);
    }
}
