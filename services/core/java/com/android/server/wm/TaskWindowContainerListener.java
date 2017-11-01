/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import android.app.ActivityManager.TaskSnapshot;
import android.graphics.Rect;

/**
 * Interface used by the creator of {@link TaskWindowContainerController} to listen to changes with
 * the task container.
 */
public interface TaskWindowContainerListener extends WindowContainerListener {

    /** Called when the snapshot of this task has changed. */
    void onSnapshotChanged(TaskSnapshot snapshot);

    /** Called when the task container would like its controller to resize. */
    void requestResize(Rect bounds, int resizeMode);
}
