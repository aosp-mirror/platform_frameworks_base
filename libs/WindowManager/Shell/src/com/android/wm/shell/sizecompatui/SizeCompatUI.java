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

package com.android.wm.shell.sizecompatui;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.IBinder;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.annotations.ExternalThread;

/**
 * Interface to engage size compat mode UI.
 */
@ExternalThread
public interface SizeCompatUI {
    /**
     * Called when the Task info changed. Creates and updates the restart button if there is an
     * activity in size compat, or removes the restart button if there is no size compat activity.
     *
     * @param displayId display the task and activity are in.
     * @param taskId task the activity is in.
     * @param taskBounds task bounds to place the restart button in.
     * @param sizeCompatActivity the size compat activity in the task. Can be {@code null} if the
     *                           top activity in this Task is not in size compat.
     * @param taskListener listener to handle the Task Surface placement.
     */
    void onSizeCompatInfoChanged(int displayId, int taskId, @Nullable Rect taskBounds,
            @Nullable IBinder sizeCompatActivity,
            @Nullable ShellTaskOrganizer.TaskListener taskListener);
}
