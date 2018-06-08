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
 * limitations under the License
 */

package com.android.servicestests.apps.jobtestapp;

import android.annotation.TargetApi;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.util.Log;

@TargetApi(24)
public class TestJobService extends JobService {
    private static final String TAG = TestJobService.class.getSimpleName();
    private static final String PACKAGE_NAME = "com.android.servicestests.apps.jobtestapp";
    public static final String ACTION_JOB_STARTED = PACKAGE_NAME + ".action.JOB_STARTED";
    public static final String ACTION_JOB_STOPPED = PACKAGE_NAME + ".action.JOB_STOPPED";
    public static final String JOB_PARAMS_EXTRA_KEY = PACKAGE_NAME + ".extra.JOB_PARAMETERS";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Test job executing: " + params.getJobId());
        Intent reportJobStartIntent = new Intent(ACTION_JOB_STARTED);
        reportJobStartIntent.putExtra(JOB_PARAMS_EXTRA_KEY, params);
        sendBroadcast(reportJobStartIntent);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "Test job stopped executing: " + params.getJobId());
        Intent reportJobStopIntent = new Intent(ACTION_JOB_STOPPED);
        reportJobStopIntent.putExtra(JOB_PARAMS_EXTRA_KEY, params);
        sendBroadcast(reportJobStopIntent);
        return true;
    }
}
