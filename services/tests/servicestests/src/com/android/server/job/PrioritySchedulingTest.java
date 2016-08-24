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
        JobInfo job1 = new JobInfo.Builder(111, kJobServiceComponent)
                .setPriority(1)
                .setOverrideDeadline(7000L)
                .build();
        JobInfo job2 = new JobInfo.Builder(222, kJobServiceComponent)
                .setPriority(1)
                .setOverrideDeadline(7000L)
                .build();
        JobInfo job3 = new JobInfo.Builder(333, kJobServiceComponent)
                .setPriority(1)
                .setOverrideDeadline(7000L)
                .build();
        JobInfo job4 = new JobInfo.Builder(444, kJobServiceComponent)
                .setPriority(2)
                .setMinimumLatency(2000L)
                .setOverrideDeadline(7000L)
                .build();
        mJobScheduler.schedule(job1);
        mJobScheduler.schedule(job2);
        mJobScheduler.schedule(job3);
        mJobScheduler.schedule(job4);
        Thread.sleep(10000);  // Wait for job 4 to preempt one of the lower priority jobs

        Event job4Execution = new Event(TestEnvironment.EVENT_START_JOB, 444);
        ArrayList<Event> executedEvents = kTestEnvironment.getExecutedEvents();
        boolean wasJob4Executed = executedEvents.contains(job4Execution);
        boolean wasSomeJobPreempted = false;
        for (Event event: executedEvents) {
            if (event.event == TestEnvironment.EVENT_PREEMPT_JOB) {
                wasSomeJobPreempted = true;
                break;
            }
        }
        assertTrue("No job was preempted.", wasSomeJobPreempted);
        assertTrue("Lower priority jobs were not preempted.",  wasJob4Executed);
    }

    public void testHigherPriorityJobNotPreempted() throws Exception {
        JobInfo job1 = new JobInfo.Builder(111, kJobServiceComponent)
                .setPriority(2)
                .setOverrideDeadline(7000L)
                .build();
        JobInfo job2 = new JobInfo.Builder(222, kJobServiceComponent)
                .setPriority(2)
                .setOverrideDeadline(7000L)
                .build();
        JobInfo job3 = new JobInfo.Builder(333, kJobServiceComponent)
                .setPriority(2)
                .setOverrideDeadline(7000L)
                .build();
        JobInfo job4 = new JobInfo.Builder(444, kJobServiceComponent)
                .setPriority(1)
                .setMinimumLatency(2000L)
                .setOverrideDeadline(7000L)
                .build();
        mJobScheduler.schedule(job1);
        mJobScheduler.schedule(job2);
        mJobScheduler.schedule(job3);
        mJobScheduler.schedule(job4);
        Thread.sleep(10000);  // Wait for job 4 to preempt one of the higher priority jobs

        Event job4Execution = new Event(TestEnvironment.EVENT_START_JOB, 444);
        boolean wasJob4Executed = kTestEnvironment.getExecutedEvents().contains(job4Execution);
        assertFalse("Higher priority job was preempted.", wasJob4Executed);
    }
}
