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
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import java.util.ArrayList;

@TargetApi(24)
public class MockPriorityJobService extends JobService {
    private static final String TAG = "MockPriorityJobService";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e(TAG, "Created test service.");
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Test job executing: " + params.getJobId());
        TestEnvironment.getTestEnvironment().executedEvents.add(
                new TestEnvironment.Event(TestEnvironment.EVENT_START_JOB, params.getJobId()));
        return true;  // Job not finished
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "Test job stop executing: " + params.getJobId());
        int reason = params.getStopReason();
        int event = TestEnvironment.EVENT_STOP_JOB;
        Log.d(TAG, "stop reason: " + String.valueOf(reason));
        if (reason == JobParameters.REASON_PREEMPT) {
            event = TestEnvironment.EVENT_PREEMPT_JOB;
            Log.d(TAG, "preempted " + String.valueOf(params.getJobId()));
        }
        TestEnvironment.getTestEnvironment().executedEvents
                .add(new TestEnvironment.Event(event, params.getJobId()));
        return false;  // Do not reschedule
    }

    public static class TestEnvironment {

        public static final int EVENT_START_JOB = 0;
        public static final int EVENT_PREEMPT_JOB = 1;
        public static final int EVENT_STOP_JOB = 2;

        private static TestEnvironment kTestEnvironment;

        private ArrayList<Event> executedEvents = new ArrayList<Event>();

        public static TestEnvironment getTestEnvironment() {
            if (kTestEnvironment == null) {
                kTestEnvironment = new TestEnvironment();
            }
            return kTestEnvironment;
        }

        public static class Event {
            public int event;
            public int jobId;

            public Event() {
            }

            public Event(int event, int jobId) {
                this.event = event;
                this.jobId = jobId;
            }

            @Override
            public boolean equals(Object other) {
                if (other instanceof Event) {
                    Event otherEvent = (Event) other;
                    return otherEvent.event == event && otherEvent.jobId == jobId;
                }
                return false;
            }
        }

        public void setUp() {
            executedEvents.clear();
        }

        public ArrayList<Event> getExecutedEvents() {
            return executedEvents;
        }
    }
}
