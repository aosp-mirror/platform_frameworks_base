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
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.RemoteAnimationTarget.MODE_OPENING;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_SIDE;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
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
import android.window.RemoteTransition;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.protolog.common.ProtoLog;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SingleInstanceRemoteListener;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.common.split.SplitLayout;
import com.android.wm.shell.common.split.SplitScreenConstants.SplitPosition;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.draganddrop.DragAndDropPolicy;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreen.StageType;
import com.android.wm.shell.sysui.KeyguardChangeListener;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
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
    private final DragAndDropController mDragAndDropController;
    private final Transitions mTransitions;
    private final TransactionPool mTransactionPool;
    private final IconProvider mIconProvider;
    private final Optional<RecentTasksController> mRecentTasksOptional;
    private final SplitScreenShellCommandHandler mSplitScreenShellCommandHandler;

    private StageCoordinator mStageCoordinator;
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
            DragAndDropController dragAndDropController,
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
    }

    public SplitScreen asSplitScreen() {
        return mImpl;
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
        if (mStageCoordinator == null) {
            // TODO: Multi-display
            mStageCoordinator = createStageCoordinator();
        }
        mDragAndDropController.setSplitScreenController(this);
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

    public ActivityManager.RunningTaskInfo getFocusingTaskInfo() {
        return mStageCoordinator.getFocusingTaskInfo();
    }

    public boolean isValidToEnterSplitScreen(@NonNull ActivityManager.RunningTaskInfo taskInfo) {
        return mStageCoordinator.isValidToEnterSplitScreen(taskInfo);
    }

    @Nullable
    public ActivityManager.RunningTaskInfo getTaskInfo(@SplitPosition int splitPosition) {
        if (!isSplitScreenVisible() || splitPosition == SPLIT_POSITION_UNDEFINED) {
            return null;
        }

        final int taskId = mStageCoordinator.getTaskId(splitPosition);
        return mTaskOrganizer.getRunningTaskInfo(taskId);
    }

    public boolean isTaskInSplitScreen(int taskId) {
        return isSplitScreenVisible()
                && mStageCoordinator.getStageOfTask(taskId) != STAGE_TYPE_UNDEFINED;
    }

    public @SplitPosition int getSplitPosition(int taskId) {
        return mStageCoordinator.getSplitPosition(taskId);
    }

    public boolean moveToSideStage(int taskId, @SplitPosition int sideStagePosition) {
        return moveToStage(taskId, STAGE_TYPE_SIDE, sideStagePosition,
                new WindowContainerTransaction());
    }

    /**
     * Update surfaces of the split screen layout based on the current state
     * @param transaction to write the updates to
     */
    public void updateSplitScreenSurfaces(SurfaceControl.Transaction transaction) {
        mStageCoordinator.updateSurfaces(transaction);
    }

    private boolean moveToStage(int taskId, @StageType int stageType,
            @SplitPosition int stagePosition, WindowContainerTransaction wct) {
        final ActivityManager.RunningTaskInfo task = mTaskOrganizer.getRunningTaskInfo(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown taskId" + taskId);
        }
        return mStageCoordinator.moveToStage(task, stageType, stagePosition, wct);
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
        final int stageType = isSplitScreenVisible() ? STAGE_TYPE_UNDEFINED : STAGE_TYPE_SIDE;
        final int stagePosition =
                leftOrTop ? SPLIT_POSITION_TOP_OR_LEFT : SPLIT_POSITION_BOTTOM_OR_RIGHT;
        moveToStage(taskId, stageType, stagePosition, wct);
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
            public void onAnimationCancelled(boolean isKeyguardOccluded) {
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
        mStageCoordinator.getLogger().enterRequested(instanceId, ENTER_REASON_LAUNCHER);
        startShortcut(packageName, shortcutId, position, options, user);
    }

    @Override
    public void startShortcut(String packageName, String shortcutId, @SplitPosition int position,
            @Nullable Bundle options, UserHandle user) {
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
                final WindowContainerTransaction evictWct = new WindowContainerTransaction();
                mStageCoordinator.prepareEvictNonOpeningChildTasks(position, apps, evictWct);
                mSyncQueue.queue(evictWct);
            }
            @Override
            public void onAnimationCancelled(boolean isKeyguardOccluded) {
            }
        };
        options = mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, position, options,
                null /* wct */);
        RemoteAnimationAdapter wrappedAdapter = new RemoteAnimationAdapter(wrapper,
                0 /* duration */, 0 /* statusBarTransitionDelay */);
        ActivityOptions activityOptions = ActivityOptions.fromBundle(options);
        activityOptions.update(ActivityOptions.makeRemoteAnimation(wrappedAdapter));
        try {
            LauncherApps launcherApps = mContext.getSystemService(LauncherApps.class);
            launcherApps.startShortcut(packageName, shortcutId, null /* sourceBounds */,
                    activityOptions.toBundle(), user);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "Failed to launch shortcut", e);
        }
    }

    /**
     * See {@link #startIntent(PendingIntent, Intent, int, Bundle)}
     * @param instanceId to be used by {@link SplitscreenEventLogger}
     */
    public void startIntent(PendingIntent intent, @Nullable Intent fillInIntent,
            @SplitPosition int position, @Nullable Bundle options, @NonNull InstanceId instanceId) {
        mStageCoordinator.getLogger().enterRequested(instanceId, ENTER_REASON_LAUNCHER);
        startIntent(intent, fillInIntent, position, options);
    }

    @Override
    public void startIntent(PendingIntent intent, @Nullable Intent fillInIntent,
            @SplitPosition int position, @Nullable Bundle options) {
        if (fillInIntent == null) {
            fillInIntent = new Intent();
        }
        // Flag this as a no-user-action launch to prevent sending user leaving event to the
        // current top activity since it's going to be put into another side of the split. This
        // prevents the current top activity from going into pip mode due to user leaving event.
        fillInIntent.addFlags(FLAG_ACTIVITY_NO_USER_ACTION);

        // Flag with MULTIPLE_TASK if this is launching the same activity into both sides of the
        // split and there is no reusable background task.
        if (shouldAddMultipleTaskFlag(intent.getIntent(), position)) {
            final ActivityManager.RecentTaskInfo taskInfo = mRecentTasksOptional.isPresent()
                    ? mRecentTasksOptional.get().findTaskInBackground(
                            intent.getIntent().getComponent())
                    : null;
            if (taskInfo != null) {
                startTask(taskInfo.taskId, position, options);
                return;
            }
            fillInIntent.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN, "Adding MULTIPLE_TASK");
        }

        if (!ENABLE_SHELL_TRANSITIONS) {
            mStageCoordinator.startIntentLegacy(intent, fillInIntent, position, options);
            return;
        }
        mStageCoordinator.startIntent(intent, fillInIntent, position, options);
    }

    /** Returns {@code true} if it's launching the same component on both sides of the split. */
    @VisibleForTesting
    boolean shouldAddMultipleTaskFlag(@Nullable Intent startIntent, @SplitPosition int position) {
        if (startIntent == null) {
            return false;
        }

        final ComponentName launchingActivity = startIntent.getComponent();
        if (launchingActivity == null) {
            return false;
        }

        if (isSplitScreenVisible()) {
            // To prevent users from constantly dropping the same app to the same side resulting in
            // a large number of instances in the background.
            final ActivityManager.RunningTaskInfo targetTaskInfo = getTaskInfo(position);
            final ComponentName targetActivity = targetTaskInfo != null
                    ? targetTaskInfo.baseIntent.getComponent() : null;
            if (Objects.equals(launchingActivity, targetActivity)) {
                return false;
            }

            // Allow users to start a new instance the same to adjacent side.
            final ActivityManager.RunningTaskInfo pairedTaskInfo =
                    getTaskInfo(SplitLayout.reversePosition(position));
            final ComponentName pairedActivity = pairedTaskInfo != null
                    ? pairedTaskInfo.baseIntent.getComponent() : null;
            return Objects.equals(launchingActivity, pairedActivity);
        }

        final ActivityManager.RunningTaskInfo taskInfo = getFocusingTaskInfo();
        if (taskInfo != null && isValidToEnterSplitScreen(taskInfo)) {
            return Objects.equals(taskInfo.baseIntent.getComponent(), launchingActivity);
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
     * Sets drag info to be logged when splitscreen is entered.
     */
    public void logOnDroppedToSplit(@SplitPosition int position, InstanceId dragSessionId) {
        mStageCoordinator.logOnDroppedToSplit(position, dragSessionId);
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
        private ISplitScreenImpl mISplitScreen;
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
        public ISplitScreen createExternalInterface() {
            if (mISplitScreen != null) {
                mISplitScreen.invalidate();
            }
            mISplitScreen = new ISplitScreenImpl(SplitScreenController.this);
            return mISplitScreen;
        }

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
            mMainExecutor.execute(() -> {
                SplitScreenController.this.onFinishedWakingUp();
            });
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class ISplitScreenImpl extends ISplitScreen.Stub {
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
        void invalidate() {
            mController = null;
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
                    (controller) -> {
                        controller.exitSplitScreen(toTopTaskId, EXIT_REASON_UNKNOWN);
                    });
        }

        @Override
        public void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) {
            executeRemoteCallWithTaskPermission(mController, "exitSplitScreenOnHide",
                    (controller) -> {
                        controller.exitSplitScreenOnHide(exitSplitScreenOnHide);
                    });
        }

        @Override
        public void removeFromSideStage(int taskId) {
            executeRemoteCallWithTaskPermission(mController, "removeFromSideStage",
                    (controller) -> {
                        controller.removeFromSideStage(taskId);
                    });
        }

        @Override
        public void startTask(int taskId, int position, @Nullable Bundle options) {
            executeRemoteCallWithTaskPermission(mController, "startTask",
                    (controller) -> {
                        controller.startTask(taskId, position, options);
                    });
        }

        @Override
        public void startTasksWithLegacyTransition(int mainTaskId, @Nullable Bundle mainOptions,
                int sideTaskId, @Nullable Bundle sideOptions, @SplitPosition int sidePosition,
                float splitRatio, RemoteAnimationAdapter adapter, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startTasks",
                    (controller) -> controller.mStageCoordinator.startTasksWithLegacyTransition(
                            mainTaskId, mainOptions, sideTaskId, sideOptions, sidePosition,
                            splitRatio, adapter, instanceId));
        }

        @Override
        public void startIntentAndTaskWithLegacyTransition(PendingIntent pendingIntent,
                Intent fillInIntent, int taskId, Bundle mainOptions, Bundle sideOptions,
                int sidePosition, float splitRatio, RemoteAnimationAdapter adapter,
                InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController,
                    "startIntentAndTaskWithLegacyTransition", (controller) ->
                            controller.mStageCoordinator.startIntentAndTaskWithLegacyTransition(
                                    pendingIntent, fillInIntent, taskId, mainOptions, sideOptions,
                                    sidePosition, splitRatio, adapter, instanceId));
        }

        @Override
        public void startShortcutAndTaskWithLegacyTransition(ShortcutInfo shortcutInfo,
                int taskId, @Nullable Bundle mainOptions, @Nullable Bundle sideOptions,
                @SplitPosition int sidePosition, float splitRatio, RemoteAnimationAdapter adapter,
                InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController,
                    "startShortcutAndTaskWithLegacyTransition", (controller) ->
                            controller.mStageCoordinator.startShortcutAndTaskWithLegacyTransition(
                                    shortcutInfo, taskId, mainOptions, sideOptions, sidePosition,
                                    splitRatio, adapter, instanceId));
        }

        @Override
        public void startTasks(int mainTaskId, @Nullable Bundle mainOptions,
                int sideTaskId, @Nullable Bundle sideOptions,
                @SplitPosition int sidePosition, float splitRatio,
                @Nullable RemoteTransition remoteTransition, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startTasks",
                    (controller) -> controller.mStageCoordinator.startTasks(mainTaskId, mainOptions,
                            sideTaskId, sideOptions, sidePosition, splitRatio, remoteTransition,
                            instanceId));
        }

        @Override
        public void startShortcut(String packageName, String shortcutId, int position,
                @Nullable Bundle options, UserHandle user, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startShortcut",
                    (controller) -> {
                        controller.startShortcut(packageName, shortcutId, position, options, user,
                                instanceId);
                    });
        }

        @Override
        public void startIntent(PendingIntent intent, Intent fillInIntent, int position,
                @Nullable Bundle options, InstanceId instanceId) {
            executeRemoteCallWithTaskPermission(mController, "startIntent",
                    (controller) -> {
                        controller.startIntent(intent, fillInIntent, position, options, instanceId);
                    });
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
