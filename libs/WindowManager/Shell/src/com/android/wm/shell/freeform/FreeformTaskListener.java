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

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FREEFORM;

import android.app.ActivityManager.RunningTaskInfo;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.TransitionInfo;

import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import java.io.PrintWriter;
import java.util.Optional;

/**
 * {@link ShellTaskOrganizer.TaskListener} for {@link
 * ShellTaskOrganizer#TASK_LISTENER_TYPE_FREEFORM}.
 *
 * @param <T> the type of window decoration instance
 */
public class FreeformTaskListener<T extends AutoCloseable>
        implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = "FreeformTaskListener";

    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final Optional<DesktopModeTaskRepository> mDesktopModeTaskRepository;
    private final WindowDecorViewModel<T> mWindowDecorationViewModel;

    private final SparseArray<State<T>> mTasks = new SparseArray<>();
    private final SparseArray<T> mWindowDecorOfVanishedTasks = new SparseArray<>();

    private static class State<T extends AutoCloseable> {
        RunningTaskInfo mTaskInfo;
        SurfaceControl mLeash;
        T mWindowDecoration;
    }

    public FreeformTaskListener(
            ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<DesktopModeTaskRepository> desktopModeTaskRepository,
            WindowDecorViewModel<T> windowDecorationViewModel) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mWindowDecorationViewModel = windowDecorationViewModel;
        mDesktopModeTaskRepository = desktopModeTaskRepository;
        if (shellInit != null) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        mShellTaskOrganizer.addListenerForType(this, TASK_LISTENER_TYPE_FREEFORM);
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Freeform Task Appeared: #%d",
                taskInfo.taskId);
        final State<T> state = createOrUpdateTaskState(taskInfo, leash);
        if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            state.mWindowDecoration =
                    mWindowDecorationViewModel.createWindowDecoration(taskInfo, leash, t, t);
            t.apply();
        }

        if (DesktopModeStatus.IS_SUPPORTED && taskInfo.isVisible) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                    "Adding active freeform task: #%d", taskInfo.taskId);
            mDesktopModeTaskRepository.ifPresent(it -> it.addActiveTask(taskInfo.taskId));
        }
    }

    private State<T> createOrUpdateTaskState(RunningTaskInfo taskInfo, SurfaceControl leash) {
        State<T> state = mTasks.get(taskInfo.taskId);
        if (state != null) {
            updateTaskInfo(taskInfo);
            return state;
        }

        state = new State<>();
        state.mTaskInfo = taskInfo;
        state.mLeash = leash;
        mTasks.put(taskInfo.taskId, state);

        return state;
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        final State<T> state = mTasks.get(taskInfo.taskId);
        if (state == null) {
            // This is possible if the transition happens before this method.
            return;
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Freeform Task Vanished: #%d",
                taskInfo.taskId);
        mTasks.remove(taskInfo.taskId);

        if (DesktopModeStatus.IS_SUPPORTED) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                    "Removing active freeform task: #%d", taskInfo.taskId);
            mDesktopModeTaskRepository.ifPresent(it -> it.removeActiveTask(taskInfo.taskId));
        }

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

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final State<T> state = updateTaskInfo(taskInfo);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Freeform Task Info Changed: #%d",
                taskInfo.taskId);
        if (state.mWindowDecoration != null) {
            mWindowDecorationViewModel.onTaskInfoChanged(state.mTaskInfo, state.mWindowDecoration);
        }

        if (DesktopModeStatus.IS_SUPPORTED) {
            if (taskInfo.isVisible) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                        "Adding active freeform task: #%d", taskInfo.taskId);
                mDesktopModeTaskRepository.ifPresent(it -> it.addActiveTask(taskInfo.taskId));
            }
        }
    }

    private State<T> updateTaskInfo(RunningTaskInfo taskInfo) {
        final State<T> state = mTasks.get(taskInfo.taskId);
        if (state == null) {
            throw new RuntimeException("Task info changed before appearing: #" + taskInfo.taskId);
        }
        state.mTaskInfo = taskInfo;
        return state;
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

    /**
     * Creates a window decoration for a transition.
     *
     * @param change the change of this task transition that needs to have the task layer as the
     *               leash
     * @return {@code true} if it creates the window decoration; {@code false} otherwise
     */
    boolean createWindowDecoration(
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final State<T> state = createOrUpdateTaskState(change.getTaskInfo(), change.getLeash());
        if (state.mWindowDecoration != null) {
            return false;
        }
        state.mWindowDecoration = mWindowDecorationViewModel.createWindowDecoration(
                state.mTaskInfo, state.mLeash, startT, finishT);
        return true;
    }

    /**
     * Gives out the ownership of the task's window decoration. The given task is leaving (of has
     * left) this task listener. This is the transition system asking for the ownership.
     *
     * @param taskInfo the maximizing task
     * @return the window decor of the maximizing task if any
     */
    T giveWindowDecoration(
            RunningTaskInfo taskInfo,
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
        if (windowDecor == null) {
            return null;
        }
        mWindowDecorationViewModel.setupWindowDecorationForTransition(
                taskInfo, startT, finishT, windowDecor);
        return windowDecor;
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
    boolean adoptWindowDecoration(
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT,
            @Nullable AutoCloseable windowDecor) {
        final State<T> state = createOrUpdateTaskState(change.getTaskInfo(), change.getLeash());
        state.mWindowDecoration = mWindowDecorationViewModel.adoptWindowDecoration(windowDecor);
        if (state.mWindowDecoration != null) {
            mWindowDecorationViewModel.setupWindowDecorationForTransition(
                    state.mTaskInfo, startT, finishT, state.mWindowDecoration);
            return true;
        } else {
            state.mWindowDecoration = mWindowDecorationViewModel.createWindowDecoration(
                    state.mTaskInfo, state.mLeash, startT, finishT);
            return false;
        }
    }

    void onTaskTransitionFinished() {
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

    private void releaseWindowDecor(T windowDecor) {
        try {
            windowDecor.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to release window decoration.", e);
        }
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
}
