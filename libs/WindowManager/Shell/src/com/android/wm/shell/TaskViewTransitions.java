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

package com.android.wm.shell;

import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.os.IBinder;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;

/**
 * Handles Shell Transitions that involve TaskView tasks.
 */
public class TaskViewTransitions implements Transitions.TransitionHandler {
    private static final String TAG = "TaskViewTransitions";

    private final ArrayList<TaskView> mTaskViews = new ArrayList<>();
    private final ArrayList<PendingTransition> mPending = new ArrayList<>();
    private final Transitions mTransitions;
    private final boolean[] mRegistered = new boolean[]{ false };

    /**
     * TaskView makes heavy use of startTransition. Only one shell-initiated transition can be
     * in-flight (collecting) at a time (because otherwise, the operations could get merged into
     * a single transition). So, keep a queue here until we add a queue in server-side.
     */
    private static class PendingTransition {
        final @WindowManager.TransitionType int mType;
        final WindowContainerTransaction mWct;
        final @NonNull TaskView mTaskView;
        IBinder mClaimed;

        PendingTransition(@WindowManager.TransitionType int type,
                @Nullable WindowContainerTransaction wct, @NonNull TaskView taskView) {
            mType = type;
            mWct = wct;
            mTaskView = taskView;
        }
    }

    public TaskViewTransitions(Transitions transitions) {
        mTransitions = transitions;
        // Defer registration until the first TaskView because we want this to be the "first" in
        // priority when handling requests.
        // TODO(210041388): register here once we have an explicit ordering mechanism.
    }

    void addTaskView(TaskView tv) {
        synchronized (mRegistered) {
            if (!mRegistered[0]) {
                mRegistered[0] = true;
                mTransitions.addHandler(this);
            }
        }
        mTaskViews.add(tv);
    }

    void removeTaskView(TaskView tv) {
        mTaskViews.remove(tv);
        // Note: Don't unregister handler since this is a singleton with lifetime bound to Shell
    }

    boolean isEnabled() {
        return mTransitions.isRegistered();
    }

    /**
     * Looks through the pending transitions for one matching `taskView`.
     * @param taskView the pending transition should be for this.
     * @param closing When true, this only returns a pending transition of the close/hide type.
     *                Otherwise it selects open/show.
     * @param latest When true, this will only check the most-recent pending transition for the
     *               specified taskView. If it doesn't match `closing`, this will return null even
     *               if there is a match earlier. The idea behind this is to check the state of
     *               the taskviews "as if all transitions already happened".
     */
    private PendingTransition findPending(TaskView taskView, boolean closing, boolean latest) {
        for (int i = mPending.size() - 1; i >= 0; --i) {
            if (mPending.get(i).mTaskView != taskView) continue;
            if (Transitions.isClosingType(mPending.get(i).mType) == closing) {
                return mPending.get(i);
            }
            if (latest) {
                return null;
            }
        }
        return null;
    }

    private PendingTransition findPending(IBinder claimed) {
        for (int i = 0; i < mPending.size(); ++i) {
            if (mPending.get(i).mClaimed != claimed) continue;
            return mPending.get(i);
        }
        return null;
    }

    /** @return whether there are pending transitions on TaskViews. */
    public boolean hasPending() {
        return !mPending.isEmpty();
    }

    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @Nullable TransitionRequestInfo request) {
        final ActivityManager.RunningTaskInfo triggerTask = request.getTriggerTask();
        if (triggerTask == null) {
            return null;
        }
        final TaskView taskView = findTaskView(triggerTask);
        if (taskView == null) return null;
        // Opening types should all be initiated by shell
        if (!Transitions.isClosingType(request.getType())) return null;
        PendingTransition pending = findPending(taskView, true /* closing */, false /* latest */);
        if (pending == null) {
            pending = new PendingTransition(request.getType(), null, taskView);
        }
        if (pending.mClaimed != null) {
            throw new IllegalStateException("Task is closing in 2 collecting transitions?"
                    + " This state doesn't make sense");
        }
        pending.mClaimed = transition;
        return new WindowContainerTransaction();
    }

    private TaskView findTaskView(ActivityManager.RunningTaskInfo taskInfo) {
        for (int i = 0; i < mTaskViews.size(); ++i) {
            if (mTaskViews.get(i).getTaskInfo() == null) continue;
            if (taskInfo.token.equals(mTaskViews.get(i).getTaskInfo().token)) {
                return mTaskViews.get(i);
            }
        }
        return null;
    }

    void startTaskView(WindowContainerTransaction wct, TaskView taskView) {
        mPending.add(new PendingTransition(TRANSIT_OPEN, wct, taskView));
        startNextTransition();
    }

    void setTaskViewVisible(TaskView taskView, boolean visible) {
        PendingTransition pending = findPending(taskView, !visible, true /* latest */);
        if (pending != null) {
            // Already opening or creating a task, so no need to do anything here.
            return;
        }
        if (taskView.getTaskInfo() == null) {
            // Nothing to update, task is not yet available
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setHidden(taskView.getTaskInfo().token, !visible /* hidden */);
        pending = new PendingTransition(
                visible ? TRANSIT_TO_FRONT : TRANSIT_TO_BACK, wct, taskView);
        mPending.add(pending);
        startNextTransition();
        // visibility is reported in transition.
    }

    private void startNextTransition() {
        if (mPending.isEmpty()) return;
        final PendingTransition pending = mPending.get(0);
        if (pending.mClaimed != null) {
            // Wait for this to start animating.
            return;
        }
        pending.mClaimed = mTransitions.startTransition(pending.mType, pending.mWct, this);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        PendingTransition pending = findPending(transition);
        if (pending == null) return false;
        mPending.remove(pending);
        TaskView taskView = pending.mTaskView;
        final ArrayList<TransitionInfo.Change> tasks = new ArrayList<>();
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = info.getChanges().get(i);
            if (chg.getTaskInfo() == null) continue;
            tasks.add(chg);
        }
        if (tasks.isEmpty()) {
            Slog.e(TAG, "Got a TaskView transition with no task.");
            return false;
        }
        WindowContainerTransaction wct = null;
        for (int i = 0; i < tasks.size(); ++i) {
            TransitionInfo.Change chg = tasks.get(i);
            if (Transitions.isClosingType(chg.getMode())) {
                final boolean isHide = chg.getMode() == TRANSIT_TO_BACK;
                TaskView tv = findTaskView(chg.getTaskInfo());
                if (tv == null) {
                    throw new IllegalStateException("TaskView transition is closing a "
                            + "non-taskview task ");
                }
                if (isHide) {
                    tv.prepareHideAnimation(finishTransaction);
                } else {
                    tv.prepareCloseAnimation();
                }
            } else if (Transitions.isOpeningType(chg.getMode())) {
                final boolean taskIsNew = chg.getMode() == TRANSIT_OPEN;
                if (wct == null) wct = new WindowContainerTransaction();
                TaskView tv = taskView;
                if (!taskIsNew) {
                    tv = findTaskView(chg.getTaskInfo());
                    if (tv == null) {
                        throw new IllegalStateException("TaskView transition is showing a "
                            + "non-taskview task ");
                    }
                }
                tv.prepareOpenAnimation(taskIsNew, startTransaction, finishTransaction,
                        chg.getTaskInfo(), chg.getLeash(), wct);
            } else {
                throw new IllegalStateException("Claimed transition isn't an opening or closing"
                        + " type: " + chg.getMode());
            }
        }
        // No animation, just show it immediately.
        startTransaction.apply();
        finishTransaction.apply();
        finishCallback.onTransitionFinished(wct, null /* wctCB */);
        startNextTransition();
        return true;
    }
}
