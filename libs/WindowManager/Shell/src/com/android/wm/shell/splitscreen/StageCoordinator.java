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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.transitTypeToString;
import static android.view.WindowManagerPolicyConstants.SPLIT_DIVIDER_LAYER;

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_MAIN;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_SIDE;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreen.stageTypeToString;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_APP_DOES_NOT_SUPPORT_MULTIWINDOW;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_APP_FINISHED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_CHILD_TASK_ENTER_PIP;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DEVICE_FOLDED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DRAG_DIVIDER;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_RETURN_HOME;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP;
import static com.android.wm.shell.splitscreen.SplitScreenController.exitReasonToString;
import static com.android.wm.shell.splitscreen.SplitScreenTransitions.FLAG_IS_DIVIDER_BAR;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_DISMISS_SNAP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_SCREEN_PAIR_OPEN;
import static com.android.wm.shell.transition.Transitions.isClosingType;
import static com.android.wm.shell.transition.Transitions.isOpeningType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Bundle;
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
import android.window.DisplayAreaInfo;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.protolog.common.ProtoLog;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
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
 * - The {@link MainStage} configuration is fullscreen when the {@link SideStage} isn't visible.
 * This rules are mostly implemented in {@link #onStageVisibilityChanged(StageListenerImpl)} and
 * {@link #onStageHasChildrenChanged(StageListenerImpl).}
 */
class StageCoordinator implements SplitLayout.SplitLayoutHandler,
        RootTaskDisplayAreaOrganizer.RootTaskDisplayAreaListener, Transitions.TransitionHandler {

    private static final String TAG = StageCoordinator.class.getSimpleName();

    /** internal value for mDismissTop that represents no dismiss */
    private static final int NO_DISMISS = -2;

    private final SurfaceSession mSurfaceSession = new SurfaceSession();

    private final MainStage mMainStage;
    private final StageListenerImpl mMainStageListener = new StageListenerImpl();
    private final StageTaskUnfoldController mMainUnfoldController;
    private final SideStage mSideStage;
    private final StageListenerImpl mSideStageListener = new StageListenerImpl();
    private final StageTaskUnfoldController mSideUnfoldController;
    @SplitPosition
    private int mSideStagePosition = SPLIT_POSITION_BOTTOM_OR_RIGHT;

    private final int mDisplayId;
    private SplitLayout mSplitLayout;
    private boolean mDividerVisible;
    private final SyncTransactionQueue mSyncQueue;
    private final RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    private final ShellTaskOrganizer mTaskOrganizer;
    private DisplayAreaInfo mDisplayAreaInfo;
    private final Context mContext;
    private final List<SplitScreen.SplitScreenListener> mListeners = new ArrayList<>();
    private final DisplayImeController mDisplayImeController;
    private final DisplayInsetsController mDisplayInsetsController;
    private final SplitScreenTransitions mSplitTransitions;
    private final SplitscreenEventLogger mLogger;
    private final Optional<RecentTasksController> mRecentTasks;
    // Tracks whether we should update the recent tasks.  Only allow this to happen in between enter
    // and exit, since exit itself can trigger a number of changes that update the stages.
    private boolean mShouldUpdateRecents;
    private boolean mExitSplitScreenOnHide;
    private boolean mKeyguardOccluded;
    private boolean mDeviceSleep;
    private boolean mIsDividerRemoteAnimating;

    @StageType
    private int mDismissTop = NO_DISMISS;

    /** The target stage to dismiss to when unlock after folded. */
    @StageType
    private int mTopStageAfterFoldDismiss = STAGE_TYPE_UNDEFINED;

    private final Runnable mOnTransitionAnimationComplete = () -> {
        // If still playing, let it finish.
        if (!isSplitScreenVisible()) {
            // Update divider state after animation so that it is still around and positioned
            // properly for the animation itself.
            setDividerVisibility(false);
            mSplitLayout.resetDividerPosition();
        }
        mDismissTop = NO_DISMISS;
    };

    private final SplitWindowManager.ParentContainerCallbacks mParentContainerCallbacks =
            new SplitWindowManager.ParentContainerCallbacks() {
                @Override
                public void attachToParentSurface(SurfaceControl.Builder b) {
                    mRootTDAOrganizer.attachToDisplayArea(mDisplayId, b);
                }

                @Override
                public void onLeashReady(SurfaceControl leash) {
                    mSyncQueue.runInSync(t -> applyDividerVisibility(t));
                }
            };

    StageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer, ShellTaskOrganizer taskOrganizer,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController, Transitions transitions,
            TransactionPool transactionPool, SplitscreenEventLogger logger,
            IconProvider iconProvider,
            Optional<RecentTasksController> recentTasks,
            Provider<Optional<StageTaskUnfoldController>> unfoldControllerProvider) {
        mContext = context;
        mDisplayId = displayId;
        mSyncQueue = syncQueue;
        mRootTDAOrganizer = rootTDAOrganizer;
        mTaskOrganizer = taskOrganizer;
        mLogger = logger;
        mRecentTasks = recentTasks;
        mMainUnfoldController = unfoldControllerProvider.get().orElse(null);
        mSideUnfoldController = unfoldControllerProvider.get().orElse(null);

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
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mRootTDAOrganizer.registerListener(displayId, this);
        final DeviceStateManager deviceStateManager =
                mContext.getSystemService(DeviceStateManager.class);
        deviceStateManager.registerCallback(taskOrganizer.getExecutor(),
                new DeviceStateManager.FoldStateListener(mContext, this::onFoldedStateChanged));
        mSplitTransitions = new SplitScreenTransitions(transactionPool, transitions,
                mOnTransitionAnimationComplete);
        transitions.addHandler(this);
    }

    @VisibleForTesting
    StageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer, ShellTaskOrganizer taskOrganizer,
            MainStage mainStage, SideStage sideStage, DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController, SplitLayout splitLayout,
            Transitions transitions, TransactionPool transactionPool,
            SplitscreenEventLogger logger,
            Optional<RecentTasksController> recentTasks,
            Provider<Optional<StageTaskUnfoldController>> unfoldControllerProvider) {
        mContext = context;
        mDisplayId = displayId;
        mSyncQueue = syncQueue;
        mRootTDAOrganizer = rootTDAOrganizer;
        mTaskOrganizer = taskOrganizer;
        mMainStage = mainStage;
        mSideStage = sideStage;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mRootTDAOrganizer.registerListener(displayId, this);
        mSplitLayout = splitLayout;
        mSplitTransitions = new SplitScreenTransitions(transactionPool, transitions,
                mOnTransitionAnimationComplete);
        mMainUnfoldController = unfoldControllerProvider.get().orElse(null);
        mSideUnfoldController = unfoldControllerProvider.get().orElse(null);
        mLogger = logger;
        mRecentTasks = recentTasks;
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
        mTaskOrganizer.applyTransaction(wct);
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

        // Build a request WCT that will launch both apps such that task 0 is on the main stage
        // while task 1 is on the side stage.
        mMainStage.activate(getMainStageBounds(), wct, false /* reparent */);
        mSideStage.setBounds(getSideStageBounds(), wct);

        mSplitLayout.setDivideRatio(splitRatio);
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
        // Ensure divider is invisible before transition.
        setDividerVisibility(false /* visible */);
        // Init divider first to make divider leash for remote animation target.
        mSplitLayout.init();
        // Set false to avoid record new bounds with old task still on top;
        mShouldUpdateRecents = false;
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
                mIsDividerRemoteAnimating = true;
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
                                mIsDividerRemoteAnimating = false;
                                mShouldUpdateRecents = true;
                                setDividerVisibility(true /* visible */);
                                mSyncQueue.queue(evictWct);
                                mSyncQueue.runInSync(t -> applyDividerVisibility(t));
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
                mIsDividerRemoteAnimating = false;
                mShouldUpdateRecents = true;
                setDividerVisibility(true /* visible */);
                mSyncQueue.queue(evictWct);
                mSyncQueue.runInSync(t -> applyDividerVisibility(t));
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
        if (mMainStage.isActive()) {
            mMainStage.moveToTop(getMainStageBounds(), wct);
        } else {
            // Build a request WCT that will launch both apps such that task 0 is on the main stage
            // while task 1 is on the side stage.
            mMainStage.activate(getMainStageBounds(), wct, false /* reparent */);
        }
        mSideStage.moveToTop(getSideStageBounds(), wct);

        // Make sure the launch options will put tasks in the corresponding split roots
        addActivityOptions(mainOptions, mMainStage);
        addActivityOptions(sideOptions, mSideStage);

        // Add task launch requests
        wct.startTask(mainTaskId, mainOptions);
        wct.startTask(sideTaskId, sideOptions);

        // Using legacy transitions, so we can't use blast sync since it conflicts.
        mTaskOrganizer.applyTransaction(wct);
    }

    public void startIntent(PendingIntent intent, Intent fillInIntent,
            @StageType int stage, @SplitPosition int position,
            @androidx.annotation.Nullable Bundle options,
            @Nullable RemoteTransition remoteTransition) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        options = resolveStartStage(stage, position, options, wct);
        wct.sendPendingIntent(intent, fillInIntent, options);
        mSplitTransitions.startEnterTransition(
                TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE, wct, remoteTransition, this);
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
                    // Exit split-screen and launch fullscreen since stage wasn't specified.
                    prepareExitSplitScreen(STAGE_TYPE_UNDEFINED, wct);
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

    void setSideStageVisibility(boolean visible) {
        if (mSideStageListener.mVisible == visible) return;

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mSideStage.setVisibility(visible, wct);
        mTaskOrganizer.applyTransaction(wct);
    }

    void onKeyguardOccludedChanged(boolean occluded) {
        // Do not exit split directly, because it needs to wait for task info update to determine
        // which task should remain on top after split dismissed.
        mKeyguardOccluded = occluded;
    }

    void onKeyguardVisibilityChanged(boolean showing) {
        if (!showing && mMainStage.isActive()
                && mTopStageAfterFoldDismiss != STAGE_TYPE_UNDEFINED) {
            exitSplitScreen(mTopStageAfterFoldDismiss == STAGE_TYPE_MAIN ? mMainStage : mSideStage,
                    EXIT_REASON_DEVICE_FOLDED);
        }
    }

    void onFinishedWakingUp() {
        if (mMainStage.isActive()) {
            exitSplitScreenIfKeyguardOccluded();
        }
        mDeviceSleep = false;
    }

    void onFinishedGoingToSleep() {
        mDeviceSleep = true;
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

    private void exitSplitScreen(StageTaskListener childrenToTop, @ExitReason int exitReason) {
        if (!mMainStage.isActive()) return;

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        applyExitSplitScreen(childrenToTop, wct, exitReason);
    }

    private void exitSplitScreenIfKeyguardOccluded() {
        final boolean mainStageVisible = mMainStageListener.mVisible;
        final boolean oneStageVisible = mainStageVisible ^ mSideStageListener.mVisible;
        if (mDeviceSleep && mKeyguardOccluded && oneStageVisible) {
            // Only the stages include show-when-locked activity is visible while keyguard occluded.
            // Dismiss split because there's show-when-locked activity showing on top of keyguard.
            // Also make sure the task contains show-when-locked activity remains on top after split
            // dismissed.
            final StageTaskListener toTop = mainStageVisible ? mMainStage : mSideStage;
            exitSplitScreen(toTop, EXIT_REASON_SCREEN_LOCKED_SHOW_ON_TOP);
        }
    }

    private void applyExitSplitScreen(StageTaskListener childrenToTop,
            WindowContainerTransaction wct, @ExitReason int exitReason) {
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
        mSideStage.removeAllTasks(wct, !fromEnteringPip && childrenToTop == mSideStage);
        mMainStage.deactivate(wct, !fromEnteringPip && childrenToTop == mMainStage);
        mTaskOrganizer.applyTransaction(wct);
        mSyncQueue.runInSync(t -> t
                .setWindowCrop(mMainStage.mRootLeash, null)
                .setWindowCrop(mSideStage.mRootLeash, null));

        // Hide divider and reset its position.
        setDividerVisibility(false);
        mSplitLayout.resetDividerPosition();
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
    void prepareExitSplitScreen(@StageType int stageToTop,
            @NonNull WindowContainerTransaction wct) {
        mSideStage.removeAllTasks(wct, stageToTop == STAGE_TYPE_SIDE);
        mMainStage.deactivate(wct, stageToTop == STAGE_TYPE_MAIN);
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
        }
    }

    private void onStageRootTaskAppeared(StageListenerImpl stageListener) {
        if (mMainStageListener.mHasRootTask && mSideStageListener.mHasRootTask) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            // Make the stages adjacent to each other so they occlude what's behind them.
            wct.setAdjacentRoots(mMainStage.mRootTaskInfo.token, mSideStage.mRootTaskInfo.token,
                    true /* moveTogether */);
            wct.setLaunchAdjacentFlagRoot(mSideStage.mRootTaskInfo.token);
            mTaskOrganizer.applyTransaction(wct);
        }
    }

    private void onStageRootTaskVanished(StageListenerImpl stageListener) {
        if (stageListener == mMainStageListener || stageListener == mSideStageListener) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.clearLaunchAdjacentFlagRoot(mSideStage.mRootTaskInfo.token);
            // Deactivate the main stage if it no longer has a root task.
            mMainStage.deactivate(wct);
            mTaskOrganizer.applyTransaction(wct);
        }
    }

    private void setDividerVisibility(boolean visible) {
        if (mIsDividerRemoteAnimating || mDividerVisible == visible) return;
        mDividerVisible = visible;
        if (visible) {
            mSplitLayout.init();
            updateUnfoldBounds();
        } else {
            mSplitLayout.release();
        }
        sendSplitVisibilityChanged();
    }

    private void onStageVisibilityChanged(StageListenerImpl stageListener) {
        final boolean sideStageVisible = mSideStageListener.mVisible;
        final boolean mainStageVisible = mMainStageListener.mVisible;
        final boolean bothStageVisible = sideStageVisible && mainStageVisible;
        final boolean bothStageInvisible = !sideStageVisible && !mainStageVisible;
        final boolean sameVisibility = sideStageVisible == mainStageVisible;
        // Only add or remove divider when both visible or both invisible to avoid sometimes we only
        // got one stage visibility changed for a moment and it will cause flicker.
        if (sameVisibility) {
            setDividerVisibility(bothStageVisible);
        }

        if (bothStageInvisible) {
            if (mExitSplitScreenOnHide
                    // Don't dismiss staged split when both stages are not visible due to sleeping
                    // display, like the cases keyguard showing or screen off.
                    || (!mMainStage.mRootTaskInfo.isSleeping
                    && !mSideStage.mRootTaskInfo.isSleeping)) {
            // Don't dismiss staged split when both stages are not visible due to sleeping display,
            // like the cases keyguard showing or screen off.
                exitSplitScreen(null /* childrenToTop */, EXIT_REASON_RETURN_HOME);
            }
        }
        exitSplitScreenIfKeyguardOccluded();

        mSyncQueue.runInSync(t -> {
            // Same above, we only set root tasks and divider leash visibility when both stage
            // change to visible or invisible to avoid flicker.
            if (sameVisibility) {
                t.setVisibility(mSideStage.mRootLeash, bothStageVisible)
                        .setVisibility(mMainStage.mRootLeash, bothStageVisible);
                applyDividerVisibility(t);
            }
        });
    }

    private void applyDividerVisibility(SurfaceControl.Transaction t) {
        if  (mIsDividerRemoteAnimating) return;

        final SurfaceControl dividerLeash = mSplitLayout.getDividerLeash();
        if (dividerLeash == null) return;

        if (mDividerVisible) {
            t.show(dividerLeash)
                    .setAlpha(dividerLeash, 1)
                    .setLayer(dividerLeash, SPLIT_DIVIDER_LAYER)
                    .setPosition(dividerLeash,
                            mSplitLayout.getDividerBounds().left,
                            mSplitLayout.getDividerBounds().top);
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
        } else if (isSideStage) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            // Make sure the main stage is active.
            mMainStage.activate(getMainStageBounds(), wct, true /* reparent */);
            mSideStage.moveToTop(getSideStageBounds(), wct);
            mSyncQueue.queue(wct);
            mSyncQueue.runInSync(t -> updateSurfaceBounds(mSplitLayout, t));
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

    @VisibleForTesting
    IBinder onSnappedToDismissTransition(boolean mainStageToTop) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        prepareExitSplitScreen(mainStageToTop ? STAGE_TYPE_MAIN : STAGE_TYPE_SIDE, wct);
        return mSplitTransitions.startSnapToDismiss(wct, this);
    }

    @Override
    public void onSnappedToDismiss(boolean bottomOrRight) {
        final boolean mainStageToTop =
                bottomOrRight ? mSideStagePosition == SPLIT_POSITION_BOTTOM_OR_RIGHT
                        : mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT;
        if (ENABLE_SHELL_TRANSITIONS) {
            onSnappedToDismissTransition(mainStageToTop);
            return;
        }
        exitSplitScreen(mainStageToTop ? mMainStage : mSideStage, EXIT_REASON_DRAG_DIVIDER);
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
        mSyncQueue.runInSync(t -> updateSurfaceBounds(layout, t));
    }

    @Override
    public void onLayoutSizeChanging(SplitLayout layout) {
        mSyncQueue.runInSync(t -> {
            updateSurfaceBounds(layout, t);
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
            updateSurfaceBounds(layout, t);
            mMainStage.onResized(getMainStageBounds(), t);
            mSideStage.onResized(getSideStageBounds(), t);
        });
        mLogger.logResize(mSplitLayout.getDividerPositionAsFraction());
    }

    private void updateUnfoldBounds() {
        if (mMainUnfoldController != null && mSideUnfoldController != null) {
            mMainUnfoldController.onLayoutChanged(getMainStageBounds());
            mSideUnfoldController.onLayoutChanged(getSideStageBounds());
        }
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

    void updateSurfaceBounds(@Nullable SplitLayout layout, @NonNull SurfaceControl.Transaction t) {
        final StageTaskListener topLeftStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mSideStage : mMainStage;
        final StageTaskListener bottomRightStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mMainStage : mSideStage;
        (layout != null ? layout : mSplitLayout).applySurfaceChanges(t, topLeftStage.mRootLeash,
                bottomRightStage.mRootLeash, topLeftStage.mDimLayer, bottomRightStage.mDimLayer);
    }

    @Override
    public int getSplitItemPosition(WindowContainerToken token) {
        if (token == null) {
            return SPLIT_POSITION_UNDEFINED;
        }

        if (token.equals(mMainStage.mRootTaskInfo.getToken())) {
            return getMainStagePosition();
        } else if (token.equals(mSideStage.mRootTaskInfo.getToken())) {
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

    @Override
    public void onDisplayAreaAppeared(DisplayAreaInfo displayAreaInfo) {
        mDisplayAreaInfo = displayAreaInfo;
        if (mSplitLayout == null) {
            mSplitLayout = new SplitLayout(TAG + "SplitDivider", mContext,
                    mDisplayAreaInfo.configuration, this, mParentContainerCallbacks,
                    mDisplayImeController, mTaskOrganizer, false /* applyDismissingParallax */);
            mDisplayInsetsController.addInsetsChangedListener(mDisplayId, mSplitLayout);

            if (mMainUnfoldController != null && mSideUnfoldController != null) {
                mMainUnfoldController.init();
                mSideUnfoldController.init();
            }
        }
    }

    @Override
    public void onDisplayAreaVanished(DisplayAreaInfo displayAreaInfo) {
        throw new IllegalStateException("Well that was unexpected...");
    }

    @Override
    public void onDisplayAreaInfoChanged(DisplayAreaInfo displayAreaInfo) {
        mDisplayAreaInfo = displayAreaInfo;
        if (mSplitLayout != null
                && mSplitLayout.updateConfiguration(mDisplayAreaInfo.configuration)
                && mMainStage.isActive()) {
            onLayoutSizeChanged(mSplitLayout);
        }
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
            // still want to monitor everything while in split-screen, so return non-null.
            return isSplitScreenVisible() ? new WindowContainerTransaction() : null;
        }

        WindowContainerTransaction out = null;
        final @WindowManager.TransitionType int type = request.getType();
        if (isSplitScreenVisible()) {
            // try to handle everything while in split-screen, so return a WCT even if it's empty.
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  split is active so using split"
                            + "Transition to handle request. triggerTask=%d type=%s mainChildren=%d"
                            + " sideChildren=%d", triggerTask.taskId, transitTypeToString(type),
                    mMainStage.getChildCount(), mSideStage.getChildCount());
            out = new WindowContainerTransaction();
            final StageTaskListener stage = getStageOfTask(triggerTask);
            if (stage != null) {
                // dismiss split if the last task in one of the stages is going away
                if (isClosingType(type) && stage.getChildCount() == 1) {
                    // The top should be the opposite side that is closing:
                    mDismissTop = getStageType(stage) == STAGE_TYPE_MAIN
                            ? STAGE_TYPE_SIDE : STAGE_TYPE_MAIN;
                }
            } else {
                if (triggerTask.getActivityType() == ACTIVITY_TYPE_HOME && isOpeningType(type)) {
                    // Going home so dismiss both.
                    mDismissTop = STAGE_TYPE_UNDEFINED;
                }
            }
            if (mDismissTop != NO_DISMISS) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  splitTransition "
                                + " deduced Dismiss from request. toTop=%s",
                        stageTypeToString(mDismissTop));
                prepareExitSplitScreen(mDismissTop, out);
                mSplitTransitions.mPendingDismiss = transition;
            }
        } else {
            // Not in split mode, so look for an open into a split stage just so we can whine and
            // complain about how this isn't a supported operation.
            if ((type == TRANSIT_OPEN || type == TRANSIT_TO_FRONT)) {
                if (getStageOfTask(triggerTask) != null) {
                    throw new IllegalStateException("Entering split implicitly with only one task"
                            + " isn't supported.");
                }
            }
        }
        return out;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (transition != mSplitTransitions.mPendingDismiss
                && transition != mSplitTransitions.mPendingEnter) {
            // Not entering or exiting, so just do some house-keeping and validation.

            // If we're not in split-mode, just abort so something else can handle it.
            if (!isSplitScreenVisible()) return false;

            for (int iC = 0; iC < info.getChanges().size(); ++iC) {
                final TransitionInfo.Change change = info.getChanges().get(iC);
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
        } else if (mSplitTransitions.mPendingDismiss == transition) {
            shouldAnimate = startPendingDismissAnimation(transition, info, startTransaction);
        }
        if (!shouldAnimate) return false;

        mSplitTransitions.playAnimation(transition, info, startTransaction, finishTransaction,
                finishCallback, mMainStage.mRootTaskInfo.token, mSideStage.mRootTaskInfo.token);
        return true;
    }

    private boolean startPendingEnterAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction t) {
        if (info.getType() == TRANSIT_SPLIT_SCREEN_PAIR_OPEN) {
            // First, verify that we actually have opened 2 apps in split.
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
            if (mainChild == null || sideChild == null) {
                throw new IllegalStateException("Launched 2 tasks in split, but didn't receive"
                        + " 2 tasks in transition. Possibly one of them failed to launch");
                // TODO: fallback logic. Probably start a new transition to exit split before
                //       applying anything here. Ideally consolidate with transition-merging.
            }

            // Update local states (before animating).
            setDividerVisibility(true);
            setSideStagePosition(SPLIT_POSITION_BOTTOM_OR_RIGHT, false /* updateBounds */,
                    null /* wct */);
            setSplitsVisible(true);

            addDividerBarToTransition(info, t, true /* show */);

            // Make some noise if things aren't totally expected. These states shouldn't effect
            // transitions locally, but remotes (like Launcher) may get confused if they were
            // depending on listener callbacks. This can happen because task-organizer callbacks
            // aren't serialized with transition callbacks.
            // TODO(b/184679596): Find a way to either include task-org information in
            //                    the transition, or synchronize task-org callbacks.
            if (!mMainStage.containsTask(mainChild.getTaskInfo().taskId)) {
                Log.w(TAG, "Expected onTaskAppeared on " + mMainStage
                        + " to have been called with " + mainChild.getTaskInfo().taskId
                        + " before startAnimation().");
            }
            if (!mSideStage.containsTask(sideChild.getTaskInfo().taskId)) {
                Log.w(TAG, "Expected onTaskAppeared on " + mSideStage
                        + " to have been called with " + sideChild.getTaskInfo().taskId
                        + " before startAnimation().");
            }
            return true;
        } else {
            // TODO: other entry method animations
            throw new RuntimeException("Unsupported split-entry");
        }
    }

    private boolean startPendingDismissAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction t) {
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

        // Update local states.
        setSplitsVisible(false);
        // Wait until after animation to update divider

        if (info.getType() == TRANSIT_SPLIT_DISMISS_SNAP) {
            // Reset crops so they don't interfere with subsequent launches
            t.setWindowCrop(mMainStage.mRootLeash, null);
            t.setWindowCrop(mSideStage.mRootLeash, null);
        }

        if (mDismissTop == STAGE_TYPE_UNDEFINED) {
            // Going home (dismissing both splits)

            // TODO: Have a proper remote for this. Until then, though, reset state and use the
            //       normal animation stuff (which falls back to the normal launcher remote).
            t.hide(mSplitLayout.getDividerLeash());
            setDividerVisibility(false);
            mSplitTransitions.mPendingDismiss = null;
            return false;
        }

        addDividerBarToTransition(info, t, false /* show */);
        // We're dismissing split by moving the other one to fullscreen.
        // Since we don't have any animations for this yet, just use the internal example
        // animations.
        return true;
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
            t.setLayer(leash, SPLIT_DIVIDER_LAYER);
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
            StageCoordinator.this.onStageRootTaskAppeared(this);
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
            StageCoordinator.this.onStageRootTaskVanished(this);
        }

        @Override
        public void onNoLongerSupportMultiWindow() {
            if (mMainStage.isActive()) {
                StageCoordinator.this.exitSplitScreen(null /* childrenToTop */,
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
