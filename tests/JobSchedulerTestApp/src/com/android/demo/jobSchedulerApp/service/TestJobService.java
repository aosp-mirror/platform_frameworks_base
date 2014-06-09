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

import android.app.Service;
import android.app.task.Task;
import android.app.task.TaskManager;
import android.app.task.TaskParams;
import android.app.task.TaskService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.demo.jobSchedulerApp.MainActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;


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
public class TestJobService extends TaskService {
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
    public boolean onStartTask(TaskParams params) {
        taskParamsMap.add(params);
        if (mActivity != null) {
            mActivity.onReceivedStartTask(params);
        }
        Log.i(TAG, "on start task: " + params.getTaskId());
        return true;
    }

    @Override
    public boolean onStopTask(TaskParams params) {
        taskParamsMap.remove(params);
        mActivity.onReceivedStopTask();
        Log.i(TAG, "on stop task: " + params.getTaskId());
        return true;
    }

    MainActivity mActivity;
    private final LinkedList<TaskParams> taskParamsMap = new LinkedList<TaskParams>();

    public void setUiCallback(MainActivity activity) {
        mActivity = activity;
    }

    /** Send job to the JobScheduler. */
    public void scheduleJob(Task t) {
        Log.d(TAG, "Scheduling job");
        TaskManager tm =
                (TaskManager) getSystemService(Context.TASK_SERVICE);
        tm.schedule(t);
    }

    public boolean callTaskFinished() {
        TaskParams params = taskParamsMap.poll();
        if (params == null) {
            return false;
        } else {
            taskFinished(params, false);
            return true;
        }
    }

}
