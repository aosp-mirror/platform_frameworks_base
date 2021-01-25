/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.splitscreen;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import android.app.ActivityManager;
import android.graphics.Rect;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * Side stage for split-screen mode. Only tasks that are explicitly pinned to this stage show up
 * here. All other task are launch in the {@link MainStage}.
 * @see StageCoordinator
 */
class SideStage extends StageTaskListener {
    private static final String TAG = SideStage.class.getSimpleName();

    SideStage(ShellTaskOrganizer taskOrganizer, int displayId,
            StageListenerCallbacks callbacks, SyncTransactionQueue syncQueue) {
        super(taskOrganizer, displayId, callbacks, syncQueue);
    }

    void addTask(ActivityManager.RunningTaskInfo task, Rect rootBounds,
            WindowContainerTransaction wct) {
        final WindowContainerToken rootToken = mRootTaskInfo.token;
        wct.setHidden(rootToken, false)
                .setBounds(rootToken, rootBounds)
                .reparent(task.token, rootToken, true /* onTop*/)
                // Moving the root task to top after the child tasks were repareted , or the root
                // task cannot be visible and focused.
                .reorder(rootToken, true);
    }

    boolean removeAllTasks(WindowContainerTransaction wct) {
        if (mChildrenTaskInfo.size() == 0) return false;
        wct.reparentTasks(
                mRootTaskInfo.token,
                null /* newParent */,
                CONTROLLED_WINDOWING_MODES_WHEN_ACTIVE,
                CONTROLLED_ACTIVITY_TYPES,
                false /* onTop */);
        return true;
    }

    boolean removeTask(int taskId, WindowContainerToken newParent, WindowContainerTransaction wct) {
        final ActivityManager.RunningTaskInfo task = mChildrenTaskInfo.get(taskId);
        if (task == null) return false;

        wct.setHidden(mRootTaskInfo.token, true)
                .reorder(mRootTaskInfo.token, false)
                .reparent(task.token, newParent, false /* onTop */);
        return true;
    }

    int getTopVisibleTaskId() {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; --i) {
            final ActivityManager.RunningTaskInfo task = mChildrenTaskInfo.valueAt(i);
            if (task.isVisible) return task.taskId;
        }
        return INVALID_TASK_ID;
    }
}
