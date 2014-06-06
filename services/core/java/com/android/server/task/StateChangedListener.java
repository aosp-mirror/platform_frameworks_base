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

import com.android.server.task.controllers.TaskStatus;

/**
 * Interface through which a {@link com.android.server.task.controllers.StateController} informs
 * the {@link com.android.server.task.TaskManagerService} that there are some tasks potentially
 * ready to be run.
 */
public interface StateChangedListener {
    /**
     * Called by the controller to notify the TaskManager that it should check on the state of a
     * task.
     */
    public void onControllerStateChanged();

    /**
     * Called by the controller to notify the TaskManager that regardless of the state of the task,
     * it must be run immediately.
     * @param taskStatus The state of the task which is to be run immediately.
     */
    public void onRunTaskNow(TaskStatus taskStatus);
}
