/*
 * Copyright 2013 Google Inc.
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

package com.android.demo.jobSchedulerApp.service;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;

import com.android.demo.jobSchedulerApp.MainActivity;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;


/**
 * Service to handle sync requests.
 * <p>
 * This service is invoked in response to Intents with action android.content.SyncAdapter, and
 * returns a Binder connection to SyncAdapter.
 * <p>
 * For performance, only one sync adapter will be initialized within this application's context.
 * <p>
 * Note: The SyncService itself is not notified when a new sync occurs. It's role is to manage the
 * lifecycle of our and provide a handle to said SyncAdapter to the OS on
 * request.
 */
public class TestJobService extends JobService {
    private static final String TAG = "SyncService";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service destroyed");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Messenger callback = intent.getParcelableExtra("messenger");
        Message m = Message.obtain();
        m.what = MainActivity.MSG_SERVICE_OBJ;
        m.obj = this;
        try {
            callback.send(m);
        } catch (RemoteException e) {
            Log.e(TAG, "Error passing service object back to activity.");
        }
        return START_NOT_STICKY;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "on start job: " + params.getJobId());
        currentId++;
        jobParamsMap.put(currentId, params);
        final int currId = this.currentId;
        if (mActivity != null) {
            mActivity.onReceivedStartJob(params);
        }

        Toast.makeText(
                this, "On start job: '" + params.getJobId() + "' deadline exceeded: " +
                        params.isOverrideDeadlineExpired(),
                Toast.LENGTH_LONG).show();

        return true;
    }


    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "on stop job: " + params.getJobId());
        int ind = jobParamsMap.indexOfValue(params);
        jobParamsMap.remove(ind);
        mActivity.onReceivedStopJob();
        return false; // no reschedule
    }

    static int currentId = 0;
    MainActivity mActivity;
    private final SparseArray<JobParameters> jobParamsMap = new SparseArray<JobParameters>();


    public void setUiCallback(MainActivity activity) {
        mActivity = activity;
    }

    /** Send job to the JobScheduler. */
    public void scheduleJob(JobInfo job) {
        Log.d(TAG, "Scheduling job " + job);
        JobScheduler tm =
                (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        tm.schedule(job);
    }

    public boolean callJobFinished() {
        if (jobParamsMap.size() == 0) {
            return false;
        }
        JobParameters params = jobParamsMap.valueAt(0);
        if (params == null) {
            return false;
        } else {
            jobFinished(params, false);
            jobParamsMap.removeAt(0);
            return true;
        }
    }

}
