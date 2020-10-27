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

package com.android.wm.shell;

import android.app.ActivityManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

/**
  * Organizes a task in {@link android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN} when
  * it's presented in the letterbox mode either because orientations of a top activity and a device
  * don't match or because a top activity is in a size compat mode.
  */
final class LetterboxTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "LetterboxTaskListener";

    private final SyncTransactionQueue mSyncQueue;

    private final SparseArray<SurfaceControl> mLeashByTaskId = new SparseArray<>();

    LetterboxTaskListener(SyncTransactionQueue syncQueue) {
        mSyncQueue = syncQueue;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        synchronized (mLeashByTaskId) {
            if (mLeashByTaskId.get(taskInfo.taskId) != null) {
                throw new RuntimeException("Task appeared more than once: #" + taskInfo.taskId);
            }
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Letterbox Task Appeared: #%d",
                    taskInfo.taskId);
            mLeashByTaskId.put(taskInfo.taskId, leash);
            final Rect taskBounds = taskInfo.getConfiguration().windowConfiguration.getBounds();
            final Rect activtyBounds = taskInfo.letterboxActivityBounds;
            final Point taskPositionInParent = taskInfo.positionInParent;
            mSyncQueue.runInSync(t -> {
                setPositionAndWindowCrop(
                        t, leash, activtyBounds, taskBounds, taskPositionInParent);
                if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
                    t.setAlpha(leash, 1f);
                    t.setMatrix(leash, 1, 0, 0, 1);
                    t.show(leash);
                }
            });
        }
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        synchronized (mLeashByTaskId) {
            if (mLeashByTaskId.get(taskInfo.taskId) == null) {
                Slog.e(TAG, "Task already vanished: #" + taskInfo.taskId);
                return;
            }
            mLeashByTaskId.remove(taskInfo.taskId);
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Letterbox Task Vanished: #%d",
                    taskInfo.taskId);
        }
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        synchronized (mLeashByTaskId) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Letterbox Task Changed: #%d",
                    taskInfo.taskId);
            final SurfaceControl leash = mLeashByTaskId.get(taskInfo.taskId);
            final Rect taskBounds = taskInfo.getConfiguration().windowConfiguration.getBounds();
            final Rect activtyBounds = taskInfo.letterboxActivityBounds;
            final Point taskPositionInParent = taskInfo.positionInParent;
            mSyncQueue.runInSync(t -> {
                setPositionAndWindowCrop(
                        t, leash, activtyBounds, taskBounds, taskPositionInParent);
            });
        }
    }

    private static void setPositionAndWindowCrop(
                SurfaceControl.Transaction transaction,
                SurfaceControl leash,
                final Rect activityBounds,
                final Rect taskBounds,
                final Point taskPositionInParent) {
        Rect activtyInTaskCoordinates =  new Rect(activityBounds);
        activtyInTaskCoordinates.offset(-taskBounds.left, -taskBounds.top);
        transaction.setPosition(leash, taskPositionInParent.x, taskPositionInParent.y);
        transaction.setWindowCrop(leash, activtyInTaskCoordinates);
    }
}
