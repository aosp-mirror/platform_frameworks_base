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

import android.content.Context;
import android.content.Task;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.task.controllers.TaskStatus;

/**
 * Responsible for taking tasks representing work to be performed by a client app, and determining
 * based on the criteria specified when that task should be run against the client application's
 * endpoint.
 * @hide
 */
public class TaskManagerService extends com.android.server.SystemService
        implements StateChangedListener, TaskCompletedListener {
    static final String TAG = "TaskManager";

    /** Master list of tasks. */
    private final TaskStore mTasks;

    /** Check the pending queue and start any tasks. */
    static final int MSG_RUN_PENDING = 0;
    /** Initiate the stop task flow. */
    static final int MSG_STOP_TASK = 1;
    /** */
    static final int MSG_CHECK_TASKS = 2;

    /**
     * Track Services that have currently active or pending tasks. The index is provided by
     * {@link TaskStatus#getServiceToken()}
     */
    private final SparseArray<TaskServiceContext> mActiveServices =
            new SparseArray<TaskServiceContext>();

    private final TaskHandler mHandler;

    private class TaskHandler extends Handler {

        public TaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_RUN_PENDING:

                    break;
                case MSG_STOP_TASK:

                    break;
                case MSG_CHECK_TASKS:
                    checkTasks();
                    break;
            }
        }

        /**
         * Called when we need to run through the list of all tasks and start/stop executing one or
         * more of them.
         */
        private void checkTasks() {
            synchronized (mTasks) {
                final SparseArray<TaskStatus> tasks = mTasks.getTasks();
                for (int i = 0; i < tasks.size(); i++) {
                    TaskStatus ts = tasks.valueAt(i);
                    if (ts.isReady() && ! isCurrentlyActive(ts)) {
                        assignTaskToServiceContext(ts);
                    }
                }
            }
        }
    }

    /**
     * Entry point from client to schedule the provided task.
     * This will add the task to the
     * @param task Task object containing execution parameters
     * @param userId The id of the user this task is for.
     * @param uId The package identifier of the application this task is for.
     * @param canPersistTask Whether or not the client has the appropriate permissions for persisting
     *                    of this task.
     * @return Result of this operation. See <code>TaskManager#RESULT_*</code> return codes.
     */
    public int schedule(Task task, int userId, int uId, boolean canPersistTask) {
        TaskStatus taskStatus = mTasks.addNewTaskForUser(task, userId, uId, canPersistTask);
        return 0;
    }

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public TaskManagerService(Context context) {
        super(context);
        mTasks = new TaskStore(context);
        mHandler = new TaskHandler(context.getMainLooper());
    }

    @Override
    public void onStart() {

    }

    // StateChangedListener implementations.

    /**
     * Off-board work to our handler thread as quickly as possible, b/c this call is probably being
     * made on the main thread.
     * For now this takes the task and if it's ready to run it will run it. In future we might not
     * provide the task, so that the StateChangedListener has to run through its list of tasks to
     * see which are ready. This will further decouple the controllers from the execution logic.
     */
    @Override
    public void onTaskStateChanged(TaskStatus taskStatus) {
        postCheckTasksMessage();

    }

    @Override
    public void onTaskDeadlineExpired(TaskStatus taskStatus) {

    }

    // TaskCompletedListener implementations.

    /**
     * A task just finished executing. We fetch the
     * {@link com.android.server.task.controllers.TaskStatus} from the store and depending on
     * whether we want to reschedule we readd it to the controllers.
     * @param serviceToken key for the service context in {@link #mActiveServices}.
     * @param taskId Id of the task that is complete.
     * @param needsReschedule Whether the implementing class should reschedule this task.
     */
    @Override
    public void onTaskCompleted(int serviceToken, int taskId, boolean needsReschedule) {
        final TaskServiceContext serviceContext = mActiveServices.get(serviceToken);
        if (serviceContext == null) {
            Log.e(TAG, "Task completed for invalid service context; " + serviceToken);
            return;
        }

    }

    @Override
    public void onAllTasksCompleted(int serviceToken) {
        
    }

    private void assignTaskToServiceContext(TaskStatus ts) {
        TaskServiceContext serviceContext =
                mActiveServices.get(ts.getServiceToken());
        if (serviceContext == null) {
            serviceContext = new TaskServiceContext(this, mHandler.getLooper(), ts);
            mActiveServices.put(ts.getServiceToken(), serviceContext);
        }
        serviceContext.addPendingTask(ts);
    }

    /**
     * @param ts TaskStatus we are querying against.
     * @return Whether or not the task represented by the status object is currently being run or
     * is pending.
     */
    private boolean isCurrentlyActive(TaskStatus ts) {
        TaskServiceContext serviceContext = mActiveServices.get(ts.getServiceToken());
        if (serviceContext == null) {
            return false;
        }
        return serviceContext.hasTaskPending(ts);
    }

    /**
     * Post a message to {@link #mHandler} to run through the list of tasks and start/stop any that
     * are eligible.
     */
    private void postCheckTasksMessage() {
        mHandler.obtainMessage(MSG_CHECK_TASKS).sendToTarget();
    }
}
