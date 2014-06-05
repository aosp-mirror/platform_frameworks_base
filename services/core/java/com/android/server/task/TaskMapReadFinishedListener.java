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

import java.util.List;

import com.android.server.task.controllers.TaskStatus;

/**
 * Callback definition for I/O thread to let the TaskManagerService know when
 * I/O read has completed. Done this way so we don't stall the main thread on
 * boot.
 */
public interface TaskMapReadFinishedListener {

    /**
     * Called by the {@link TaskStore} at boot, when the disk read is finished.
     */
    public void onTaskMapReadFinished(List<TaskStatus> tasks);
}
