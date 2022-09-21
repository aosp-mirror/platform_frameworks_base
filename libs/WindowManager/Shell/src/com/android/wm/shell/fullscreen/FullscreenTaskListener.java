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

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;
import static com.android.wm.shell.ShellTaskOrganizer.taskListenerTypeToString;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Point;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import java.io.PrintWriter;
import java.util.Optional;

/**
  * Organizes tasks presented in {@link android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN}.
 * @param <T> the type of window decoration instance
  */
public class FullscreenTaskListener<T extends AutoCloseable>
        implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "FullscreenTaskListener";

    private final ShellTaskOrganizer mShellTaskOrganizer;

    private final SparseArray<State<T>> mTasks = new SparseArray<>();
    private final SparseArray<T> mWindowDecorOfVanishedTasks = new SparseArray<>();

    private static class State<T extends AutoCloseable> {
        RunningTaskInfo mTaskInfo;
        SurfaceControl mLeash;
        T mWindowDecoration;
    }
    private final SyncTransactionQueue mSyncQueue;
    private final Optional<RecentTasksController> mRecentTasksOptional;
    private final Optional<WindowDecorViewModel<T>> mWindowDecorViewModelOptional;
    /**
     * This constructor is used by downstream products.
     */
    public FullscreenTaskListener(SyncTransactionQueue syncQueue) {
        this(null /* shellInit */, null /* shellTaskOrganizer */, syncQueue, Optional.empty(),
                Optional.empty());
    }

    public FullscreenTaskListener(ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue,
            Optional<RecentTasksController> recentTasksOptional,
            Optional<WindowDecorViewModel<T>> windowDecorViewModelOptional) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mRecentTasksOptional = recentTasksOptional;
        mWindowDecorViewModelOptional = windowDecorViewModelOptional;
        // Note: Some derivative FullscreenTaskListener implementations do not use ShellInit
        if (shellInit != null) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        mShellTaskOrganizer.addListenerForType(this, TASK_LISTENER_TYPE_FULLSCREEN);
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mTasks.get(taskInfo.taskId) != null) {
            throw new IllegalStateException("Task appeared more than once: #" + taskInfo.taskId);
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Appeared: #%d",
                taskInfo.taskId);
        final Point positionInParent = taskInfo.positionInParent;
        final State<T> state = new State();
        state.mLeash = leash;
        state.mTaskInfo = taskInfo;
        mTasks.put(taskInfo.taskId, state);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        updateRecentsForVisibleFullscreenTask(taskInfo);
        if (mWindowDecorViewModelOptional.isPresent()) {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            state.mWindowDecoration =
                    mWindowDecorViewModelOptional.get().createWindowDecoration(taskInfo,
                            leash, t, t);
            t.apply();
        }
        if (state.mWindowDecoration == null) {
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
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final State<T> state = mTasks.get(taskInfo.taskId);
        final Point oldPositionInParent = state.mTaskInfo.positionInParent;
        state.mTaskInfo = taskInfo;
        if (state.mWindowDecoration != null) {
            mWindowDecorViewModelOptional.get().onTaskInfoChanged(
                    state.mTaskInfo, state.mWindowDecoration);
        }
        if (Transitions.ENABLE_SHELL_TRANSITIONS) return;
        updateRecentsForVisibleFullscreenTask(taskInfo);

        final Point positionInParent = state.mTaskInfo.positionInParent;
        if (!oldPositionInParent.equals(state.mTaskInfo.positionInParent)) {
            mSyncQueue.runInSync(t -> {
                t.setPosition(state.mLeash, positionInParent.x, positionInParent.y);
            });
        }
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        final State<T> state = mTasks.get(taskInfo.taskId);
        if (state == null) {
            // This is possible if the transition happens before this method.
            return;
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Fullscreen Task Vanished: #%d",
                taskInfo.taskId);
        mTasks.remove(taskInfo.taskId);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            // Save window decorations of closing tasks so that we can hand them over to the
            // transition system if this method happens before the transition. In case where the
            // transition didn't happen, it'd be cleared when the next transition finished.
            if (state.mWindowDecoration != null) {
                mWindowDecorOfVanishedTasks.put(taskInfo.taskId, state.mWindowDecoration);
            }
            return;
        }
        releaseWindowDecor(state.mWindowDecoration);
    }

    /**
     * Creates a window decoration for a transition.
     *
     * @param change the change of this task transition that needs to have the task layer as the
     *               leash
     * @return {@code true} if a decoration was actually created.
     */
    public boolean createWindowDecoration(TransitionInfo.Change change,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT) {
        final State<T> state = createOrUpdateTaskState(change.getTaskInfo(), change.getLeash());
        if (!mWindowDecorViewModelOptional.isPresent()) return false;
        if (state.mWindowDecoration != null) {
            // Already has a decoration.
            return false;
        }
        T newWindowDecor = mWindowDecorViewModelOptional.get().createWindowDecoration(
                state.mTaskInfo, state.mLeash, startT, finishT);
        if (newWindowDecor != null) {
            state.mWindowDecoration = newWindowDecor;
            return true;
        }
        return false;
    }

    /**
     * Adopt the incoming window decoration and lets the window decoration prepare for a transition.
     *
     * @param change the change of this task transition that needs to have the task layer as the
     *               leash
     * @param startT the start transaction of this transition
     * @param finishT the finish transaction of this transition
     * @param windowDecor the window decoration to adopt
     * @return {@code true} if it adopts the window decoration; {@code false} otherwise
     */
    public boolean adoptWindowDecoration(
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT,
            @Nullable AutoCloseable windowDecor) {
        if (!mWindowDecorViewModelOptional.isPresent()) {
            return false;
        }
        final State<T> state = createOrUpdateTaskState(change.getTaskInfo(), change.getLeash());
        state.mWindowDecoration = mWindowDecorViewModelOptional.get().adoptWindowDecoration(
                windowDecor);
        if (state.mWindowDecoration != null) {
            mWindowDecorViewModelOptional.get().setupWindowDecorationForTransition(
                    state.mTaskInfo, startT, finishT, state.mWindowDecoration);
            return true;
        } else {
            T newWindowDecor = mWindowDecorViewModelOptional.get().createWindowDecoration(
                    state.mTaskInfo, state.mLeash, startT, finishT);
            if (newWindowDecor != null) {
                state.mWindowDecoration = newWindowDecor;
            }
            return false;
        }
    }

    /**
     * Clear window decors of vanished tasks.
     */
    public void onTaskTransitionFinished() {
        if (mWindowDecorOfVanishedTasks.size() == 0) {
            return;
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "Clearing window decors of vanished tasks. There could be visual defects "
                + "if any of them is used later in transitions.");
        for (int i = 0; i < mWindowDecorOfVanishedTasks.size(); ++i) {
            releaseWindowDecor(mWindowDecorOfVanishedTasks.valueAt(i));
        }
        mWindowDecorOfVanishedTasks.clear();
    }

    /**
     * Gives out the ownership of the task's window decoration. The given task is leaving (of has
     * left) this task listener. This is the transition system asking for the ownership.
     *
     * @param taskInfo the maximizing task
     * @return the window decor of the maximizing task if any
     */
    public T giveWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        T windowDecor;
        final State<T> state = mTasks.get(taskInfo.taskId);
        if (state != null) {
            windowDecor = state.mWindowDecoration;
            state.mWindowDecoration = null;
        } else {
            windowDecor =
                    mWindowDecorOfVanishedTasks.removeReturnOld(taskInfo.taskId);
        }
        if (mWindowDecorViewModelOptional.isPresent() && windowDecor != null) {
            mWindowDecorViewModelOptional.get().setupWindowDecorationForTransition(
                    taskInfo, startT, finishT, windowDecor);
        }

        return windowDecor;
    }

    private State<T> createOrUpdateTaskState(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl leash) {
        State<T> state = mTasks.get(taskInfo.taskId);
        if (state != null) {
            updateTaskInfo(taskInfo);
            return state;
        }

        state = new State<T>();
        state.mTaskInfo = taskInfo;
        state.mLeash = leash;
        mTasks.put(taskInfo.taskId, state);

        return state;
    }

    private State<T> updateTaskInfo(ActivityManager.RunningTaskInfo taskInfo) {
        final State<T> state = mTasks.get(taskInfo.taskId);
        state.mTaskInfo = taskInfo;
        return state;
    }

    private void releaseWindowDecor(T windowDecor) {
        if (windowDecor == null) {
            return;
        }
        try {
            windowDecor.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to release window decoration.", e);
        }
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
        if (!mTasks.contains(taskId)) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        return mTasks.get(taskId).mLeash;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + this);
        pw.println(innerPrefix + mTasks.size() + " Tasks");
    }

    @Override
    public String toString() {
        return TAG + ":" + taskListenerTypeToString(TASK_LISTENER_TYPE_FULLSCREEN);
    }


}
