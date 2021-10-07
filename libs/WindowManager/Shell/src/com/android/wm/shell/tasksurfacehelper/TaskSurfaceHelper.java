/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.tasksurfacehelper;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Rect;
import android.view.SurfaceControl;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Interface to communicate with a Task's SurfaceControl.
 */
public interface TaskSurfaceHelper {

    /** Sets the METADATA_GAME_MODE for the layer corresponding to the task **/
    default void setGameModeForTask(int taskId, int gameMode) {}

    /** Takes a screenshot for a task **/
    default void screenshotTask(RunningTaskInfo taskInfo, Rect crop, Executor executor,
            Consumer<SurfaceControl.ScreenshotHardwareBuffer> consumer) {}
}
