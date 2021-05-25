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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.transitTypeToString;

import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitLayout.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_MAIN;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_SIDE;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreen.stageTypeToString;
import static com.android.wm.shell.splitscreen.SplitScreenTransitions.FLAG_IS_DIVIDER_BAR;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_DISMISS_SNAP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_SCREEN_PAIR_OPEN;
import static com.android.wm.shell.transition.Transitions.isClosingType;
import static com.android.wm.shell.transition.Transitions.isOpeningType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.window.DisplayAreaInfo;
import android.window.IRemoteTransition;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.split.SplitLayout;
import com.android.wm.shell.common.split.SplitLayout.SplitPosition;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

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
    private final SideStage mSideStage;
    private final StageListenerImpl mSideStageListener = new StageListenerImpl();
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
    private final SplitScreenTransitions mSplitTransitions;
    private boolean mExitSplitScreenOnHide = true;

    // TODO(b/187041611): remove this flag after totally deprecated legacy split
    /** Whether the device is supporting legacy split or not. */
    private boolean mUseLegacySplit;

    @SplitScreen.StageType int mDismissTop = NO_DISMISS;

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

    StageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer, ShellTaskOrganizer taskOrganizer,
            DisplayImeController displayImeController, Transitions transitions,
            TransactionPool transactionPool) {
        mContext = context;
        mDisplayId = displayId;
        mSyncQueue = syncQueue;
        mRootTDAOrganizer = rootTDAOrganizer;
        mTaskOrganizer = taskOrganizer;
        mMainStage = new MainStage(
                mTaskOrganizer,
                mDisplayId,
                mMainStageListener,
                mSyncQueue,
                mSurfaceSession);
        mSideStage = new SideStage(
                mTaskOrganizer,
                mDisplayId,
                mSideStageListener,
                mSyncQueue,
                mSurfaceSession);
        mDisplayImeController = displayImeController;
        mRootTDAOrganizer.registerListener(displayId, this);
        mSplitTransitions = new SplitScreenTransitions(transactionPool, transitions,
                mOnTransitionAnimationComplete);
        transitions.addHandler(this);
    }

    @VisibleForTesting
    StageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTDAOrganizer, ShellTaskOrganizer taskOrganizer,
            MainStage mainStage, SideStage sideStage, DisplayImeController displayImeController,
            SplitLayout splitLayout, Transitions transitions, TransactionPool transactionPool) {
        mContext = context;
        mDisplayId = displayId;
        mSyncQueue = syncQueue;
        mRootTDAOrganizer = rootTDAOrganizer;
        mTaskOrganizer = taskOrganizer;
        mMainStage = mainStage;
        mSideStage = sideStage;
        mDisplayImeController = displayImeController;
        mRootTDAOrganizer.registerListener(displayId, this);
        mSplitLayout = splitLayout;
        mSplitTransitions = new SplitScreenTransitions(transactionPool, transitions,
                mOnTransitionAnimationComplete);
        transitions.addHandler(this);
    }

    @VisibleForTesting
    SplitScreenTransitions getSplitTransitions() {
        return mSplitTransitions;
    }

    boolean isSplitScreenVisible() {
        return mSideStageListener.mVisible && mMainStageListener.mVisible;
    }

    boolean moveToSideStage(ActivityManager.RunningTaskInfo task,
            @SplitPosition int sideStagePosition) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        setSideStagePosition(sideStagePosition);
        mMainStage.activate(getMainStageBounds(), wct);
        mSideStage.addTask(task, getSideStageBounds(), wct);
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
            @Nullable Bundle sideOptions, @SplitPosition int sidePosition,
            @Nullable IRemoteTransition remoteTransition) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mainOptions = mainOptions != null ? mainOptions : new Bundle();
        sideOptions = sideOptions != null ? sideOptions : new Bundle();
        setSideStagePosition(sidePosition);

        // Build a request WCT that will launch both apps such that task 0 is on the main stage
        // while task 1 is on the side stage.
        mMainStage.activate(getMainStageBounds(), wct);
        mSideStage.setBounds(getSideStageBounds(), wct);

        // Make sure the launch options will put tasks in the corresponding split roots
        addActivityOptions(mainOptions, mMainStage);
        addActivityOptions(sideOptions, mSideStage);

        // Add task launch requests
        wct.startTask(mainTaskId, mainOptions);
        wct.startTask(sideTaskId, sideOptions);

        mSplitTransitions.startEnterTransition(
                TRANSIT_SPLIT_SCREEN_PAIR_OPEN, wct, remoteTransition, this);
    }

    @SplitLayout.SplitPosition
    int getSideStagePosition() {
        return mSideStagePosition;
    }

    @SplitLayout.SplitPosition
    int getMainStagePosition() {
        return mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT
                ? SPLIT_POSITION_BOTTOM_OR_RIGHT : SPLIT_POSITION_TOP_OR_LEFT;
    }

    void setSideStagePosition(@SplitPosition int sideStagePosition) {
        setSideStagePosition(sideStagePosition, true /* updateBounds */);
    }

    private void setSideStagePosition(@SplitPosition int sideStagePosition,
            boolean updateBounds) {
        if (mSideStagePosition == sideStagePosition) return;
        mSideStagePosition = sideStagePosition;
        sendOnStagePositionChanged();

        if (mSideStageListener.mVisible && updateBounds) {
            onBoundsChanged(mSplitLayout);
        }
    }

    void setSideStageVisibility(boolean visible) {
        if (mSideStageListener.mVisible == visible) return;

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mSideStage.setVisibility(visible, wct);
        mTaskOrganizer.applyTransaction(wct);
    }

    void exitSplitScreen() {
        exitSplitScreen(null /* childrenToTop */);
    }

    void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) {
        mExitSplitScreenOnHide = exitSplitScreenOnHide;
    }

    private void exitSplitScreen(StageTaskListener childrenToTop) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mSideStage.removeAllTasks(wct, childrenToTop == mSideStage);
        mMainStage.deactivate(wct, childrenToTop == mMainStage);
        mTaskOrganizer.applyTransaction(wct);
        // Reset divider position.
        mSplitLayout.resetDividerPosition();
    }

    private void prepareExitSplitScreen(@SplitScreen.StageType int stageToTop,
            @NonNull WindowContainerTransaction wct) {
        mSideStage.removeAllTasks(wct, stageToTop == STAGE_TYPE_SIDE);
        mMainStage.deactivate(wct, stageToTop == STAGE_TYPE_MAIN);
    }

    void getStageBounds(Rect outTopOrLeftBounds, Rect outBottomOrRightBounds) {
        outTopOrLeftBounds.set(mSplitLayout.getBounds1());
        outBottomOrRightBounds.set(mSplitLayout.getBounds2());
    }

    private void addActivityOptions(Bundle opts, StageTaskListener stage) {
        opts.putParcelable(KEY_LAUNCH_ROOT_TASK_TOKEN, stage.mRootTaskInfo.token);
    }

    void updateActivityOptions(Bundle opts, @SplitPosition int position) {
        addActivityOptions(opts, position == mSideStagePosition ? mSideStage : mMainStage);

        if (!mMainStage.isActive()) {
            // Activate the main stage in anticipation of an app launch.
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            mMainStage.activate(getMainStageBounds(), wct);
            mSideStage.setBounds(getSideStageBounds(), wct);
            mTaskOrganizer.applyTransaction(wct);
        }
    }

    void registerSplitScreenListener(SplitScreen.SplitScreenListener listener) {
        if (mListeners.contains(listener)) return;
        mListeners.add(listener);
        listener.onStagePositionChanged(STAGE_TYPE_MAIN, getMainStagePosition());
        listener.onStagePositionChanged(STAGE_TYPE_SIDE, getSideStagePosition());
        mSideStage.onSplitScreenListenerRegistered(listener, STAGE_TYPE_SIDE);
        mMainStage.onSplitScreenListenerRegistered(listener, STAGE_TYPE_MAIN);
    }

    void unregisterSplitScreenListener(SplitScreen.SplitScreenListener listener) {
        mListeners.remove(listener);
    }

    private void sendOnStagePositionChanged() {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            final SplitScreen.SplitScreenListener l = mListeners.get(i);
            l.onStagePositionChanged(STAGE_TYPE_MAIN, getMainStagePosition());
            l.onStagePositionChanged(STAGE_TYPE_SIDE, getSideStagePosition());
        }
    }

    private void onStageChildTaskStatusChanged(
            StageListenerImpl stageListener, int taskId, boolean present, boolean visible) {

        int stage;
        if (present) {
            stage = stageListener == mSideStageListener ? STAGE_TYPE_SIDE : STAGE_TYPE_MAIN;
        } else {
            // No longer on any stage
            stage = STAGE_TYPE_UNDEFINED;
        }

        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onTaskStageChanged(taskId, stage, visible);
        }
    }

    private void onStageRootTaskAppeared(StageListenerImpl stageListener) {
        if (mMainStageListener.mHasRootTask && mSideStageListener.mHasRootTask) {
            mUseLegacySplit = mContext.getResources().getBoolean(R.bool.config_useLegacySplit);
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            // Make the stages adjacent to each other so they occlude what's behind them.
            wct.setAdjacentRoots(mMainStage.mRootTaskInfo.token, mSideStage.mRootTaskInfo.token);

            // Only sets side stage as launch-adjacent-flag-root when the device is not using legacy
            // split to prevent new split behavior confusing users.
            if (!mUseLegacySplit) {
                wct.setLaunchAdjacentFlagRoot(mSideStage.mRootTaskInfo.token);
            }

            mTaskOrganizer.applyTransaction(wct);
        }
    }

    private void onStageRootTaskVanished(StageListenerImpl stageListener) {
        if (stageListener == mMainStageListener || stageListener == mSideStageListener) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            // Deactivate the main stage if it no longer has a root task.
            mMainStage.deactivate(wct);

            if (!mUseLegacySplit) {
                wct.clearLaunchAdjacentFlagRoot(mSideStage.mRootTaskInfo.token);
            }

            mTaskOrganizer.applyTransaction(wct);
        }
    }

    private void setDividerVisibility(boolean visible) {
        if (mDividerVisible == visible) return;
        mDividerVisible = visible;
        if (visible) {
            mSplitLayout.init();
        } else {
            mSplitLayout.release();
        }
    }

    private void onStageVisibilityChanged(StageListenerImpl stageListener) {
        final boolean sideStageVisible = mSideStageListener.mVisible;
        final boolean mainStageVisible = mMainStageListener.mVisible;
        // Divider is only visible if both the main stage and side stages are visible
        setDividerVisibility(isSplitScreenVisible());

        if (mExitSplitScreenOnHide && !mainStageVisible && !sideStageVisible) {
            // Exit split-screen if both stage are not visible.
            // TODO: This is only a temporary request from UX and is likely to be removed soon...
            exitSplitScreen();
        }

        if (mainStageVisible) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            if (sideStageVisible) {
                // The main stage configuration should to follow split layout when side stage is
                // visible.
                mMainStage.updateConfiguration(
                        WINDOWING_MODE_MULTI_WINDOW, getMainStageBounds(), wct);
            } else {
                // We want the main stage configuration to be fullscreen when the side stage isn't
                // visible.
                mMainStage.updateConfiguration(WINDOWING_MODE_FULLSCREEN, null, wct);
            }
            // TODO: Change to `mSyncQueue.queue(wct)` once BLAST is stable.
            mTaskOrganizer.applyTransaction(wct);
        }

        mSyncQueue.runInSync(t -> {
            final SurfaceControl dividerLeash = mSplitLayout.getDividerLeash();
            final SurfaceControl sideStageLeash = mSideStage.mRootLeash;
            final SurfaceControl mainStageLeash = mMainStage.mRootLeash;

            if (dividerLeash != null) {
                if (mDividerVisible) {
                    t.show(dividerLeash)
                            .setLayer(dividerLeash, Integer.MAX_VALUE)
                            .setPosition(dividerLeash,
                                    mSplitLayout.getDividerBounds().left,
                                    mSplitLayout.getDividerBounds().top);
                } else {
                    t.hide(dividerLeash);
                }
            }

            if (sideStageVisible) {
                final Rect sideStageBounds = getSideStageBounds();
                t.show(sideStageLeash)
                        .setPosition(sideStageLeash,
                                sideStageBounds.left, sideStageBounds.top)
                        .setWindowCrop(sideStageLeash,
                                sideStageBounds.width(), sideStageBounds.height());
            } else {
                t.hide(sideStageLeash);
            }

            if (mainStageVisible) {
                final Rect mainStageBounds = getMainStageBounds();
                t.show(mainStageLeash);
                if (sideStageVisible) {
                    t.setPosition(mainStageLeash, mainStageBounds.left, mainStageBounds.top)
                            .setWindowCrop(mainStageLeash,
                                    mainStageBounds.width(), mainStageBounds.height());
                } else {
                    // Clear window crop and position if side stage isn't visible.
                    t.setPosition(mainStageLeash, 0, 0)
                            .setWindowCrop(mainStageLeash, null);
                }
            } else {
                t.hide(mainStageLeash);
            }
        });
    }

    private void onStageHasChildrenChanged(StageListenerImpl stageListener) {
        final boolean hasChildren = stageListener.mHasChildren;
        final boolean isSideStage = stageListener == mSideStageListener;
        if (!hasChildren) {
            if (isSideStage && mMainStageListener.mVisible) {
                // Exit to main stage if side stage no longer has children.
                exitSplitScreen(mMainStage);
            } else if (!isSideStage && mSideStageListener.mVisible) {
                // Exit to side stage if main stage no longer has children.
                exitSplitScreen(mSideStage);
            }
        } else if (isSideStage) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            // Make sure the main stage is active.
            mMainStage.activate(getMainStageBounds(), wct);
            mSideStage.setBounds(getSideStageBounds(), wct);
            // Reorder side stage to the top whenever there's a new child task appeared in side
            // stage. This is needed to prevent main stage occludes side stage and makes main stage
            // flipping between fullscreen and multi-window windowing mode.
            wct.reorder(mSideStage.mRootTaskInfo.token, true);
            mTaskOrganizer.applyTransaction(wct);
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
        exitSplitScreen(mainStageToTop ? mMainStage : mSideStage);
    }

    @Override
    public void onDoubleTappedDivider() {
        setSideStagePosition(mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT
                ? SPLIT_POSITION_BOTTOM_OR_RIGHT : SPLIT_POSITION_TOP_OR_LEFT);
    }

    @Override
    public void onBoundsChanging(SplitLayout layout) {
        final StageTaskListener topLeftStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mSideStage : mMainStage;
        final StageTaskListener bottomRightStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mMainStage : mSideStage;

        mSyncQueue.runInSync(t -> layout.applySurfaceChanges(t, topLeftStage.mRootLeash,
                bottomRightStage.mRootLeash, topLeftStage.mDimLayer, bottomRightStage.mDimLayer));
    }

    @Override
    public void onBoundsChanged(SplitLayout layout) {
        final StageTaskListener topLeftStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mSideStage : mMainStage;
        final StageTaskListener bottomRightStage =
                mSideStagePosition == SPLIT_POSITION_TOP_OR_LEFT ? mMainStage : mSideStage;

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        layout.applyTaskChanges(wct, topLeftStage.mRootTaskInfo, bottomRightStage.mRootTaskInfo);
        mSyncQueue.queue(wct);
        mSyncQueue.runInSync(t -> layout.applySurfaceChanges(t, topLeftStage.mRootLeash,
                bottomRightStage.mRootLeash, topLeftStage.mDimLayer, bottomRightStage.mDimLayer));
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
    public void onDisplayAreaAppeared(DisplayAreaInfo displayAreaInfo) {
        mDisplayAreaInfo = displayAreaInfo;
        if (mSplitLayout == null) {
            mSplitLayout = new SplitLayout(TAG + "SplitDivider", mContext,
                    mDisplayAreaInfo.configuration, this,
                    b -> mRootTDAOrganizer.attachToDisplayArea(mDisplayId, b),
                    mDisplayImeController, mTaskOrganizer);
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
                && mSplitLayout.updateConfiguration(mDisplayAreaInfo.configuration)) {
            onBoundsChanged(mSplitLayout);
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

    @SplitScreen.StageType
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
                final @SplitScreen.StageType int stageType = getStageType(getStageOfTask(taskInfo));
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
            setSideStagePosition(SPLIT_POSITION_BOTTOM_OR_RIGHT, false /* updateBounds */);
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
            t.setLayer(leash, Integer.MAX_VALUE);
            t.setPosition(leash, bounds.left, bounds.top);
            t.show(leash);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + TAG + " mDisplayId=" + mDisplayId);
        pw.println(innerPrefix + "mDividerVisible=" + mDividerVisible);
        pw.println(innerPrefix + "MainStage");
        pw.println(childPrefix + "isActive=" + mMainStage.isActive());
        mMainStageListener.dump(pw, childPrefix);
        pw.println(innerPrefix + "SideStage");
        mSideStageListener.dump(pw, childPrefix);
        pw.println(innerPrefix + "mSplitLayout=" + mSplitLayout);
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
        public void onRootTaskVanished() {
            reset();
            StageCoordinator.this.onStageRootTaskVanished(this);
        }

        @Override
        public void onNoLongerSupportMultiWindow() {
            if (mMainStage.isActive()) {
                StageCoordinator.this.exitSplitScreen();
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
