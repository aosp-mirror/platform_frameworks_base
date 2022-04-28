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
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.IRemoteAnimationFinishedCallback;
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

import com.android.internal.logging.InstanceId;
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
import com.android.wm.shell.draganddrop.DragAndDropPolicy;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreen.StageType;
import com.android.wm.shell.transition.LegacyTransitions;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Provider;

/**
 * Class manages split-screen multitasking mode and implements the main interface
 * {@link SplitScreen}.
 *
 * @see StageCoordinator
 */
// TODO(b/198577848): Implement split screen flicker test to consolidate CUJ of split screen.
public class SplitScreenController implements DragAndDropPolicy.Starter,
        RemoteCallable<SplitScreenController> {
    private static final String TAG = SplitScreenController.class.getSimpleName();

    static final int EXIT_REASON_UNKNOWN = 0;
    static final int EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW = 1;
    static final int EXIT_REASON_APP_FINISHED = 2;
    static final int EXIT_REASON_DEVICE_FOLDED = 3;
    static final int EXIT_REASON_DRAG_DIVIDER = 4;
    static final int EXIT_REASON_RETURN_HOME = 5;
    static final int EXIT_REASON_ROOT_TASK_VANISHED = 6;
    static final int EXIT_REASON_SCREEN_LOCKED = 7;
    static final int EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP = 8;
    static final int EXIT_REASON_CHILD_TASK_ENTER_PIP = 9;
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

    private final ShellTaskOrganizer mTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final Context mContext;
    private final RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    private final ShellExecutor mMainExecutor;
    private final SplitScreenImpl mImpl = new SplitScreenImpl();
    private final DisplayController mDisplayController;
    private final DisplayImeController mDisplayImeController;
    private final DisplayInsetsController mDisplayInsetsController;
    private final Transitions mTransitions;
    private final TransactionPool mTransactionPool;
    private final SplitscreenEventLogger mLogger;
    private final IconProvider mIconProvider;
    private final Optional<RecentTasksController> mRecentTasksOptional;
    private final Provider<Optional<StageTaskUnfoldController>> mUnfoldControllerProvider;

    private StageCoordinator mStageCoordinator;
    // Only used for the legacy recents animation from splitscreen to allow the tasks to be animated
    // outside the bounds of the roots by being reparented into a higher level fullscreen container
    private SurfaceControl mSplitTasksContainerLayer;

    public SplitScreenController(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer,
            ShellExecutor mainExecutor, DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            Transitions transitions, TransactionPool transactionPool, IconProvider iconProvider,
            Optional<RecentTasksController> recentTasks,
            Provider<Optional<StageTaskUnfoldController>> unfoldControllerProvider) {
        mTaskOrganizer = shellTaskOrganizer;
        mSyncQueue = syncQueue;
        mContext = context;
        mRootTDAOrganizer = rootTDAOrganizer;
        mMainExecutor = mainExecutor;
        mDisplayController = displayController;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mTransitions = transitions;
        mTransactionPool = transactionPool;
        mUnfoldControllerProvider = unfoldControllerProvider;
        mLogger = new SplitscreenEventLogger();
        mIconProvider = iconProvider;
        mRecentTasksOptional = recentTasks;
    }

    public SplitScreen asSplitScreen() {
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

    public void onOrganizerRegistered() {
        if (mStageCoordinator == null) {
            // TODO: Multi-display
            mStageCoordinator = new StageCoordinator(mContext, DEFAULT_DISPLAY, mSyncQueue,
                    mTaskOrganizer, mDisplayController, mDisplayImeController,
                    mDisplayInsetsController, mTransitions, mTransactionPool, mLogger,
                    mIconProvider, mMainExecutor, mRecentTasksOptional, mUnfoldControllerProvider);
        }
    }

    public boolean isSplitScreenVisible() {
        return mStageCoordinator.isSplitScreenVisible();
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

    public void onKeyguardVisibilityChanged(boolean showing) {
        mStageCoordinator.onKeyguardVisibilityChanged(showing);
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
        options = mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, position, options,
                null /* wct */);

        try {
            final WindowContainerTransaction evictWct = new WindowContainerTransaction();
            mStageCoordinator.prepareEvictChildTasks(position, evictWct);
            final int result =
                    ActivityTaskManager.getService().startActivityFromRecents(taskId, options);
            if (result == START_SUCCESS || result == START_TASK_TO_FRONT) {
                mSyncQueue.queue(evictWct);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to launch task", e);
        }
    }

    public void startShortcut(String packageName, String shortcutId, @SplitPosition int position,
            @Nullable Bundle options, UserHandle user) {
        options = mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, position, options,
                null /* wct */);
        final WindowContainerTransaction evictWct = new WindowContainerTransaction();
        mStageCoordinator.prepareEvictChildTasks(position, evictWct);

        try {
            LauncherApps launcherApps =
                    mContext.getSystemService(LauncherApps.class);
            launcherApps.startShortcut(packageName, shortcutId, null /* sourceBounds */,
                    options, user);
            mSyncQueue.queue(evictWct);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "Failed to launch shortcut", e);
        }
    }

    public void startIntent(PendingIntent intent, @Nullable Intent fillInIntent,
            @SplitPosition int position, @Nullable Bundle options) {
        if (!ENABLE_SHELL_TRANSITIONS) {
            startIntentLegacy(intent, fillInIntent, position, options);
            return;
        }

        try {
            options = mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, position, options,
                    null /* wct */);

            // Flag this as a no-user-action launch to prevent sending user leaving event to the
            // current top activity since it's going to be put into another side of the split. This
            // prevents the current top activity from going into pip mode due to user leaving event.
            if (fillInIntent == null) {
                fillInIntent = new Intent();
            }
            fillInIntent.addFlags(FLAG_ACTIVITY_NO_USER_ACTION);

            intent.send(mContext, 0, fillInIntent, null /* onFinished */, null /* handler */,
                    null /* requiredPermission */, options);
        } catch (PendingIntent.CanceledException e) {
            Slog.e(TAG, "Failed to launch task", e);
        }
    }

    private void startIntentLegacy(PendingIntent intent, @Nullable Intent fillInIntent,
            @SplitPosition int position, @Nullable Bundle options) {
        final WindowContainerTransaction evictWct = new WindowContainerTransaction();
        mStageCoordinator.prepareEvictChildTasks(position, evictWct);

        LegacyTransitions.ILegacyTransition transition = new LegacyTransitions.ILegacyTransition() {
            @Override
            public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                    IRemoteAnimationFinishedCallback finishedCallback,
                    SurfaceControl.Transaction t) {
                if (apps == null || apps.length == 0) {
                    final ActivityManager.RunningTaskInfo pairedTaskInfo =
                            getTaskInfo(SplitLayout.reversePosition(position));
                    final ComponentName pairedActivity =
                            pairedTaskInfo != null ? pairedTaskInfo.baseActivity : null;
                    final ComponentName intentActivity =
                            intent.getIntent() != null ? intent.getIntent().getComponent() : null;
                    if (pairedActivity != null && pairedActivity.equals(intentActivity)) {
                        // Switch split position if dragging the same activity to another side.
                        setSideStagePosition(SplitLayout.reversePosition(
                                mStageCoordinator.getSideStagePosition()));
                    }

                    // Do nothing when the animation was cancelled.
                    t.apply();
                    return;
                }

                mStageCoordinator.updateSurfaceBounds(null /* layout */, t,
                        false /* applyResizingOffset */);
                for (int i = 0; i < apps.length; ++i) {
                    if (apps[i].mode == MODE_OPENING) {
                        t.show(apps[i].leash);
                    }
                }
                t.apply();

                if (finishedCallback != null) {
                    try {
                        finishedCallback.onAnimationFinished();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error finishing legacy transition: ", e);
                    }
                }

                mSyncQueue.queue(evictWct);
            }
        };

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        options = mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, position, options, wct);

        // Flag this as a no-user-action launch to prevent sending user leaving event to the current
        // top activity since it's going to be put into another side of the split. This prevents the
        // current top activity from going into pip mode due to user leaving event.
        if (fillInIntent == null) {
            fillInIntent = new Intent();
        }
        fillInIntent.addFlags(FLAG_ACTIVITY_NO_USER_ACTION);

        wct.sendPendingIntent(intent, fillInIntent, options);
        mSyncQueue.queue(transition, WindowManager.TRANSIT_OPEN, wct);
    }

    RemoteAnimationTarget[] onGoingToRecentsLegacy(boolean cancel, RemoteAnimationTarget[] apps) {
        if (ENABLE_SHELL_TRANSITIONS || !isSplitScreenVisible()) return null;
        // TODO(b/206487881): Integrate this with shell transition.
        SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        if (mSplitTasksContainerLayer != null) {
            // Remove the previous layer before recreating
            transaction.remove(mSplitTasksContainerLayer);
        }
        final SurfaceControl.Builder builder = new SurfaceControl.Builder(new SurfaceSession())
                .setContainerLayer()
                .setName("RecentsAnimationSplitTasks")
                .setHidden(false)
                .setCallsite("SplitScreenController#onGoingtoRecentsLegacy");
        mRootTDAOrganizer.attachToDisplayArea(DEFAULT_DISPLAY, builder);
        mSplitTasksContainerLayer = builder.build();

        // Ensure that we order these in the parent in the right z-order as their previous order
        Arrays.sort(apps, (a1, a2) -> a1.prefixOrderIndex - a2.prefixOrderIndex);
        int layer = 1;
        for (RemoteAnimationTarget appTarget : apps) {
            transaction.reparent(appTarget.leash, mSplitTasksContainerLayer);
            transaction.setPosition(appTarget.leash, appTarget.screenSpaceBounds.left,
                    appTarget.screenSpaceBounds.top);
            transaction.setLayer(appTarget.leash, layer++);
        }
        transaction.apply();
        transaction.close();
        return new RemoteAnimationTarget[]{mStageCoordinator.getDividerBarLegacyTarget()};
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
        public void onKeyguardVisibilityChanged(boolean showing) {
            mMainExecutor.execute(() -> {
                SplitScreenController.this.onKeyguardVisibilityChanged(showing);
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
                float splitRatio, RemoteAnimationAdapter adapter) {
            executeRemoteCallWithTaskPermission(mController, "startTasks",
                    (controller) -> controller.mStageCoordinator.startTasksWithLegacyTransition(
                            mainTaskId, mainOptions, sideTaskId, sideOptions, sidePosition,
                            splitRatio, adapter));
        }

        @Override
        public void startIntentAndTaskWithLegacyTransition(PendingIntent pendingIntent,
                Intent fillInIntent, int taskId, Bundle mainOptions, Bundle sideOptions,
                int sidePosition, float splitRatio, RemoteAnimationAdapter adapter) {
            executeRemoteCallWithTaskPermission(mController,
                    "startIntentAndTaskWithLegacyTransition", (controller) ->
                            controller.mStageCoordinator.startIntentAndTaskWithLegacyTransition(
                                    pendingIntent, fillInIntent, taskId, mainOptions, sideOptions,
                                    sidePosition, splitRatio, adapter));
        }

        @Override
        public void startTasks(int mainTaskId, @Nullable Bundle mainOptions,
                int sideTaskId, @Nullable Bundle sideOptions,
                @SplitPosition int sidePosition, float splitRatio,
                @Nullable RemoteTransition remoteTransition) {
            executeRemoteCallWithTaskPermission(mController, "startTasks",
                    (controller) -> controller.mStageCoordinator.startTasks(mainTaskId, mainOptions,
                            sideTaskId, sideOptions, sidePosition, splitRatio, remoteTransition));
        }

        @Override
        public void startShortcut(String packageName, String shortcutId, int position,
                @Nullable Bundle options, UserHandle user) {
            executeRemoteCallWithTaskPermission(mController, "startShortcut",
                    (controller) -> {
                        controller.startShortcut(packageName, shortcutId, position, options, user);
                    });
        }

        @Override
        public void startIntent(PendingIntent intent, Intent fillInIntent, int position,
                @Nullable Bundle options) {
            executeRemoteCallWithTaskPermission(mController, "startIntent",
                    (controller) -> {
                        controller.startIntent(intent, fillInIntent, position, options);
                    });
        }

        @Override
        public RemoteAnimationTarget[] onGoingToRecentsLegacy(boolean cancel,
                RemoteAnimationTarget[] apps) {
            final RemoteAnimationTarget[][] out = new RemoteAnimationTarget[][]{null};
            executeRemoteCallWithTaskPermission(mController, "onGoingToRecentsLegacy",
                    (controller) -> out[0] = controller.onGoingToRecentsLegacy(cancel, apps),
                    true /* blocking */);
            return out[0];
        }
    }
}
