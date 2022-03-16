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

package com.android.wm.shell.freeform;

import static android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.io.PrintWriter;

/**
 * {@link ShellTaskOrganizer.TaskListener} for {@link
 * ShellTaskOrganizer#TASK_LISTENER_TYPE_FREEFORM}.
 */
public class FreeformTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "FreeformTaskListener";

    private final SyncTransactionQueue mSyncQueue;

    private final SparseArray<State> mTasks = new SparseArray<>();

    private static class State {
        RunningTaskInfo mTaskInfo;
        SurfaceControl mLeash;
    }

    public FreeformTaskListener(SyncTransactionQueue syncQueue) {
        mSyncQueue = syncQueue;
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mTasks.get(taskInfo.taskId) != null) {
            throw new RuntimeException("Task appeared more than once: #" + taskInfo.taskId);
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Freeform Task Appeared: #%d",
                taskInfo.taskId);
        final State state = new State();
        state.mTaskInfo = taskInfo;
        state.mLeash = leash;
        mTasks.put(taskInfo.taskId, state);

        final Rect taskBounds = taskInfo.configuration.windowConfiguration.getBounds();
        mSyncQueue.runInSync(t -> {
            Point taskPosition = taskInfo.positionInParent;
            t.setPosition(leash, taskPosition.x, taskPosition.y)
                    .setWindowCrop(leash, taskBounds.width(), taskBounds.height())
                    .show(leash);
        });
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        State state = mTasks.get(taskInfo.taskId);
        if (state == null) {
            Slog.e(TAG, "Task already vanished: #" + taskInfo.taskId);
            return;
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Freeform Task Vanished: #%d",
                taskInfo.taskId);
        mTasks.remove(taskInfo.taskId);
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        State state = mTasks.get(taskInfo.taskId);
        if (state == null) {
            throw new RuntimeException(
                    "Task info changed before appearing: #" + taskInfo.taskId);
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Freeform Task Info Changed: #%d",
                taskInfo.taskId);
        state.mTaskInfo = taskInfo;

        final Rect taskBounds = taskInfo.configuration.windowConfiguration.getBounds();
        final SurfaceControl leash = state.mLeash;
        mSyncQueue.runInSync(t -> {
            Point taskPosition = taskInfo.positionInParent;
            t.setPosition(leash, taskPosition.x, taskPosition.y)
                    .setWindowCrop(leash, taskBounds.width(), taskBounds.height())
                    .show(leash);
        });
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        b.setParent(findTaskSurface(taskId));
    }

    @Override
    public void reparentChildSurfaceToTask(int taskId, SurfaceControl sc,
            SurfaceControl.Transaction t) {
        t.reparent(sc, findTaskSurface(taskId));
    }

    private SurfaceControl findTaskSurface(int taskId) {
        if (!mTasks.contains(taskId)) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        return mTasks.get(taskId).mLeash;
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + mTasks.size() + " tasks");
    }

    @Override
    public String toString() {
        return TAG;
    }

    /**
     * Checks if freeform support is enabled in system.
     *
     * @param context context used to check settings and package manager.
     * @return {@code true} if freeform is enabled, {@code false} if not.
     */
    public static boolean isFreeformEnabled(Context context) {
        return context.getPackageManager().hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                || Settings.Global.getInt(context.getContentResolver(),
                DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0;
    }
}
