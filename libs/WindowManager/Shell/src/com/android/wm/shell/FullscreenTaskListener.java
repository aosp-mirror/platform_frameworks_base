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
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.io.PrintWriter;

class FullscreenTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "FullscreenTaskListener";

    private final SyncTransactionQueue mSyncQueue;

    private final ArraySet<Integer> mTasks = new ArraySet<>();

    FullscreenTaskListener(SyncTransactionQueue syncQueue) {
        mSyncQueue = syncQueue;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        synchronized (mTasks) {
            if (mTasks.contains(taskInfo.taskId)) {
                throw new RuntimeException("Task appeared more than once: #" + taskInfo.taskId);
            }
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Appeared: #%d",
                    taskInfo.taskId);
            mTasks.add(taskInfo.taskId);
            mSyncQueue.runInSync(t -> {
                // Reset several properties back to fullscreen (PiP, for example, leaves all these
                // properties in a bad state).
                t.setPosition(leash, 0, 0);
                t.setWindowCrop(leash, null);
                // TODO(shell-transitions): Eventually set everything in transition so there's no
                //                          SF Transaction here.
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
        synchronized (mTasks) {
            if (!mTasks.remove(taskInfo.taskId)) {
                Slog.e(TAG, "Task already vanished: #" + taskInfo.taskId);
                return;
            }
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Vanished: #%d",
                    taskInfo.taskId);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + mTasks.size() + " Tasks");
    }

    @Override
    public String toString() {
        return TAG + ":" + taskListenerTypeToString(TASK_LISTENER_TYPE_FULLSCREEN);
    }
}
