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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.util.CloseGuard;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * This class implements the core logic to show a task on the {@link TaskView}. All the {@link
 * TaskView} to {@link TaskViewTaskController} interactions are done via direct method calls.
 *
 * The reverse communication is done via the {@link TaskViewBase} interface.
 */
public class TaskViewTaskController implements ShellTaskOrganizer.TaskListener {

    private final CloseGuard mGuard = new CloseGuard();

    private final ShellTaskOrganizer mTaskOrganizer;
    private final Executor mShellExecutor;
    private final SyncTransactionQueue mSyncQueue;
    private final TaskViewTransitions mTaskViewTransitions;
    private TaskViewBase mTaskViewBase;
    private final Context mContext;

    protected ActivityManager.RunningTaskInfo mTaskInfo;
    private WindowContainerToken mTaskToken;
    private SurfaceControl mTaskLeash;
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
    private boolean mSurfaceCreated;
    private SurfaceControl mSurfaceControl;
    private boolean mIsInitialized;
    private boolean mNotifiedForInitialized;
    private TaskView.Listener mListener;
    private Executor mListenerExecutor;

    public TaskViewTaskController(Context context, ShellTaskOrganizer organizer,
            TaskViewTransitions taskViewTransitions, SyncTransactionQueue syncQueue) {
        mContext = context;
        mTaskOrganizer = organizer;
        mShellExecutor = organizer.getExecutor();
        mSyncQueue = syncQueue;
        mTaskViewTransitions = taskViewTransitions;
        if (mTaskViewTransitions != null) {
            mTaskViewTransitions.addTaskView(this);
        }
        mGuard.open("release");
    }

    SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
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

    /** Until all users are converted, we may have mixed-use (eg. Car). */
    public boolean isUsingShellTransitions() {
        return mTaskViewTransitions != null && mTaskViewTransitions.isEnabled();
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
     * Launch an activity represented by {@link ShortcutInfo}.
     * <p>The owner of this container must be allowed to access the shortcut information,
     * as defined in {@link LauncherApps#hasShortcutHostPermission()} to use this method.
     *
     * @param shortcut the shortcut used to launch the activity.
     * @param options options for the activity.
     * @param launchBounds the bounds (window size and position) that the activity should be
     *                     launched in, in pixels and in screen coordinates.
     */
    public void startShortcutActivity(@NonNull ShortcutInfo shortcut,
            @NonNull ActivityOptions options, @Nullable Rect launchBounds) {
        prepareActivityOptions(options, launchBounds);
        LauncherApps service = mContext.getSystemService(LauncherApps.class);
        if (isUsingShellTransitions()) {
            mShellExecutor.execute(() -> {
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.startShortcut(mContext.getPackageName(), shortcut, options.toBundle());
                mTaskViewTransitions.startTaskView(wct, this, options.getLaunchCookie());
            });
            return;
        }
        try {
            service.startShortcut(shortcut, null /* sourceBounds */, options.toBundle());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Launch a new activity.
     *
     * @param pendingIntent Intent used to launch an activity.
     * @param fillInIntent Additional Intent data, see {@link Intent#fillIn Intent.fillIn()}
     * @param options options for the activity.
     * @param launchBounds the bounds (window size and position) that the activity should be
     *                     launched in, in pixels and in screen coordinates.
     */
    public void startActivity(@NonNull PendingIntent pendingIntent, @Nullable Intent fillInIntent,
            @NonNull ActivityOptions options, @Nullable Rect launchBounds) {
        prepareActivityOptions(options, launchBounds);
        if (isUsingShellTransitions()) {
            mShellExecutor.execute(() -> {
                WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.sendPendingIntent(pendingIntent, fillInIntent, options.toBundle());
                mTaskViewTransitions.startTaskView(wct, this, options.getLaunchCookie());
            });
            return;
        }
        try {
            pendingIntent.send(mContext, 0 /* code */, fillInIntent,
                    null /* onFinished */, null /* handler */, null /* requiredPermission */,
                    options.toBundle());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void prepareActivityOptions(ActivityOptions options, Rect launchBounds) {
        final Binder launchCookie = new Binder();
        mShellExecutor.execute(() -> {
            mTaskOrganizer.setPendingLaunchCookieListener(launchCookie, this);
        });
        options.setLaunchBounds(launchBounds);
        options.setLaunchCookie(launchCookie);
        options.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        options.setRemoveWithTaskOrganizer(true);
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
        if (mTaskViewTransitions != null) {
            mTaskViewTransitions.removeTaskView(this);
        }
        mShellExecutor.execute(() -> {
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
    }

    private void updateTaskVisibility() {
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setHidden(mTaskToken, !mSurfaceCreated /* hidden */);
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
        if (isUsingShellTransitions()) {
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

        if (mListener != null) {
            final int taskId = taskInfo.taskId;
            mListenerExecutor.execute(() -> {
                mListener.onTaskRemovalStarted(taskId);
            });
        }
        mTaskOrganizer.setInterceptBackPressedOnTaskRoot(mTaskToken, false);

        // Unparent the task when this surface is destroyed
        mTransaction.reparent(mTaskLeash, null).apply();
        resetTaskInfo();
        mTaskViewBase.onTaskVanished(taskInfo);
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
        notifyInitialized();
        mShellExecutor.execute(() -> {
            if (mTaskToken == null) {
                // Nothing to update, task is not yet available
                return;
            }
            if (isUsingShellTransitions()) {
                mTaskViewTransitions.setTaskViewVisible(this, true /* visible */);
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
     * Sets the window bounds to {@code boundsOnScreen}.
     * Call when view position or size has changed. Can also be called before the animation when
     * the final bounds are known.
     * Do not call during the animation.
     *
     * @param boundsOnScreen the on screen bounds of the surface view.
     */
    public void setWindowBounds(Rect boundsOnScreen) {
        if (mTaskToken == null) {
            return;
        }
        // Sync Transactions can't operate simultaneously with shell transition collection.
        if (isUsingShellTransitions()) {
            mTaskViewTransitions.setTaskBounds(this, boundsOnScreen);
            return;
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(mTaskToken, boundsOnScreen);
        mSyncQueue.queue(wct);
    }

    /**
     * Call to remove the task from window manager. This task will not appear in recents.
     */
    void removeTask() {
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.removeTask(mTaskToken);
        mTaskViewTransitions.closeTaskView(wct, this);
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

            if (isUsingShellTransitions()) {
                mTaskViewTransitions.setTaskViewVisible(this, false /* visible */);
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

    /** Returns the task info for the task in the TaskView. */
    @Nullable
    public ActivityManager.RunningTaskInfo getTaskInfo() {
        return mTaskInfo;
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
        if (mTaskToken != null) {
            if (mListener != null) {
                final int taskId = mTaskInfo.taskId;
                mListenerExecutor.execute(() -> {
                    mListener.onTaskRemovalStarted(taskId);
                });
            }
            mTaskViewBase.onTaskVanished(mTaskInfo);
            mTaskOrganizer.setInterceptBackPressedOnTaskRoot(mTaskToken, false);
        }
        resetTaskInfo();
    }

    void prepareOpenAnimation(final boolean newTask,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash,
            WindowContainerTransaction wct) {
        mTaskInfo = taskInfo;
        mTaskToken = mTaskInfo.token;
        mTaskLeash = leash;
        if (mSurfaceCreated) {
            // Surface is ready, so just reparent the task to this surface control
            startTransaction.reparent(mTaskLeash, mSurfaceControl)
                    .show(mTaskLeash);
            // Also reparent on finishTransaction since the finishTransaction will reparent back
            // to its "original" parent by default.
            Rect boundsOnScreen = mTaskViewBase.getCurrentBoundsOnScreen();
            finishTransaction.reparent(mTaskLeash, mSurfaceControl)
                    .setPosition(mTaskLeash, 0, 0)
                    // TODO: maybe once b/280900002 is fixed this will be unnecessary
                    .setWindowCrop(mTaskLeash, boundsOnScreen.width(), boundsOnScreen.height());
            mTaskViewTransitions.updateBoundsState(this, boundsOnScreen);
            mTaskViewTransitions.updateVisibilityState(this, true /* visible */);
            wct.setBounds(mTaskToken, boundsOnScreen);
        } else {
            // The surface has already been destroyed before the task has appeared,
            // so go ahead and hide the task entirely
            wct.setHidden(mTaskToken, true /* hidden */);
            mTaskViewTransitions.updateVisibilityState(this, false /* visible */);
            // listener callback is below
        }
        if (newTask) {
            mTaskOrganizer.setInterceptBackPressedOnTaskRoot(mTaskToken, true /* intercept */);
        }

        if (mTaskInfo.taskDescription != null) {
            int backgroundColor = mTaskInfo.taskDescription.getBackgroundColor();
            mTaskViewBase.setResizeBgColor(startTransaction, backgroundColor);
        }

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
