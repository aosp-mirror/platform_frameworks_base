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

import android.content.ComponentName;
import android.content.Task;

import com.android.server.task.controllers.TaskStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 */
public class TaskList {

    final List<TaskStatus> mTasks;

    TaskList() {
        mTasks = intialiseTaskMapFromDisk();
    }

    /**
     * Add a task to the master list, persisting it if necessary.
     * @param task Task to add.
     * @param persistable true if the TaskQueue should persist this task to the disk.
     * @return true if this operation was successful. If false, this task was neither added nor
     * persisted.
     */
    // TODO: implement this when i decide whether i want to key by TaskStatus
    public boolean add(Task task, boolean persistable) {
        return true;
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
     *
     * @return
     */
    // TODO: Implement this.
    private List<TaskStatus> intialiseTaskMapFromDisk() {
        return new ArrayList<TaskStatus>();
    }
}
