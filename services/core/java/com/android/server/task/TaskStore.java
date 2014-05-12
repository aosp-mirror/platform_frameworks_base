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
import android.util.SparseArray;

import com.android.server.task.controllers.TaskStatus;

/**
 * Maintain a list of classes, and accessor methods/logic for these tasks.
 * This class offers the following functionality:
 *     - When a task is added, it will determine if the task requirements have changed (update) and
 *       whether the controllers need to be updated.
 *     - Persists Tasks, figures out when to to rewrite the Task to disk.
 *     - Is threadsafe.
 *     - Handles rescheduling of tasks.
 *       - When a periodic task is executed and must be re-added.
 *       - When a task fails and the client requests that it be retried with backoff.
 *       - This class is <strong>not</strong> thread-safe.
 */
public class TaskStore {

    /**
     * Master list, indexed by {@link com.android.server.task.controllers.TaskStatus#hashCode()}.
     */
    final SparseArray<TaskStatus> mTasks;
    final Context mContext;

    TaskStore(Context context) {
        mTasks = intialiseTaskMapFromDisk();
        mContext = context;
    }

    /**
     * Add a task to the master list, persisting it if necessary.
     * Will first check to see if the task already exists. If so, it will replace it.
     * {@link android.content.pm.PackageManager} is queried to see if the calling package has
     * permission to
     * @param task Task to add.
     * @return The initialised TaskStatus object if this operation was successful, null if it
     * failed.
     */
    public TaskStatus addNewTaskForUser(Task task, int userId, int uId,
                                                     boolean canPersistTask) {
        TaskStatus taskStatus = TaskStatus.getForTaskAndUser(task, userId, uId);
        if (canPersistTask && task.isPeriodic()) {
            if (writeStatusToDisk()) {
                mTasks.put(taskStatus.hashCode(), taskStatus);
            }
        }
        return taskStatus;
    }

    /**
     * Remove the provided task. Will also delete the task if it was persisted. Note that this
     * function does not return the validity of the operation, as we assume a delete will always
     * succeed.
     * @param task Task to remove.
     */
    public void remove(Task task) {

    }

    /**
     * Every time the state changes we write all the tasks in one swathe, instead of trying to
     * track incremental changes.
     */
    private boolean writeStatusToDisk() {
        return true;
    }

    /**
     *
     * @return
     */
    // TODO: Implement this.
    private SparseArray<TaskStatus> intialiseTaskMapFromDisk() {
        return new SparseArray<TaskStatus>();
    }

    /**
     * @return The live array of TaskStatus objects.
     */
    public SparseArray<TaskStatus> getTasks() {
        return mTasks;
    }
}
