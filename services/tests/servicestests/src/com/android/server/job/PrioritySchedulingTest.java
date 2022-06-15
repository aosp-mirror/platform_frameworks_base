/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.job;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.test.AndroidTestCase;

import com.android.server.job.MockPriorityJobService.TestEnvironment;
import com.android.server.job.MockPriorityJobService.TestEnvironment.Event;

import java.util.ArrayList;

@TargetApi(24)
public class PrioritySchedulingTest extends AndroidTestCase {
    /** Environment that notifies of JobScheduler callbacks. */
    static TestEnvironment kTestEnvironment = TestEnvironment.getTestEnvironment();
    /** Handle for the service which receives the execution callbacks from the JobScheduler. */
    static ComponentName kJobServiceComponent;
    JobScheduler mJobScheduler;

    // The system overrides the test app priority to be a minimum of FOREGROUND_SERVICE. We can
    // bypass that override by using a priority of at least bound foreground service.
    private static final int HIGH_PRIORITY = JobInfo.PRIORITY_BOUND_FOREGROUND_SERVICE + 1;
    private static final int LOW_PRIORITY = JobInfo.PRIORITY_BOUND_FOREGROUND_SERVICE;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        kTestEnvironment.setUp();
        kJobServiceComponent = new ComponentName(getContext(), MockPriorityJobService.class);
        mJobScheduler = (JobScheduler) getContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
        mJobScheduler.cancelAll();
    }

    @Override
    public void tearDown() throws Exception {
        mJobScheduler.cancelAll();
        super.tearDown();
    }

    public void testLowerPriorityJobPreempted() throws Exception {
        for (int i = 0; i < JobSchedulerService.MAX_JOB_CONTEXTS_COUNT; ++i) {
            JobInfo job = new JobInfo.Builder(100 + i, kJobServiceComponent)
                    .setPriority(LOW_PRIORITY)
                    .setOverrideDeadline(0)
                    .build();
            mJobScheduler.schedule(job);
        }
        final int higherPriorityJobId = 100 + JobSchedulerService.MAX_JOB_CONTEXTS_COUNT;
        JobInfo jobHigher = new JobInfo.Builder(higherPriorityJobId, kJobServiceComponent)
                .setPriority(HIGH_PRIORITY)
                .setMinimumLatency(2000)
                .setOverrideDeadline(4000)
                .build();
        mJobScheduler.schedule(jobHigher);
        Thread.sleep(10000);  // Wait for jobHigher to preempt one of the lower priority jobs

        Event jobHigherExecution = new Event(TestEnvironment.EVENT_START_JOB, higherPriorityJobId);
        ArrayList<Event> executedEvents = kTestEnvironment.getExecutedEvents();
        boolean wasJobHigherExecuted = executedEvents.contains(jobHigherExecution);
        boolean wasSomeJobPreempted = false;
        for (Event event: executedEvents) {
            if (event.event == TestEnvironment.EVENT_PREEMPT_JOB) {
                wasSomeJobPreempted = true;
                break;
            }
        }
        assertTrue("No job was preempted.", wasSomeJobPreempted);
        assertTrue("Lower priority jobs were not preempted.", wasJobHigherExecuted);
    }

    public void testHigherPriorityJobNotPreempted() throws Exception {
        for (int i = 0; i < JobSchedulerService.MAX_JOB_CONTEXTS_COUNT; ++i) {
            JobInfo job = new JobInfo.Builder(100 + i, kJobServiceComponent)
                    .setPriority(HIGH_PRIORITY)
                    .setOverrideDeadline(0)
                    .build();
            mJobScheduler.schedule(job);
        }
        final int lowerPriorityJobId = 100 + JobSchedulerService.MAX_JOB_CONTEXTS_COUNT;
        JobInfo jobLower = new JobInfo.Builder(lowerPriorityJobId, kJobServiceComponent)
                .setPriority(LOW_PRIORITY)
                .setMinimumLatency(2000)
                .setOverrideDeadline(3000)
                .build();
        mJobScheduler.schedule(jobLower);
        Thread.sleep(10000);

        Event jobLowerExecution = new Event(TestEnvironment.EVENT_START_JOB, lowerPriorityJobId);
        boolean wasLowerExecuted = kTestEnvironment.getExecutedEvents().contains(jobLowerExecution);
        assertFalse("Higher priority job was preempted.", wasLowerExecuted);
    }
}
