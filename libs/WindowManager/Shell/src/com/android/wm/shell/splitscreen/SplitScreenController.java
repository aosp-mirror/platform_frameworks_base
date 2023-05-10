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

package com.android.wm.shell.splitscreen;

import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.RemoteAnimationTarget.MODE_OPENING;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.common.split.SplitScreenUtils.isValidToSplit;
import static com.android.wm.shell.common.split.SplitScreenUtils.reverseSplitPosition;
import static com.android.wm.shell.common.split.SplitScreenUtils.samePackage;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.sysui.ShellSharedConstants.KEY_EXTRA_SHELL_SPLIT_SCREEN;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.app.TaskInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.widget.Toast;
import android.window.RemoteTransition;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;
import androidx.annotation.IntDef;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.protolog.common.ProtoLog;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SingleInstanceRemoteListener;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.common.split.SplitScreenConstants.SplitPosition;
import com.android.wm.shell.common.split.SplitScreenUtils;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.draganddrop.DragAndDropPolicy;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.sysui.KeyguardChangeListener;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Class manages split-screen multitasking mode and implements the main interface
 * {@link SplitScreen}.
 *
 * @see StageCoordinator
 */
// TODO(b/198577848): Implement split screen flicker test to consolidate CUJ of split screen.
public class SplitScreenController implements DragAndDropPolicy.Starter,
        RemoteCallable<SplitScreenController>, KeyguardChangeListener {
    private static final String TAG = SplitScreenController.class.getSimpleName();

    public static final int EXIT_REASON_UNKNOWN = 0;
    public static final int EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW = 1;
    public static final int EXIT_REASON_APP_FINISHED = 2;
    public static final int EXIT_REASON_DEVICE_FOLDED = 3;
    public static final int EXIT_REASON_DRAG_DIVIDER = 4;
    public static final int EXIT_REASON_RETURN_HOME = 5;
    public static final int EXIT_REASON_ROOT_TASK_VANISHED = 6;
    public static final int EXIT_REASON_SCREEN_LOCKED = 7;
    public static final int EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP = 8;
    public static final int EXIT_REASON_CHILD_TASK_ENTER_PIP = 9;
    public static final int EXIT_REASON_RECREATE_SPLIT = 10;
    public static final int EXIT_REASON_FULLSCREEN_SHORTCUT = 11;
    @IntDef(value = {
            EXIT_REASON_UNKNOWN,
            EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW,
            EXIT_REASON_APP_FINISHED,
            EXIT_REASON_DEVICE_FOLDED,
            EXIT_REASON_DRAG_DIVIDER,
            EXIT_REASON_RETURN_HOME,
            EXIT_REASON_ROOT_TASK_VANISHED,
            EXIT_REASON_SCREEN_LOCKED,
            EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP,
            EXIT_REASON_CHILD_TASK_ENTER_PIP,
            EXIT_REASON_RECREATE_SPLIT,
            EXIT_REASON_FULLSCREEN_SHORTCUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ExitReason{}

    public static final int ENTER_REASON_UNKNOWN = 0;
    public static final int ENTER_REASON_MULTI_INSTANCE = 1;
    public static final int ENTER_REASON_DRAG = 2;
    public static final int ENTER_REASON_LAUNCHER = 3;
    /** Acts as a mapping to the actual EnterReasons as defined in the logging proto */
    @IntDef(value = {
            ENTER_REASON_MULTI_INSTANCE,
            ENTER_REASON_DRAG,
            ENTER_REASON_LAUNCHER,
            ENTER_REASON_UNKNOWN
    })
    public @interface SplitEnterReason {
    }

    private final ShellCommandHandler mShellCommandHandler;
    private final ShellController mShellController;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final Context mContext;
    private final RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    private final ShellExecutor mMainExecutor;
    private final SplitScreenImpl mImpl = new SplitScreenImpl();
    private final DisplayController mDisplayController;
    private final DisplayImeController mDisplayImeController;
    private final DisplayInsetsController mDisplayInsetsController;
    private final Optional<DragAndDropController> mDragAndDropController;
    private final Transitions mTransitions;
    private final TransactionPool mTransactionPool;
    private final IconProvider mIconProvider;
    private final Optional<RecentTasksController> mRecentTasksOptional;
    private final SplitScreenShellCommandHandler mSplitScreenShellCommandHandler;
    private final String[] mAppsSupportMultiInstances;

    @VisibleForTesting
    StageCoordinator mStageCoordinator;

    // Only used for the legacy recents animation from splitscreen to allow the tasks to be animated
    // outside the bounds of the roots by being reparented into a higher level fullscreen container
    private SurfaceControl mGoingToRecentsTasksLayer;
    private SurfaceControl mStartingSplitTasksLayer;

    public SplitScreenController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer,
            DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            Optional<DragAndDropController> dragAndDropController,
            Transitions transitions,
            TransactionPool transactionPool,
            IconProvider iconProvider,
            Optional<RecentTasksController> recentTasks,
            ShellExecutor mainExecutor) {
        mShellCommandHandler = shellCommandHandler;
        mShellController = shellController;
        mTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mContext = context;
        mRootTDAOrganizer = rootTDAOrganizer;
        mMainExecutor = mainExecutor;
        mDisplayController = displayController;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mDragAndDropController = dragAndDropController;
        mTransitions = transitions;
        mTransactionPool = transactionPool;
        mIconProvider = iconProvider;
        mRecentTasksOptional = recentTasks;
        mSplitScreenShellCommandHandler = new SplitScreenShellCommandHandler(this);
        // TODO(b/238217847): Temporarily add this check here until we can remove the dynamic
        //                    override for this controller from the base module
        if (ActivityTaskManager.supportsSplitScreenMultiWindow(context)) {
            shellInit.addInitCallback(this::onInit, this);
        }

        // TODO(255224696): Remove the config once having a way for client apps to opt-in
        //                  multi-instances split.
        mAppsSupportMultiInstances = mContext.getResources()
                .getStringArray(R.array.config_appsSupportMultiInstancesSplit);
    }

    @VisibleForTesting
    SplitScreenController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer,
            DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            DragAndDropController dragAndDropController,
            Transitions transitions,
            TransactionPool transactionPool,
            IconProvider iconProvider,
            RecentTasksController recentTasks,
            ShellExecutor mainExecutor,
            StageCoordinator stageCoordinator) {
        mShellCommandHandler = shellCommandHandler;
        mShellController = shellController;
        mTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mContext = context;
        mRootTDAOrganizer = rootTDAOrganizer;
        mMainExecutor = mainExecutor;
        mDisplayController = displayController;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mDragAndDropController = Optional.of(dragAndDropController);
        mTransitions = transitions;
        mTransactionPool = transactionPool;
        mIconProvider = iconProvider;
        mRecentTasksOptional = Optional.of(recentTasks);
        mStageCoordinator = stageCoordinator;
        mSplitScreenShellCommandHandler = new SplitScreenShellCommandHandler(this);
        shellInit.addInitCallback(this::onInit, this);
        mAppsSupportMultiInstances = mContext.getResources()
                .getStringArray(R.array.config_appsSupportMultiInstancesSplit);
    }

    public SplitScreen asSplitScreen() {
        return mImpl;
    }

    private ExternalInterfaceBinder createExternalInterface() {
        return new ISplitScreenImpl(this);
    }

    /**
     * This will be called after ShellTaskOrganizer has initialized/registered because of the
     * dependency order.
     */
    @VisibleForTesting
    void onInit() {
        mShellCommandHandler.addDumpCallback(this::dump, this);
        mShellCommandHandler.addCommandCallback("splitscreen", mSplitScreenShellCommandHandler,
                this);
        mShellController.addKeyguardChangeListener(this);
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_SPLIT_SCREEN,
                this::createExternalInterface, this);
        if (mStageCoordinator == null) {
            // TODO: Multi-display
            mStageCoordinator = createStageCoordinator();
        }
        mDragAndDropController.ifPresent(controller -> controller.setSplitScreenController(this));
    }

    protected StageCoordinator createStageCoordinator() {
        return new StageCoordinator(mContext, DEFAULT_DISPLAY, mSyncQueue,
                mTaskOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mTransitions, mTransactionPool,
                mIconProvider, mMainExecutor, mRecentTasksOptional);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    public boolean isSplitScreenVisible() {
        return mStageCoordinator.isSplitScreenVisible();
    }

    public StageCoordinator getTransitionHandler() {
        return mStageCoordinator;
    }

    @Nullable
    public ActivityManager.RunningTaskInfo getTaskInfo(@SplitPosition int splitPosition) {
        if (!isSplitScreenVisible() || splitPosition == SPLIT_POSITION_UNDEFINED) {
            return null;
        }

        final int taskId = mStageCoordinator.getTaskId(splitPosition);
        return mTaskOrganizer.getRunningTaskInfo(taskId);
    }

    /** Check task is under split or not by taskId. */
    public boolean isTaskInSplitScreen(int taskId) {
        return mStageCoordinator.getStageOfTask(taskId) != STAGE_TYPE_UNDEFINED;
    }

    /** Check split is foreground and task is under split or not by taskId. */
    public boolean isTaskInSplitScreenForeground(int taskId) {
        return isTaskInSplitScreen(taskId) && isSplitScreenVisible();
    }

    /** Check whether the task is the single-top root or the root of one of the stages. */
    public boolean isTaskRootOrStageRoot(int taskId) {
        return mStageCoordinator.isRootOrStageRoot(taskId);
    }

    public @SplitPosition int getSplitPosition(int taskId) {
        return mStageCoordinator.getSplitPosition(taskId);
    }

    public boolean moveToSideStage(int taskId, @SplitPosition int sideStagePosition) {
        return moveToStage(taskId, sideStagePosition, new WindowContainerTransaction());
    }

    /**
     * Update surfaces of the split screen layout based on the current state
     * @param transaction to write the updates to
     */
    public void updateSplitScreenSurfaces(SurfaceControl.Transaction transaction) {
        mStageCoordinator.updateSurfaces(transaction);
    }

    private boolean moveToStage(int taskId, @SplitPosition int stagePosition,
            WindowContainerTransaction wct) {
        final ActivityManager.RunningTaskInfo task = mTaskOrganizer.getRunningTaskInfo(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown taskId" + taskId);
        }
        if (isTaskInSplitScreen(taskId)) {
            throw new IllegalArgumentException("taskId is in split" + taskId);
        }
        return mStageCoordinator.moveToStage(task, stagePosition, wct);
    }

    public boolean removeFromSideStage(int taskId) {
        return mStageCoordinator.removeFromSideStage(taskId);
    }

    public void setSideStagePosition(@SplitPosition int sideStagePosition) {
        mStageCoordinator.setSideStagePosition(sideStagePosition, null /* wct */);
    }

    public void enterSplitScreen(int taskId, boolean leftOrTop) {
        enterSplitScreen(taskId, leftOrTop, new WindowContainerTransaction());
    }

    public void prepareEnterSplitScreen(WindowContainerTransaction wct,
            ActivityManager.RunningTaskInfo taskInfo, int startPosition) {
        mStageCoordinator.prepareEnterSplitScreen(wct, taskInfo, startPosition);
    }

    public void finishEnterSplitScreen(SurfaceControl.Transaction t) {
        mStageCoordinator.finishEnterSplitScreen(t);
    }

    public void enterSplitScreen(int taskId, boolean leftOrTop, WindowContainerTransaction wct) {
        final int stagePosition =
                leftOrTop ? SPLIT_POSITION_TOP_OR_LEFT : SPLIT_POSITION_BOTTOM_OR_RIGHT;
        moveToStage(taskId, stagePosition, wct);
    }

    public void exitSplitScreen(int toTopTaskId, @ExitReason int exitReason) {
        mStageCoordinator.exitSplitScreen(toTopTaskId, exitReason);
    }

    @Override
    public void onKeyguardVisibilityChanged(boolean visible, boolean occluded,
            boolean animatingDismiss) {
        mStageCoordinator.onKeyguardVisibilityChanged(visible);
    }

    public void onFinishedWakingUp() {
        mStageCoordinator.onFinishedWakingUp();
    }

    public void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) {
        mStageCoordinator.exitSplitScreenOnHide(exitSplitScreenOnHide);
    }

    public void getStageBounds(Rect outTopOrLeftBounds, Rect outBottomOrRightBounds) {
        mStageCoordinator.getStageBounds(outTopOrLeftBounds, outBottomOrRightBounds);
    }

    public void registerSplitScreenListener(SplitScreen.SplitScreenListener listener) {
        mStageCoordinator.registerSplitScreenListener(listener);
    }

    public void unregisterSplitScreenListener(SplitScreen.SplitScreenListener listener) {
        mStageCoordinator.unregisterSplitScreenListener(listener);
    }

    public void goToFullscreenFromSplit() {
        mStageCoordinator.goToFullscreenFromSplit();
    }

    /** Move the specified task to fullscreen, regardless of focus state. */
    public void moveTaskToFullscreen(int taskId) {
        mStageCoordinator.moveTaskToFullscreen(taskId);
    }

    public boolean isLaunchToSplit(TaskInfo taskInfo) {
        return mStageCoordinator.isLaunchToSplit(taskInfo);
    }

    public int getActivateSplitPosition(TaskInfo taskInfo) {
        return mStageCoordinator.getActivateSplitPosition(taskInfo);
    }

    public void startTask(int taskId, @SplitPosition int position, @Nullable Bundle options) {
        final int[] result = new int[1];
        IRemoteAnimationRunner wrapper = new IRemoteAnimationRunner.Stub() {
            @Override
            public void onAnimationStart(@WindowManager.TransitionOldType int transit,
                    RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers,
                    RemoteAnimationTarget[] nonApps,
                    final IRemoteAnimationFinishedCallback finishedCallback) {
                try {
                    finishedCallback.onAnimationFinished();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to invoke onAnimationFinished", e);
                }
                if (result[0] == START_SUCCESS || result[0] == START_TASK_TO_FRONT) {
                    final WindowContainerTransaction evictWct = new WindowContainerTransaction();
                    mStageCoordinator.prepareEvictNonOpeningChildTasks(position, apps, evictWct);
                    mSyncQueue.queue(evictWct);
                }
            }
            @Override
            public void onAnimationCancelled() {
                final WindowContainerTransaction evictWct = new WindowContainerTransaction();
                mStageCoordinator.prepareEvictInvisibleChildTasks(evictWct);
                mSyncQueue.queue(evictWct);
            }
        };
        options = mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, position, options,
                null /* wct */);
        RemoteAnimationAdapter wrappedAdapter = new RemoteAnimationAdapter(wrapper,
                0 /* duration */, 0 /* statusBarTransitionDelay */);
        ActivityOptions activityOptions = ActivityOptions.fromBundle(options);
        activityOptions.update(ActivityOptions.makeRemoteAnimation(wrappedAdapter));

        try {
            result[0] = ActivityTaskManager.getService().startActivityFromRecents(taskId,
                    activityOptions.toBundle());
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to launch task", e);
        }
    }

    /**
     * See {@link #startShortcut(String, String, int, Bundle, UserHandle)}
     * @param instanceId to be used by {@link SplitscreenEventLogger}
     */
    public void startShortcut(String packageName, String shortcutId, @SplitPosition int position,
            @Nullable Bundle options, UserHandle user, @NonNull InstanceId instanceId) {
        mStageCoordinator.onRequestToSplit(instanceId, ENTER_REASON_LAUNCHER);
        startShortcut(packageName, shortcutId, position, options, user);
    }

    @Override
    public void startShortcut(String packageName, String shortcutId, @SplitPosition int position,
            @Nullable Bundle options, UserHandle user) {
        if (options == null) options = new Bundle();
        final ActivityOptions activityOptions = ActivityOptions.fromBundle(options);

        if (samePackage(packageName, getPackageName(reverseSplitPosition(position)))) {
            if (supportMultiInstancesSplit(packageName)) {
                activityOptions.setApplyMultipleTaskFlagForShortcut(true);
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
            } else if (isSplitScreenVisible()) {
                mStageCoordinator.switchSplitPosition("startShortcut");
                return;
            } else {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                        "Cancel entering split as not supporting multi-instances");
                Toast.makeText(mContext, R.string.dock_multi_instances_not_supported_text,
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        mStageCoordinator.startShortcut(packageName, shortcutId, position,
                activityOptions.toBundle(), user);
    }

    void startShortcutAndTaskWithLegacyTransition(ShortcutInfo shortcutInfo,
            @Nullable Bundle options1, int taskId, @Nullable Bundle options2,
            @SplitPosition int splitPosition, float splitRatio, RemoteAnimationAdapter adapter,
            InstanceId instanceId) {
        if (options1 == null) options1 = new Bundle();
        final ActivityOptions activityOptions = ActivityOptions.fromBundle(options1);

        final String packageName1 = shortcutInfo.getPackage();
        final String packageName2 = SplitScreenUtils.getPackageName(taskId, mTaskOrganizer);
        if (samePackage(packageName1, packageName2)) {
            if (supportMultiInstancesSplit(shortcutInfo.getPackage())) {
                activityOptions.setApplyMultipleTaskFlagForShortcut(true);
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
            } else {
                taskId = INVALID_TASK_ID;
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                        "Cancel entering split as not supporting multi-instances");
                Toast.makeText(mContext, R.string.dock_multi_instances_not_supported_text,
                        Toast.LENGTH_SHORT).show();
            }
        }

        mStageCoordinator.startShortcutAndTaskWithLegacyTransition(shortcutInfo,
                activityOptions.toBundle(), taskId, options2, splitPosition, splitRatio, adapter,
                instanceId);
    }

    void startShortcutAndTask(ShortcutInfo shortcutInfo, @Nullable Bundle options1,
            int taskId, @Nullable Bundle options2, @SplitPosition int splitPosition,
            float splitRatio, @Nullable RemoteTransition remoteTransition, InstanceId instanceId) {
        if (options1 == null) options1 = new Bundle();
        final ActivityOptions activityOptions = ActivityOptions.fromBundle(options1);
        final String packageName1 = shortcutInfo.getPackage();
        // NOTE: This doesn't correctly pull out packageName2 if taskId is referring to a task in
        //       recents that hasn't launched and is not being organized
        final String packageName2 = SplitScreenUtils.getPackageName(taskId, mTaskOrganizer);
        if (samePackage(packageName1, packageName2)) {
            if (supportMultiInstancesSplit(packageName1)) {
                activityOptions.setApplyMultipleTaskFlagForShortcut(true);
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
            } else {
                if (mRecentTasksOptional.isPresent()) {
                    mRecentTasksOptional.get().removeSplitPair(taskId);
                }
                taskId = INVALID_TASK_ID;
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                        "Cancel entering split as not supporting multi-instances");
                Toast.makeText(mContext, R.string.dock_multi_instances_not_supported_text,
                        Toast.LENGTH_SHORT).show();
            }
        }
        mStageCoordinator.startShortcutAndTask(shortcutInfo, options1, taskId, options2,
                splitPosition, splitRatio, remoteTransition, instanceId);
    }

    /**
     * See {@link #startIntent(PendingIntent, Intent, int, Bundle)}
     * @param instanceId to be used by {@link SplitscreenEventLogger}
     */
    public void startIntent(PendingIntent intent, @Nullable Intent fillInIntent,
            @SplitPosition int position, @Nullable Bundle options, @NonNull InstanceId instanceId) {
        mStageCoordinator.onRequestToSplit(instanceId, ENTER_REASON_LAUNCHER);
        startIntent(intent, fillInIntent, position, options);
    }

    private void startIntentAndTaskWithLegacyTransition(PendingIntent pendingIntent,
            @Nullable Bundle options1, int taskId, @Nullable Bundle options2,
            @SplitPosition int splitPosition, float splitRatio, RemoteAnimationAdapter adapter,
            InstanceId instanceId) {
        Intent fillInIntent = null;
        final String packageName1 = SplitScreenUtils.getPackageName(pendingIntent);
        final String packageName2 = SplitScreenUtils.getPackageName(taskId, mTaskOrganizer);
        if (samePackage(packageName1, packageName2)) {
            if (supportMultiInstancesSplit(packageName1)) {
                fillInIntent = new Intent();
                fillInIntent.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
            } else {
                taskId = INVALID_TASK_ID;
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                        "Cancel entering split as not supporting multi-instances");
                Toast.makeText(mContext, R.string.dock_multi_instances_not_supported_text,
                        Toast.LENGTH_SHORT).show();
            }
        }
        mStageCoordinator.startIntentAndTaskWithLegacyTransition(pendingIntent, fillInIntent,
                options1, taskId, options2, splitPosition, splitRatio, adapter, instanceId);
    }

    private void startIntentAndTask(PendingIntent pendingIntent, @Nullable Bundle options1,
            int taskId, @Nullable Bundle options2, @SplitPosition int splitPosition,
            float splitRatio, @Nullable RemoteTransition remoteTransition, InstanceId instanceId) {
        Intent fillInIntent = null;
        final String packageName1 = SplitScreenUtils.getPackageName(pendingIntent);
        // NOTE: This doesn't correctly pull out packageName2 if taskId is referring to a task in
        //       recents that hasn't launched and is not being organized
        final String packageName2 = SplitScreenUtils.getPackageName(taskId, mTaskOrganizer);
        if (samePackage(packageName1, packageName2)) {
            if (supportMultiInstancesSplit(packageName1)) {
                fillInIntent = new Intent();
                fillInIntent.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
            } else {
                if (mRecentTasksOptional.isPresent()) {
                    mRecentTasksOptional.get().removeSplitPair(taskId);
                }
                taskId = INVALID_TASK_ID;
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                        "Cancel entering split as not supporting multi-instances");
                Toast.makeText(mContext, R.string.dock_multi_instances_not_supported_text,
                        Toast.LENGTH_SHORT).show();
            }
        }
        mStageCoordinator.startIntentAndTask(pendingIntent, fillInIntent, options1, taskId,
                options2, splitPosition, splitRatio, remoteTransition, instanceId);
    }

    private void startIntentsWithLegacyTransition(PendingIntent pendingIntent1,
            @Nullable ShortcutInfo shortcutInfo1, @Nullable Bundle options1,
            PendingIntent pendingIntent2, @Nullable ShortcutInfo shortcutInfo2,
            @Nullable Bundle options2, @SplitPosition int splitPosition, float splitRatio,
            RemoteAnimationAdapter adapter, InstanceId instanceId) {
        Intent fillInIntent1 = null;
        Intent fillInIntent2 = null;
        final String packageName1 = SplitScreenUtils.getPackageName(pendingIntent1);
        final String packageName2 = SplitScreenUtils.getPackageName(pendingIntent2);
        if (samePackage(packageName1, packageName2)) {
            if (supportMultiInstancesSplit(packageName1)) {
                fillInIntent1 = new Intent();
                fillInIntent1.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                fillInIntent2 = new Intent();
                fillInIntent2.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
            } else {
                pendingIntent2 = null;
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                        "Cancel entering split as not supporting multi-instances");
                Toast.makeText(mContext, R.string.dock_multi_instances_not_supported_text,
                        Toast.LENGTH_SHORT).show();
            }
        }
        mStageCoordinator.startIntentsWithLegacyTransition(pendingIntent1, fillInIntent1,
                shortcutInfo1, options1, pendingIntent2, fillInIntent2, shortcutInfo2, options2,
                splitPosition, splitRatio, adapter, instanceId);
    }

    private void startIntents(PendingIntent pendingIntent1,
            @Nullable ShortcutInfo shortcutInfo1, @Nullable Bundle options1,
            PendingIntent pendingIntent2, @Nullable ShortcutInfo shortcutInfo2,
            @Nullable Bundle options2, @SplitPosition int splitPosition, float splitRatio,
            @Nullable RemoteTransition remoteTransition, InstanceId instanceId) {
        Intent fillInIntent1 = null;
        Intent fillInIntent2 = null;
        final String packageName1 = SplitScreenUtils.getPackageName(pendingIntent1);
        final String packageName2 = SplitScreenUtils.getPackageName(pendingIntent2);
        if (samePackage(packageName1, packageName2)) {
            if (supportMultiInstancesSplit(packageName1)) {
                fillInIntent1 = new Intent();
                fillInIntent1.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                fillInIntent2 = new Intent();
                fillInIntent2.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
            } else {
                pendingIntent2 = null;
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                        "Cancel entering split as not supporting multi-instances");
                Toast.makeText(mContext, R.string.dock_multi_instances_not_supported_text,
                        Toast.LENGTH_SHORT).show();
            }
        }
        mStageCoordinator.startIntents(pendingIntent1, fillInIntent1, shortcutInfo1, options1,
                pendingIntent2, fillInIntent2, shortcutInfo2, options2, splitPosition, splitRatio,
                remoteTransition, instanceId);
    }

    @Override
    public void startIntent(PendingIntent intent, @Nullable Intent fillInIntent,
            @SplitPosition int position, @Nullable Bundle options) {
        // Flag this as a no-user-action launch to prevent sending user leaving event to the current
        // top activity since it's going to be put into another side of the split. This prevents the
        // current top activity from going into pip mode due to user leaving event.
        if (fillInIntent == null) fillInIntent = new Intent();
        fillInIntent.addFlags(FLAG_ACTIVITY_NO_USER_ACTION);

        final String packageName1 = SplitScreenUtils.getPackageName(intent);
        final String packageName2 = getPackageName(reverseSplitPosition(position));
        if (SplitScreenUtils.samePackage(packageName1, packageName2)) {
            if (supportMultiInstancesSplit(packageName1)) {
                // To prevent accumulating large number of instances in the background, reuse task
                // in the background with priority.
                final ActivityManager.RecentTaskInfo taskInfo = mRecentTasksOptional
                        .map(recentTasks -> recentTasks.findTaskInBackground(
                                intent.getIntent().getComponent()))
                        .orElse(null);
                if (taskInfo != null) {
                    startTask(taskInfo.taskId, position, options);
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                            "Start task in background");
                    return;
                }

                // Flag with MULTIPLE_TASK if this is launching the same activity into both sides of
                // the split and there is no reusable background task.
                fillInIntent.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
            } else if (isSplitScreenVisible()) {
                mStageCoordinator.switchSplitPosition("startIntent");
                return;
            } else {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                        "Cancel entering split as not supporting multi-instances");
                Toast.makeText(mContext, R.string.dock_multi_instances_not_supported_text,
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        mStageCoordinator.startIntent(intent, fillInIntent, position, options);
    }

    /** Retrieve package name of a specific split position if split screen is activated, otherwise
     *  returns the package name of the top running task. */
    @Nullable
    private String getPackageName(@SplitPosition int position) {
        ActivityManager.RunningTaskInfo taskInfo;
        if (isSplitScreenVisible()) {
            taskInfo = getTaskInfo(position);
        } else {
            taskInfo = mRecentTasksOptional
                    .map(recentTasks -> recentTasks.getTopRunningTask())
                    .orElse(null);
            if (!isValidToSplit(taskInfo)) {
                return null;
            }
        }

        return taskInfo != null ? SplitScreenUtils.getPackageName(taskInfo.baseIntent) : null;
    }

    @VisibleForTesting
    boolean supportMultiInstancesSplit(String packageName) {
        if (packageName != null) {
            for (int i = 0; i < mAppsSupportMultiInstances.length; i++) {
                if (mAppsSupportMultiInstances[i].equals(packageName)) {
                    return true;
                }
            }
        }

        return false;
    }

    RemoteAnimationTarget[] onGoingToRecentsLegacy(RemoteAnimationTarget[] apps) {
        if (ENABLE_SHELL_TRANSITIONS) return null;

        if (isSplitScreenVisible()) {
            // Evict child tasks except the top visible one under split root to ensure it could be
            // launched as full screen when switching to it on recents.
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            mStageCoordinator.prepareEvictInvisibleChildTasks(wct);
            mSyncQueue.queue(wct);
        } else {
            return null;
        }

        SurfaceControl.Transaction t = mTransactionPool.acquire();
        if (mGoingToRecentsTasksLayer != null) {
            t.remove(mGoingToRecentsTasksLayer);
        }
        mGoingToRecentsTasksLayer = reparentSplitTasksForAnimation(apps, t,
                "SplitScreenController#onGoingToRecentsLegacy" /* callsite */);
        t.apply();
        mTransactionPool.release(t);

        return new RemoteAnimationTarget[]{mStageCoordinator.getDividerBarLegacyTarget()};
    }

    RemoteAnimationTarget[] onStartingSplitLegacy(RemoteAnimationTarget[] apps) {
        if (ENABLE_SHELL_TRANSITIONS) return null;

        int openingApps = 0;
        for (int i = 0; i < apps.length; ++i) {
            if (apps[i].mode == MODE_OPENING) openingApps++;
        }
        if (openingApps < 2) {
            // Not having enough apps to enter split screen
            return null;
        }

        SurfaceControl.Transaction t = mTransactionPool.acquire();
        if (mStartingSplitTasksLayer != null) {
            t.remove(mStartingSplitTasksLayer);
        }
        mStartingSplitTasksLayer = reparentSplitTasksForAnimation(apps, t,
                "SplitScreenController#onStartingSplitLegacy" /* callsite */);
        t.apply();
        mTransactionPool.release(t);

        try {
            return new RemoteAnimationTarget[]{mStageCoordinator.getDividerBarLegacyTarget()};
        } finally {
            for (RemoteAnimationTarget appTarget : apps) {
                if (appTarget.leash != null) {
                    appTarget.leash.release();
                }
            }
        }
    }

    private SurfaceControl reparentSplitTasksForAnimation(RemoteAnimationTarget[] apps,
            SurfaceControl.Transaction t, String callsite) {
        final SurfaceControl.Builder builder = new SurfaceControl.Builder(new SurfaceSession())
                .setContainerLayer()
                .setName("RecentsAnimationSplitTasks")
                .setHidden(false)
                .setCallsite(callsite);
        mRootTDAOrganizer.attachToDisplayArea(DEFAULT_DISPLAY, builder);
        final SurfaceControl splitTasksLayer = builder.build();

        for (int i = 0; i < apps.length; ++i) {
            final RemoteAnimationTarget appTarget = apps[i];
            t.reparent(appTarget.leash, splitTasksLayer);
            t.setPosition(appTarget.leash, appTarget.screenSpaceBounds.left,
                    appTarget.screenSpaceBounds.top);
        }
        return splitTasksLayer;
    }
    /**
     * Drop callback when splitscreen is entered.
     */
    public void onDroppedToSplit(@SplitPosition int position, InstanceId dragSessionId) {
        mStageCoordinator.onDroppedToSplit(position, dragSessionId);
    }

    /**
     * Return the {@param exitReason} as a string.
     */
    public static String exitReasonToString(int exitReason) {
        switch (exitReason) {
            case EXIT_REASON_UNKNOWN:
                return "UNKNOWN_EXIT";
            case EXIT_REASON_DRAG_DIVIDER:
                return "DRAG_DIVIDER";
            case EXIT_REASON_RETURN_HOME:
                return "RETURN_HOME";
            case EXIT_REASON_SCREEN_LOCKED:
                return "SCREEN_LOCKED";
            case EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP:
                return "SCREEN_LOCKED_SHOW_ON_TOP";
            case EXIT_REASON_DEVICE_FOLDED:
                return "DEVICE_FOLDED";
            case EXIT_REASON_ROOT_TASK_VANISHED:
                return "ROOT_TASK_VANISHED";
            case EXIT_REASON_APP_FINISHED:
                return "APP_FINISHED";
            case EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW:
                return "APP_DOES_NOT_SUPPORT_MULTIWINDOW";
            case EXIT_REASON_CHILD_TASK_ENTER_PIP:
                return "CHILD_TASK_ENTER_PIP";
            case EXIT_REASON_RECREATE_SPLIT:
                return "RECREATE_SPLIT";
            default:
                return "unknown reason, reason int = " + exitReason;
        }
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        pw.println(prefix + TAG);
        if (mStageCoordinator != null) {
            mStageCoordinator.dump(pw, prefix);
        }
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class SplitScreenImpl implements SplitScreen {
        private final ArrayMap<SplitScreenListener, Executor> mExecutors = new ArrayMap<>();
        private final SplitScreen.SplitScreenListener mListener = new SplitScreenListener() {
            @Override
            public void onStagePositionChanged(int stage, int position) {
                for (int i = 0; i < mExecutors.size(); i++) {
                    final int index = i;
                    mExecutors.valueAt(index).execute(() -> {
                        mExecutors.keyAt(index).onStagePositionChanged(stage, position);
                    });
                }
            }

            @Override
            public void onTaskStageChanged(int taskId, int stage, boolean visible) {
                for (int i = 0; i < mExecutors.size(); i++) {
                    final int index = i;
                    mExecutors.valueAt(index).execute(() -> {
                        mExecutors.keyAt(index).onTaskStageChanged(taskId, stage, visible);
                    });
                }
            }

            @Override
            public void onSplitBoundsChanged(Rect rootBounds, Rect mainBounds, Rect sideBounds) {
                for (int i = 0; i < mExecutors.size(); i++) {
                    final int index = i;
                    mExecutors.valueAt(index).execute(() -> {
                        mExecutors.keyAt(index).onSplitBoundsChanged(rootBounds, mainBounds,
                                sideBounds);
                    });
                }
            }

            @Override
            public void onSplitVisibilityChanged(boolean visible) {
                for (int i = 0; i < mExecutors.size(); i++) {
                    final int index = i;
                    mExecutors.valueAt(index).execute(() -> {
                        mExecutors.keyAt(index).onSplitVisibilityChanged(visible);
                    });
                }
            }
        };

        @Override
        public void registerSplitScreenListener(SplitScreenListener listener, Executor executor) {
            if (mExecutors.containsKey(listener)) return;

            mMainExecutor.execute(() -> {
                if (mExecutors.size() == 0) {
                    SplitScreenController.this.registerSplitScreenListener(mListener);
                }

                mExecutors.put(listener, executor);
            });

            executor.execute(() -> {
                mStageCoordinator.sendStatusToListener(listener);
            });
        }

        @Override
        public void unregisterSplitScreenListener(SplitScreenListener listener) {
            mMainExecutor.execute(() -> {
                mExecutors.remove(listener);

                if (mExecutors.size() == 0) {
                    SplitScreenController.this.unregisterSplitScreenListener(mListener);
                }
            });
        }

        @Override
        public void onFinishedWakingUp() {
            mMainExecutor.execute(SplitScreenController.this::onFinishedWakingUp);
        }

        @Override
        public void goToFullscreenFromSplit() {
            mMainExecutor.execute(SplitScreenController.this::goToFullscreenFromSplit);
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class ISplitScreenImpl extends ISplitScreen.Stub
            implements ExternalInterfaceBinder {
        private SplitScreenController mController;
        private final SingleInstanceRemoteListener<SplitScreenController,
                ISplitScreenListener> mListener;
        private final SplitScreen.SplitScreenListener mSplitScreenListener =
                new SplitScreen.SplitScreenListener() {
                    @Override
                    public void onStagePositionChanged(int stage, int position) {
                        mListener.call(l -> l.onStagePositionChanged(stage, position));
                    }

                    @Override
                    public void onTaskStageChanged(int taskId, int stage, boolean visible) {
                        mListener.call(l -> l.onTaskStageChanged(taskId, stage, visible));
                    }
                };

        public ISplitScreenImpl(SplitScreenController controller) {
            mController = controller;
            mListener = new SingleInstanceRemoteListener<>(controller,
                    c -> c.registerSplitScreenListener(mSplitScreenListener),
                    c -> c.unregisterSplitScreenListener(mSplitScreenListener));
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        @Override
        public void invalidate() {
            mController = null;
            // Unregister the listener to ensure any registered binder death recipients are unlinked
            mListener.unregister();
        }

        @Override
        public void registerSplitScreenListener(ISplitScreenListener listener) {
            executeRemoteCallWithTaskPermission(mController, "registerSplitScreenListener",
                    (controller) -> mListener.register(listener));
        }

        @Override
        public void unregisterSplitScreenListener(ISplitScreenListener listener) {
            executeRemoteCallWithTaskPermission(mController, "unregisterSplitScreenListener",
                    (controller) -> mListener.unregister());
        }

        @Override
        public void exitSplitScreen(int toTopTaskId) {
            executeRemoteCallWithTaskPermission(mController, "exitSplitScreen",
                    (controller) -> controller.exitSplitScreen(toTopTaskId, EXIT_REASON_UNKNOWN));
        }

        @Override
        public void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) {
            executeRemoteCallWithTaskPermission(mController, "exitSplitScreenOnHide",
                    (controller) -> controller.exitSplitScreenOnHide(exitSplitScreenOnHide));
        }

        @Override
        public void removeFromSideStage(int taskId) {
            executeRemoteCallWithTaskPermission(mController, "removeFromSideStage",
                    (controller) -> controller.removeFromSideStage(taskId));
        }

        @Override
        public void startTask(int taskId, int position, @Nullable Bundle options) {
            executeRemoteCallWithTaskPermission(mController, "startTask",
                    (controller) -> controller.startTask(taskId, position, options));
        }

        @Override
        public void startTasksWithLegacyTransition(int taskId1, @Nullable Bundle options1,
                int taskId2, @Nullable Bundle options2, @SplitPosition int splitPosition,
                float splitRatio, RemoteAnimationAdapter adapter, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startTasks",
                    (controller) -> controller.mStageCoordinator.startTasksWithLegacyTransition(
                            taskId1, options1, taskId2, options2, splitPosition,
                            splitRatio, adapter, instanceId));
        }

        @Override
        public void startIntentAndTaskWithLegacyTransition(PendingIntent pendingIntent,
                Bundle options1, int taskId, Bundle options2, int splitPosition, float splitRatio,
                RemoteAnimationAdapter adapter, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController,
                    "startIntentAndTaskWithLegacyTransition", (controller) ->
                            controller.startIntentAndTaskWithLegacyTransition(pendingIntent,
                                    options1, taskId, options2, splitPosition, splitRatio, adapter,
                                    instanceId));
        }

        @Override
        public void startShortcutAndTaskWithLegacyTransition(ShortcutInfo shortcutInfo,
                @Nullable Bundle options1, int taskId, @Nullable Bundle options2,
                @SplitPosition int splitPosition, float splitRatio, RemoteAnimationAdapter adapter,
                InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController,
                    "startShortcutAndTaskWithLegacyTransition", (controller) ->
                            controller.startShortcutAndTaskWithLegacyTransition(
                                    shortcutInfo, options1, taskId, options2, splitPosition,
                                    splitRatio, adapter, instanceId));
        }

        @Override
        public void startTasks(int taskId1, @Nullable Bundle options1, int taskId2,
                @Nullable Bundle options2, @SplitPosition int splitPosition, float splitRatio,
                @Nullable RemoteTransition remoteTransition, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startTasks",
                    (controller) -> controller.mStageCoordinator.startTasks(taskId1, options1,
                            taskId2, options2, splitPosition, splitRatio, remoteTransition,
                            instanceId));
        }

        @Override
        public void startIntentAndTask(PendingIntent pendingIntent, @Nullable Bundle options1,
                int taskId, @Nullable Bundle options2, @SplitPosition int splitPosition,
                float splitRatio, @Nullable RemoteTransition remoteTransition,
                InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startIntentAndTask",
                    (controller) -> controller.startIntentAndTask(pendingIntent, options1, taskId,
                            options2, splitPosition, splitRatio, remoteTransition, instanceId));
        }

        @Override
        public void startShortcutAndTask(ShortcutInfo shortcutInfo, @Nullable Bundle options1,
                int taskId, @Nullable Bundle options2, @SplitPosition int splitPosition,
                float splitRatio, @Nullable RemoteTransition remoteTransition,
                InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startShortcutAndTask",
                    (controller) -> controller.startShortcutAndTask(shortcutInfo, options1, taskId,
                            options2, splitPosition, splitRatio, remoteTransition, instanceId));
        }

        @Override
        public void startIntentsWithLegacyTransition(PendingIntent pendingIntent1,
                @Nullable ShortcutInfo shortcutInfo1, @Nullable Bundle options1,
                PendingIntent pendingIntent2, @Nullable ShortcutInfo shortcutInfo2,
                @Nullable Bundle options2, @SplitPosition int splitPosition, float splitRatio,
                RemoteAnimationAdapter adapter, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startIntentsWithLegacyTransition",
                    (controller) ->
                        controller.startIntentsWithLegacyTransition(pendingIntent1, shortcutInfo1,
                                options1, pendingIntent2, shortcutInfo2, options2, splitPosition,
                                splitRatio, adapter, instanceId)
                    );
        }

        @Override
        public void startIntents(PendingIntent pendingIntent1, @Nullable ShortcutInfo shortcutInfo1,
                @Nullable Bundle options1, PendingIntent pendingIntent2,
                @Nullable ShortcutInfo shortcutInfo2, @Nullable Bundle options2,
                @SplitPosition int splitPosition, float splitRatio,
                @Nullable RemoteTransition remoteTransition, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startIntents",
                    (controller) ->
                            controller.startIntents(pendingIntent1, shortcutInfo1,
                                    options1, pendingIntent2, shortcutInfo2, options2,
                                    splitPosition, splitRatio, remoteTransition, instanceId)
            );
        }

        @Override
        public void startShortcut(String packageName, String shortcutId, int position,
                @Nullable Bundle options, UserHandle user, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startShortcut",
                    (controller) -> controller.startShortcut(packageName, shortcutId, position,
                            options, user, instanceId));
        }

        @Override
        public void startIntent(PendingIntent intent, Intent fillInIntent, int position,
                @Nullable Bundle options, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startIntent",
                    (controller) -> controller.startIntent(intent, fillInIntent, position, options,
                            instanceId));
        }

        @Override
        public RemoteAnimationTarget[] onGoingToRecentsLegacy(RemoteAnimationTarget[] apps) {
            final RemoteAnimationTarget[][] out = new RemoteAnimationTarget[][]{null};
            executeRemoteCallWithTaskPermission(mController, "onGoingToRecentsLegacy",
                    (controller) -> out[0] = controller.onGoingToRecentsLegacy(apps),
                    true /* blocking */);
            return out[0];
        }

        @Override
        public RemoteAnimationTarget[] onStartingSplitLegacy(RemoteAnimationTarget[] apps) {
            final RemoteAnimationTarget[][] out = new RemoteAnimationTarget[][]{null};
            executeRemoteCallWithTaskPermission(mController, "onStartingSplitLegacy",
                    (controller) -> out[0] = controller.onStartingSplitLegacy(apps),
                    true /* blocking */);
            return out[0];
        }
    }
}
