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

import android.view.SurfaceControl;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ShellExecutor;

/**
 * Intermediary controller that communicates with {@link ShellTaskOrganizer} to send commands
 * to SurfaceControl.
 */
public class TaskSurfaceHelperController {

    private final ShellTaskOrganizer mTaskOrganizer;
    private final ShellExecutor mMainExecutor;
    private final TaskSurfaceHelperImpl mImpl = new TaskSurfaceHelperImpl();

    public TaskSurfaceHelperController(ShellTaskOrganizer taskOrganizer,
            ShellExecutor mainExecutor) {
        mTaskOrganizer = taskOrganizer;
        mMainExecutor = mainExecutor;
    }

    public TaskSurfaceHelper asTaskSurfaceHelper() {
        return mImpl;
    }

    /**
     * Sends a Transaction to set the game mode metadata on the
     * corresponding SurfaceControl
     */
    public void setGameModeForTask(int taskId, int gameMode) {
        mTaskOrganizer.setSurfaceMetadata(taskId, SurfaceControl.METADATA_GAME_MODE, gameMode);
    }

    private class TaskSurfaceHelperImpl implements TaskSurfaceHelper {
        @Override
        public void setGameModeForTask(int taskId, int gameMode) {
            mMainExecutor.execute(() -> {
                TaskSurfaceHelperController.this.setGameModeForTask(taskId, gameMode);
            });
        }
    }
}
