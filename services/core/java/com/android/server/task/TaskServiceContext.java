/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.task;

import android.app.task.ITaskCallback;
import android.app.task.ITaskService;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Task;
import android.os.IBinder;

import com.android.server.task.controllers.TaskStatus;

/**
 * Maintains information required to bind to a {@link android.app.task.TaskService}. This binding
 * can then be reused to start concurrent tasks on the TaskService. Information here is unique
 * within this service.
 * Functionality provided by this class:
 *     - Managages wakelock for the service.
 *     - Sends onStartTask() and onStopTask() messages to client app, and handles callbacks.
 *     -
 */
public class TaskServiceContext extends ITaskCallback.Stub implements ServiceConnection {

    final ComponentName component;
    int uid;
    ITaskService service;

    /** Whether this service is actively bound. */
    boolean mBound;

    TaskServiceContext(Task task) {
        this.component = task.getService();
    }

    public void stopTask() {

    }

    public void startTask(Task task) {

    }

    @Override
    public void taskFinished(int taskId, boolean reschedule) {

    }

    @Override
    public void acknowledgeStopMessage(int taskId) {

    }

    @Override
    public void acknowledgeStartMessage(int taskId) {

    }

    /**
     * @return true if this task is pending or active within this context.
     */
    public boolean hasTaskPending(TaskStatus taskStatus) {
        return true;
    }

    public boolean isBound() {
        return mBound;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {

        mBound = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mBound = false;
    }
}
