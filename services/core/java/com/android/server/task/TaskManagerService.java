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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.SparseArray;

import com.android.server.task.controllers.TaskStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Responsible for taking tasks representing work to be performed by a client app, and determining
 * based on the criteria specified when that task should be run against the client application's
 * endpoint.
 * @hide
 */
public class TaskManagerService extends com.android.server.SystemService
        implements StateChangedListener {

    /** Master list of tasks. */
    private final TaskList mTaskList;

    /**
     * Track Services that have currently active or pending tasks. The index is provided by
     * {@link TaskStatus#getServiceToken()}
     */
    private final SparseArray<TaskServiceContext> mPendingTaskServices =
            new SparseArray<TaskServiceContext>();

    private final TaskHandler mHandler;

    private class TaskHandler extends Handler {
        /** Check the pending queue and start any tasks. */
        static final int MSG_RUN_PENDING = 0;
        /** Initiate the stop task flow. */
        static final int MSG_STOP_TASK = 1;

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
            }
        }

        /**
         * Helper to post a message to this handler that will run through the pending queue and
         * start any tasks it can.
         */
        void sendRunPendingTasksMessage() {
            Message m = Message.obtain(this, MSG_RUN_PENDING);
            m.sendToTarget();
        }

        void sendOnStopMessage(TaskStatus taskStatus) {

        }
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
        mTaskList = new TaskList();
        mHandler = new TaskHandler(context.getMainLooper());
    }

    @Override
    public void onStart() {

    }

    /**
     * Offboard work to our handler thread as quickly as possible, b/c this call is probably being
     * made on the main thread.
     * @param taskStatus The state of the task which has changed.
     */
    @Override
    public void onTaskStateChanged(TaskStatus taskStatus) {
        if (taskStatus.isReady()) {

        } else {
            if (mPendingTaskServices.get(taskStatus.getServiceToken()) != null) {
                // The task is either pending or being executed, which we have to cancel.
            }
        }

    }

    @Override
    public void onTaskDeadlineExpired(TaskStatus taskStatus) {

    }
}
