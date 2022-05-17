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

package com.android.wm.shell.pip.tv;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.app.TaskInfo;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.RemoteException;
import android.view.Gravity;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipAppOpsListener;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipParamsChangedForwarder;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manages the picture-in-picture (PIP) UI and states.
 */
public class TvPipController implements PipTransitionController.PipTransitionCallback,
        TvPipBoundsController.PipBoundsListener, TvPipMenuController.Delegate,
        TvPipNotificationController.Delegate, DisplayController.OnDisplaysChangedListener {
    private static final String TAG = "TvPipController";
    static final boolean DEBUG = false;

    private static final int NONEXISTENT_TASK_ID = -1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"}, value = {
            STATE_NO_PIP,
            STATE_PIP,
            STATE_PIP_MENU,
    })
    public @interface State {}

    /**
     * State when there is no applications in Pip.
     */
    private static final int STATE_NO_PIP = 0;
    /**
     * State when there is an applications in Pip and the Pip window located at its "normal" place
     * (usually the bottom right corner).
     */
    private static final int STATE_PIP = 1;
    /**
     * State when there is an applications in Pip and the Pip menu is open. In this state Pip window
     * is usually moved from its "normal" position on the screen to the "menu" position - which is
     * often at the middle of the screen, and gets slightly scaled up.
     */
    private static final int STATE_PIP_MENU = 2;

    private final Context mContext;

    private final TvPipBoundsState mTvPipBoundsState;
    private final TvPipBoundsAlgorithm mTvPipBoundsAlgorithm;
    private final TvPipBoundsController mTvPipBoundsController;
    private final PipAppOpsListener mAppOpsListener;
    private final PipTaskOrganizer mPipTaskOrganizer;
    private final PipMediaController mPipMediaController;
    private final TvPipNotificationController mPipNotificationController;
    private final TvPipMenuController mTvPipMenuController;
    private final ShellExecutor mMainExecutor;
    private final TvPipImpl mImpl = new TvPipImpl();

    private @State int mState = STATE_NO_PIP;
    private int mPreviousGravity = TvPipBoundsState.DEFAULT_TV_GRAVITY;
    private int mPinnedTaskId = NONEXISTENT_TASK_ID;

    private RemoteAction mCloseAction;
    // How long the shell will wait for the app to close the PiP if a custom action is set.
    private int mPipForceCloseDelay;

    private int mResizeAnimationDuration;
    private int mEduTextWindowExitAnimationDurationMs;

    public static Pip create(
            Context context,
            TvPipBoundsState tvPipBoundsState,
            TvPipBoundsAlgorithm tvPipBoundsAlgorithm,
            TvPipBoundsController tvPipBoundsController,
            PipAppOpsListener pipAppOpsListener,
            PipTaskOrganizer pipTaskOrganizer,
            PipTransitionController pipTransitionController,
            TvPipMenuController tvPipMenuController,
            PipMediaController pipMediaController,
            TvPipNotificationController pipNotificationController,
            TaskStackListenerImpl taskStackListener,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            DisplayController displayController,
            WindowManagerShellWrapper wmShell,
            ShellExecutor mainExecutor) {
        return new TvPipController(
                context,
                tvPipBoundsState,
                tvPipBoundsAlgorithm,
                tvPipBoundsController,
                pipAppOpsListener,
                pipTaskOrganizer,
                pipTransitionController,
                tvPipMenuController,
                pipMediaController,
                pipNotificationController,
                taskStackListener,
                pipParamsChangedForwarder,
                displayController,
                wmShell,
                mainExecutor).mImpl;
    }

    private TvPipController(
            Context context,
            TvPipBoundsState tvPipBoundsState,
            TvPipBoundsAlgorithm tvPipBoundsAlgorithm,
            TvPipBoundsController tvPipBoundsController,
            PipAppOpsListener pipAppOpsListener,
            PipTaskOrganizer pipTaskOrganizer,
            PipTransitionController pipTransitionController,
            TvPipMenuController tvPipMenuController,
            PipMediaController pipMediaController,
            TvPipNotificationController pipNotificationController,
            TaskStackListenerImpl taskStackListener,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            DisplayController displayController,
            WindowManagerShellWrapper wmShell,
            ShellExecutor mainExecutor) {
        mContext = context;
        mMainExecutor = mainExecutor;

        mTvPipBoundsState = tvPipBoundsState;
        mTvPipBoundsState.setDisplayId(context.getDisplayId());
        mTvPipBoundsState.setDisplayLayout(new DisplayLayout(context, context.getDisplay()));
        mTvPipBoundsAlgorithm = tvPipBoundsAlgorithm;
        mTvPipBoundsController = tvPipBoundsController;
        mTvPipBoundsController.setListener(this);

        mPipMediaController = pipMediaController;

        mPipNotificationController = pipNotificationController;
        mPipNotificationController.setDelegate(this);

        mTvPipMenuController = tvPipMenuController;
        mTvPipMenuController.setDelegate(this);

        mAppOpsListener = pipAppOpsListener;
        mPipTaskOrganizer = pipTaskOrganizer;
        pipTransitionController.registerPipTransitionCallback(this);

        loadConfigurations();

        registerPipParamsChangedListener(pipParamsChangedForwarder);
        registerTaskStackListenerCallback(taskStackListener);
        registerWmShellPinnedStackListener(wmShell);
        displayController.addDisplayWindowListener(this);
    }

    private void onConfigurationChanged(Configuration newConfig) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onConfigurationChanged(), state=%s", TAG, stateToName(mState));
        }

        if (isPipShown()) {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s:  > closing Pip.", TAG);
            }
            closePip();
        }

        loadConfigurations();
        mPipNotificationController.onConfigurationChanged(mContext);
        mTvPipBoundsAlgorithm.onConfigurationChanged(mContext);
    }

    /**
     * Returns {@code true} if Pip is shown.
     */
    private boolean isPipShown() {
        return mState != STATE_NO_PIP;
    }

    /**
     * Starts the process if bringing up the Pip menu if by issuing a command to move Pip
     * task/window to the "Menu" position. We'll show the actual Menu UI (eg. actions) once the Pip
     * task/window is properly positioned in {@link #onPipTransitionFinished(int)}.
     */
    @Override
    public void showPictureInPictureMenu() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: showPictureInPictureMenu(), state=%s", TAG, stateToName(mState));
        }

        if (mState == STATE_NO_PIP) {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s:  > cannot open Menu from the current state.", TAG);
            }
            return;
        }

        setState(STATE_PIP_MENU);
        mTvPipMenuController.showMenu();
        updatePinnedStackBounds();
    }

    @Override
    public void onMenuClosed() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: closeMenu(), state before=%s", TAG, stateToName(mState));
        }
        setState(STATE_PIP);
        updatePinnedStackBounds();
    }

    @Override
    public void onInMoveModeChanged() {
        updatePinnedStackBounds();
    }

    /**
     * Opens the "Pip-ed" Activity fullscreen.
     */
    @Override
    public void movePipToFullscreen() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: movePipToFullscreen(), state=%s", TAG, stateToName(mState));
        }

        mPipTaskOrganizer.exitPip(mResizeAnimationDuration, false /* requestEnterSplit */);
        onPipDisappeared();
    }

    @Override
    public void togglePipExpansion() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: togglePipExpansion()", TAG);
        }
        boolean expanding = !mTvPipBoundsState.isTvPipExpanded();
        int saveGravity = mTvPipBoundsAlgorithm
                .updateGravityOnExpandToggled(mPreviousGravity, expanding);
        if (saveGravity != Gravity.NO_GRAVITY) {
            mPreviousGravity = saveGravity;
        }
        mTvPipBoundsState.setTvPipManuallyCollapsed(!expanding);
        mTvPipBoundsState.setTvPipExpanded(expanding);
        mPipNotificationController.updateExpansionState();

        updatePinnedStackBounds();
    }

    @Override
    public void enterPipMovementMenu() {
        setState(STATE_PIP_MENU);
        mTvPipMenuController.showMovementMenuOnly();
    }

    @Override
    public void movePip(int keycode) {
        if (mTvPipBoundsAlgorithm.updateGravity(keycode)) {
            mTvPipMenuController.updateGravity(mTvPipBoundsState.getTvPipGravity());
            mPreviousGravity = Gravity.NO_GRAVITY;
            updatePinnedStackBounds();
        } else {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: Position hasn't changed", TAG);
            }
        }
    }

    @Override
    public int getPipGravity() {
        return mTvPipBoundsState.getTvPipGravity();
    }

    public int getOrientation() {
        return mTvPipBoundsState.getTvFixedPipOrientation();
    }

    @Override
    public void onKeepClearAreasChanged(int displayId, Set<Rect> restricted,
            Set<Rect> unrestricted) {
        if (mTvPipBoundsState.getDisplayId() == displayId) {
            boolean unrestrictedAreasChanged = !Objects.equals(unrestricted,
                    mTvPipBoundsState.getUnrestrictedKeepClearAreas());
            mTvPipBoundsState.setKeepClearAreas(restricted, unrestricted);
            updatePinnedStackBounds(mResizeAnimationDuration, unrestrictedAreasChanged);
        }
    }

    private void updatePinnedStackBounds() {
        updatePinnedStackBounds(mResizeAnimationDuration, true);
    }

    /**
     * Update the PiP bounds based on the state of the PiP and keep clear areas.
     */
    private void updatePinnedStackBounds(int animationDuration, boolean immediate) {
        if (mState == STATE_NO_PIP) {
            return;
        }
        final boolean stayAtAnchorPosition = mTvPipMenuController.isInMoveMode();
        final boolean disallowStashing = mState == STATE_PIP_MENU || stayAtAnchorPosition;
        mTvPipBoundsController.recalculatePipBounds(stayAtAnchorPosition, disallowStashing,
                animationDuration, immediate);
    }

    @Override
    public void onPipTargetBoundsChange(Rect newTargetBounds, int animationDuration) {
        mPipTaskOrganizer.scheduleAnimateResizePip(newTargetBounds,
                animationDuration, rect -> mTvPipMenuController.updateExpansionState());
        mTvPipMenuController.onPipTransitionStarted(newTargetBounds);
    }

    /**
     * Closes Pip window.
     */
    @Override
    public void closePip() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: closePip(), state=%s, loseAction=%s", TAG, stateToName(mState),
                    mCloseAction);
        }

        if (mCloseAction != null) {
            try {
                mCloseAction.getActionIntent().send();
            } catch (PendingIntent.CanceledException e) {
                ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: Failed to send close action, %s", TAG, e);
            }
            mMainExecutor.executeDelayed(() -> closeCurrentPiP(mPinnedTaskId), mPipForceCloseDelay);
        } else {
            closeCurrentPiP(mPinnedTaskId);
        }
    }

    private void closeCurrentPiP(int pinnedTaskId) {
        if (mPinnedTaskId != pinnedTaskId) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: PiP has already been closed by custom close action", TAG);
            return;
        }
        removeTask(mPinnedTaskId);
        onPipDisappeared();
    }

    @Override
    public void closeEduText() {
        updatePinnedStackBounds(mEduTextWindowExitAnimationDurationMs, false);
    }

    private void registerSessionListenerForCurrentUser() {
        mPipMediaController.registerSessionListenerForCurrentUser();
    }

    private void checkIfPinnedTaskAppeared() {
        final TaskInfo pinnedTask = getPinnedTaskInfo();
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: checkIfPinnedTaskAppeared(), task=%s", TAG, pinnedTask);
        }
        if (pinnedTask == null || pinnedTask.topActivity == null) return;
        mPinnedTaskId = pinnedTask.taskId;

        mPipMediaController.onActivityPinned();
        mPipNotificationController.show(pinnedTask.topActivity.getPackageName());
    }

    private void checkIfPinnedTaskIsGone() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onTaskStackChanged()", TAG);
        }

        if (isPipShown() && getPinnedTaskInfo() == null) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Pinned task is gone.", TAG);
            onPipDisappeared();
        }
    }

    private void onPipDisappeared() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onPipDisappeared() state=%s", TAG, stateToName(mState));
        }

        mPipNotificationController.dismiss();
        mTvPipMenuController.closeMenu();
        mTvPipBoundsState.resetTvPipState();
        mTvPipBoundsController.onPipDismissed();
        setState(STATE_NO_PIP);
        mPinnedTaskId = NONEXISTENT_TASK_ID;
    }

    @Override
    public void onPipTransitionStarted(int direction, Rect pipBounds) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onPipTransition_Started(), state=%s", TAG, stateToName(mState));
        }
        mTvPipMenuController.notifyPipAnimating(true);
    }

    @Override
    public void onPipTransitionCanceled(int direction) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onPipTransition_Canceled(), state=%s", TAG, stateToName(mState));
        }
        mTvPipMenuController.notifyPipAnimating(false);
    }

    @Override
    public void onPipTransitionFinished(int direction) {
        if (PipAnimationController.isInPipDirection(direction) && mState == STATE_NO_PIP) {
            setState(STATE_PIP);
        }
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onPipTransition_Finished(), state=%s", TAG, stateToName(mState));
        }
        mTvPipMenuController.notifyPipAnimating(false);
    }

    private void setState(@State int state) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: setState(), state=%s, prev=%s",
                    TAG, stateToName(state), stateToName(mState));
        }
        mState = state;
    }

    private void loadConfigurations() {
        final Resources res = mContext.getResources();
        mResizeAnimationDuration = res.getInteger(R.integer.config_pipResizeAnimationDuration);
        mPipForceCloseDelay = res.getInteger(R.integer.config_pipForceCloseDelay);
        mEduTextWindowExitAnimationDurationMs =
                res.getInteger(R.integer.pip_edu_text_window_exit_animation_duration_ms);
    }

    private void registerTaskStackListenerCallback(TaskStackListenerImpl taskStackListener) {
        taskStackListener.addListener(new TaskStackListenerCallback() {
            @Override
            public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
                checkIfPinnedTaskAppeared();
                mAppOpsListener.onActivityPinned(packageName);
            }

            @Override
            public void onActivityUnpinned() {
                mAppOpsListener.onActivityUnpinned();
            }

            @Override
            public void onTaskStackChanged() {
                checkIfPinnedTaskIsGone();
            }

            @Override
            public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                    boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                if (task.getWindowingMode() == WINDOWING_MODE_PINNED) {
                    if (DEBUG) {
                        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                                "%s: onPinnedActivityRestartAttempt()", TAG);
                    }

                    // If the "Pip-ed" Activity is launched again by Launcher or intent, make it
                    // fullscreen.
                    movePipToFullscreen();
                }
            }
        });
    }

    private void registerPipParamsChangedListener(PipParamsChangedForwarder provider) {
        provider.addListener(new PipParamsChangedForwarder.PipParamsChangedCallback() {
            @Override
            public void onActionsChanged(List<RemoteAction> actions,
                    RemoteAction closeAction) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                            "%s: onActionsChanged()", TAG);

                mTvPipMenuController.setAppActions(actions, closeAction);
                mCloseAction = closeAction;
            }

            @Override
            public void onAspectRatioChanged(float ratio) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                            "%s: onAspectRatioChanged: %f", TAG, ratio);

                mTvPipBoundsState.setAspectRatio(ratio);
                if (!mTvPipBoundsState.isTvPipExpanded()) {
                    updatePinnedStackBounds();
                }
            }

            @Override
            public void onExpandedAspectRatioChanged(float ratio) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                            "%s: onExpandedAspectRatioChanged: %f", TAG, ratio);

                mTvPipBoundsState.setDesiredTvExpandedAspectRatio(ratio, false);
                mTvPipMenuController.updateExpansionState();

                // 1) PiP is expanded and only aspect ratio changed, but wasn't disabled
                // --> update bounds, but don't toggle
                if (mTvPipBoundsState.isTvPipExpanded() && ratio != 0) {
                    mTvPipBoundsAlgorithm.updateExpandedPipSize();
                    updatePinnedStackBounds();
                }

                // 2) PiP is expanded, but expanded PiP was disabled
                // --> collapse PiP
                if (mTvPipBoundsState.isTvPipExpanded() && ratio == 0) {
                    int saveGravity = mTvPipBoundsAlgorithm
                            .updateGravityOnExpandToggled(mPreviousGravity, false);
                    if (saveGravity != Gravity.NO_GRAVITY) {
                        mPreviousGravity = saveGravity;
                    }
                    mTvPipBoundsState.setTvPipExpanded(false);
                    updatePinnedStackBounds();
                }

                // 3) PiP not expanded and not manually collapsed and expand was enabled
                // --> expand to new ratio
                if (!mTvPipBoundsState.isTvPipExpanded() && ratio != 0
                        && !mTvPipBoundsState.isTvPipManuallyCollapsed()) {
                    mTvPipBoundsAlgorithm.updateExpandedPipSize();
                    int saveGravity = mTvPipBoundsAlgorithm
                            .updateGravityOnExpandToggled(mPreviousGravity, true);
                    if (saveGravity != Gravity.NO_GRAVITY) {
                        mPreviousGravity = saveGravity;
                    }
                    mTvPipBoundsState.setTvPipExpanded(true);
                    updatePinnedStackBounds();
                }
            }
        });
    }

    private void registerWmShellPinnedStackListener(WindowManagerShellWrapper wmShell) {
        try {
            wmShell.addPinnedStackListener(new PinnedStackListenerForwarder.PinnedTaskListener() {
                @Override
                public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
                    if (DEBUG) {
                        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                                "%s: onImeVisibilityChanged(), visible=%b, height=%d",
                                TAG, imeVisible, imeHeight);
                    }

                    if (imeVisible == mTvPipBoundsState.isImeShowing()
                            && (!imeVisible || imeHeight == mTvPipBoundsState.getImeHeight())) {
                        // Nothing changed: either IME has been and remains invisible, or remains
                        // visible with the same height.
                        return;
                    }
                    mTvPipBoundsState.setImeVisibility(imeVisible, imeHeight);

                    if (mState != STATE_NO_PIP) {
                        updatePinnedStackBounds();
                    }
                }
            });
        } catch (RemoteException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to register pinned stack listener, %s", TAG, e);
        }
    }

    private static TaskInfo getPinnedTaskInfo() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: getPinnedTaskInfo()", TAG);
        }
        try {
            final TaskInfo taskInfo = ActivityTaskManager.getService().getRootTaskInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: taskInfo=%s", TAG, taskInfo);
            }
            return taskInfo;
        } catch (RemoteException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: getRootTaskInfo() failed, %s", TAG, e);
            return null;
        }
    }

    private static void removeTask(int taskId) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: removeTask(), taskId=%d", TAG, taskId);
        }
        try {
            ActivityTaskManager.getService().removeTask(taskId);
        } catch (Exception e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Atm.removeTask() failed, %s", TAG, e);
        }
    }

    private static String stateToName(@State int state) {
        switch (state) {
            case STATE_NO_PIP:
                return "NO_PIP";
            case STATE_PIP:
                return "PIP";
            case STATE_PIP_MENU:
                return "PIP_MENU";
            default:
                // This can't happen.
                throw new IllegalArgumentException("Unknown state " + state);
        }
    }

    private class TvPipImpl implements Pip {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            mMainExecutor.execute(() -> {
                TvPipController.this.onConfigurationChanged(newConfig);
            });
        }

        @Override
        public void registerSessionListenerForCurrentUser() {
            mMainExecutor.execute(() -> {
                TvPipController.this.registerSessionListenerForCurrentUser();
            });
        }
    }
}
