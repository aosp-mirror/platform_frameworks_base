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

package com.android.server.task.controllers;

import android.content.Context;

import com.android.server.task.StateChangedListener;
import com.android.server.task.TaskManagerService;

/**
 * Incorporates shared controller logic between the various controllers of the TaskManager.
 * These are solely responsible for tracking a list of tasks, and notifying the TM when these
 * are ready to run, or whether they must be stopped.
 */
public abstract class StateController {

    protected Context mContext;
    protected StateChangedListener mStateChangedListener;

    public StateController(TaskManagerService service) {
        mStateChangedListener = service;
        mContext = service.getContext();
    }

    /**
     * Implement the logic here to decide whether a task should be tracked by this controller.
     * This logic is put here so the TaskManger can be completely agnostic of Controller logic.
     * Also called when updating a task, so implementing controllers have to be aware of
     * preexisting tasks.
     */
    public abstract void maybeStartTrackingTask(TaskStatus taskStatus);
    /**
     * Remove task - this will happen if the task is cancelled, completed, etc.
     */
    public abstract void maybeStopTrackingTask(TaskStatus taskStatus);

}
