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

package com.android.wm.shell.taskview;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.Flags;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.shared.bubbles.BubbleAnythingFlagHelper;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * Handles Shell Transitions that involve TaskView tasks.
 */
public class TaskViewTransitions implements Transitions.TransitionHandler, TaskViewController {
    static final String TAG = "TaskViewTransitions";

    /**
     * Map of {@link TaskViewTaskController} to {@link TaskViewRepository.TaskViewState}.
     * <p>
     * {@link TaskView} keeps a reference to the {@link TaskViewTaskController} instance and
     * manages its lifecycle.
     * Only keep a weak reference to the controller instance here to allow for it to be cleaned
     * up when its TaskView is no longer used.
     */
    private final Map<TaskViewTaskController, TaskViewRepository.TaskViewState> mTaskViews;
    private final TaskViewRepository mTaskViewRepo;
    private final ArrayList<PendingTransition> mPending = new ArrayList<>();
    private final Transitions mTransitions;
    private final boolean[] mRegistered = new boolean[]{false};
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Executor mShellExecutor;
    private final SyncTransactionQueue mSyncQueue;

    /** A temp transaction used for quick things. */
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    /**
     * TaskView makes heavy use of startTransition. Only one shell-initiated transition can be
     * in-flight (collecting) at a time (because otherwise, the operations could get merged into
     * a single transition). So, keep a queue here until we add a queue in server-side.
     */
    @VisibleForTesting
    static class PendingTransition {
        final @WindowManager.TransitionType int mType;
        final WindowContainerTransaction mWct;
        final @NonNull TaskViewTaskController mTaskView;
        ExternalTransition mExternalTransition;
        IBinder mClaimed;

        /**
         * This is needed because arbitrary activity launches can still "intrude" into any
         * transition since `startActivity` is a synchronous call. Once that is solved, we can
         * remove this.
         */
        final IBinder mLaunchCookie;

        PendingTransition(@WindowManager.TransitionType int type,
                @Nullable WindowContainerTransaction wct,
                @NonNull TaskViewTaskController taskView,
                @Nullable IBinder launchCookie) {
            mType = type;
            mWct = wct;
            mTaskView = taskView;
            mLaunchCookie = launchCookie;
        }
    }

    public TaskViewTransitions(Transitions transitions, TaskViewRepository repository,
            ShellTaskOrganizer taskOrganizer, SyncTransactionQueue syncQueue) {
        mTransitions = transitions;
        mTaskOrganizer = taskOrganizer;
        mShellExecutor = taskOrganizer.getExecutor();
        mSyncQueue = syncQueue;
        if (useRepo()) {
            mTaskViews = null;
        } else if (Flags.enableTaskViewControllerCleanup()) {
            mTaskViews = new WeakHashMap<>();
        } else {
            mTaskViews = new ArrayMap<>();
        }
        mTaskViewRepo = repository;
        // Defer registration until the first TaskView because we want this to be the "first" in
        // priority when handling requests.
        // TODO(210041388): register here once we have an explicit ordering mechanism.
    }

    /** @return whether the shared taskview repository is being used. */
    public static boolean useRepo() {
        return Flags.taskViewRepository() || Flags.enableBubbleAnything();
    }

    public TaskViewRepository getRepository() {
        return mTaskViewRepo;
    }

    @Override
    public void registerTaskView(TaskViewTaskController tv) {
        synchronized (mRegistered) {
            if (!mRegistered[0]) {
                mRegistered[0] = true;
                mTransitions.addHandler(this);
            }
        }
        if (useRepo()) {
            mTaskViewRepo.add(tv);
        } else {
            mTaskViews.put(tv, new TaskViewRepository.TaskViewState(null));
        }
    }

    @Override
    public void unregisterTaskView(TaskViewTaskController tv) {
        if (useRepo()) {
            mTaskViewRepo.remove(tv);
        } else {
            mTaskViews.remove(tv);
        }
        // Note: Don't unregister handler since this is a singleton with lifetime bound to Shell
    }

    @Override
    public boolean isUsingShellTransitions() {
        return mTransitions.isRegistered();
    }

    /**
     * Starts a transition outside of the handler associated with {@link TaskViewTransitions}.
     */
    public void startInstantTransition(@WindowManager.TransitionType int type,
            WindowContainerTransaction wct) {
        mTransitions.startTransition(type, wct, null);
    }

    /**
     * Starts or queues an "external" runnable into the pending queue. This means it will run
     * in order relative to the local transitions.
     *
     * The external operation *must* call {@link #onExternalDone} once it has finished.
     *
     * In practice, the external is usually another transition on a different handler.
     */
    public void enqueueExternal(@NonNull TaskViewTaskController taskView, ExternalTransition ext) {
        final PendingTransition pending = new PendingTransition(
                TRANSIT_NONE, null /* wct */, taskView, null /* cookie */);
        pending.mExternalTransition = ext;
        mPending.add(pending);
        startNextTransition();
    }

    /**
     * An external transition run in this "queue" is required to call this once it becomes ready.
     */
    public void onExternalDone(IBinder key) {
        final PendingTransition pending = findPending(key);
        if (pending == null) return;
        mPending.remove(pending);
        startNextTransition();
    }

    /**
     * Looks through the pending transitions for a opening transaction that matches the provided
     * `taskView`.
     *
     * @param taskView the pending transition should be for this.
     */
    @VisibleForTesting
    PendingTransition findPendingOpeningTransition(TaskViewTaskController taskView) {
        for (int i = mPending.size() - 1; i >= 0; --i) {
            if (mPending.get(i).mTaskView != taskView) continue;
            if (mPending.get(i).mExternalTransition != null) continue;
            if (TransitionUtil.isOpeningType(mPending.get(i).mType)) {
                return mPending.get(i);
            }
        }
        return null;
    }

    /**
     * Looks through the pending transitions for one matching `taskView`.
     *
     * @param taskView the pending transition should be for this.
     * @param type     the type of transition it's looking for
     */
    PendingTransition findPending(TaskViewTaskController taskView, int type) {
        for (int i = mPending.size() - 1; i >= 0; --i) {
            if (mPending.get(i).mTaskView != taskView) continue;
            if (mPending.get(i).mExternalTransition != null) continue;
            if (mPending.get(i).mType == type) {
                return mPending.get(i);
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
        final TaskViewTaskController taskView = findTaskView(triggerTask);
        if (taskView == null) return null;
        // Opening types should all be initiated by shell
        if (!TransitionUtil.isClosingType(request.getType())) return null;
        PendingTransition pending = new PendingTransition(request.getType(), null,
                taskView, null /* cookie */);
        pending.mClaimed = transition;
        mPending.add(pending);
        return new WindowContainerTransaction();
    }

    private TaskViewTaskController findTaskView(ActivityManager.RunningTaskInfo taskInfo) {
        if (useRepo()) {
            final TaskViewRepository.TaskViewState state = mTaskViewRepo.byToken(taskInfo.token);
            return state != null ? state.getTaskView() : null;
        }
        if (Flags.enableTaskViewControllerCleanup()) {
            for (TaskViewTaskController controller : mTaskViews.keySet()) {
                if (controller.getTaskInfo() == null) continue;
                if (taskInfo.token.equals(controller.getTaskInfo().token)) {
                    return controller;
                }
            }
        } else {
            ArrayMap<TaskViewTaskController, TaskViewRepository.TaskViewState> taskViews =
                    (ArrayMap<TaskViewTaskController, TaskViewRepository.TaskViewState>) mTaskViews;
            for (int i = 0; i < taskViews.size(); ++i) {
                if (taskViews.keyAt(i).getTaskInfo() == null) continue;
                if (taskInfo.token.equals(taskViews.keyAt(i).getTaskInfo().token)) {
                    return taskViews.keyAt(i);
                }
            }
        }
        return null;
    }

    /** Returns true if the given {@code taskInfo} belongs to a task view. */
    public boolean isTaskViewTask(ActivityManager.RunningTaskInfo taskInfo) {
        return findTaskView(taskInfo) != null;
    }

    private void prepareActivityOptions(ActivityOptions options, Rect launchBounds,
            @NonNull TaskViewTaskController destination) {
        final Binder launchCookie = new Binder();
        mShellExecutor.execute(() -> {
            mTaskOrganizer.setPendingLaunchCookieListener(launchCookie, destination);
        });
        options.setLaunchBounds(launchBounds);
        options.setLaunchCookie(launchCookie);
        options.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        options.setRemoveWithTaskOrganizer(true);
    }

    @Override
    public void startShortcutActivity(@NonNull TaskViewTaskController destination,
            @NonNull ShortcutInfo shortcut, @NonNull ActivityOptions options,
            @Nullable Rect launchBounds) {
        prepareActivityOptions(options, launchBounds, destination);
        final Context context = destination.getContext();
        if (isUsingShellTransitions()) {
            mShellExecutor.execute(() -> {
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.startShortcut(context.getPackageName(), shortcut, options.toBundle());
                startTaskView(wct, destination, options.getLaunchCookie());
            });
            return;
        }
        try {
            LauncherApps service = context.getSystemService(LauncherApps.class);
            service.startShortcut(shortcut, null /* sourceBounds */, options.toBundle());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void startActivity(@NonNull TaskViewTaskController destination,
            @NonNull PendingIntent pendingIntent, @Nullable Intent fillInIntent,
            @NonNull ActivityOptions options, @Nullable Rect launchBounds) {
        prepareActivityOptions(options, launchBounds, destination);
        if (isUsingShellTransitions()) {
            mShellExecutor.execute(() -> {
                WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.sendPendingIntent(pendingIntent, fillInIntent, options.toBundle());
                startTaskView(wct, destination, options.getLaunchCookie());
            });
            return;
        }
        try {
            pendingIntent.send(destination.getContext(), 0 /* code */, fillInIntent,
                    null /* onFinished */, null /* handler */, null /* requiredPermission */,
                    options.toBundle());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void startRootTask(@NonNull TaskViewTaskController destination,
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash,
            @Nullable WindowContainerTransaction wct) {
        if (wct == null) {
            wct = new WindowContainerTransaction();
        }
        // This method skips the regular flow where an activity task is launched as part of a new
        // transition in taskview and then transition is intercepted using the launchcookie.
        // The task here is already created and running, it just needs to be reparented, resized
        // and tracked correctly inside taskview. Which is done by calling
        // prepareOpenAnimationInternal() and then manually enqueuing the resulting window container
        // transaction.
        prepareOpenAnimation(destination, true /* newTask */, mTransaction /* startTransaction */,
                null /* finishTransaction */, taskInfo, leash, wct);
        mTransaction.apply();
        mTransitions.startTransition(TRANSIT_CHANGE, wct, null);
    }

    @VisibleForTesting
    void startTaskView(@NonNull WindowContainerTransaction wct,
            @NonNull TaskViewTaskController taskView, @NonNull IBinder launchCookie) {
        updateVisibilityState(taskView, true /* visible */);
        mPending.add(new PendingTransition(TRANSIT_OPEN, wct, taskView, launchCookie));
        startNextTransition();
    }

    @Override
    public void removeTaskView(@NonNull TaskViewTaskController taskView,
            @Nullable WindowContainerToken taskToken) {
        final WindowContainerToken token = taskToken != null ? taskToken : taskView.getTaskToken();
        if (token == null) {
            // We don't have a task yet, so just clean up records
            if (!Flags.enableTaskViewControllerCleanup()) {
                // Call to remove task before we have one, do nothing
                Slog.w(TAG, "Trying to remove a task that was never added? (no taskToken)");
                return;
            }
            unregisterTaskView(taskView);
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.removeTask(token);
        updateVisibilityState(taskView, false /* visible */);
        mShellExecutor.execute(() -> {
            mPending.add(new PendingTransition(TRANSIT_CLOSE, wct, taskView, null /* cookie */));
            startNextTransition();
        });
    }

    @Override
    public void moveTaskViewToFullscreen(@NonNull TaskViewTaskController taskView) {
        final WindowContainerToken taskToken = taskView.getTaskToken();
        if (taskToken == null) return;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(taskToken, WINDOWING_MODE_UNDEFINED);
        wct.setAlwaysOnTop(taskToken, false);
        mShellExecutor.execute(() -> {
            mTaskOrganizer.setInterceptBackPressedOnTaskRoot(taskToken, false);
            mPending.add(new PendingTransition(TRANSIT_CHANGE, wct, taskView, null /* cookie */));
            startNextTransition();
            taskView.notifyTaskRemovalStarted(taskView.getTaskInfo());
        });
    }

    @Override
    public void setTaskViewVisible(TaskViewTaskController taskView, boolean visible) {
        setTaskViewVisible(taskView, visible, false /* reorder */);
    }

    /**
     * Starts a new transition to make the given {@code taskView} visible and optionally
     * reordering it.
     *
     * @param reorder  whether to reorder the task or not. If this is {@code true}, the task will
     *                 be reordered as per the given {@code visible}. For {@code visible = true},
     *                 task will be reordered to top. For {@code visible = false}, task will be
     *                 reordered to the bottom
     */
    public void setTaskViewVisible(TaskViewTaskController taskView, boolean visible,
            boolean reorder) {
        final TaskViewRepository.TaskViewState state = useRepo()
                ? mTaskViewRepo.byTaskView(taskView)
                : mTaskViews.get(taskView);
        if (state == null) return;
        if (state.mVisible == visible) return;
        if (taskView.getTaskInfo() == null) {
            // Nothing to update, task is not yet available
            return;
        }
        state.mVisible = visible;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setHidden(taskView.getTaskInfo().token, !visible /* hidden */);
        wct.setBounds(taskView.getTaskInfo().token, state.mBounds);
        if (reorder) {
            wct.reorder(taskView.getTaskInfo().token, visible /* onTop */);
        }
        PendingTransition pending = new PendingTransition(
                visible ? TRANSIT_TO_FRONT : TRANSIT_TO_BACK, wct, taskView, null /* cookie */);
        mPending.add(pending);
        startNextTransition();
        // visibility is reported in transition.
    }

    /** Starts a new transition to reorder the given {@code taskView}'s task. */
    public void reorderTaskViewTask(TaskViewTaskController taskView, boolean onTop) {
        final TaskViewRepository.TaskViewState state = useRepo()
                ? mTaskViewRepo.byTaskView(taskView)
                : mTaskViews.get(taskView);
        if (state == null) return;
        if (taskView.getTaskInfo() == null) {
            // Nothing to update, task is not yet available
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reorder(taskView.getTaskInfo().token, onTop /* onTop */);
        PendingTransition pending = new PendingTransition(
                onTop ? TRANSIT_TO_FRONT : TRANSIT_TO_BACK, wct, taskView, null /* cookie */);
        mPending.add(pending);
        startNextTransition();
        // visibility is reported in transition.
    }

    void updateBoundsState(TaskViewTaskController taskView, Rect boundsOnScreen) {
        if (useRepo()) return;
        final TaskViewRepository.TaskViewState state = mTaskViews.get(taskView);
        if (state == null) return;
        state.mBounds.set(boundsOnScreen);
    }

    void updateVisibilityState(TaskViewTaskController taskView, boolean visible) {
        final TaskViewRepository.TaskViewState state = useRepo()
                ? mTaskViewRepo.byTaskView(taskView)
                : mTaskViews.get(taskView);
        if (state == null) return;
        state.mVisible = visible;
    }

    @Override
    public void setTaskBounds(TaskViewTaskController taskView, Rect boundsOnScreen) {
        if (taskView.getTaskToken() == null) {
            return;
        }

        if (isUsingShellTransitions()) {
            mShellExecutor.execute(() -> {
                // Sync Transactions can't operate simultaneously with shell transition collection.
                setTaskBoundsInTransition(taskView, boundsOnScreen);
            });
            return;
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(taskView.getTaskToken(), boundsOnScreen);
        mSyncQueue.queue(wct);
    }

    private void setTaskBoundsInTransition(TaskViewTaskController taskView, Rect boundsOnScreen) {
        final TaskViewRepository.TaskViewState state = useRepo()
                ? mTaskViewRepo.byTaskView(taskView)
                : mTaskViews.get(taskView);
        if (state == null || Objects.equals(boundsOnScreen, state.mBounds)) {
            return;
        }
        state.mBounds.set(boundsOnScreen);
        if (!state.mVisible) {
            // Task view isn't visible, the bounds will next visibility update.
            return;
        }
        if (hasPending()) {
            // There is already a transition in-flight, the window bounds will be set in
            // prepareOpenAnimation.
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(taskView.getTaskInfo().token, boundsOnScreen);
        mPending.add(new PendingTransition(TRANSIT_CHANGE, wct, taskView, null /* cookie */));
        startNextTransition();
    }

    private void startNextTransition() {
        if (mPending.isEmpty()) return;
        final PendingTransition pending = mPending.get(0);
        if (pending.mClaimed != null) {
            // Wait for this to start animating.
            return;
        }
        if (pending.mExternalTransition != null) {
            pending.mClaimed = pending.mExternalTransition.start();
        } else {
            pending.mClaimed = mTransitions.startTransition(pending.mType, pending.mWct, this);
        }
    }

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        if (!aborted) return;
        final PendingTransition pending = findPending(transition);
        if (pending == null) return;
        mPending.remove(pending);
        startNextTransition();
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final PendingTransition pending = findPending(transition);
        if (pending != null) {
            mPending.remove(pending);
        }
        if (useRepo() ? mTaskViewRepo.isEmpty() : mTaskViews.isEmpty()) {
            if (pending != null) {
                Slog.e(TAG, "Pending taskview transition but no task-views");
            }
            return false;
        }
        boolean stillNeedsMatchingLaunch = pending != null && pending.mLaunchCookie != null;
        int changesHandled = 0;
        WindowContainerTransaction wct = null;
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = info.getChanges().get(i);
            final ActivityManager.RunningTaskInfo taskInfo = chg.getTaskInfo();
            if (taskInfo == null) continue;
            if (TransitionUtil.isClosingType(chg.getMode())) {
                final boolean isHide = chg.getMode() == TRANSIT_TO_BACK;
                TaskViewTaskController tv = findTaskView(taskInfo);
                if (tv == null && !isHide) {
                    // TaskView can be null when closing
                    changesHandled++;
                    continue;
                }
                if (tv == null) {
                    if (pending != null) {
                        Slog.w(TAG, "Found a non-TaskView task in a TaskView Transition. This "
                                + "shouldn't happen, so there may be a visual artifact: "
                                + taskInfo.taskId);
                    }
                    continue;
                }
                if (isHide) {
                    if (pending != null && pending.mType == TRANSIT_TO_BACK) {
                        // TO_BACK is only used when setting the task view visibility immediately,
                        // so in that case we can also hide the surface immediately
                        startTransaction.hide(chg.getLeash());
                    }
                    tv.prepareHideAnimation(finishTransaction);
                } else {
                    tv.prepareCloseAnimation();
                }
                changesHandled++;
            } else if (TransitionUtil.isOpeningType(chg.getMode())) {
                boolean isNewInTaskView = false;
                TaskViewTaskController tv;
                if (chg.getMode() == TRANSIT_OPEN) {
                    isNewInTaskView = true;
                    if (pending == null || !taskInfo.containsLaunchCookie(pending.mLaunchCookie)) {
                        Slog.e(TAG, "Found a launching TaskView in the wrong transition. All "
                                + "TaskView launches should be initiated by shell and in their "
                                + "own transition: " + taskInfo.taskId);
                        continue;
                    }
                    stillNeedsMatchingLaunch = false;
                    tv = pending.mTaskView;
                } else {
                    tv = findTaskView(taskInfo);
                    if (tv == null && pending != null) {
                        if (BubbleAnythingFlagHelper.enableCreateAnyBubble()
                                && chg.getMode() == TRANSIT_TO_FRONT
                                && pending.mTaskView.getPendingInfo() != null
                                && pending.mTaskView.getPendingInfo().taskId == taskInfo.taskId) {
                            // In this case an existing task, not currently in TaskView, is
                            // brought to the front to be moved into TaskView. This is still
                            // "new" from TaskView's perspective. (e.g. task being moved into a
                            // bubble)
                            isNewInTaskView = true;
                            stillNeedsMatchingLaunch = false;
                            tv = pending.mTaskView;
                        } else {
                            Slog.w(TAG, "Found a non-TaskView task in a TaskView Transition. "
                                    + "This shouldn't happen, so there may be a visual "
                                    + "artifact: " + taskInfo.taskId);
                        }
                    }
                    if (tv == null) continue;
                }
                if (wct == null) wct = new WindowContainerTransaction();
                prepareOpenAnimation(tv, isNewInTaskView, startTransaction, finishTransaction,
                        taskInfo, chg.getLeash(), wct);
                changesHandled++;
            } else if (chg.getMode() == TRANSIT_CHANGE) {
                TaskViewTaskController tv = findTaskView(taskInfo);
                if (tv == null) {
                    if (pending != null) {
                        Slog.w(TAG, "Found a non-TaskView task in a TaskView Transition. This "
                                + "shouldn't happen, so there may be a visual artifact: "
                                + taskInfo.taskId);
                    }
                    continue;
                }
                final Rect boundsOnScreen = tv.prepareOpen(chg.getTaskInfo(), chg.getLeash());
                if (boundsOnScreen != null) {
                    if (wct == null) wct = new WindowContainerTransaction();
                    updateBounds(tv, boundsOnScreen, startTransaction, finishTransaction,
                            chg.getTaskInfo(), chg.getLeash(), wct);
                } else {
                    startTransaction.reparent(chg.getLeash(), tv.getSurfaceControl());
                    finishTransaction.reparent(chg.getLeash(), tv.getSurfaceControl())
                            .setPosition(chg.getLeash(), 0, 0);
                }
                changesHandled++;
            }
        }
        if (stillNeedsMatchingLaunch) {
            Slog.w(TAG, "Expected a TaskView launch in this transition but didn't get one, "
                    + "cleaning up the task view");
            // Didn't find a task so the task must have never launched
            pending.mTaskView.setTaskNotFound();
        } else if (wct == null && pending == null && changesHandled != info.getChanges().size()) {
            // Just some house-keeping, let another handler animate.
            return false;
        }
        // No animation, just show it immediately.
        startTransaction.apply();
        finishCallback.onTransitionFinished(wct);
        startNextTransition();
        return true;
    }

    @VisibleForTesting
    public void prepareOpenAnimation(TaskViewTaskController taskView,
            final boolean newTask,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash,
            WindowContainerTransaction wct) {
        final Rect boundsOnScreen = taskView.prepareOpen(taskInfo, leash);
        if (boundsOnScreen != null) {
            updateBounds(taskView, boundsOnScreen, startTransaction, finishTransaction, taskInfo,
                    leash, wct);
        } else {
            // The surface has already been destroyed before the task has appeared,
            // so go ahead and hide the task entirely
            wct.setHidden(taskInfo.token, true /* hidden */);
            updateVisibilityState(taskView, false /* visible */);
            // listener callback is below
        }
        if (newTask) {
            mTaskOrganizer.setInterceptBackPressedOnTaskRoot(taskInfo.token, true /* intercept */);
        }

        if (taskInfo.taskDescription != null) {
            int backgroundColor = taskInfo.taskDescription.getBackgroundColor();
            taskView.setResizeBgColor(startTransaction, backgroundColor);
        }

        // After the embedded task has appeared, set it to non-trimmable. This is important
        // to prevent recents from trimming and removing the embedded task.
        wct.setTaskTrimmableFromRecents(taskInfo.token, false /* isTrimmableFromRecents */);

        taskView.notifyAppeared(newTask);
    }

    private void updateBounds(TaskViewTaskController taskView, Rect boundsOnScreen,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash,
            WindowContainerTransaction wct) {
        final SurfaceControl tvSurface = taskView.getSurfaceControl();
        // Surface is ready, so just reparent the task to this surface control
        startTransaction.reparent(leash, tvSurface)
                .show(leash);
        // Also reparent on finishTransaction since the finishTransaction will reparent back
        // to its "original" parent by default.
        if (finishTransaction != null) {
            finishTransaction.reparent(leash, tvSurface)
                    .setPosition(leash, 0, 0)
                    .setWindowCrop(leash, boundsOnScreen.width(), boundsOnScreen.height());
        }
        if (useRepo()) {
            final TaskViewRepository.TaskViewState state = mTaskViewRepo.byTaskView(taskView);
            if (state != null) {
                state.mBounds.set(boundsOnScreen);
                state.mVisible = true;
            }
        } else {
            updateBoundsState(taskView, boundsOnScreen);
            updateVisibilityState(taskView, true /* visible */);
        }
        wct.setBounds(taskInfo.token, boundsOnScreen);
        taskView.applyCaptionInsetsIfNeeded();
    }

    /** Interface for running an external transition in this object's pending queue. */
    public interface ExternalTransition {
        /** Starts a transition and returns an identifying key for lookup. */
        IBinder start();
    }
}
