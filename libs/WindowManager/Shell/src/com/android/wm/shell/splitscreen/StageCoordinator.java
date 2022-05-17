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

import static android.app.ActivityOptions.KEY_LAUNCH_ROOT_TASK_TOKEN;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;

import static com.android.wm.shell.common.split.SplitLayout.PARALLAX_ALIGN_CENTER;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_MAIN;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_SIDE;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_APP_FINISHED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_CHILD_TASK_ENTER_PIP;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DEVICE_FOLDED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DRAG_DIVIDER;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_RETURN_HOME;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_ROOT_TASK_VANISHED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_UNKNOWN;
import static com.android.wm.shell.splitscreen.SplitScreenController.exitReasonToString;
import static com.android.wm.shell.splitscreen.SplitScreenTransitions.FLAG_IS_DIVIDER_BAR;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_SCREEN_PAIR_OPEN;
import static com.android.wm.shell.transition.Transitions.isClosingType;
import static com.android.wm.shell.transition.Transitions.isOpeningType;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.protolog.common.ProtoLog;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.split.SplitLayout;
import com.android.wm.shell.common.split.SplitScreenConstants.SplitPosition;
import com.android.wm.shell.common.split.SplitWindowManager;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreen.StageType;
import com.android.wm.shell.splitscreen.SplitScreenController.ExitReason;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.util.StagedSplitBounds;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.inject.Provider;

/**
 * Coordinates the staging (visibility, sizing, ...) of the split-screen {@link MainStage} and
 * {@link SideStage} stages.
 * Some high-level rules:
 * - The {@link StageCoordinator} is only considered active if the {@link SideStage} contains at
 * least one child task.
 * - The {@link MainStage} should only have children if the coordinator is active.
 * - The {@link SplitLayout} divider is only visible if both the {@link MainStage}
 * and {@link SideStage} are visible.
 * - Both stages are put under a single-top root task.
 * This rules are mostly implemented in {@link #onStageVisibilityChanged(StageListenerImpl)} and
 * {@link #onStageHasChildrenChanged(StageListenerImpl).}
 */
class StageCoordinator implements SplitLayout.SplitLayoutHandler,
        DisplayController.OnDisplaysChangedListener, Transitions.TransitionHandler,
        ShellTaskOrganizer.TaskListener {

    private static final String TAG = StageCoordinator.class.getSimpleName();

    private final SurfaceSession mSurfaceSession = new SurfaceSession();

    private final MainStage mMainStage;
    private final StageListenerImpl mMainStageListener = new StageListenerImpl();
    private final StageTaskUnfoldController mMainUnfoldController;
    private final SideStage mSideStage;
    private final StageListenerImpl mSideStageListener = new StageListenerImpl();
    private final StageTaskUnfoldController mSideUnfoldController;
    private final DisplayLayout mDisplayLayout;
    @SplitPosition
    private int mSideStagePosition = SPLIT_POSITION_BOTTOM_OR_RIGHT;

    private final int mDisplayId;
    private SplitLayout mSplitLayout;
    private ValueAnimator mDividerFadeInAnimator;
    private boolean mDividerVisible;
    private boolean mKeyguardShowing;
    private final SyncTransactionQueue mSyncQueue;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Context mContext;
    private final List<SplitScreen.SplitScreenListener> mListeners = new ArrayList<>();
    private final DisplayController mDisplayController;
    private final DisplayImeController mDisplayImeController;
    private final DisplayInsetsController mDisplayInsetsController;
    private final TransactionPool mTransactionPool;
    private final SplitScreenTransitions mSplitTransitions;
    private final SplitscreenEventLogger mLogger;
    private final ShellExecutor mMainExecutor;
    private final Optional<RecentTasksController> mRecentTasks;

    /**
     * A single-top root task which the split divider attached to.
     */
    @VisibleForTesting
    ActivityManager.RunningTaskInfo mRootTaskInfo;

    private SurfaceControl mRootTaskLeash;

    // Tracks whether we should update the recent tasks.  Only allow this to happen in between enter
    // and exit, since exit itself can trigger a number of changes that update the stages.
    private boolean mShouldUpdateRecents;
    private boolean mExitSplitScreenOnHide;
    private boolean mIsDividerRemoteAnimating;
    private boolean mResizingSplits;

    /** The target stage to dismiss to when unlock after folded. */
    @StageType
    private int mTopStageAfterFoldDismiss = STAGE_TYPE_UNDEFINED;

    private final SplitWindowManager.ParentContainerCallbacks mParentContainerCallbacks =
            new SplitWindowManager.ParentContainerCallbacks() {
                @Override
                public void attachToParentSurface(SurfaceControl.Builder b) {
                    b.setParent(mRootTaskLeash);
                }

                @Override
                public void onLeashReady(SurfaceControl leash) {
                    mSyncQueue.runInSync(t -> applyDividerVisibility(t));
                }
            };

    StageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
            ShellTaskOrganizer taskOrganizer, DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController, Transitions transitions,
            TransactionPool transactionPool, SplitscreenEventLogger logger,
            IconProvider iconProvider, ShellExecutor mainExecutor,
            Optional<RecentTasksController> recentTasks,
            Provider<Optional<StageTaskUnfoldController>> unfoldControllerProvider) {
        mContext = context;
        mDisplayId = displayId;
        mSyncQueue = syncQueue;
        mTaskOrganizer = taskOrganizer;
        mLogger = logger;
        mMainExecutor = mainExecutor;
        mRecentTasks = recentTasks;
        mMainUnfoldController = unfoldControllerProvider.get().orElse(null);
        mSideUnfoldController = unfoldControllerProvider.get().orElse(null);
        taskOrganizer.createRootTask(displayId, WINDOWING_MODE_FULLSCREEN, this /* listener */);

        mMainStage = new MainStage(
                mContext,
                mTaskOrganizer,
                mDisplayId,
                mMainStageListener,
                mSyncQueue,
                mSurfaceSession,
                iconProvider,
                mMainUnfoldController);
        mSideStage = new SideStage(
                mContext,
                mTaskOrganizer,
                mDisplayId,
                mSideStageListener,
                mSyncQueue,
                mSurfaceSession,
                iconProvider,
                mSideUnfoldController);
        mDisplayController = displayController;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mTransactionPool = transactionPool;
        final DeviceStateManager deviceStateManager =
                mContext.getSystemService(DeviceStateManager.class);
        deviceStateManager.registerCallback(taskOrganizer.getExecutor(),
                new DeviceStateManager.FoldStateListener(mContext, this::onFoldedStateChanged));
        mSplitTransitions = new SplitScreenTransitions(transactionPool, transitions,
                this::onTransitionAnimationComplete, this);
        mDisplayController.addDisplayWindowListener(this);
        mDisplayLayout = new DisplayLayout(displayController.getDisplayLayout(displayId));
        transitions.addHandler(this);
    }

    @VisibleForTesting
    StageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
            ShellTaskOrganizer taskOrganizer, MainStage mainStage, SideStage sideStage,
            DisplayController displayController, DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController, SplitLayout splitLayout,
            Transitions transitions, TransactionPool transactionPool,
            SplitscreenEventLogger logger, ShellExecutor mainExecutor,
            Optional<RecentTasksController> recentTasks,
            Provider<Optional<StageTaskUnfoldController>> unfoldControllerProvider) {
        mContext = context;
        mDisplayId = displayId;
        mSyncQueue = syncQueue;
        mTaskOrganizer = taskOrganizer;
        mMainStage = mainStage;
        mSideStage = sideStage;
        mDisplayController = displayController;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mTransactionPool = transactionPool;
        mSplitLayout = splitLayout;
        mSplitTransitions = new SplitScreenTransitions(transactionPool, transitions,
                this::onTransitionAnimationComplete, this);
        mMainUnfoldController = unfoldControllerProvider.get().orElse(null);
        mSideUnfoldController = unfoldControllerProvider.get().orElse(null);
        mLogger = logger;
        mMainExecutor = mainExecutor;
        mRecentTasks = recentTasks;
        mDisplayController.addDisplayWindowListener(this);
        mDisplayLayout = new DisplayLayout();
        transitions.addHandler(this);
    }

    @VisibleForTesting
    SplitScreenTransitions getSplitTransitions() {
        return mSplitTransitions;
    }

    boolean isSplitScreenVisible() {
        return mSideStageListener.mVisible && mMainStageListener.mVisible;
    }

    @StageType
    int getStageOfTask(int taskId) {
        if (mMainStage.containsTask(taskId)) {
            return STAGE_TYPE_MAIN;
        } else if (mSideStage.containsTask(taskId)) {
            return STAGE_TYPE_SIDE;
        }

        return STAGE_TYPE_UNDEFINED;
    }

    boolean moveToStage(ActivityManager.RunningTaskInfo task, @StageType int stageType,
            @SplitPosition int stagePosition, WindowContainerTransaction wct) {
        StageTaskListener targetStage;
        int sideStagePosition;
        if (stageType == STAGE_TYPE_MAIN) {
            targetStage = mMainStage;
            sideStagePosition = SplitLayout.reversePosition(stagePosition);
        } else if (stageType == STAGE_TYPE_SIDE) {
            targetStage = mSideStage;
            sideStagePosition = stagePosition;
        } else {
            if (mMainStage.isActive()) {
                // If the split screen is activated, retrieves target stage based on position.
                targetStage = stagePosition == mSideStagePosition ? mSideStage : mMainStage;
                sideStagePosition = mSideStagePosition;
            } else {
                targetStage = mSideStage;
                sideStagePosition = stagePosition;
            }
        }

        setSideStagePosition(sideStagePosition, wct);
        final WindowContainerTransaction evictWct = new WindowContainerTransaction();
        targetStage.evictAllChildren(evictWct);
        targetStage.addTask(task, wct);
        if (!evictWct.isEmpty()) {
            wct.merge(evictWct, true /* transfer */);
        }

        if (ENABLE_SHELL_TRANSITIONS) {
            prepareEnterSplitScreen(wct);
            mSplitTransitions.startEnterTransition(TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE,
                    wct, null, this);
        } else {
            mTaskOrganizer.applyTransaction(wct);
        }
        return true;
    }

    boolean removeFromSideStage(int taskId) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        /**
         * {@link MainStage} will be deactivated in {@link #onStageHasChildrenChanged} if the
         * {@link SideStage} no longer has children.
         */
        final boolean result = mSideStage.removeTask(taskId,
                mMainStage.isActive() ? mMainStage.mRootTaskInfo.token : null,
                wct);
        mTaskOrganizer.applyTransaction(wct);
        return result;
    }

    /** Starts 2 tasks in one transition. */
    void startTasks(int mainTaskId, @Nullable Bundle mainOptions, int sideTaskId,
            @Nullable Bundle sideOptions, @SplitPosition int sidePosition, float splitRatio,
            @Nullable RemoteTransition remoteTransition) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mainOptions = mainOptions != null ? mainOptions : new Bundle();
        sideOptions = sideOptions != null ? sideOptions : new Bundle();
        setSideStagePosition(sidePosition, wct);

        mSplitLayout.setDivideRatio(splitRatio);
        // Build a request WCT that will launch both apps such that task 0 is on the main stage
        // while task 1 is on the side stage.
        mMainStage.activate(wct, false /* reparent */);
        updateWindowBounds(mSplitLayout, wct);
        wct.reorder(mRootTaskInfo.token, true);

        // Make sure the launch options will put tasks in the corresponding split roots
        addActivityOptions(mainOptions, mMainStage);
        addActivityOptions(sideOptions, mSideStage);

        // Add task launch requests
        wct.startTask(mainTaskId, mainOptions);
        wct.startTask(sideTaskId, sideOptions);

        mSplitTransitions.startEnterTransition(
                TRANSIT_SPLIT_SCREEN_PAIR_OPEN, wct, remoteTransition, this);
    }

    /** Starts 2 tasks in one legacy transition. */
    void startTasksWithLegacyTransition(int mainTaskId, @Nullable Bundle mainOptions,
            int sideTaskId, @Nullable Bundle sideOptions, @SplitPosition int sidePosition,
            float splitRatio, RemoteAnimationAdapter adapter) {
        startWithLegacyTransition(mainTaskId, sideTaskId, null /* pendingIntent */,
                null /* fillInIntent */, mainOptions, sideOptions, sidePosition, splitRatio,
                adapter);
    }

    /** Start an intent and a task ordered by {@code intentFirst}. */
    void startIntentAndTaskWithLegacyTransition(PendingIntent pendingIntent, Intent fillInIntent,
            int taskId, @Nullable Bundle mainOptions, @Nullable Bundle sideOptions,
            @SplitPosition int sidePosition, float splitRatio, RemoteAnimationAdapter adapter) {
        startWithLegacyTransition(taskId, INVALID_TASK_ID, pendingIntent, fillInIntent,
                mainOptions, sideOptions, sidePosition, splitRatio, adapter);
    }

    private void startWithLegacyTransition(int mainTaskId, int sideTaskId,
            @Nullable PendingIntent pendingIntent, @Nullable Intent fillInIntent,
            @Nullable Bundle mainOptions, @Nullable Bundle sideOptions,
            @SplitPosition int sidePosition, float splitRatio, RemoteAnimationAdapter adapter) {
        final boolean withIntent = pendingIntent != null && fillInIntent != null;
        // Init divider first to make divider leash for remote animation target.
        mSplitLayout.init();
        // Set false to avoid record new bounds with old task still on top;
        mShouldUpdateRecents = false;
        mIsDividerRemoteAnimating = true;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final WindowContainerTransaction evictWct = new WindowContainerTransaction();
        prepareEvictChildTasks(SPLIT_POSITION_TOP_OR_LEFT, evictWct);
        prepareEvictChildTasks(SPLIT_POSITION_BOTTOM_OR_RIGHT, evictWct);
        // Need to add another wrapper here in shell so that we can inject the divider bar
        // and also manage the process elevation via setRunningRemote
        IRemoteAnimationRunner wrapper = new IRemoteAnimationRunner.Stub() {
            @Override
            public void onAnimationStart(@WindowManager.TransitionOldType int transit,
                    RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers,
                    RemoteAnimationTarget[] nonApps,
                    final IRemoteAnimationFinishedCallback finishedCallback) {
                RemoteAnimationTarget[] augmentedNonApps =
                        new RemoteAnimationTarget[nonApps.length + 1];
                for (int i = 0; i < nonApps.length; ++i) {
                    augmentedNonApps[i] = nonApps[i];
                }
                augmentedNonApps[augmentedNonApps.length - 1] = getDividerBarLegacyTarget();

                IRemoteAnimationFinishedCallback wrapCallback =
                        new IRemoteAnimationFinishedCallback.Stub() {
                            @Override
                            public void onAnimationFinished() throws RemoteException {
                                onRemoteAnimationFinishedOrCancelled(evictWct);
                                finishedCallback.onAnimationFinished();
                            }
                        };
                try {
                    try {
                        ActivityTaskManager.getService().setRunningRemoteTransitionDelegate(
                                adapter.getCallingApplication());
                    } catch (SecurityException e) {
                        Slog.e(TAG, "Unable to boost animation thread. This should only happen"
                                + " during unit tests");
                    }
                    adapter.getRunner().onAnimationStart(transit, apps, wallpapers,
                            augmentedNonApps, wrapCallback);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error starting remote animation", e);
                }
            }

            @Override
            public void onAnimationCancelled() {
                onRemoteAnimationFinishedOrCancelled(evictWct);
                try {
                    adapter.getRunner().onAnimationCancelled();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error starting remote animation", e);
                }
            }
        };
        RemoteAnimationAdapter wrappedAdapter = new RemoteAnimationAdapter(
                wrapper, adapter.getDuration(), adapter.getStatusBarTransitionDelay());

        if (mainOptions == null) {
            mainOptions = ActivityOptions.makeRemoteAnimation(wrappedAdapter).toBundle();
        } else {
            ActivityOptions mainActivityOptions = ActivityOptions.fromBundle(mainOptions);
            mainActivityOptions.update(ActivityOptions.makeRemoteAnimation(wrappedAdapter));
            mainOptions = mainActivityOptions.toBundle();
        }

        sideOptions = sideOptions != null ? sideOptions : new Bundle();
        setSideStagePosition(sidePosition, wct);

        mSplitLayout.setDivideRatio(splitRatio);
        if (!mMainStage.isActive()) {
            // Build a request WCT that will launch both apps such that task 0 is on the main stage
            // while task 1 is on the side stage.
            mMainStage.activate(wct, false /* reparent */);
        }
        updateWindowBounds(mSplitLayout, wct);
        wct.reorder(mRootTaskInfo.token, true);

        // Make sure the launch options will put tasks in the corresponding split roots
        addActivityOptions(mainOptions, mMainStage);
        addActivityOptions(sideOptions, mSideStage);

        // Add task launch requests
        wct.startTask(mainTaskId, mainOptions);
        if (withIntent) {
            wct.sendPendingIntent(pendingIntent, fillInIntent, sideOptions);
        } else {
            wct.startTask(sideTaskId, sideOptions);
        }
        // Using legacy transitions, so we can't use blast sync since it conflicts.
        mTaskOrganizer.applyTransaction(wct);
        mSyncQueue.runInSync(t -> {
            setDividerVisibility(true, t);
            updateSurfaceBounds(mSplitLayout, t, false /* applyResizingOffset */);
        });
    }

    private void onRemoteAnimationFinishedOrCancelled(WindowContainerTransaction evictWct) {
        mIsDividerRemoteAnimating = false;
        mShouldUpdateRecents = true;
        // If any stage has no child after animation finished, it means that split will display
        // nothing, such status will happen if task and intent is same app but not support
        // multi-instagce, we should exit split and expand that app as full screen.
        if (mMainStage.getChildCount() == 0 || mSideStage.getChildCount() == 0) {
            mMainExecutor.execute(() ->
                    exitSplitScreen(mMainStage.getChildCount() == 0
                        ? mSideStage : mMainStage, EXIT_REASON_UNKNOWN));
        } else {
            mSyncQueue.queue(evictWct);
        }
    }

    /**
     * Collects all the current child tasks of a specific split and prepares transaction to evict
     * them to display.
     */
    void prepareEvictChildTasks(@SplitPosition int position, WindowContainerTransaction wct) {
        if (position == mSideStagePosition) {
            mSideStage.evictAllChildren(wct);
        } else {
            mMainStage.evictAllChildren(wct);
        }
    }

    void prepareEvictInvisibleChildTasks(WindowContainerTransaction wct) {
        mMainStage.evictInvisibleChildren(wct);
        mSideStage.evictInvisibleChildren(wct);
    }

    Bundle resolveStartStage(@StageType int stage,
            @SplitPosition int position, @androidx.annotation.Nullable Bundle options,
            @androidx.annotation.Nullable WindowContainerTransaction wct) {
        switch (stage) {
            case STAGE_TYPE_UNDEFINED: {
                if (position != SPLIT_POSITION_UNDEFINED) {
                    if (mMainStage.isActive()) {
                        // Use the stage of the specified position
                        options = resolveStartStage(
                                position == mSideStagePosition ? STAGE_TYPE_SIDE : STAGE_TYPE_MAIN,
                                position, options, wct);
                    } else {
                        // Use the side stage as default to active split screen
                        options = resolveStartStage(STAGE_TYPE_SIDE, position, options, wct);
                    }
                } else {
                    Slog.w(TAG,
                            "No stage type nor split position specified to resolve start stage");
                }
                break;
            }
            case STAGE_TYPE_SIDE: {
                if (position != SPLIT_POSITION_UNDEFINED) {
                    setSideStagePosition(position, wct);
                } else {
                    position = getSideStagePosition();
                }
                if (options == null) {
                    options = new Bundle();
                }
                updateActivityOptions(options, position);
                break;
            }
            case STAGE_TYPE_MAIN: {
                if (position != SPLIT_POSITION_UNDEFINED) {
                    // Set the side stage opposite of what we want to the main stage.
                    setSideStagePosition(SplitLayout.reversePosition(position), wct);
                } else {
                    position = getMainStagePosition();
                }
                if (options == null) {
                    options = new Bundle();
                }
                updateActivityOptions(options, position);
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown stage=" + stage);
        }

        return options;
    }

    @SplitPosition
    int getSideStagePosition() {
        return mSideStagePosition;
    }

    @SplitPosition
    int getMainStagePosition() {
        return SplitLayout.reversePosition(mSideStagePosition);
    }

    int getTaskId(@SplitPosition int splitPosition) {
        if (mSideStagePosition == splitPosition) {
            return mSideStage.getTopVisibleChildTaskId();
        } else {
            return mMainStage.getTopVisibleChildTaskId();
        }
    }

    void setSideStagePosition(@SplitPosition int sideStagePosition,
            @Nullable WindowContainerTransaction wct) {
        setSideStagePosition(sideStagePosition, true /* updateBounds */, wct);
    }

    private void setSideStagePosition(@SplitPosition int sideStagePosition, boolean updateBounds,
            @Nullable WindowContainerTransaction wct) {
        if (mSideStagePosition == sideStagePosition) return;
        mSideStagePosition = sideStagePosition;
        sendOnStagePositionChanged();

        if (mSideStageListener.mVisible && updateBounds) {
            if (wct == null) {
                // onLayoutChanged builds/applies a wct with the contents of updateWindowBounds.
                onLayoutSizeChanged(mSplitLayout);
            } else {
                updateWindowBounds(mSplitLayout, wct);
                updateUnfoldBounds();
            }
        }
    }

    void onKeyguardVisibilityChanged(boolean showing) {
        mKeyguardShowing = showing;
        if (!mMainStage.isActive()) {
            return;
        }

        if (!mKeyguardShowing && mTopStageAfterFoldDismiss != STAGE_TYPE_UNDEFINED) {
            if (ENABLE_SHELL_TRANSITIONS) {
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                prepareExitSplitScreen(mTopStageAfterFoldDismiss, wct);
                mSplitTransitions.startDismissTransition(null /* transition */, wct, this,
                        mTopStageAfterFoldDismiss, EXIT_REASON_DEVICE_FOLDED);
            } else {
                exitSplitScreen(
                        mTopStageAfterFoldDismiss == STAGE_TYPE_MAIN ? mMainStage : mSideStage,
                        EXIT_REASON_DEVICE_FOLDED);
            }
            return;
        }

        setDividerVisibility(!mKeyguardShowing, null);
    }

    void onFinishedWakingUp() {
        if (!mMainStage.isActive()) {
            return;
        }

        // Check if there's only one stage visible while keyguard occluded.
        final boolean mainStageVisible = mMainStage.mRootTaskInfo.isVisible;
        final boolean oneStageVisible =
                mMainStage.mRootTaskInfo.isVisible != mSideStage.mRootTaskInfo.isVisible;
        if (oneStageVisible) {
            // Dismiss split because there's show-when-locked activity showing on top of keyguard.
            // Also make sure the task contains show-when-locked activity remains on top after split
            // dismissed.
            if (!ENABLE_SHELL_TRANSITIONS) {
                final StageTaskListener toTop = mainStageVisible ? mMainStage : mSideStage;
                exitSplitScreen(toTop, EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP);
            } else {
                final int dismissTop = mainStageVisible ? STAGE_TYPE_MAIN : STAGE_TYPE_SIDE;
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                prepareExitSplitScreen(dismissTop, wct);
                mSplitTransitions.startDismissTransition(null /* transition */, wct, this,
                        dismissTop, EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP);
            }
        }
    }

    void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) {
        mExitSplitScreenOnHide = exitSplitScreenOnHide;
    }

    void exitSplitScreen(int toTopTaskId, @ExitReason int exitReason) {
        if (!mMainStage.isActive()) return;

        StageTaskListener childrenToTop = null;
        if (mMainStage.containsTask(toTopTaskId)) {
            childrenToTop = mMainStage;
        } else if (mSideStage.containsTask(toTopTaskId)) {
            childrenToTop = mSideStage;
        }

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        if (childrenToTop != null) {
            childrenToTop.reorderChild(toTopTaskId, true /* onTop */, wct);
        }
        applyExitSplitScreen(childrenToTop, wct, exitReason);
    }

    private void exitSplitScreen(@Nullable StageTaskListener childrenToTop,
            @ExitReason int exitReason) {
        if (!mMainStage.isActive()) return;

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        applyExitSplitScreen(childrenToTop, wct, exitReason);
    }

    private void applyExitSplitScreen(@Nullable StageTaskListener childrenToTop,
            WindowContainerTransaction wct, @ExitReason int exitReason) {
        if (!mMainStage.isActive()) return;

        mRecentTasks.ifPresent(recentTasks -> {
            // Notify recents if we are exiting in a way that breaks the pair, and disable further
            // updates to splits in the recents until we enter split again
            if (shouldBreakPairedTaskInRecents(exitReason) && mShouldUpdateRecents) {
                recentTasks.removeSplitPair(mMainStage.getTopVisibleChildTaskId());
                recentTasks.removeSplitPair(mSideStage.getTopVisibleChildTaskId());
            }
        });
        mShouldUpdateRecents = false;

        // When the exit split-screen is caused by one of the task enters auto pip,
        // we want the tasks to be put to bottom instead of top, otherwise it will end up
        // a fullscreen plus a pinned task instead of pinned only at the end of the transition.
        final boolean fromEnteringPip = exitReason == EXIT_REASON_CHILD_TASK_ENTER_PIP;
        mSideStage.removeAllTasks(wct, !fromEnteringPip && mSideStage == childrenToTop);
        mMainStage.deactivate(wct, !fromEnteringPip && mMainStage == childrenToTop);
        wct.reorder(mRootTaskInfo.token, false /* onTop */);
        mTaskOrganizer.applyTransaction(wct);
        mSyncQueue.runInSync(t -> {
            setResizingSplits(false /* resizing */);
            t.setWindowCrop(mMainStage.mRootLeash, null)
                    .setWindowCrop(mSideStage.mRootLeash, null);
            setDividerVisibility(false, t);
        });

        // Hide divider and reset its position.
        mSplitLayout.resetDividerPosition();
        mSplitLayout.release();
        mTopStageAfterFoldDismiss = STAGE_TYPE_UNDEFINED;
        Slog.i(TAG, "applyExitSplitScreen, reason = " + exitReasonToString(exitReason));
        // Log the exit
        if (childrenToTop != null) {
            logExitToStage(exitReason, childrenToTop == mMainStage);
        } else {
            logExit(exitReason);
        }
    }

    /**
     * Returns whether the split pair in the recent tasks list should be broken.
     */
    private boolean shouldBreakPairedTaskInRecents(@ExitReason int exitReason) {
        switch (exitReason) {
            // One of the apps doesn't support MW
            case EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW:
            // User has explicitly dragged the divider to dismiss split
            case EXIT_REASON_DRAG_DIVIDER:
            // Either of the split apps have finished
            case EXIT_REASON_APP_FINISHED:
            // One of the children enters PiP
            case EXIT_REASON_CHILD_TASK_ENTER_PIP:
            // One of the apps occludes lock screen.
            case EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP:
            // User has unlocked the device after folded
            case EXIT_REASON_DEVICE_FOLDED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Unlike exitSplitScreen, this takes a stagetype vs an actual stage-reference and populates
     * an existing WindowContainerTransaction (rather than applying immediately). This is intended
     * to be used when exiting split might be bundled with other window operations.
     */
    private void prepareExitSplitScreen(@StageType int stageToTop,
            @NonNull WindowContainerTransaction wct) {
        if (!mMainStage.isActive()) return;
        mSideStage.removeAllTasks(wct, stageToTop == STAGE_TYPE_SIDE);
        mMainStage.deactivate(wct, stageToTop == STAGE_TYPE_MAIN);
    }

    private void prepareEnterSplitScreen(WindowContainerTransaction wct) {
        prepareEnterSplitScreen(wct, null /* taskInfo */, SPLIT_POSITION_UNDEFINED);
    }

    /**
     * Prepare transaction to active split screen. If there's a task indicated, the task will be put
     * into side stage.
     */
    void prepareEnterSplitScreen(WindowContainerTransaction wct,
            @Nullable ActivityManager.RunningTaskInfo taskInfo, @SplitPosition int startPosition) {
        if (mMainStage.isActive()) return;

        if (taskInfo != null) {
            setSideStagePosition(startPosition, wct);
            mSideStage.addTask(taskInfo, wct);
        }
        mMainStage.activate(wct, true /* includingTopTask */);
        updateWindowBounds(mSplitLayout, wct);
        wct.reorder(mRootTaskInfo.token, true);
    }

    void finishEnterSplitScreen(SurfaceControl.Transaction t) {
        mSplitLayout.init();
        setDividerVisibility(true, t);
        updateSurfaceBounds(mSplitLayout, t, false /* applyResizingOffset */);
        setSplitsVisible(true);
        mShouldUpdateRecents = true;
        updateRecentTasksSplitPair();
        if (!mLogger.hasStartedSession()) {
            mLogger.logEnter(mSplitLayout.getDividerPositionAsFraction(),
                    getMainStagePosition(), mMainStage.getTopChildTaskUid(),
                    getSideStagePosition(), mSideStage.getTopChildTaskUid(),
                    mSplitLayout.isLandscape());
        }
    }

    void getStageBounds(Rect outTopOrLeftBounds, Rect outBottomOrRightBounds) {
        outTopOrLeftBounds.set(mSplitLayout.getBounds1());
        outBottomOrRightBounds.set(mSplitLayout.getBounds2());
    }

    @SplitPosition
    int getSplitPosition(int taskId) {
        if (mSideStage.getTopVisibleChildTaskId() == taskId) {
            return getSideStagePosition();
        } else if (mMainStage.getTopVisibleChildTaskId() == taskId) {
            return getMainStagePosition();
        }
        return SPLIT_POSITION_UNDEFINED;
    }

    private void addActivityOptions(Bundle opts, StageTaskListener stage) {
        opts.putParcelable(KEY_LAUNCH_ROOT_TASK_TOKEN, stage.mRootTaskInfo.token);
    }

    void updateActivityOptions(Bundle opts, @SplitPosition int position) {
        addActivityOptions(opts, position == mSideStagePosition ? mSideStage : mMainStage);
    }

    void registerSplitScreenListener(SplitScreen.SplitScreenListener listener) {
        if (mListeners.contains(listener)) return;
        mListeners.add(listener);
        sendStatusToListener(listener);
    }

    void unregisterSplitScreenListener(SplitScreen.SplitScreenListener listener) {
        mListeners.remove(listener);
    }

    void sendStatusToListener(SplitScreen.SplitScreenListener listener) {
        listener.onStagePositionChanged(STAGE_TYPE_MAIN, getMainStagePosition());
        listener.onStagePositionChanged(STAGE_TYPE_SIDE, getSideStagePosition());
        listener.onSplitVisibilityChanged(isSplitScreenVisible());
        mSideStage.onSplitScreenListenerRegistered(listener, STAGE_TYPE_SIDE);
        mMainStage.onSplitScreenListenerRegistered(listener, STAGE_TYPE_MAIN);
    }

    private void sendOnStagePositionChanged() {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            final SplitScreen.SplitScreenListener l = mListeners.get(i);
            l.onStagePositionChanged(STAGE_TYPE_MAIN, getMainStagePosition());
            l.onStagePositionChanged(STAGE_TYPE_SIDE, getSideStagePosition());
        }
    }

    private void onStageChildTaskStatusChanged(StageListenerImpl stageListener, int taskId,
            boolean present, boolean visible) {
        int stage;
        if (present) {
            stage = stageListener == mSideStageListener ? STAGE_TYPE_SIDE : STAGE_TYPE_MAIN;
        } else {
            // No longer on any stage
            stage = STAGE_TYPE_UNDEFINED;
        }
        if (stage == STAGE_TYPE_MAIN) {
            mLogger.logMainStageAppChange(getMainStagePosition(), mMainStage.getTopChildTaskUid(),
                    mSplitLayout.isLandscape());
        } else {
            mLogger.logSideStageAppChange(getSideStagePosition(), mSideStage.getTopChildTaskUid(),
                    mSplitLayout.isLandscape());
        }
        if (present && visible) {
            updateRecentTasksSplitPair();
        }

        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onTaskStageChanged(taskId, stage, visible);
        }
    }

    private void onStageChildTaskEnterPip(StageListenerImpl stageListener, int taskId) {
        exitSplitScreen(stageListener == mMainStageListener ? mMainStage : mSideStage,
                EXIT_REASON_CHILD_TASK_ENTER_PIP);
    }

    private void updateRecentTasksSplitPair() {
        if (!mShouldUpdateRecents) {
            return;
        }
        mRecentTasks.ifPresent(recentTasks -> {
            Rect topLeftBounds = mSplitLayout.getBounds1();
            Rect bottomRightBounds = mSplitLayout.getBounds2();
            int mainStageTopTaskId = mMainStage.getTopVisibleChildTaskId();
            int sideStageTopTaskId = mSideStage.getTopVisibleChildTaskId();
            boolean sideStageTopLeft = mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT;
            int leftTopTaskId;
            int rightBottomTaskId;
            if (sideStageTopLeft) {
                leftTopTaskId = sideStageTopTaskId;
                rightBottomTaskId = mainStageTopTaskId;
            } else {
                leftTopTaskId = mainStageTopTaskId;
                rightBottomTaskId = sideStageTopTaskId;
            }
            StagedSplitBounds splitBounds = new StagedSplitBounds(topLeftBounds, bottomRightBounds,
                    leftTopTaskId, rightBottomTaskId);
            if (mainStageTopTaskId != INVALID_TASK_ID && sideStageTopTaskId != INVALID_TASK_ID) {
                // Update the pair for the top tasks
                recentTasks.addSplitPair(mainStageTopTaskId, sideStageTopTaskId, splitBounds);
            }
        });
    }

    private void sendSplitVisibilityChanged() {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            final SplitScreen.SplitScreenListener l = mListeners.get(i);
            l.onSplitVisibilityChanged(mDividerVisible);
        }

        if (mMainUnfoldController != null && mSideUnfoldController != null) {
            mMainUnfoldController.onSplitVisibilityChanged(mDividerVisible);
            mSideUnfoldController.onSplitVisibilityChanged(mDividerVisible);
            updateUnfoldBounds();
        }
    }

    @Override
    @CallSuper
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mRootTaskInfo != null || taskInfo.hasParentTask()) {
            throw new IllegalArgumentException(this + "\n Unknown task appeared: " + taskInfo);
        }

        mRootTaskInfo = taskInfo;
        mRootTaskLeash = leash;

        if (mSplitLayout == null) {
            mSplitLayout = new SplitLayout(TAG + "SplitDivider", mContext,
                    mRootTaskInfo.configuration, this, mParentContainerCallbacks,
                    mDisplayImeController, mTaskOrganizer,
                    PARALLAX_ALIGN_CENTER /* parallaxType */);
            mDisplayInsetsController.addInsetsChangedListener(mDisplayId, mSplitLayout);
        }

        if (mMainUnfoldController != null && mSideUnfoldController != null) {
            mMainUnfoldController.init();
            mSideUnfoldController.init();
        }

        onRootTaskAppeared();
    }

    @Override
    @CallSuper
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (mRootTaskInfo == null || mRootTaskInfo.taskId != taskInfo.taskId) {
            throw new IllegalArgumentException(this + "\n Unknown task info changed: " + taskInfo);
        }

        mRootTaskInfo = taskInfo;
        if (mSplitLayout != null
                && mSplitLayout.updateConfiguration(mRootTaskInfo.configuration)
                && mMainStage.isActive()) {
            // TODO(b/204925795): With Shell transition, We are handling split bounds rotation at
            //  onRotateDisplay. But still need to handle unfold case.
            if (ENABLE_SHELL_TRANSITIONS) {
                updateUnfoldBounds();
                return;
            }
            mSplitLayout.update(null /* t */);
            onLayoutSizeChanged(mSplitLayout);
        }
    }

    @Override
    @CallSuper
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        if (mRootTaskInfo == null) {
            throw new IllegalArgumentException(this + "\n Unknown task vanished: " + taskInfo);
        }

        onRootTaskVanished();

        if (mSplitLayout != null) {
            mSplitLayout.release();
            mSplitLayout = null;
        }

        mRootTaskInfo = null;
    }


    @VisibleForTesting
    void onRootTaskAppeared() {
        // Wait unit all root tasks appeared.
        if (mRootTaskInfo == null
                || !mMainStageListener.mHasRootTask
                || !mSideStageListener.mHasRootTask) {
            return;
        }

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(mMainStage.mRootTaskInfo.token, mRootTaskInfo.token, true);
        wct.reparent(mSideStage.mRootTaskInfo.token, mRootTaskInfo.token, true);
        // Make the stages adjacent to each other so they occlude what's behind them.
        wct.setAdjacentRoots(mMainStage.mRootTaskInfo.token, mSideStage.mRootTaskInfo.token,
                true /* moveTogether */);
        wct.setLaunchAdjacentFlagRoot(mSideStage.mRootTaskInfo.token);
        mTaskOrganizer.applyTransaction(wct);
    }

    private void onRootTaskVanished() {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        if (mRootTaskInfo != null) {
            wct.clearLaunchAdjacentFlagRoot(mRootTaskInfo.token);
        }
        applyExitSplitScreen(null /* childrenToTop */, wct, EXIT_REASON_ROOT_TASK_VANISHED);
        mDisplayInsetsController.removeInsetsChangedListener(mDisplayId, mSplitLayout);
    }

    private void onStageVisibilityChanged(StageListenerImpl stageListener) {
        final boolean sideStageVisible = mSideStageListener.mVisible;
        final boolean mainStageVisible = mMainStageListener.mVisible;

        // Wait for both stages having the same visibility to prevent causing flicker.
        if (mainStageVisible != sideStageVisible) {
            return;
        }

        if (!mainStageVisible) {
            // Both stages are not visible, check if it needs to dismiss split screen.
            if (mExitSplitScreenOnHide
                    // Don't dismiss split screen when both stages are not visible due to sleeping
                    // display.
                    || (!mMainStage.mRootTaskInfo.isSleeping
                    && !mSideStage.mRootTaskInfo.isSleeping)) {
                exitSplitScreen(null /* childrenToTop */, EXIT_REASON_RETURN_HOME);
            }
        }

        mSyncQueue.runInSync(t -> {
            t.setVisibility(mSideStage.mRootLeash, sideStageVisible)
                    .setVisibility(mMainStage.mRootLeash, mainStageVisible);
            setDividerVisibility(mainStageVisible, t);
        });
    }

    private void setDividerVisibility(boolean visible, @Nullable SurfaceControl.Transaction t) {
        if (visible == mDividerVisible) {
            return;
        }

        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                "%s: Request to %s divider bar from %s.", TAG,
                (visible ? "show" : "hide"), Debug.getCaller());

        // Defer showing divider bar after keyguard dismissed, so it won't interfere with keyguard
        // dismissing animation.
        if (visible && mKeyguardShowing) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                    "%s:   Defer showing divider bar due to keyguard showing.", TAG);
            return;
        }

        mDividerVisible = visible;
        sendSplitVisibilityChanged();

        if (mIsDividerRemoteAnimating) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                    "%s:   Skip animating divider bar due to it's remote animating.", TAG);
            return;
        }

        if (t != null) {
            applyDividerVisibility(t);
        } else {
            mSyncQueue.runInSync(transaction -> applyDividerVisibility(transaction));
        }
    }

    private void applyDividerVisibility(SurfaceControl.Transaction t) {
        final SurfaceControl dividerLeash = mSplitLayout.getDividerLeash();
        if (dividerLeash == null) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                    "%s:   Skip animating divider bar due to divider leash not ready.", TAG);
            return;
        }
        if (mIsDividerRemoteAnimating) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN,
                    "%s:   Skip animating divider bar due to it's remote animating.", TAG);
            return;
        }

        if (mDividerFadeInAnimator != null && mDividerFadeInAnimator.isRunning()) {
            mDividerFadeInAnimator.cancel();
        }

        if (mDividerVisible) {
            final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
            mDividerFadeInAnimator = ValueAnimator.ofFloat(0f, 1f);
            mDividerFadeInAnimator.addUpdateListener(animation -> {
                if (dividerLeash == null) {
                    mDividerFadeInAnimator.cancel();
                    return;
                }
                transaction.setAlpha(dividerLeash, (float) animation.getAnimatedValue());
                transaction.apply();
            });
            mDividerFadeInAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    if (dividerLeash == null) {
                        mDividerFadeInAnimator.cancel();
                        return;
                    }
                    transaction.show(dividerLeash);
                    transaction.setAlpha(dividerLeash, 0);
                    transaction.setLayer(dividerLeash, Integer.MAX_VALUE);
                    transaction.setPosition(dividerLeash,
                                    mSplitLayout.getRefDividerBounds().left,
                                    mSplitLayout.getRefDividerBounds().top);
                    transaction.apply();
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mTransactionPool.release(transaction);
                    mDividerFadeInAnimator = null;
                }
            });

            mDividerFadeInAnimator.start();
        } else {
            t.hide(dividerLeash);
        }
    }

    private void onStageHasChildrenChanged(StageListenerImpl stageListener) {
        final boolean hasChildren = stageListener.mHasChildren;
        final boolean isSideStage = stageListener == mSideStageListener;
        if (!hasChildren) {
            if (isSideStage && mMainStageListener.mVisible) {
                // Exit to main stage if side stage no longer has children.
                exitSplitScreen(mMainStage, EXIT_REASON_APP_FINISHED);
            } else if (!isSideStage && mSideStageListener.mVisible) {
                // Exit to side stage if main stage no longer has children.
                exitSplitScreen(mSideStage, EXIT_REASON_APP_FINISHED);
            }
        } else if (isSideStage && !mMainStage.isActive()) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            mSplitLayout.init();
            prepareEnterSplitScreen(wct);
            mSyncQueue.queue(wct);
            mSyncQueue.runInSync(t ->
                    updateSurfaceBounds(mSplitLayout, t, false /* applyResizingOffset */));
        }
        if (mMainStageListener.mHasChildren && mSideStageListener.mHasChildren) {
            mShouldUpdateRecents = true;
            updateRecentTasksSplitPair();

            if (!mLogger.hasStartedSession()) {
                mLogger.logEnter(mSplitLayout.getDividerPositionAsFraction(),
                        getMainStagePosition(), mMainStage.getTopChildTaskUid(),
                        getSideStagePosition(), mSideStage.getTopChildTaskUid(),
                        mSplitLayout.isLandscape());
            }
        }
    }

    @Override
    public void onSnappedToDismiss(boolean bottomOrRight) {
        final boolean mainStageToTop =
                bottomOrRight ? mSideStagePosition == SPLIT_POSITION_BOTTOM_OR_RIGHT
                        : mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT;
        if (!ENABLE_SHELL_TRANSITIONS) {
            exitSplitScreen(mainStageToTop ? mMainStage : mSideStage, EXIT_REASON_DRAG_DIVIDER);
            return;
        }

        setResizingSplits(false /* resizing */);
        final int dismissTop = mainStageToTop ? STAGE_TYPE_MAIN : STAGE_TYPE_SIDE;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        prepareExitSplitScreen(dismissTop, wct);
        mSplitTransitions.startDismissTransition(
                null /* transition */, wct, this, dismissTop, EXIT_REASON_DRAG_DIVIDER);
    }

    @Override
    public void onDoubleTappedDivider() {
        setSideStagePosition(SplitLayout.reversePosition(mSideStagePosition), null /* wct */);
        mLogger.logSwap(getMainStagePosition(), mMainStage.getTopChildTaskUid(),
                getSideStagePosition(), mSideStage.getTopChildTaskUid(),
                mSplitLayout.isLandscape());
    }

    @Override
    public void onLayoutPositionChanging(SplitLayout layout) {
        mSyncQueue.runInSync(t -> updateSurfaceBounds(layout, t, false /* applyResizingOffset */));
    }

    @Override
    public void onLayoutSizeChanging(SplitLayout layout) {
        mSyncQueue.runInSync(t -> {
            setResizingSplits(true /* resizing */);
            updateSurfaceBounds(layout, t, true /* applyResizingOffset */);
            mMainStage.onResizing(getMainStageBounds(), t);
            mSideStage.onResizing(getSideStageBounds(), t);
        });
    }

    @Override
    public void onLayoutSizeChanged(SplitLayout layout) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        updateWindowBounds(layout, wct);
        updateUnfoldBounds();
        mSyncQueue.queue(wct);
        mSyncQueue.runInSync(t -> {
            setResizingSplits(false /* resizing */);
            updateSurfaceBounds(layout, t, false /* applyResizingOffset */);
            mMainStage.onResized(t);
            mSideStage.onResized(t);
        });
        mLogger.logResize(mSplitLayout.getDividerPositionAsFraction());
    }

    private void updateUnfoldBounds() {
        if (mMainUnfoldController != null && mSideUnfoldController != null) {
            mMainUnfoldController.onLayoutChanged(getMainStageBounds(), getMainStagePosition(),
                    isLandscape());
            mSideUnfoldController.onLayoutChanged(getSideStageBounds(), getSideStagePosition(),
                    isLandscape());
        }
    }

    private boolean isLandscape() {
        return mSplitLayout.isLandscape();
    }

    /**
     * Populates `wct` with operations that match the split windows to the current layout.
     * To match relevant surfaces, make sure to call updateSurfaceBounds after `wct` is applied
     */
    private void updateWindowBounds(SplitLayout layout, WindowContainerTransaction wct) {
        final StageTaskListener topLeftStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mSideStage : mMainStage;
        final StageTaskListener bottomRightStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mMainStage : mSideStage;
        layout.applyTaskChanges(wct, topLeftStage.mRootTaskInfo, bottomRightStage.mRootTaskInfo);
    }

    void updateSurfaceBounds(@Nullable SplitLayout layout, @NonNull SurfaceControl.Transaction t,
            boolean applyResizingOffset) {
        final StageTaskListener topLeftStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mSideStage : mMainStage;
        final StageTaskListener bottomRightStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mMainStage : mSideStage;
        (layout != null ? layout : mSplitLayout).applySurfaceChanges(t, topLeftStage.mRootLeash,
                bottomRightStage.mRootLeash, topLeftStage.mDimLayer, bottomRightStage.mDimLayer,
                applyResizingOffset);
    }

    void setResizingSplits(boolean resizing) {
        if (resizing == mResizingSplits) return;
        try {
            ActivityTaskManager.getService().setSplitScreenResizing(resizing);
            mResizingSplits = resizing;
        } catch (RemoteException e) {
            Slog.w(TAG, "Error calling setSplitScreenResizing", e);
        }
    }

    @Override
    public int getSplitItemPosition(WindowContainerToken token) {
        if (token == null) {
            return SPLIT_POSITION_UNDEFINED;
        }

        if (mMainStage.containsToken(token)) {
            return getMainStagePosition();
        } else if (mSideStage.containsToken(token)) {
            return getSideStagePosition();
        }

        return SPLIT_POSITION_UNDEFINED;
    }

    @Override
    public void setLayoutOffsetTarget(int offsetX, int offsetY, SplitLayout layout) {
        final StageTaskListener topLeftStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mSideStage : mMainStage;
        final StageTaskListener bottomRightStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mMainStage : mSideStage;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        layout.applyLayoutOffsetTarget(wct, offsetX, offsetY, topLeftStage.mRootTaskInfo,
                bottomRightStage.mRootTaskInfo);
        mTaskOrganizer.applyTransaction(wct);
    }

    public void onDisplayAdded(int displayId) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        mDisplayController.addDisplayChangingController(this::onRotateDisplay);
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        mDisplayLayout.set(mDisplayController.getDisplayLayout(displayId));
    }

    private void onRotateDisplay(int displayId, int fromRotation, int toRotation,
            WindowContainerTransaction wct) {
        if (!mMainStage.isActive()) return;
        // Only do this when shell transition
        if (!ENABLE_SHELL_TRANSITIONS) return;

        mDisplayLayout.rotateTo(mContext.getResources(), toRotation);
        mSplitLayout.rotateTo(toRotation, mDisplayLayout.stableInsets());
        updateWindowBounds(mSplitLayout, wct);
        updateUnfoldBounds();
    }

    private void onFoldedStateChanged(boolean folded) {
        mTopStageAfterFoldDismiss = STAGE_TYPE_UNDEFINED;
        if (!folded) return;

        if (mMainStage.isFocused()) {
            mTopStageAfterFoldDismiss = STAGE_TYPE_MAIN;
        } else if (mSideStage.isFocused()) {
            mTopStageAfterFoldDismiss = STAGE_TYPE_SIDE;
        }
    }

    private Rect getSideStageBounds() {
        return mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT
                ? mSplitLayout.getBounds1() : mSplitLayout.getBounds2();
    }

    private Rect getMainStageBounds() {
        return mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT
                ? mSplitLayout.getBounds2() : mSplitLayout.getBounds1();
    }

    /**
     * Get the stage that should contain this `taskInfo`. The stage doesn't necessarily contain
     * this task (yet) so this can also be used to identify which stage to put a task into.
     */
    private StageTaskListener getStageOfTask(ActivityManager.RunningTaskInfo taskInfo) {
        // TODO(b/184679596): Find a way to either include task-org information in the transition,
        //                    or synchronize task-org callbacks so we can use stage.containsTask
        if (mMainStage.mRootTaskInfo != null
                && taskInfo.parentTaskId == mMainStage.mRootTaskInfo.taskId) {
            return mMainStage;
        } else if (mSideStage.mRootTaskInfo != null
                && taskInfo.parentTaskId == mSideStage.mRootTaskInfo.taskId) {
            return mSideStage;
        }
        return null;
    }

    @StageType
    private int getStageType(StageTaskListener stage) {
        return stage == mMainStage ? STAGE_TYPE_MAIN : STAGE_TYPE_SIDE;
    }

    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @Nullable TransitionRequestInfo request) {
        final ActivityManager.RunningTaskInfo triggerTask = request.getTriggerTask();
        if (triggerTask == null) {
            if (mMainStage.isActive()) {
                final TransitionRequestInfo.DisplayChange displayChange =
                        request.getDisplayChange();
                if (request.getType() == TRANSIT_CHANGE && displayChange != null
                        && displayChange.getStartRotation() != displayChange.getEndRotation()) {
                    mSplitLayout.setFreezeDividerWindow(true);
                }
                // Still want to monitor everything while in split-screen, so return non-null.
                return new WindowContainerTransaction();
            } else {
                return null;
            }
        } else if (triggerTask.displayId != mDisplayId) {
            // Skip handling task on the other display.
            return null;
        }

        WindowContainerTransaction out = null;
        final @WindowManager.TransitionType int type = request.getType();
        final boolean isOpening = isOpeningType(type);
        final boolean inFullscreen = triggerTask.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;

        if (isOpening && inFullscreen) {
            // One task is opening into fullscreen mode, remove the corresponding split record.
            mRecentTasks.ifPresent(recentTasks -> recentTasks.removeSplitPair(triggerTask.taskId));
        }

        if (mMainStage.isActive()) {
            // Try to handle everything while in split-screen, so return a WCT even if it's empty.
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  split is active so using split"
                            + "Transition to handle request. triggerTask=%d type=%s mainChildren=%d"
                            + " sideChildren=%d", triggerTask.taskId, transitTypeToString(type),
                    mMainStage.getChildCount(), mSideStage.getChildCount());
            out = new WindowContainerTransaction();
            final StageTaskListener stage = getStageOfTask(triggerTask);
            if (stage != null) {
                // Dismiss split if the last task in one of the stages is going away
                if (isClosingType(type) && stage.getChildCount() == 1) {
                    // The top should be the opposite side that is closing:
                    int dismissTop = getStageType(stage) == STAGE_TYPE_MAIN ? STAGE_TYPE_SIDE
                            : STAGE_TYPE_MAIN;
                    prepareExitSplitScreen(dismissTop, out);
                    mSplitTransitions.startDismissTransition(transition, out, this, dismissTop,
                            EXIT_REASON_APP_FINISHED);
                }
            } else if (isOpening && inFullscreen) {
                final int activityType = triggerTask.getActivityType();
                if (activityType == ACTIVITY_TYPE_ASSISTANT) {
                    // We don't want assistant panel to dismiss split screen, so do nothing.
                } else if (activityType == ACTIVITY_TYPE_HOME
                        || activityType == ACTIVITY_TYPE_RECENTS) {
                    // Enter overview panel, so start recent transition.
                    mSplitTransitions.startRecentTransition(transition, out, this,
                            request.getRemoteTransition());
                } else {
                    // Occluded by the other fullscreen task, so dismiss both.
                    prepareExitSplitScreen(STAGE_TYPE_UNDEFINED, out);
                    mSplitTransitions.startDismissTransition(transition, out, this,
                            STAGE_TYPE_UNDEFINED, EXIT_REASON_UNKNOWN);
                }
            }
        } else {
            if (isOpening && getStageOfTask(triggerTask) != null) {
                // One task is appearing into split, prepare to enter split screen.
                out = new WindowContainerTransaction();
                prepareEnterSplitScreen(out);
                mSplitTransitions.mPendingEnter = transition;
            }
        }
        return out;
    }

    @Override
    public void mergeAnimation(IBinder transition, TransitionInfo info,
            SurfaceControl.Transaction t, IBinder mergeTarget,
            Transitions.TransitionFinishCallback finishCallback) {
        mSplitTransitions.mergeAnimation(transition, info, t, mergeTarget, finishCallback);
    }

    @Override
    public void onTransitionMerged(@NonNull IBinder transition) {
        // Once the pending enter transition got merged, make sure to bring divider bar visible and
        // clear the pending transition from cache to prevent mess-up the following state.
        if (transition == mSplitTransitions.mPendingEnter) {
            final SurfaceControl.Transaction t = mTransactionPool.acquire();
            finishEnterSplitScreen(t);
            mSplitTransitions.mPendingEnter = null;
            t.apply();
            mTransactionPool.release(t);
        }
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (transition != mSplitTransitions.mPendingEnter
                && transition != mSplitTransitions.mPendingRecent
                && (mSplitTransitions.mPendingDismiss == null
                        || mSplitTransitions.mPendingDismiss.mTransition != transition)) {
            // Not entering or exiting, so just do some house-keeping and validation.

            // If we're not in split-mode, just abort so something else can handle it.
            if (!mMainStage.isActive()) return false;

            mSplitLayout.setFreezeDividerWindow(false);
            for (int iC = 0; iC < info.getChanges().size(); ++iC) {
                final TransitionInfo.Change change = info.getChanges().get(iC);
                if (change.getMode() == TRANSIT_CHANGE
                        && (change.getFlags() & FLAG_IS_DISPLAY) != 0) {
                    mSplitLayout.update(startTransaction);
                }

                final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                if (taskInfo == null || !taskInfo.hasParentTask()) continue;
                final StageTaskListener stage = getStageOfTask(taskInfo);
                if (stage == null) continue;
                if (isOpeningType(change.getMode())) {
                    if (!stage.containsTask(taskInfo.taskId)) {
                        Log.w(TAG, "Expected onTaskAppeared on " + stage + " to have been called"
                                + " with " + taskInfo.taskId + " before startAnimation().");
                    }
                } else if (isClosingType(change.getMode())) {
                    if (stage.containsTask(taskInfo.taskId)) {
                        Log.w(TAG, "Expected onTaskVanished on " + stage + " to have been called"
                                + " with " + taskInfo.taskId + " before startAnimation().");
                    }
                }
            }
            if (mMainStage.getChildCount() == 0 || mSideStage.getChildCount() == 0) {
                // TODO(shell-transitions): Implement a fallback behavior for now.
                throw new IllegalStateException("Somehow removed the last task in a stage"
                        + " outside of a proper transition");
                // This can happen in some pathological cases. For example:
                // 1. main has 2 tasks [Task A (Single-task), Task B], side has one task [Task C]
                // 2. Task B closes itself and starts Task A in LAUNCH_ADJACENT at the same time
                // In this case, the result *should* be that we leave split.
                // TODO(b/184679596): Find a way to either include task-org information in
                //                    the transition, or synchronize task-org callbacks.
            }

            // Use normal animations.
            return false;
        }

        boolean shouldAnimate = true;
        if (mSplitTransitions.mPendingEnter == transition) {
            shouldAnimate = startPendingEnterAnimation(transition, info, startTransaction);
        } else if (mSplitTransitions.mPendingRecent == transition) {
            shouldAnimate = startPendingRecentAnimation(transition, info, startTransaction);
        } else if (mSplitTransitions.mPendingDismiss != null
                && mSplitTransitions.mPendingDismiss.mTransition == transition) {
            shouldAnimate = startPendingDismissAnimation(
                    mSplitTransitions.mPendingDismiss, info, startTransaction, finishTransaction);
        }
        if (!shouldAnimate) return false;

        mSplitTransitions.playAnimation(transition, info, startTransaction, finishTransaction,
                finishCallback, mMainStage.mRootTaskInfo.token, mSideStage.mRootTaskInfo.token);
        return true;
    }

    void onTransitionAnimationComplete() {
        // If still playing, let it finish.
        if (!mMainStage.isActive()) {
            // Update divider state after animation so that it is still around and positioned
            // properly for the animation itself.
            mSplitLayout.release();
            mSplitLayout.resetDividerPosition();
            mTopStageAfterFoldDismiss = STAGE_TYPE_UNDEFINED;
        }
    }

    private boolean startPendingEnterAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction t) {
        // First, verify that we actually have opened apps in both splits.
        TransitionInfo.Change mainChild = null;
        TransitionInfo.Change sideChild = null;
        for (int iC = 0; iC < info.getChanges().size(); ++iC) {
            final TransitionInfo.Change change = info.getChanges().get(iC);
            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null || !taskInfo.hasParentTask()) continue;
            final @StageType int stageType = getStageType(getStageOfTask(taskInfo));
            if (stageType == STAGE_TYPE_MAIN) {
                mainChild = change;
            } else if (stageType == STAGE_TYPE_SIDE) {
                sideChild = change;
            }
        }

        // TODO: fallback logic. Probably start a new transition to exit split before applying
        //       anything here. Ideally consolidate with transition-merging.
        if (info.getType() == TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE) {
            if (mainChild == null && sideChild == null) {
                throw new IllegalStateException("Launched a task in split, but didn't receive any"
                        + " task in transition.");
            }
        } else {
            if (mainChild == null || sideChild == null) {
                throw new IllegalStateException("Launched 2 tasks in split, but didn't receive"
                        + " 2 tasks in transition. Possibly one of them failed to launch");
            }
        }

        // Make some noise if things aren't totally expected. These states shouldn't effect
        // transitions locally, but remotes (like Launcher) may get confused if they were
        // depending on listener callbacks. This can happen because task-organizer callbacks
        // aren't serialized with transition callbacks.
        // TODO(b/184679596): Find a way to either include task-org information in
        //                    the transition, or synchronize task-org callbacks.
        if (mainChild != null && !mMainStage.containsTask(mainChild.getTaskInfo().taskId)) {
            Log.w(TAG, "Expected onTaskAppeared on " + mMainStage
                    + " to have been called with " + mainChild.getTaskInfo().taskId
                    + " before startAnimation().");
        }
        if (sideChild != null && !mSideStage.containsTask(sideChild.getTaskInfo().taskId)) {
            Log.w(TAG, "Expected onTaskAppeared on " + mSideStage
                    + " to have been called with " + sideChild.getTaskInfo().taskId
                    + " before startAnimation().");
        }

        finishEnterSplitScreen(t);
        addDividerBarToTransition(info, t, true /* show */);
        return true;
    }

    private boolean startPendingDismissAnimation(
            @NonNull SplitScreenTransitions.DismissTransition dismissTransition,
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction t,
            @NonNull SurfaceControl.Transaction finishT) {
        // Make some noise if things aren't totally expected. These states shouldn't effect
        // transitions locally, but remotes (like Launcher) may get confused if they were
        // depending on listener callbacks. This can happen because task-organizer callbacks
        // aren't serialized with transition callbacks.
        // TODO(b/184679596): Find a way to either include task-org information in
        //                    the transition, or synchronize task-org callbacks.
        if (mMainStage.getChildCount() != 0) {
            final StringBuilder tasksLeft = new StringBuilder();
            for (int i = 0; i < mMainStage.getChildCount(); ++i) {
                tasksLeft.append(i != 0 ? ", " : "");
                tasksLeft.append(mMainStage.mChildrenTaskInfo.keyAt(i));
            }
            Log.w(TAG, "Expected onTaskVanished on " + mMainStage
                    + " to have been called with [" + tasksLeft.toString()
                    + "] before startAnimation().");
        }
        if (mSideStage.getChildCount() != 0) {
            final StringBuilder tasksLeft = new StringBuilder();
            for (int i = 0; i < mSideStage.getChildCount(); ++i) {
                tasksLeft.append(i != 0 ? ", " : "");
                tasksLeft.append(mSideStage.mChildrenTaskInfo.keyAt(i));
            }
            Log.w(TAG, "Expected onTaskVanished on " + mSideStage
                    + " to have been called with [" + tasksLeft.toString()
                    + "] before startAnimation().");
        }

        mRecentTasks.ifPresent(recentTasks -> {
            // Notify recents if we are exiting in a way that breaks the pair, and disable further
            // updates to splits in the recents until we enter split again
            if (shouldBreakPairedTaskInRecents(dismissTransition.mReason) && mShouldUpdateRecents) {
                for (TransitionInfo.Change change : info.getChanges()) {
                    final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                    if (taskInfo != null
                            && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                        recentTasks.removeSplitPair(taskInfo.taskId);
                    }
                }
            }
        });
        mShouldUpdateRecents = false;

        // Update local states.
        setSplitsVisible(false);
        // Wait until after animation to update divider

        // Reset crops so they don't interfere with subsequent launches
        t.setWindowCrop(mMainStage.mRootLeash, null);
        t.setWindowCrop(mSideStage.mRootLeash, null);

        if (dismissTransition.mDismissTop == STAGE_TYPE_UNDEFINED) {
            logExit(dismissTransition.mReason);
            // TODO: Have a proper remote for this. Until then, though, reset state and use the
            //       normal animation stuff (which falls back to the normal launcher remote).
            mSplitLayout.release(t);
            mSplitTransitions.mPendingDismiss = null;
            return false;
        } else {
            logExitToStage(dismissTransition.mReason,
                    dismissTransition.mDismissTop == STAGE_TYPE_MAIN);
        }

        addDividerBarToTransition(info, t, false /* show */);
        // We're dismissing split by moving the other one to fullscreen.
        // Since we don't have any animations for this yet, just use the internal example
        // animations.

        // Hide divider and dim layer on transition finished.
        setDividerVisibility(false, finishT);
        finishT.hide(mMainStage.mDimLayer);
        finishT.hide(mSideStage.mDimLayer);
        return true;
    }

    private boolean startPendingRecentAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction t) {
        setDividerVisibility(false, t);
        return true;
    }

    void onRecentTransitionFinished(boolean returnToHome, WindowContainerTransaction wct,
            SurfaceControl.Transaction finishT) {
        // Exclude the case that the split screen has been dismissed already.
        if (!mMainStage.isActive()) {
            // The latest split dismissing transition might be a no-op transition and thus won't
            // callback startAnimation, update split visibility here to cover this kind of no-op
            // transition case.
            setSplitsVisible(false);
            return;
        }

        if (returnToHome) {
            // When returning to home from recent apps, the splitting tasks are already hidden, so
            // append the reset of dismissing operations into the clean-up wct.
            prepareExitSplitScreen(STAGE_TYPE_UNDEFINED, wct);
            setSplitsVisible(false);
            logExit(EXIT_REASON_RETURN_HOME);
        } else {
            setDividerVisibility(true, finishT);
        }
    }

    private void addDividerBarToTransition(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, boolean show) {
        final SurfaceControl leash = mSplitLayout.getDividerLeash();
        final TransitionInfo.Change barChange = new TransitionInfo.Change(null /* token */, leash);
        final Rect bounds = mSplitLayout.getDividerBounds();
        barChange.setStartAbsBounds(bounds);
        barChange.setEndAbsBounds(bounds);
        barChange.setMode(show ? TRANSIT_TO_FRONT : TRANSIT_TO_BACK);
        barChange.setFlags(FLAG_IS_DIVIDER_BAR);
        // Technically this should be order-0, but this is running after layer assignment
        // and it's a special case, so just add to end.
        info.addChange(barChange);
        // Be default, make it visible. The remote animator can adjust alpha if it plans to animate.
        if (show) {
            t.setAlpha(leash, 1.f);
            t.setLayer(leash, Integer.MAX_VALUE);
            t.setPosition(leash, bounds.left, bounds.top);
            t.show(leash);
        }
    }

    RemoteAnimationTarget getDividerBarLegacyTarget() {
        final Rect bounds = mSplitLayout.getDividerBounds();
        return new RemoteAnimationTarget(-1 /* taskId */, -1 /* mode */,
                mSplitLayout.getDividerLeash(), false /* isTranslucent */, null /* clipRect */,
                null /* contentInsets */, Integer.MAX_VALUE /* prefixOrderIndex */,
                new android.graphics.Point(0, 0) /* position */, bounds, bounds,
                new WindowConfiguration(), true, null /* startLeash */, null /* startBounds */,
                null /* taskInfo */, false /* allowEnterPip */, TYPE_DOCK_DIVIDER);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + TAG + " mDisplayId=" + mDisplayId);
        pw.println(innerPrefix + "mDividerVisible=" + mDividerVisible);
        pw.println(innerPrefix + "MainStage");
        pw.println(childPrefix + "stagePosition=" + getMainStagePosition());
        pw.println(childPrefix + "isActive=" + mMainStage.isActive());
        mMainStageListener.dump(pw, childPrefix);
        pw.println(innerPrefix + "SideStage");
        pw.println(childPrefix + "stagePosition=" + getSideStagePosition());
        mSideStageListener.dump(pw, childPrefix);
        if (mMainStage.isActive()) {
            pw.println(innerPrefix + "SplitLayout");
            mSplitLayout.dump(pw, childPrefix);
        }
    }

    /**
     * Directly set the visibility of both splits. This assumes hasChildren matches visibility.
     * This is intended for batch use, so it assumes other state management logic is already
     * handled.
     */
    private void setSplitsVisible(boolean visible) {
        mMainStageListener.mVisible = mSideStageListener.mVisible = visible;
        mMainStageListener.mHasChildren = mSideStageListener.mHasChildren = visible;
    }

    /**
     * Sets drag info to be logged when splitscreen is next entered.
     */
    public void logOnDroppedToSplit(@SplitPosition int position, InstanceId dragSessionId) {
        mLogger.enterRequestedByDrag(position, dragSessionId);
    }

    /**
     * Logs the exit of splitscreen.
     */
    private void logExit(@ExitReason int exitReason) {
        mLogger.logExit(exitReason,
                SPLIT_POSITION_UNDEFINED, 0 /* mainStageUid */,
                SPLIT_POSITION_UNDEFINED, 0 /* sideStageUid */,
                mSplitLayout.isLandscape());
    }

    /**
     * Logs the exit of splitscreen to a specific stage. This must be called before the exit is
     * executed.
     */
    private void logExitToStage(@ExitReason int exitReason, boolean toMainStage) {
        mLogger.logExit(exitReason,
                toMainStage ? getMainStagePosition() : SPLIT_POSITION_UNDEFINED,
                toMainStage ? mMainStage.getTopChildTaskUid() : 0 /* mainStageUid */,
                !toMainStage ? getSideStagePosition() : SPLIT_POSITION_UNDEFINED,
                !toMainStage ? mSideStage.getTopChildTaskUid() : 0 /* sideStageUid */,
                mSplitLayout.isLandscape());
    }

    class StageListenerImpl implements StageTaskListener.StageListenerCallbacks {
        boolean mHasRootTask = false;
        boolean mVisible = false;
        boolean mHasChildren = false;

        @Override
        public void onRootTaskAppeared() {
            mHasRootTask = true;
            StageCoordinator.this.onRootTaskAppeared();
        }

        @Override
        public void onStatusChanged(boolean visible, boolean hasChildren) {
            if (!mHasRootTask) return;

            if (mHasChildren != hasChildren) {
                mHasChildren = hasChildren;
                StageCoordinator.this.onStageHasChildrenChanged(this);
            }
            if (mVisible != visible) {
                mVisible = visible;
                StageCoordinator.this.onStageVisibilityChanged(this);
            }
        }

        @Override
        public void onChildTaskStatusChanged(int taskId, boolean present, boolean visible) {
            StageCoordinator.this.onStageChildTaskStatusChanged(this, taskId, present, visible);
        }

        @Override
        public void onChildTaskEnterPip(int taskId) {
            StageCoordinator.this.onStageChildTaskEnterPip(this, taskId);
        }

        @Override
        public void onRootTaskVanished() {
            reset();
            StageCoordinator.this.onRootTaskVanished();
        }

        @Override
        public void onNoLongerSupportMultiWindow() {
            if (mMainStage.isActive()) {
                final boolean isMainStage = mMainStageListener == this;
                if (!ENABLE_SHELL_TRANSITIONS) {
                    StageCoordinator.this.exitSplitScreen(isMainStage ? mMainStage : mSideStage,
                            EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW);
                    return;
                }

                final int stageType = isMainStage ? STAGE_TYPE_MAIN : STAGE_TYPE_SIDE;
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                prepareExitSplitScreen(stageType, wct);
                mSplitTransitions.startDismissTransition(null /* transition */, wct,
                        StageCoordinator.this, stageType,
                        EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW);
            }
        }

        private void reset() {
            mHasRootTask = false;
            mVisible = false;
            mHasChildren = false;
        }

        public void dump(@NonNull PrintWriter pw, String prefix) {
            pw.println(prefix + "mHasRootTask=" + mHasRootTask);
            pw.println(prefix + "mVisible=" + mVisible);
            pw.println(prefix + "mHasChildren=" + mHasChildren);
        }
    }
}
