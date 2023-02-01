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

package com.android.server.job;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.test.AndroidTestCase;

import com.android.server.job.MockBiasJobService.TestEnvironment;
import com.android.server.job.MockBiasJobService.TestEnvironment.Event;

import java.util.ArrayList;

@TargetApi(24)
public class BiasSchedulingTest extends AndroidTestCase {
    /** Environment that notifies of JobScheduler callbacks. */
    private static final TestEnvironment sTestEnvironment = TestEnvironment.getTestEnvironment();
    /** Handle for the service which receives the execution callbacks from the JobScheduler. */
    private static ComponentName sJobServiceComponent;
    private JobScheduler mJobScheduler;

    // The system overrides the test app bias to be a minimum of FOREGROUND_SERVICE. We can
    // bypass that override by using a bias of at least bound foreground service.
    private static final int HIGH_BIAS = JobInfo.BIAS_BOUND_FOREGROUND_SERVICE + 1;
    private static final int LOW_BIAS = JobInfo.BIAS_BOUND_FOREGROUND_SERVICE;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        sTestEnvironment.setUp();
        sJobServiceComponent = new ComponentName(getContext(), MockBiasJobService.class);
        mJobScheduler = (JobScheduler) getContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
        mJobScheduler.cancelAll();
    }

    @Override
    public void tearDown() throws Exception {
        mJobScheduler.cancelAll();
        super.tearDown();
    }

    public void testLowerBiasJobPreempted() throws Exception {
        for (int i = 0; i < JobConcurrencyManager.MAX_CONCURRENCY_LIMIT; ++i) {
            JobInfo job = new JobInfo.Builder(100 + i, sJobServiceComponent)
                    .setBias(LOW_BIAS)
                    .setOverrideDeadline(0)
                    .build();
            mJobScheduler.schedule(job);
        }
        final int higherBiasJobId = 100 + JobConcurrencyManager.MAX_CONCURRENCY_LIMIT;
        JobInfo jobHigher = new JobInfo.Builder(higherBiasJobId, sJobServiceComponent)
                .setBias(HIGH_BIAS)
                .setMinimumLatency(2000)
                .setOverrideDeadline(4000)
                .build();
        mJobScheduler.schedule(jobHigher);
        Thread.sleep(10000);  // Wait for jobHigher to preempt one of the lower bias jobs

        Event jobHigherExecution = new Event(TestEnvironment.EVENT_START_JOB, higherBiasJobId);
        ArrayList<Event> executedEvents = sTestEnvironment.getExecutedEvents();
        boolean wasJobHigherExecuted = executedEvents.contains(jobHigherExecution);
        boolean wasSomeJobPreempted = false;
        for (Event event: executedEvents) {
            if (event.event == TestEnvironment.EVENT_PREEMPT_JOB) {
                wasSomeJobPreempted = true;
                break;
            }
        }
        assertTrue("No job was preempted.", wasSomeJobPreempted);
        assertTrue("Lower bias jobs were not preempted.", wasJobHigherExecuted);
    }

    public void testHigherBiasJobNotPreempted() throws Exception {
        for (int i = 0; i < JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT; ++i) {
            JobInfo job = new JobInfo.Builder(100 + i, sJobServiceComponent)
                    .setBias(HIGH_BIAS)
                    .setOverrideDeadline(0)
                    .build();
            mJobScheduler.schedule(job);
        }
        final int lowerBiasJobId = 100 + JobConcurrencyManager.DEFAULT_CONCURRENCY_LIMIT;
        JobInfo jobLower = new JobInfo.Builder(lowerBiasJobId, sJobServiceComponent)
                .setBias(LOW_BIAS)
                .setMinimumLatency(2000)
                .setOverrideDeadline(3000)
                .build();
        mJobScheduler.schedule(jobLower);
        Thread.sleep(10000);

        Event jobLowerExecution = new Event(TestEnvironment.EVENT_START_JOB, lowerBiasJobId);
        boolean wasLowerExecuted = sTestEnvironment.getExecutedEvents().contains(jobLowerExecution);
        assertFalse("Higher bias job was preempted.", wasLowerExecuted);
    }
}
