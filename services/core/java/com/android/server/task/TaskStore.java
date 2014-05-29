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

import android.app.task.Task;
import android.content.Context;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.task.controllers.TaskStatus;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

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
    private static final String TAG = "TaskManagerStore";
    /** Threshold to adjust how often we want to write to the db. */
    private static final int MAX_OPS_BEFORE_WRITE = 1;
    final ArraySet<TaskStatus> mTasks;
    final Context mContext;

    private int mDirtyOperations;

    TaskStore(Context context) {
        mTasks = intialiseTasksFromDisk();
        mContext = context;
        mDirtyOperations = 0;
    }

    /**
     * Add a task to the master list, persisting it if necessary. If the TaskStatus already exists,
     * it will be replaced.
     * @param taskStatus Task to add.
     * @return true if the operation succeeded.
     */
    public boolean add(TaskStatus taskStatus) {
        if (taskStatus.isPersisted()) {
            if (!maybeWriteStatusToDisk()) {
                return false;
            }
        }
        mTasks.remove(taskStatus);
        mTasks.add(taskStatus);
        return true;
    }

    public int size() {
        return mTasks.size();
    }

    /**
     * Remove the provided task. Will also delete the task if it was persisted.
     * @return The TaskStatus that was removed, or null if an invalid token was provided.
     */
    public boolean remove(TaskStatus taskStatus) {
        boolean removed = mTasks.remove(taskStatus);
        if (!removed) {
            Slog.e(TAG, "Error removing task: " + taskStatus);
            return false;
        } else {
            maybeWriteStatusToDisk();
        }
        return true;
    }

    /**
     * Removes all TaskStatus objects for a given uid from the master list. Note that it is
     * possible to remove a task that is pending/active. This operation will succeed, and the
     * removal will take effect when the task has completed executing.
     * @param uid Uid of the requesting app.
     * @return True if at least one task was removed, false if nothing matching the provided uId
     * was found.
     */
    public boolean removeAllByUid(int uid) {
        Iterator<TaskStatus> it = mTasks.iterator();
        boolean removed = false;
        while (it.hasNext()) {
            TaskStatus ts = it.next();
            if (ts.getUid() == uid) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            maybeWriteStatusToDisk();
        }
        return removed;
    }

    /**
     * Remove the TaskStatus that matches the provided uId and taskId.  Note that it is possible
     * to remove a task that is pending/active. This operation will succeed, and the removal will
     * take effect when the task has completed executing.
     * @param uid Uid of the requesting app.
     * @param taskId Task id, specified at schedule-time.
     * @return true if a removal occurred, false if the provided parameters didn't match anything.
     */
    public boolean remove(int uid, int taskId) {
        Iterator<TaskStatus> it = mTasks.iterator();
        while (it.hasNext()) {
            TaskStatus ts = it.next();
            if (ts.getUid() == uid && ts.getTaskId() == taskId) {
                it.remove();
                maybeWriteStatusToDisk();
                return true;
            }
        }
        return false;
    }

    /**
     * @return The live array of TaskStatus objects.
     */
    public Set<TaskStatus> getTasks() {
        return mTasks;
    }

    /**
     * Every time the state changes we write all the tasks in one swathe, instead of trying to
     * track incremental changes.
     * @return Whether the operation was successful. This will only fail for e.g. if the system is
     * low on storage. If this happens, we continue as normal
     */
    private boolean maybeWriteStatusToDisk() {
        mDirtyOperations++;
        if (mDirtyOperations > MAX_OPS_BEFORE_WRITE) {
            for (TaskStatus ts : mTasks) {
                //
            }
            mDirtyOperations = 0;
        }
        return true;
    }

    /**
     *
     * @return
     */
    // TODO: Implement this.
    private ArraySet<TaskStatus> intialiseTasksFromDisk() {
        return new ArraySet<TaskStatus>();
    }
}
