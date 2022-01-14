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
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.RemoteAction;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.DisplayInfo;
import android.view.Gravity;

import com.android.wm.shell.R;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Manages the picture-in-picture (PIP) UI and states.
 */
public class TvPipController implements PipTransitionController.PipTransitionCallback,
        TvPipMenuController.Delegate, TvPipNotificationController.Delegate {
    private static final String TAG = "TvPipController";
    static final boolean DEBUG = false;

    private static final int NONEXISTENT_TASK_ID = -1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_NO_PIP,
            STATE_PIP,
            STATE_PIP_MENU
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

    private static final int DEFAULT_GRAVITY = Gravity.BOTTOM | Gravity.RIGHT;

    private final Context mContext;

    private final PipBoundsState mPipBoundsState;
    private final TvPipBoundsAlgorithm mTvPipBoundsAlgorithm;
    private final PipTaskOrganizer mPipTaskOrganizer;
    private final PipMediaController mPipMediaController;
    private final TvPipNotificationController mPipNotificationController;
    private final TvPipMenuController mTvPipMenuController;
    private final ShellExecutor mMainExecutor;
    private final TvPipImpl mImpl = new TvPipImpl();

    private @State int mState = STATE_NO_PIP;
    private @Gravity.GravityFlags int mGravity = DEFAULT_GRAVITY;
    private int mPinnedTaskId = NONEXISTENT_TASK_ID;

    private int mResizeAnimationDuration;

    public static Pip create(
            Context context,
            PipBoundsState pipBoundsState,
            TvPipBoundsAlgorithm tvPipBoundsAlgorithm,
            PipTaskOrganizer pipTaskOrganizer,
            PipTransitionController pipTransitionController,
            TvPipMenuController tvPipMenuController,
            PipMediaController pipMediaController,
            TvPipNotificationController pipNotificationController,
            TaskStackListenerImpl taskStackListener,
            WindowManagerShellWrapper wmShell,
            ShellExecutor mainExecutor) {
        return new TvPipController(
                context,
                pipBoundsState,
                tvPipBoundsAlgorithm,
                pipTaskOrganizer,
                pipTransitionController,
                tvPipMenuController,
                pipMediaController,
                pipNotificationController,
                taskStackListener,
                wmShell,
                mainExecutor).mImpl;
    }

    private TvPipController(
            Context context,
            PipBoundsState pipBoundsState,
            TvPipBoundsAlgorithm tvPipBoundsAlgorithm,
            PipTaskOrganizer pipTaskOrganizer,
            PipTransitionController pipTransitionController,
            TvPipMenuController tvPipMenuController,
            PipMediaController pipMediaController,
            TvPipNotificationController pipNotificationController,
            TaskStackListenerImpl taskStackListener,
            WindowManagerShellWrapper wmShell,
            ShellExecutor mainExecutor) {
        mContext = context;
        mMainExecutor = mainExecutor;

        mPipBoundsState = pipBoundsState;
        mPipBoundsState.setDisplayId(context.getDisplayId());
        mPipBoundsState.setDisplayLayout(new DisplayLayout(context, context.getDisplay()));
        mTvPipBoundsAlgorithm = tvPipBoundsAlgorithm;

        mPipMediaController = pipMediaController;

        mPipNotificationController = pipNotificationController;
        mPipNotificationController.setDelegate(this);

        mTvPipMenuController = tvPipMenuController;
        mTvPipMenuController.setDelegate(this);

        mPipTaskOrganizer = pipTaskOrganizer;
        pipTransitionController.registerPipTransitionCallback(this);

        loadConfigurations();

        registerTaskStackListenerCallback(taskStackListener);
        registerWmShellPinnedStackListener(wmShell);
    }

    private void onConfigurationChanged(Configuration newConfig) {
        if (DEBUG) Log.d(TAG, "onConfigurationChanged(), state=" + stateToName(mState));

        if (isPipShown()) {
            if (DEBUG) Log.d(TAG, "  > closing Pip.");
            closePip();
        }

        loadConfigurations();
        mPipNotificationController.onConfigurationChanged(mContext);
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
     * task/window is properly positioned in {@link #onPipTransitionFinished(ComponentName, int)}.
     */
    @Override
    public void showPictureInPictureMenu() {
        if (DEBUG) Log.d(TAG, "showPictureInPictureMenu(), state=" + stateToName(mState));

        if (mState == STATE_NO_PIP) {
            if (DEBUG) Log.d(TAG, "  > cannot open Menu from the current state.");
            return;
        }

        setState(STATE_PIP_MENU);
        movePinnedStack();
    }

    @Override
    public void closeMenu() {
        if (DEBUG) Log.d(TAG, "closeMenu(), state before=" + stateToName(mState));
        setState(STATE_PIP);
    }

    /**
     * Opens the "Pip-ed" Activity fullscreen.
     */
    @Override
    public void movePipToFullscreen() {
        if (DEBUG) Log.d(TAG, "movePipToFullscreen(), state=" + stateToName(mState));

        mPipTaskOrganizer.exitPip(mResizeAnimationDuration, false /* requestEnterSplit */);
        onPipDisappeared();
    }

    @Override
    public void movePip(int keycode) {
        if (updatePosition(keycode)) {
            if (DEBUG) Log.d(TAG, "New gravity: " + Gravity.toString(mGravity));
            mTvPipMenuController.updateMenu(mGravity);
            movePinnedStack();
        } else {
            if (DEBUG) Log.d(TAG, "Position hasn't changed");
        }
    }

    @Override
    public int getPipGravity() {
        return mGravity;
    }

    /**
     * @return true if position changed
     */
    private boolean updatePosition(int keycode) {
        if (DEBUG) Log.d(TAG, "updatePosition, keycode: " + keycode);

        int updatedGravity;
        switch (keycode) {
            case KEYCODE_DPAD_UP:
                updatedGravity = (mGravity & (~Gravity.BOTTOM)) | Gravity.TOP;
                break;
            case KEYCODE_DPAD_DOWN:
                updatedGravity =  (mGravity & (~Gravity.TOP)) | Gravity.BOTTOM;
                break;
            case KEYCODE_DPAD_LEFT:
                updatedGravity = (mGravity & (~Gravity.RIGHT)) | Gravity.LEFT;
                break;
            case KEYCODE_DPAD_RIGHT:
                updatedGravity = (mGravity & (~Gravity.LEFT)) | Gravity.RIGHT;
                break;
            default:
                updatedGravity = mGravity;
        }

        if (updatedGravity != mGravity) {
            mGravity = updatedGravity;
            return true;
        }
        return false;
    }

    /**
     * Animate to the updated position of the PiP based on the state and position of the PiP.
     */
    private void movePinnedStack() {
        if (mState == STATE_NO_PIP) {
            return;
        }

        Rect bounds = mTvPipBoundsAlgorithm.getTvNormalBounds(mGravity);
        if (DEBUG) Log.d(TAG, "movePinnedStack() - new pip bounds: " + bounds.toShortString());
        mPipTaskOrganizer.scheduleAnimateResizePip(bounds,
                mResizeAnimationDuration, rect -> {
                    if (DEBUG) Log.d(TAG, "movePinnedStack() animation done");
                });
    }

    /**
     * Closes Pip window.
     */
    @Override
    public void closePip() {
        if (DEBUG) Log.d(TAG, "closePip(), state=" + stateToName(mState));

        removeTask(mPinnedTaskId);
        onPipDisappeared();
    }

    private void registerSessionListenerForCurrentUser() {
        mPipMediaController.registerSessionListenerForCurrentUser();
    }

    private void checkIfPinnedTaskAppeared() {
        final TaskInfo pinnedTask = getPinnedTaskInfo();
        if (DEBUG) Log.d(TAG, "checkIfPinnedTaskAppeared(), task=" + pinnedTask);
        if (pinnedTask == null || pinnedTask.topActivity == null) return;
        mPinnedTaskId = pinnedTask.taskId;
        setState(STATE_PIP);

        mPipMediaController.onActivityPinned();
        mPipNotificationController.show(pinnedTask.topActivity.getPackageName());
    }

    private void checkIfPinnedTaskIsGone() {
        if (DEBUG) Log.d(TAG, "onTaskStackChanged()");

        if (isPipShown() && getPinnedTaskInfo() == null) {
            Log.w(TAG, "Pinned task is gone.");
            onPipDisappeared();
        }
    }

    private void onPipDisappeared() {
        if (DEBUG) Log.d(TAG, "onPipDisappeared() state=" + stateToName(mState));

        mPipNotificationController.dismiss();
        mTvPipMenuController.hideMenu();
        mGravity = DEFAULT_GRAVITY;
        setState(STATE_NO_PIP);
        mPinnedTaskId = NONEXISTENT_TASK_ID;
    }

    @Override
    public void onPipTransitionStarted(int direction, Rect pipBounds) {
        if (DEBUG) Log.d(TAG, "onPipTransition_Started(), state=" + stateToName(mState));
    }

    @Override
    public void onPipTransitionCanceled(int direction) {
        if (DEBUG) Log.d(TAG, "onPipTransition_Canceled(), state=" + stateToName(mState));
    }

    @Override
    public void onPipTransitionFinished(int direction) {
        if (DEBUG) Log.d(TAG, "onPipTransition_Finished(), state=" + stateToName(mState));

        if (mState == STATE_PIP_MENU) {
            if (DEBUG) Log.d(TAG, "  > show menu");
            mTvPipMenuController.showMenu();
        }
    }

    private void setState(@State int state) {
        if (DEBUG) {
            Log.d(TAG, "setState(), state=" + stateToName(state) + ", prev="
                    + stateToName(mState));
        }
        mState = state;
    }

    private void loadConfigurations() {
        final Resources res = mContext.getResources();
        mResizeAnimationDuration = res.getInteger(R.integer.config_pipResizeAnimationDuration);
    }

    private DisplayInfo getDisplayInfo() {
        final DisplayInfo displayInfo = new DisplayInfo();
        mContext.getDisplay().getDisplayInfo(displayInfo);
        return displayInfo;
    }

    private void registerTaskStackListenerCallback(TaskStackListenerImpl taskStackListener) {
        taskStackListener.addListener(new TaskStackListenerCallback() {
            @Override
            public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
                checkIfPinnedTaskAppeared();
            }

            @Override
            public void onTaskStackChanged() {
                checkIfPinnedTaskIsGone();
            }

            @Override
            public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                    boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                if (task.getWindowingMode() == WINDOWING_MODE_PINNED) {
                    if (DEBUG) Log.d(TAG, "onPinnedActivityRestartAttempt()");

                    // If the "Pip-ed" Activity is launched again by Launcher or intent, make it
                    // fullscreen.
                    movePipToFullscreen();
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
                        Log.d(TAG, "onImeVisibilityChanged(), visible=" + imeVisible
                                + ", height=" + imeHeight);
                    }

                    if (imeVisible == mPipBoundsState.isImeShowing()
                            && (!imeVisible || imeHeight == mPipBoundsState.getImeHeight())) {
                        // Nothing changed: either IME has been and remains invisible, or remains
                        // visible with the same height.
                        return;
                    }
                    mPipBoundsState.setImeVisibility(imeVisible, imeHeight);

                    if (mState != STATE_NO_PIP) {
                        movePinnedStack();
                    }
                }

                @Override
                public void onMovementBoundsChanged(boolean fromImeAdjustment) {}

                @Override
                public void onActionsChanged(ParceledListSlice<RemoteAction> actions) {
                    if (DEBUG) Log.d(TAG, "onActionsChanged()");

                    mTvPipMenuController.setAppActions(actions);
                }
            });
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
    }

    private static TaskInfo getPinnedTaskInfo() {
        if (DEBUG) Log.d(TAG, "getPinnedTaskInfo()");
        try {
            final TaskInfo taskInfo = ActivityTaskManager.getService().getRootTaskInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (DEBUG) Log.d(TAG, "  > taskInfo=" + taskInfo);
            return taskInfo;
        } catch (RemoteException e) {
            Log.e(TAG, "getRootTaskInfo() failed", e);
            return null;
        }
    }

    private static void removeTask(int taskId) {
        if (DEBUG) Log.d(TAG, "removeTask(), taskId=" + taskId);
        try {
            ActivityTaskManager.getService().removeTask(taskId);
        } catch (Exception e) {
            Log.e(TAG, "Atm.removeTask() failed", e);
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
