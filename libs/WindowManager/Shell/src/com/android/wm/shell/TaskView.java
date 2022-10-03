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
import android.graphics.Region;
import android.os.Binder;
import android.util.CloseGuard;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * View that can display a task.
 */
public class TaskView extends SurfaceView implements SurfaceHolder.Callback,
        ShellTaskOrganizer.TaskListener, ViewTreeObserver.OnComputeInternalInsetsListener {

    /** Callback for listening task state. */
    public interface Listener {
        /**
         * Only called once when the surface has been created & the container is ready for
         * launching activities.
         */
        default void onInitialized() {}

        /** Called when the container can no longer launch activities. */
        default void onReleased() {}

        /** Called when a task is created inside the container. */
        default void onTaskCreated(int taskId, ComponentName name) {}

        /** Called when a task visibility changes. */
        default void onTaskVisibilityChanged(int taskId, boolean visible) {}

        /** Called when a task is about to be removed from the stack inside the container. */
        default void onTaskRemovalStarted(int taskId) {}

        /** Called when a task is created inside the container. */
        default void onBackPressedOnTaskRoot(int taskId) {}
    }

    private final CloseGuard mGuard = new CloseGuard();

    private final ShellTaskOrganizer mTaskOrganizer;
    private final Executor mShellExecutor;
    private final SyncTransactionQueue mSyncQueue;
    private final TaskViewTransitions mTaskViewTransitions;

    protected ActivityManager.RunningTaskInfo mTaskInfo;
    private WindowContainerToken mTaskToken;
    private SurfaceControl mTaskLeash;
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
    private boolean mSurfaceCreated;
    private boolean mIsInitialized;
    private boolean mNotifiedForInitialized;
    private Listener mListener;
    private Executor mListenerExecutor;
    private Region mObscuredTouchRegion;

    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRootRect = new Rect();
    private final int[] mTmpLocation = new int[2];

    public TaskView(Context context, ShellTaskOrganizer organizer,
            TaskViewTransitions taskViewTransitions, SyncTransactionQueue syncQueue) {
        super(context, null, 0, 0, true /* disableBackgroundLayer */);

        mTaskOrganizer = organizer;
        mShellExecutor = organizer.getExecutor();
        mSyncQueue = syncQueue;
        mTaskViewTransitions = taskViewTransitions;
        if (mTaskViewTransitions != null) {
            mTaskViewTransitions.addTaskView(this);
        }
        setUseAlpha();
        getHolder().addCallback(this);
        mGuard.open("release");
    }

    /**
     * @return {@code True} when the TaskView's surface has been created, {@code False} otherwise.
     */
    public boolean isInitialized() {
        return mIsInitialized;
    }

    /** Until all users are converted, we may have mixed-use (eg. Car). */
    private boolean isUsingShellTransitions() {
        return mTaskViewTransitions != null && Transitions.ENABLE_SHELL_TRANSITIONS;
    }

    /**
     * Only one listener may be set on the view, throws an exception otherwise.
     */
    public void setListener(@NonNull Executor executor, Listener listener) {
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
                mTaskViewTransitions.startTaskView(wct, this);
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
                mTaskViewTransitions.startTaskView(wct, this);
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
     * Indicates a region of the view that is not touchable.
     *
     * @param obscuredRect the obscured region of the view.
     */
    public void setObscuredTouchRect(Rect obscuredRect) {
        mObscuredTouchRegion = obscuredRect != null ? new Region(obscuredRect) : null;
    }

    /**
     * Indicates a region of the view that is not touchable.
     *
     * @param obscuredRegion the obscured region of the view.
     */
    public void setObscuredTouchRegion(Region obscuredRegion) {
        mObscuredTouchRegion = obscuredRegion;
    }

    private void onLocationChanged(WindowContainerTransaction wct) {
        // Update based on the screen bounds
        getBoundsOnScreen(mTmpRect);
        getRootView().getBoundsOnScreen(mTmpRootRect);
        if (!mTmpRootRect.contains(mTmpRect)) {
            mTmpRect.offsetTo(0, 0);
        }
        wct.setBounds(mTaskToken, mTmpRect);
    }

    /**
     * Call when view position or size has changed. Do not call when animating.
     */
    public void onLocationChanged() {
        if (mTaskToken == null) {
            return;
        }
        // Sync Transactions can't operate simultaneously with shell transition collection.
        // The transition animation (upon showing) will sync the location itself.
        if (isUsingShellTransitions() && mTaskViewTransitions.hasPending()) return;

        WindowContainerTransaction wct = new WindowContainerTransaction();
        onLocationChanged(wct);
        mSyncQueue.queue(wct);
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
        getHolder().removeCallback(this);
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

    /** Called when the {@link TaskView} has been released. */
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
            mTransaction.reparent(mTaskLeash, getSurfaceControl())
                    .show(mTaskLeash)
                    .apply();
        } else {
            // The surface has already been destroyed before the task has appeared,
            // so go ahead and hide the task entirely
            updateTaskVisibility();
        }
        mTaskOrganizer.setInterceptBackPressedOnTaskRoot(mTaskToken, true);
        onLocationChanged();
        if (taskInfo.taskDescription != null) {
            int backgroundColor = taskInfo.taskDescription.getBackgroundColor();
            mSyncQueue.runInSync((t) -> {
                setResizeBackgroundColor(t, backgroundColor);
            });
        }

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
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo.taskDescription != null) {
            setResizeBackgroundColor(taskInfo.taskDescription.getBackgroundColor());
        }
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
        return "TaskView" + ":" + (mTaskInfo != null ? mTaskInfo.taskId : "null");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceCreated = true;
        mIsInitialized = true;
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
            mTransaction.reparent(mTaskLeash, getSurfaceControl())
                    .show(mTaskLeash)
                    .apply();
            updateTaskVisibility();
        });
    }

    /** Called when the {@link TaskView} is initialized. */
    protected void notifyInitialized() {
        if (mListener != null && !mNotifiedForInitialized) {
            mNotifiedForInitialized = true;
            mListenerExecutor.execute(() -> {
                mListener.onInitialized();
            });
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mTaskToken == null) {
            return;
        }
        onLocationChanged();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurfaceCreated = false;
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

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        // TODO(b/176854108): Consider to move the logic into gatherTransparentRegions since this
        //   is dependent on the order of listener.
        // If there are multiple TaskViews, we'll set the touchable area as the root-view, then
        // subtract each TaskView from it.
        if (inoutInfo.touchableRegion.isEmpty()) {
            inoutInfo.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            View root = getRootView();
            root.getLocationInWindow(mTmpLocation);
            mTmpRootRect.set(mTmpLocation[0], mTmpLocation[1], root.getWidth(), root.getHeight());
            inoutInfo.touchableRegion.set(mTmpRootRect);
        }
        getLocationInWindow(mTmpLocation);
        mTmpRect.set(mTmpLocation[0], mTmpLocation[1],
                mTmpLocation[0] + getWidth(), mTmpLocation[1] + getHeight());
        inoutInfo.touchableRegion.op(mTmpRect, Region.Op.DIFFERENCE);

        if (mObscuredTouchRegion != null) {
            inoutInfo.touchableRegion.op(mObscuredTouchRegion, Region.Op.UNION);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
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

        finishTransaction.reparent(mTaskLeash, null).apply();

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
            startTransaction.reparent(mTaskLeash, getSurfaceControl())
                    .show(mTaskLeash)
                    .apply();
            // Also reparent on finishTransaction since the finishTransaction will reparent back
            // to its "original" parent by default.
            finishTransaction.reparent(mTaskLeash, getSurfaceControl())
                    .setPosition(mTaskLeash, 0, 0)
                    .apply();

            // TODO: determine if this is really necessary or not
            onLocationChanged(wct);
        } else {
            // The surface has already been destroyed before the task has appeared,
            // so go ahead and hide the task entirely
            wct.setHidden(mTaskToken, true /* hidden */);
            // listener callback is below
        }
        if (newTask) {
            mTaskOrganizer.setInterceptBackPressedOnTaskRoot(mTaskToken, true /* intercept */);
        }

        if (mTaskInfo.taskDescription != null) {
            int backgroundColor = mTaskInfo.taskDescription.getBackgroundColor();
            setResizeBackgroundColor(startTransaction, backgroundColor);
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
