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

package com.android.wm.shell.fullscreen;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;
import static com.android.wm.shell.ShellTaskOrganizer.taskListenerTypeToString;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.TaskInfo;
import android.graphics.Point;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.Optional;

/**
  * Organizes tasks presented in {@link android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN}.
  */
public class FullscreenTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "FullscreenTaskListener";

    private final SyncTransactionQueue mSyncQueue;
    private final FullscreenUnfoldController mFullscreenUnfoldController;
    private final Optional<RecentTasksController> mRecentTasksOptional;

    private final SparseArray<TaskData> mDataByTaskId = new SparseArray<>();
    private final AnimatableTasksListener mAnimatableTasksListener = new AnimatableTasksListener();

    public FullscreenTaskListener(SyncTransactionQueue syncQueue,
            Optional<FullscreenUnfoldController> unfoldController) {
        this(syncQueue, unfoldController, Optional.empty());
    }

    public FullscreenTaskListener(SyncTransactionQueue syncQueue,
            Optional<FullscreenUnfoldController> unfoldController,
            Optional<RecentTasksController> recentTasks) {
        mSyncQueue = syncQueue;
        mFullscreenUnfoldController = unfoldController.orElse(null);
        mRecentTasksOptional = recentTasks;
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mDataByTaskId.get(taskInfo.taskId) != null) {
            throw new IllegalStateException("Task appeared more than once: #" + taskInfo.taskId);
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Appeared: #%d",
                taskInfo.taskId);
        final Point positionInParent = taskInfo.positionInParent;
        mDataByTaskId.put(taskInfo.taskId, new TaskData(leash, positionInParent));
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        mSyncQueue.runInSync(t -> {
            // Reset several properties back to fullscreen (PiP, for example, leaves all these
            // properties in a bad state).
            t.setWindowCrop(leash, null);
            t.setPosition(leash, positionInParent.x, positionInParent.y);
            t.setAlpha(leash, 1f);
            t.setMatrix(leash, 1, 0, 0, 1);
            t.show(leash);
        });

        mAnimatableTasksListener.onTaskAppeared(taskInfo);
        updateRecentsForVisibleFullscreenTask(taskInfo);
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;

        mAnimatableTasksListener.onTaskInfoChanged(taskInfo);
        updateRecentsForVisibleFullscreenTask(taskInfo);

        final TaskData data = mDataByTaskId.get(taskInfo.taskId);
        final Point positionInParent = taskInfo.positionInParent;
        if (!positionInParent.equals(data.positionInParent)) {
            data.positionInParent.set(positionInParent.x, positionInParent.y);
            mSyncQueue.runInSync(t -> {
                t.setPosition(data.surface, positionInParent.x, positionInParent.y);
            });
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        if (mDataByTaskId.get(taskInfo.taskId) == null) {
            Slog.e(TAG, "Task already vanished: #" + taskInfo.taskId);
            return;
        }

        mAnimatableTasksListener.onTaskVanished(taskInfo);
        mDataByTaskId.remove(taskInfo.taskId);

        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Vanished: #%d",
                taskInfo.taskId);
    }

    private void updateRecentsForVisibleFullscreenTask(RunningTaskInfo taskInfo) {
        mRecentTasksOptional.ifPresent(recentTasks -> {
            if (taskInfo.isVisible) {
                // Remove any persisted splits if either tasks are now made fullscreen and visible
                recentTasks.removeSplitPair(taskInfo.taskId);
            }
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
        if (!mDataByTaskId.contains(taskId)) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        return mDataByTaskId.get(taskId).surface;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + mDataByTaskId.size() + " Tasks");
    }

    @Override
    public String toString() {
        return TAG + ":" + taskListenerTypeToString(TASK_LISTENER_TYPE_FULLSCREEN);
    }

    /**
     * Per-task data for each managed task.
     */
    private static class TaskData {
        public final SurfaceControl surface;
        public final Point positionInParent;

        public TaskData(SurfaceControl surface, Point positionInParent) {
            this.surface = surface;
            this.positionInParent = positionInParent;
        }
    }

    class AnimatableTasksListener {
        private final SparseBooleanArray mTaskIds = new SparseBooleanArray();

        public void onTaskAppeared(RunningTaskInfo taskInfo) {
            final boolean isApplicable = isAnimatable(taskInfo);
            if (isApplicable) {
                mTaskIds.put(taskInfo.taskId, true);

                if (mFullscreenUnfoldController != null) {
                    SurfaceControl leash = mDataByTaskId.get(taskInfo.taskId).surface;
                    mFullscreenUnfoldController.onTaskAppeared(taskInfo, leash);
                }
            }
        }

        public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
            final boolean isCurrentlyApplicable = mTaskIds.get(taskInfo.taskId);
            final boolean isApplicable = isAnimatable(taskInfo);

            if (isCurrentlyApplicable) {
                if (isApplicable) {
                    // Still applicable, send update
                    if (mFullscreenUnfoldController != null) {
                        mFullscreenUnfoldController.onTaskInfoChanged(taskInfo);
                    }
                } else {
                    // Became inapplicable
                    if (mFullscreenUnfoldController != null) {
                        mFullscreenUnfoldController.onTaskVanished(taskInfo);
                    }
                    mTaskIds.put(taskInfo.taskId, false);
                }
            } else {
                if (isApplicable) {
                    // Became applicable
                    mTaskIds.put(taskInfo.taskId, true);

                    if (mFullscreenUnfoldController != null) {
                        SurfaceControl leash = mDataByTaskId.get(taskInfo.taskId).surface;
                        mFullscreenUnfoldController.onTaskAppeared(taskInfo, leash);
                    }
                }
            }
        }

        public void onTaskVanished(RunningTaskInfo taskInfo) {
            final boolean isCurrentlyApplicable = mTaskIds.get(taskInfo.taskId);
            if (isCurrentlyApplicable && mFullscreenUnfoldController != null) {
                mFullscreenUnfoldController.onTaskVanished(taskInfo);
            }
            mTaskIds.put(taskInfo.taskId, false);
        }

        private boolean isAnimatable(TaskInfo taskInfo) {
            // Filter all visible tasks that are not launcher tasks
            // We do not animate launcher as it handles the animation by itself
            return taskInfo != null && taskInfo.isVisible && taskInfo.getConfiguration()
                    .windowConfiguration.getActivityType() != ACTIVITY_TYPE_HOME;
        }
    }
}
