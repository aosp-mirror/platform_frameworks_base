/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.floating;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_FLOATING_APPS;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.SystemProperties;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.BinderThread;
import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskViewTransitions;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.floating.views.FloatingTaskLayer;
import com.android.wm.shell.floating.views.FloatingTaskView;
import com.android.wm.shell.sysui.ConfigurationChangeListener;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.Optional;

/**
 * Entry point for creating and managing floating tasks.
 *
 * A single window layer is added and the task(s) are displayed using a {@link FloatingTaskView}
 * within that window.
 *
 * Currently optimized for a single task. Multiple tasks are not supported.
 */
public class FloatingTasksController implements RemoteCallable<FloatingTasksController>,
        ConfigurationChangeListener {

    private static final String TAG = FloatingTasksController.class.getSimpleName();

    public static final boolean FLOATING_TASKS_ENABLED =
            SystemProperties.getBoolean("persist.wm.debug.floating_tasks", false);
    public static final boolean SHOW_FLOATING_TASKS_AS_BUBBLES =
            SystemProperties.getBoolean("persist.wm.debug.floating_tasks_as_bubbles", false);

    @VisibleForTesting
    static final int SMALLEST_SCREEN_WIDTH_DP_TO_BE_TABLET = 600;

    // Only used for testing
    private Configuration mConfig;
    private boolean mFloatingTasksEnabledForTests;

    private FloatingTaskImpl mImpl = new FloatingTaskImpl();
    private Context mContext;
    private ShellController mShellController;
    private ShellCommandHandler mShellCommandHandler;
    private @Nullable BubbleController mBubbleController;
    private WindowManager mWindowManager;
    private ShellTaskOrganizer mTaskOrganizer;
    private TaskViewTransitions mTaskViewTransitions;
    private @ShellMainThread ShellExecutor mMainExecutor;
    // TODO: mBackgroundThread is not used but we'll probs need it eventually?
    private @ShellBackgroundThread ShellExecutor mBackgroundThread;
    private SyncTransactionQueue mSyncQueue;

    private boolean mIsFloatingLayerAdded;
    private FloatingTaskLayer mFloatingTaskLayer;
    private final Point mLastPosition = new Point(-1, -1);

    private Task mTask;

    // Simple class to hold onto info for intent or shortcut based tasks.
    public static class Task {
        public int taskId = INVALID_TASK_ID;
        @Nullable
        public Intent intent;
        @Nullable
        public ShortcutInfo info;
        @Nullable
        public FloatingTaskView floatingView;
    }

    public FloatingTasksController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            ShellCommandHandler shellCommandHandler,
            Optional<BubbleController> bubbleController,
            WindowManager windowManager,
            ShellTaskOrganizer organizer,
            TaskViewTransitions transitions,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellBackgroundThread ShellExecutor bgExceutor,
            SyncTransactionQueue syncTransactionQueue) {
        mContext = context;
        mShellController = shellController;
        mShellCommandHandler = shellCommandHandler;
        mBubbleController = bubbleController.get();
        mWindowManager = windowManager;
        mTaskOrganizer = organizer;
        mTaskViewTransitions = transitions;
        mMainExecutor = mainExecutor;
        mBackgroundThread = bgExceutor;
        mSyncQueue = syncTransactionQueue;
        if (isFloatingTasksEnabled()) {
            shellInit.addInitCallback(this::onInit, this);
        }
        mShellCommandHandler.addDumpCallback(this::dump, this);
    }

    protected void onInit() {
        mShellController.addConfigurationChangeListener(this);
    }

    /** Only used for testing. */
    @VisibleForTesting
    void setConfig(Configuration config) {
        mConfig = config;
    }

    /** Only used for testing. */
    @VisibleForTesting
    void setFloatingTasksEnabled(boolean enabled) {
        mFloatingTasksEnabledForTests = enabled;
    }

    /** Whether the floating layer is available. */
    boolean isFloatingLayerAvailable() {
        Configuration config = mConfig == null
                ? mContext.getResources().getConfiguration()
                : mConfig;
        return config.smallestScreenWidthDp >= SMALLEST_SCREEN_WIDTH_DP_TO_BE_TABLET;
    }

    /** Whether floating tasks are enabled.  */
    boolean isFloatingTasksEnabled() {
        return FLOATING_TASKS_ENABLED || mFloatingTasksEnabledForTests;
    }

    @Override
    public void onThemeChanged() {
        if (mIsFloatingLayerAdded) {
            mFloatingTaskLayer.updateSizes();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // TODO: probably other stuff here to do (e.g. handle rotation)
        if (mIsFloatingLayerAdded) {
            mFloatingTaskLayer.updateSizes();
        }
    }

    /** Returns false if the task shouldn't be shown. */
    private boolean canShowTask(Intent intent) {
        ProtoLog.d(WM_SHELL_FLOATING_APPS, "canShowTask --  %s", intent);
        if (!isFloatingTasksEnabled() || !isFloatingLayerAvailable()) return false;
        if (intent == null) {
            ProtoLog.e(WM_SHELL_FLOATING_APPS, "canShowTask given null intent, doing nothing");
            return false;
        }
        return true;
    }

    /** Returns true if the task was or should be shown as a bubble. */
    private boolean maybeShowTaskAsBubble(Intent intent) {
        if (SHOW_FLOATING_TASKS_AS_BUBBLES && mBubbleController != null) {
            removeFloatingLayer();
            if (intent.getPackage() != null) {
                mBubbleController.addAppBubble(intent);
                ProtoLog.d(WM_SHELL_FLOATING_APPS, "showing floating task as bubble: %s", intent);
            } else {
                ProtoLog.d(WM_SHELL_FLOATING_APPS,
                        "failed to show floating task as bubble: %s; unknown package", intent);
            }
            return true;
        }
        return false;
    }

    /**
     * Shows, stashes, or un-stashes the floating task depending on state:
     * - If there is no floating task for this intent, it shows this the provided task.
     * - If there is a floating task for this intent, but it's stashed, this un-stashes it.
     * - If there is a floating task for this intent, and it's not stashed, this stashes it.
     */
    public void showOrSetStashed(Intent intent) {
        if (!canShowTask(intent)) return;
        if (maybeShowTaskAsBubble(intent)) return;

        addFloatingLayer();

        if (isTaskAttached(mTask) && intent.filterEquals(mTask.intent)) {
            // The task is already added, toggle the stash state.
            mFloatingTaskLayer.setStashed(mTask, !mTask.floatingView.isStashed());
            return;
        }

        // If we're here it's either a new or different task
        showNewTask(intent);
    }

    /**
     * Shows a floating task with the provided intent.
     * If the same task is present it will un-stash it or do nothing if it is already un-stashed.
     * Removes any other floating tasks that might exist.
     */
    public void showTask(Intent intent) {
        if (!canShowTask(intent)) return;
        if (maybeShowTaskAsBubble(intent)) return;

        addFloatingLayer();

        if (isTaskAttached(mTask) && intent.filterEquals(mTask.intent)) {
            // The task is already added, show it if it's stashed.
            if (mTask.floatingView.isStashed()) {
                mFloatingTaskLayer.setStashed(mTask, false);
            }
            return;
        }
        showNewTask(intent);
    }

    private void showNewTask(Intent intent) {
        if (mTask != null && !intent.filterEquals(mTask.intent)) {
            mFloatingTaskLayer.removeAllTaskViews();
            mTask.floatingView.cleanUpTaskView();
            mTask = null;
        }

        FloatingTaskView ftv = new FloatingTaskView(mContext, this);
        ftv.createTaskView(mContext, mTaskOrganizer, mTaskViewTransitions, mSyncQueue);

        mTask = new Task();
        mTask.floatingView = ftv;
        mTask.intent = intent;

        // Add & start the task.
        mFloatingTaskLayer.addTask(mTask);
        ProtoLog.d(WM_SHELL_FLOATING_APPS, "showNewTask, startingIntent: %s", intent);
        mTask.floatingView.startTask(mMainExecutor, mTask);
    }

    /**
     * Removes the task and cleans up the view.
     */
    public void removeTask() {
        if (mTask != null) {
            ProtoLog.d(WM_SHELL_FLOATING_APPS, "Removing task with id=%d", mTask.taskId);

            if (mTask.floatingView != null) {
                // TODO: animate it
                mFloatingTaskLayer.removeView(mTask.floatingView);
                mTask.floatingView.cleanUpTaskView();
            }
            removeFloatingLayer();
        }
    }

    /**
     * Whether there is a floating task and if it is stashed.
     */
    public boolean isStashed() {
        return isTaskAttached(mTask) && mTask.floatingView.isStashed();
    }

    /**
     * If a floating task exists, this sets whether it is stashed and animates if needed.
     */
    public void setStashed(boolean shouldStash) {
        if (mTask != null && mTask.floatingView != null && mIsFloatingLayerAdded) {
            mFloatingTaskLayer.setStashed(mTask, shouldStash);
        }
    }

    /**
     * Saves the last position the floating task was in so that it can be put there again.
     */
    public void setLastPosition(int x, int y) {
        mLastPosition.set(x, y);
    }

    /**
     * Returns the last position the floating task was in.
     */
    public Point getLastPosition() {
        return mLastPosition;
    }

    /**
     * Whether the provided task has a view that's attached to the floating layer.
     */
    private boolean isTaskAttached(Task t) {
        return t != null && t.floatingView != null
                && mIsFloatingLayerAdded
                && mFloatingTaskLayer.getTaskViewCount() > 0
                && Objects.equals(mFloatingTaskLayer.getFirstTaskView(), t.floatingView);
    }

    // TODO: when this is added, if there are bubbles, they get hidden? Is only one layer of this
    //  type allowed? Bubbles & floating tasks should probably be in the same layer to reduce
    //  # of windows.
    private void addFloatingLayer() {
        if (mIsFloatingLayerAdded) {
            return;
        }

        mFloatingTaskLayer = new FloatingTaskLayer(mContext, this, mWindowManager);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.setTrustedOverlay();
        params.setFitInsetsTypes(0);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        params.setTitle("FloatingTaskLayer");
        params.packageName = mContext.getPackageName();
        params.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;

        try {
            mIsFloatingLayerAdded = true;
            mWindowManager.addView(mFloatingTaskLayer, params);
        } catch (IllegalStateException e) {
            // This means the floating layer has already been added which shouldn't happen.
            e.printStackTrace();
        }
    }

    private void removeFloatingLayer() {
        if (!mIsFloatingLayerAdded) {
            return;
        }
        try {
            mIsFloatingLayerAdded = false;
            if (mFloatingTaskLayer != null) {
                mWindowManager.removeView(mFloatingTaskLayer);
            }
        } catch (IllegalArgumentException e) {
            // This means the floating layer has already been removed which shouldn't happen.
            e.printStackTrace();
        }
    }

    /**
     * Description of current floating task state.
     */
    private void dump(PrintWriter pw, String prefix) {
        pw.println("FloatingTaskController state:");
        pw.print("   isFloatingLayerAvailable= "); pw.println(isFloatingLayerAvailable());
        pw.print("   isFloatingTasksEnabled= "); pw.println(isFloatingTasksEnabled());
        pw.print("   mIsFloatingLayerAdded= "); pw.println(mIsFloatingLayerAdded);
        pw.print("   mLastPosition= "); pw.println(mLastPosition);
        pw.println();
    }

    /** Returns the {@link FloatingTasks} implementation. */
    public FloatingTasks asFloatingTasks() {
        return mImpl;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    /**
     * The interface for calls from outside the shell, within the host process.
     */
    @ExternalThread
    private class FloatingTaskImpl implements FloatingTasks {
        private IFloatingTasksImpl mIFloatingTasks;

        @Override
        public void showOrSetStashed(Intent intent) {
            mMainExecutor.execute(() -> FloatingTasksController.this.showOrSetStashed(intent));
        }

        @Override
        public IFloatingTasks createExternalInterface() {
            if (mIFloatingTasks != null) {
                mIFloatingTasks.invalidate();
            }
            mIFloatingTasks = new IFloatingTasksImpl(FloatingTasksController.this);
            return mIFloatingTasks;
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IFloatingTasksImpl extends IFloatingTasks.Stub {
        private FloatingTasksController mController;

        IFloatingTasksImpl(FloatingTasksController controller) {
            mController = controller;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        void invalidate() {
            mController = null;
        }

        public void showTask(Intent intent) {
            executeRemoteCallWithTaskPermission(mController, "showTask",
                    (controller) ->  controller.showTask(intent));
        }
    }
}
