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

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;
import static com.android.wm.shell.ShellTaskOrganizer.taskListenerTypeToString;

import android.app.ActivityManager;
import android.graphics.Point;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;

/**
  * Organizes tasks presented in {@link android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN}.
  */
public class FullscreenTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "FullscreenTaskListener";

    private final SyncTransactionQueue mSyncQueue;

    private final SparseArray<SurfaceControl> mLeashByTaskId = new SparseArray<>();

    public FullscreenTaskListener(SyncTransactionQueue syncQueue) {
        mSyncQueue = syncQueue;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mLeashByTaskId.get(taskInfo.taskId) != null) {
            throw new IllegalStateException("Task appeared more than once: #" + taskInfo.taskId);
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Appeared: #%d",
                taskInfo.taskId);
        mLeashByTaskId.put(taskInfo.taskId, leash);
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        final Point positionInParent = taskInfo.positionInParent;
        mSyncQueue.runInSync(t -> {
            // Reset several properties back to fullscreen (PiP, for example, leaves all these
            // properties in a bad state).
            t.setWindowCrop(leash, null);
            t.setPosition(leash, positionInParent.x, positionInParent.y);
            t.setAlpha(leash, 1f);
            t.setMatrix(leash, 1, 0, 0, 1);
            t.show(leash);
        });
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        final SurfaceControl leash = mLeashByTaskId.get(taskInfo.taskId);
        final Point positionInParent = taskInfo.positionInParent;
        mSyncQueue.runInSync(t -> {
            // Reset several properties back. For instance, when an Activity enters PiP with
            // multiple activities in the same task, a new task will be created from that Activity
            // and we want reset the leash of the original task.
            t.setPosition(leash, positionInParent.x, positionInParent.y);
            t.setWindowCrop(leash, null);
        });
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        if (mLeashByTaskId.get(taskInfo.taskId) == null) {
            Slog.e(TAG, "Task already vanished: #" + taskInfo.taskId);
            return;
        }
        mLeashByTaskId.remove(taskInfo.taskId);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Vanished: #%d",
                taskInfo.taskId);
    }

    @Override
    public boolean supportSizeCompatUI() {
        return true;
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        if (!mLeashByTaskId.contains(taskId)) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        b.setParent(mLeashByTaskId.get(taskId));
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + mLeashByTaskId.size() + " Tasks");
    }

    @Override
    public String toString() {
        return TAG + ":" + taskListenerTypeToString(TASK_LISTENER_TYPE_FULLSCREEN);
    }
}
