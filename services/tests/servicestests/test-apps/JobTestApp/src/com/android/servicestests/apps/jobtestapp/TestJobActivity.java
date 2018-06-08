/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.servicestests.apps.jobtestapp;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class TestJobActivity extends Activity {
    private static final String TAG = TestJobActivity.class.getSimpleName();
    private static final String PACKAGE_NAME = "com.android.servicestests.apps.jobtestapp";

    public static final String EXTRA_JOB_ID_KEY = PACKAGE_NAME + ".extra.JOB_ID";
    public static final String ACTION_START_JOB = PACKAGE_NAME + ".action.START_JOB";
    public static final String ACTION_CANCEL_JOBS = PACKAGE_NAME + ".action.CANCEL_JOBS";
    public static final int JOB_INITIAL_BACKOFF = 10_000;
    public static final int JOB_MINIMUM_LATENCY = 5_000;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ComponentName jobServiceComponent = new ComponentName(this, TestJobService.class);
        JobScheduler jobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        final Intent intent = getIntent();
        switch (intent.getAction()) {
            case ACTION_CANCEL_JOBS:
                jobScheduler.cancelAll();
                Log.d(TAG, "Cancelled all jobs for " + getPackageName());
                break;
            case ACTION_START_JOB:
                final int jobId = intent.getIntExtra(EXTRA_JOB_ID_KEY, hashCode());
                JobInfo.Builder jobBuilder = new JobInfo.Builder(jobId, jobServiceComponent)
                        .setBackoffCriteria(JOB_INITIAL_BACKOFF, JobInfo.BACKOFF_POLICY_LINEAR)
                        .setMinimumLatency(JOB_MINIMUM_LATENCY);
                final int result = jobScheduler.schedule(jobBuilder.build());
                if (result != JobScheduler.RESULT_SUCCESS) {
                    Log.e(TAG, "Could not schedule job " + jobId);
                } else {
                    Log.d(TAG, "Successfully scheduled job with id " + jobId);
                }
                break;
            default:
                Log.e(TAG, "Unknown action " + intent.getAction());
        }
        finish();
    }
}