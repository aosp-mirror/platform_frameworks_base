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

package com.android.wm.shell.taskview;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.gui.TrustedOverlay;
import android.os.Binder;
import android.util.CloseGuard;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * This class represents the visible aspect of a task in a {@link TaskView}. All the {@link
 * TaskView} to {@link TaskViewTaskController} interactions are done via direct method calls.
 *
 * The reverse communication is done via the {@link TaskViewBase} interface.
 */
public class TaskViewTaskController implements ShellTaskOrganizer.TaskListener {

    private static final String TAG = TaskViewTaskController.class.getSimpleName();

    private final CloseGuard mGuard = new CloseGuard();
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
    /** Used to inset the activity content to allow space for a caption bar. */
    private final Binder mCaptionInsetsOwner = new Binder();
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Executor mShellExecutor;
    private final SyncTransactionQueue mSyncQueue;
    private final TaskViewController mTaskViewController;
    private final Context mContext;

    /**
     * There could be a situation where we have task info and receive
     * {@link #onTaskAppeared(ActivityManager.RunningTaskInfo, SurfaceControl)}, however, the
     * activity might fail to open, and in this case we need to clean up the task view / notify
     * listeners of a task removal. This requires task info, so we save the info from onTaskAppeared
     * in this situation to allow us to notify listeners correctly if the task failed to open.
     */
    private ActivityManager.RunningTaskInfo mPendingInfo;
    private TaskViewBase mTaskViewBase;
    protected ActivityManager.RunningTaskInfo mTaskInfo;
    private WindowContainerToken mTaskToken;
    private SurfaceControl mTaskLeash;
    /* Indicates that the task we attempted to launch in the task view failed to launch. */
    private boolean mTaskNotFound;
    private boolean mSurfaceCreated;
    private SurfaceControl mSurfaceControl;
    private boolean mIsInitialized;
    private boolean mNotifiedForInitialized;
    private boolean mHideTaskWithSurface = true;
    private TaskView.Listener mListener;
    private Executor mListenerExecutor;
    private Rect mCaptionInsets;

    public TaskViewTaskController(Context context, ShellTaskOrganizer organizer,
            TaskViewController taskViewController, SyncTransactionQueue syncQueue) {
        mContext = context;
        mTaskOrganizer = organizer;
        mShellExecutor = organizer.getExecutor();
        mSyncQueue = syncQueue;
        mTaskViewController = taskViewController;
        mShellExecutor.execute(() -> {
            if (mTaskViewController != null) {
                mTaskViewController.registerTaskView(this);
            }
        });
        mGuard.open("release");
    }

    /**
     * Specifies if the task should be hidden when the surface is destroyed.
     * <p>This is {@code true} by default.
     *
     * @param hideTaskWithSurface {@code false} if task needs to remain visible even when the
     *                            surface is destroyed, {@code true} otherwise.
     */
    public void setHideTaskWithSurface(boolean hideTaskWithSurface) {
        // TODO(b/299535374): Remove mHideTaskWithSurface once the taskviews with launch root tasks
        // are moved to a window in SystemUI in auto.
        mHideTaskWithSurface = hideTaskWithSurface;
    }

    SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    Context getContext() {
        return mContext;
    }

    /**
     * Sets the provided {@link TaskViewBase}, which is used to notify the client part about the
     * task related changes and getting the current bounds.
     */
    public void setTaskViewBase(TaskViewBase taskViewBase) {
        mTaskViewBase = taskViewBase;
    }

    /**
     * @return {@code True} when the TaskView's surface has been created, {@code False} otherwise.
     */
    public boolean isInitialized() {
        return mIsInitialized;
    }

    WindowContainerToken getTaskToken() {
        return mTaskToken;
    }

    void setResizeBgColor(SurfaceControl.Transaction t, int bgColor) {
        mTaskViewBase.setResizeBgColor(t, bgColor);
    }

    /**
     * Only one listener may be set on the view, throws an exception otherwise.
     */
    void setListener(@NonNull Executor executor, TaskView.Listener listener) {
        if (mListener != null) {
            throw new IllegalStateException(
                    "Trying to set a listener when one has already been set");
        }
        mListener = listener;
        mListenerExecutor = executor;
    }

    /**
     * Release this container if it is initialized.
     */
    public void release() {
        performRelease();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mGuard != null) {
                mGuard.warnIfOpen();
                performRelease();
            }
        } finally {
            super.finalize();
        }
    }

    private void performRelease() {
        mShellExecutor.execute(() -> {
            if (mTaskViewController != null) {
                mTaskViewController.unregisterTaskView(this);
            }
            mTaskOrganizer.removeListener(this);
            resetTaskInfo();
        });
        mGuard.close();
        mIsInitialized = false;
        notifyReleased();
    }

    /** Called when the {@link TaskViewTaskController} has been released. */
    protected void notifyReleased() {
        if (mListener != null && mNotifiedForInitialized) {
            mListenerExecutor.execute(() -> {
                mListener.onReleased();
            });
            mNotifiedForInitialized = false;
        }
    }

    private void resetTaskInfo() {
        mTaskInfo = null;
        mTaskToken = null;
        mTaskLeash = null;
        mPendingInfo = null;
        mTaskNotFound = false;
    }

    /** This method shouldn't be called when shell transitions are enabled. */
    private void updateTaskVisibility() {
        boolean visible = mSurfaceCreated;
        if (!visible && !mHideTaskWithSurface) {
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setHidden(mTaskToken, !visible /* hidden */);
        if (!visible) {
            wct.reorder(mTaskToken, false /* onTop */);
        }
        mSyncQueue.queue(wct);
        if (mListener == null) {
            return;
        }
        int taskId = mTaskInfo.taskId;
        mSyncQueue.runInSync((t) -> {
            mListenerExecutor.execute(() -> {
                mListener.onTaskVisibilityChanged(taskId, mSurfaceCreated);
            });
        });
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl leash) {
        if (mTaskViewController.isUsingShellTransitions()) {
            mPendingInfo = taskInfo;
            if (mTaskNotFound) {
                // If we were already notified by shell transit that we don't have the
                // the task, clean it up now.
                cleanUpPendingTask();
            }
            // Everything else handled by enter transition.
            return;
        }
        mTaskInfo = taskInfo;
        mTaskToken = taskInfo.token;
        mTaskLeash = leash;

        if (mSurfaceCreated) {
            // Surface is ready, so just reparent the task to this surface control
            mTransaction.reparent(mTaskLeash, mSurfaceControl)
                    .show(mTaskLeash)
                    .apply();
        } else {
            // The surface has already been destroyed before the task has appeared,
            // so go ahead and hide the task entirely
            updateTaskVisibility();
        }
        mTaskOrganizer.setInterceptBackPressedOnTaskRoot(mTaskToken, true);
        mSyncQueue.runInSync((t) -> {
            mTaskViewBase.onTaskAppeared(taskInfo, leash);
        });

        if (mListener != null) {
            final int taskId = taskInfo.taskId;
            final ComponentName baseActivity = taskInfo.baseActivity;
            mListenerExecutor.execute(() -> {
                mListener.onTaskCreated(taskId, baseActivity);
            });
        }
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        // Unlike Appeared, we can't yet guarantee that vanish will happen within a transition that
        // we know about -- so leave clean-up here even if shell transitions are enabled.
        if (mTaskToken == null || !mTaskToken.equals(taskInfo.token)) return;

        final SurfaceControl taskLeash = mTaskLeash;
        handleAndNotifyTaskRemoval(mTaskInfo);

        mTransaction.reparent(taskLeash, null).apply();
        resetTaskInfo();
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        mTaskViewBase.onTaskInfoChanged(taskInfo);
    }

    @Override
    public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
        if (mTaskToken == null || !mTaskToken.equals(taskInfo.token)) return;
        if (mListener != null) {
            final int taskId = taskInfo.taskId;
            mListenerExecutor.execute(() -> {
                mListener.onBackPressedOnTaskRoot(taskId);
            });
        }
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
        if (mTaskInfo == null || mTaskLeash == null || mTaskInfo.taskId != taskId) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        return mTaskLeash;
    }

    @Override
    public void dump(@androidx.annotation.NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
    }

    @Override
    public String toString() {
        return "TaskViewTaskController" + ":" + (mTaskInfo != null ? mTaskInfo.taskId : "null");
    }

    /**
     * Should be called when the client surface is created.
     *
     * @param surfaceControl the {@link SurfaceControl} for the underlying surface.
     */
    public void surfaceCreated(SurfaceControl surfaceControl) {
        mSurfaceCreated = true;
        mIsInitialized = true;
        mSurfaceControl = surfaceControl;
        // SurfaceControl is expected to be null only in the case of unit tests. Guard against it
        // to avoid runtime exception in SurfaceControl.Transaction.
        if (surfaceControl != null) {
            // TaskView is meant to contain app activities which shouldn't have trusted overlays
            // flag set even when itself reparented in a window which is trusted.
            mTransaction.setTrustedOverlay(surfaceControl, TrustedOverlay.DISABLED)
                    .apply();
        }
        notifyInitialized();
        mShellExecutor.execute(() -> {
            if (mTaskToken == null) {
                // Nothing to update, task is not yet available
                return;
            }
            if (mTaskViewController.isUsingShellTransitions()) {
                mTaskViewController.setTaskViewVisible(this, true /* visible */);
                return;
            }
            // Reparent the task when this surface is created
            mTransaction.reparent(mTaskLeash, mSurfaceControl)
                    .show(mTaskLeash)
                    .apply();
            updateTaskVisibility();
        });
    }

    /**
     * Sets a region of the task to inset to allow for a caption bar.
     *
     * @param captionInsets the rect for the insets in screen coordinates.
     */
    void setCaptionInsets(Rect captionInsets) {
        if (mCaptionInsets != null && mCaptionInsets.equals(captionInsets)) {
            return;
        }
        mCaptionInsets = captionInsets;
        applyCaptionInsetsIfNeeded();
    }

    void applyCaptionInsetsIfNeeded() {
        if (mTaskToken == null) return;
        WindowContainerTransaction wct = new WindowContainerTransaction();
        if (mCaptionInsets != null) {
            wct.addInsetsSource(mTaskToken, mCaptionInsetsOwner, 0,
                    WindowInsets.Type.captionBar(), mCaptionInsets, null /* boundingRects */,
                    0 /* flags */);
        } else {
            wct.removeInsetsSource(mTaskToken, mCaptionInsetsOwner, 0,
                    WindowInsets.Type.captionBar());
        }
        mTaskOrganizer.applyTransaction(wct);
    }

    /** Should be called when the client surface is destroyed. */
    public void surfaceDestroyed() {
        mSurfaceCreated = false;
        mSurfaceControl = null;
        mShellExecutor.execute(() -> {
            if (mTaskToken == null) {
                // Nothing to update, task is not yet available
                return;
            }

            if (mTaskViewController.isUsingShellTransitions()) {
                mTaskViewController.setTaskViewVisible(this, false /* visible */);
                return;
            }

            // Unparent the task when this surface is destroyed
            mTransaction.reparent(mTaskLeash, null).apply();
            updateTaskVisibility();
        });
    }

    /** Called when the {@link TaskViewTaskController} is initialized. */
    protected void notifyInitialized() {
        if (mListener != null && !mNotifiedForInitialized) {
            mNotifiedForInitialized = true;
            mListenerExecutor.execute(() -> {
                mListener.onInitialized();
            });
        }
    }

    /** Notifies listeners of a task being removed. */
    public void notifyTaskRemovalStarted(@NonNull ActivityManager.RunningTaskInfo taskInfo) {
        if (mListener == null) return;
        final int taskId = taskInfo.taskId;
        mListenerExecutor.execute(() -> mListener.onTaskRemovalStarted(taskId));
    }

    /** Notifies listeners of a task being removed and stops intercepting back presses on it. */
    private void handleAndNotifyTaskRemoval(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo != null) {
            notifyTaskRemovalStarted(taskInfo);
            mTaskViewBase.onTaskVanished(taskInfo);
        }
    }

    /** Returns the task info for the task in the TaskView. */
    @Nullable
    public ActivityManager.RunningTaskInfo getTaskInfo() {
        return mTaskInfo;
    }

    @VisibleForTesting
    ActivityManager.RunningTaskInfo getPendingInfo() {
        return mPendingInfo;
    }

    /**
     * Indicates that the task was not found in the start animation for the transition.
     * In this case we should clean up the task if we have the pending info. If we don't
     * have the pending info, we'll do it when we receive it in
     * {@link #onTaskAppeared(ActivityManager.RunningTaskInfo, SurfaceControl)}.
     */
    public void setTaskNotFound() {
        mTaskNotFound = true;
        if (mPendingInfo != null) {
            cleanUpPendingTask();
        }
    }

    /**
     * Called when a task failed to open and we need to clean up task view /
     * notify users of task view.
     */
    void cleanUpPendingTask() {
        if (mPendingInfo != null) {
            final ActivityManager.RunningTaskInfo pendingInfo = mPendingInfo;
            handleAndNotifyTaskRemoval(pendingInfo);

            // Make sure the task is removed
            mTaskViewController.removeTaskView(this, pendingInfo.token);
        }
        resetTaskInfo();
    }

    void prepareHideAnimation(@NonNull SurfaceControl.Transaction finishTransaction) {
        if (mTaskToken == null) {
            // Nothing to update, task is not yet available
            return;
        }

        finishTransaction.reparent(mTaskLeash, null);

        if (mListener != null) {
            final int taskId = mTaskInfo.taskId;
            mListener.onTaskVisibilityChanged(taskId, mSurfaceCreated /* visible */);
        }
    }

    /**
     * Called when the associated Task closes. If the TaskView is just being hidden, prepareHide
     * is used instead.
     */
    void prepareCloseAnimation() {
        handleAndNotifyTaskRemoval(mTaskInfo);
        resetTaskInfo();
    }

    /**
     * Prepare this taskview to open {@param taskInfo}.
     * @return The bounds of the task or {@code null} on failure (surface is destroyed)
     */
    Rect prepareOpen(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        mPendingInfo = null;
        mTaskInfo = taskInfo;
        mTaskToken = mTaskInfo.token;
        mTaskLeash = leash;
        if (!mSurfaceCreated) {
            return null;
        }
        return mTaskViewBase.getCurrentBoundsOnScreen();
    }

    /** Notify that the associated task has appeared. This will call appropriate listeners. */
    void notifyAppeared(final boolean newTask) {
        mTaskViewBase.onTaskAppeared(mTaskInfo, mTaskLeash);
        if (mListener != null) {
            final int taskId = mTaskInfo.taskId;
            final ComponentName baseActivity = mTaskInfo.baseActivity;

            mListenerExecutor.execute(() -> {
                if (newTask) {
                    mListener.onTaskCreated(taskId, baseActivity);
                }
                // Even if newTask, send a visibilityChange if the surface was destroyed.
                if (!newTask || !mSurfaceCreated) {
                    mListener.onTaskVisibilityChanged(taskId, mSurfaceCreated /* visible */);
                }
            });
        }
    }
}
