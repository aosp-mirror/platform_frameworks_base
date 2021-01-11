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

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.Intent.ACTION_MEDIA_RESOURCE_GRANTED;

import static com.android.wm.shell.pip.tv.PipNotification.ACTION_CLOSE;
import static com.android.wm.shell.pip.tv.PipNotification.ACTION_MENU;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.DisplayInfo;

import com.android.wm.shell.R;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipTaskOrganizer;

import java.util.Objects;

/**
 * Manages the picture-in-picture (PIP) UI and states.
 */
public class PipController implements Pip, PipTaskOrganizer.PipTransitionCallback,
        TvPipMenuController.Delegate {
    private static final String TAG = "TvPipController";
    static final boolean DEBUG = false;

    /**
     * Unknown or invalid state
     */
    public static final int STATE_UNKNOWN = -1;
    /**
     * State when there's no PIP.
     */
    public static final int STATE_NO_PIP = 0;
    /**
     * State when PIP is shown. This is used as default PIP state.
     */
    public static final int STATE_PIP = 1;
    /**
     * State when PIP menu dialog is shown.
     */
    public static final int STATE_PIP_MENU = 2;

    private static final int TASK_ID_NO_PIP = -1;
    private static final int INVALID_RESOURCE_TYPE = -1;

    private final Context mContext;
    private final PipBoundsState mPipBoundsState;
    private final PipBoundsAlgorithm mPipBoundsAlgorithm;
    private final PipTaskOrganizer mPipTaskOrganizer;
    private final PipMediaController mPipMediaController;
    private final TvPipMenuController mTvPipMenuController;
    private final PipNotification mPipNotification;

    private IActivityTaskManager mActivityTaskManager;
    private int mState = STATE_NO_PIP;
    private final Handler mHandler = new Handler();
    private int mLastOrientation = Configuration.ORIENTATION_UNDEFINED;
    private int mPipTaskId = TASK_ID_NO_PIP;
    private int mPinnedStackId = INVALID_STACK_ID;
    private String[] mLastPackagesResourceGranted;
    private ParceledListSlice<RemoteAction> mCustomActions;
    private WindowManagerShellWrapper mWindowManagerShellWrapper;
    private int mResizeAnimationDuration;

    // Used to calculate the movement bounds
    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();
    private final Rect mTmpInsetBounds = new Rect();

    // Keeps track of the IME visibility to adjust the PiP when the IME is visible
    private boolean mImeVisible;
    private int mImeHeightAdjustment;

    private final Runnable mClosePipRunnable = this::closePip;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(TAG, "mBroadcastReceiver, action: " + intent.getAction());
            }
            switch (intent.getAction()) {
                case ACTION_MENU:
                    showPictureInPictureMenu();
                    break;
                case ACTION_CLOSE:
                    closePip();
                    break;
                case ACTION_MEDIA_RESOURCE_GRANTED:
                    String[] packageNames = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                    int resourceType = intent.getIntExtra(Intent.EXTRA_MEDIA_RESOURCE_TYPE,
                            INVALID_RESOURCE_TYPE);
                    if (packageNames != null && packageNames.length > 0
                            && resourceType == Intent.EXTRA_MEDIA_RESOURCE_TYPE_VIDEO_CODEC) {
                        handleMediaResourceGranted(packageNames);
                    }
                    break;
            }
        }
    };

    private final PinnedStackListenerForwarder.PinnedStackListener mPinnedStackListener =
            new PipControllerPinnedStackListener();

    @Override
    public void registerSessionListenerForCurrentUser() {
        mPipMediaController.registerSessionListenerForCurrentUser();
    }

    /**
     * Handler for messages from the PIP controller.
     */
    private class PipControllerPinnedStackListener extends
            PinnedStackListenerForwarder.PinnedStackListener {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            mPipBoundsState.setImeVisibility(imeVisible, imeHeight);
            if (mState == STATE_PIP) {
                if (mImeVisible != imeVisible) {
                    if (imeVisible) {
                        // Save the IME height adjustment, and offset to not occlude the IME
                        mPipBoundsState.getNormalBounds().offset(0, -imeHeight);
                        mImeHeightAdjustment = imeHeight;
                    } else {
                        // Apply the inverse adjustment when the IME is hidden
                        mPipBoundsState.getNormalBounds().offset(0, mImeHeightAdjustment);
                    }
                    mImeVisible = imeVisible;
                    resizePinnedStack(STATE_PIP);
                }
            }
        }

        @Override
        public void onMovementBoundsChanged(boolean fromImeAdjustment) {
            mTmpDisplayInfo.copyFrom(mPipBoundsState.getDisplayInfo());
            mPipBoundsAlgorithm.getInsetBounds(mTmpInsetBounds);
        }

        @Override
        public void onActionsChanged(ParceledListSlice<RemoteAction> actions) {
            mCustomActions = actions;
            mTvPipMenuController.setAppActions(mCustomActions);
        }
    }

    public PipController(Context context,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipTaskOrganizer pipTaskOrganizer,
            TvPipMenuController tvPipMenuController,
            PipMediaController pipMediaController,
            PipNotification pipNotification,
            TaskStackListenerImpl taskStackListener,
            WindowManagerShellWrapper windowManagerShellWrapper) {
        mContext = context;
        mPipBoundsState = pipBoundsState;
        mPipNotification = pipNotification;
        mPipBoundsAlgorithm = pipBoundsAlgorithm;
        mPipMediaController = pipMediaController;
        mTvPipMenuController = tvPipMenuController;
        mTvPipMenuController.setDelegate(this);
        // Ensure that we have the display info in case we get calls to update the bounds
        // before the listener calls back
        final DisplayInfo displayInfo = new DisplayInfo();
        context.getDisplay().getDisplayInfo(displayInfo);
        mPipBoundsState.setDisplayInfo(displayInfo);

        mResizeAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
        mPipTaskOrganizer = pipTaskOrganizer;
        mPipTaskOrganizer.registerPipTransitionCallback(this);
        mActivityTaskManager = ActivityTaskManager.getService();

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_CLOSE);
        intentFilter.addAction(ACTION_MENU);
        intentFilter.addAction(ACTION_MEDIA_RESOURCE_GRANTED);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter, UserHandle.USER_ALL);

        // Initialize the last orientation and apply the current configuration
        Configuration initialConfig = mContext.getResources().getConfiguration();
        mLastOrientation = initialConfig.orientation;
        loadConfigurationsAndApply(initialConfig);

        mWindowManagerShellWrapper = windowManagerShellWrapper;
        try {
            mWindowManagerShellWrapper.addPinnedStackListener(mPinnedStackListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }

        // Handle for system task stack changes.
        taskStackListener.addListener(
                new TaskStackListenerCallback() {
                    @Override
                    public void onTaskStackChanged() {
                        PipController.this.onTaskStackChanged();
                    }

                    @Override
                    public void onActivityPinned(String packageName, int userId, int taskId,
                            int stackId) {
                        PipController.this.onActivityPinned(packageName);
                    }

                    @Override
                    public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                            boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                        PipController.this.onActivityRestartAttempt(task, clearedTask);
                    }
                });
    }

    private void loadConfigurationsAndApply(Configuration newConfig) {
        if (mLastOrientation != newConfig.orientation) {
            // Don't resize the pinned stack on orientation change. TV does not care about this case
            // and this could clobber the existing animation to the new bounds calculated by WM.
            mLastOrientation = newConfig.orientation;
            return;
        }

        final Rect menuBounds = Rect.unflattenFromString(
                mContext.getResources().getString(R.string.pip_menu_bounds));
        mPipBoundsState.setExpandedBounds(menuBounds);

        resizePinnedStack(getPinnedTaskInfo() == null ? STATE_NO_PIP : STATE_PIP);
    }

    /**
     * Updates the PIP per configuration changed.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        loadConfigurationsAndApply(newConfig);
        mPipNotification.onConfigurationChanged(mContext);
    }

    /**
     * Shows the picture-in-picture menu if an activity is in picture-in-picture mode.
     */
    public void showPictureInPictureMenu() {
        if (DEBUG) Log.d(TAG, "showPictureInPictureMenu(), current state=" + getStateDescription());

        if (getState() == STATE_PIP) {
            resizePinnedStack(STATE_PIP_MENU);
        }
    }

    /**
     * Closes PIP (PIPed activity and PIP system UI).
     */
    @Override
    public void closePip() {
        if (DEBUG) Log.d(TAG, "closePip(), current state=" + getStateDescription());

        closePipInternal(true);
    }

    private void closePipInternal(boolean removePipStack) {
        if (DEBUG) {
            Log.d(TAG,
                    "closePipInternal() removePipStack=" + removePipStack + ", current state="
                            + getStateDescription());
        }

        mState = STATE_NO_PIP;
        mPipTaskId = TASK_ID_NO_PIP;
        if (removePipStack) {
            try {
                mActivityTaskManager.removeTask(mPinnedStackId);
            } catch (RemoteException e) {
                Log.e(TAG, "removeTask failed", e);
            } finally {
                mPinnedStackId = INVALID_STACK_ID;
            }
        }
        mPipNotification.dismiss();
        mTvPipMenuController.hideMenu();
        mHandler.removeCallbacks(mClosePipRunnable);
    }

    @Override
    public void movePipToNormalPosition() {
        resizePinnedStack(PipController.STATE_PIP);
    }

    /**
     * Moves the PIPed activity to the fullscreen and closes PIP system UI.
     */
    @Override
    public void movePipToFullscreen() {
        if (DEBUG) Log.d(TAG, "movePipToFullscreen(), current state=" + getStateDescription());

        mPipTaskId = TASK_ID_NO_PIP;
        mTvPipMenuController.hideMenu();
        mPipNotification.dismiss();

        resizePinnedStack(STATE_NO_PIP);
    }

    private void onActivityPinned(String packageName) {
        final RootTaskInfo taskInfo = getPinnedTaskInfo();
        if (DEBUG) Log.d(TAG, "onActivityPinned, task=" + taskInfo);
        if (taskInfo == null) {
            Log.w(TAG, "Cannot find pinned stack");
            return;
        }

        // At this point PipBoundsState knows the correct aspect ratio for this pinned task, so we
        // use PipBoundsAlgorithm to calculate the normal bounds for the task (PipBoundsAlgorithm
        // will query PipBoundsState for the aspect ratio) and pass the bounds over to the
        // PipBoundsState.
        mPipBoundsState.setNormalBounds(mPipBoundsAlgorithm.getNormalBounds());

        mPinnedStackId = taskInfo.taskId;
        mPipTaskId = taskInfo.childTaskIds[taskInfo.childTaskIds.length - 1];

        // Set state to STATE_PIP so we show it when the pinned stack animation ends.
        mState = STATE_PIP;
        mPipMediaController.onActivityPinned();
        mPipNotification.show(packageName);
    }

    private void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
            boolean clearedTask) {
        if (task.getWindowingMode() != WINDOWING_MODE_PINNED) {
            return;
        }
        if (DEBUG) Log.d(TAG, "onPinnedActivityRestartAttempt()");

        // If PIPed activity is launched again by Launcher or intent, make it fullscreen.
        movePipToFullscreen();
    }

    private void onTaskStackChanged() {
        if (DEBUG) Log.d(TAG, "onTaskStackChanged()");

        if (getState() != STATE_NO_PIP) {
            boolean hasPip = false;

            RootTaskInfo taskInfo = getPinnedTaskInfo();
            if (taskInfo == null || taskInfo.childTaskIds == null) {
                Log.w(TAG, "There is nothing in pinned stack");
                closePipInternal(false);
                return;
            }
            for (int i = taskInfo.childTaskIds.length - 1; i >= 0; --i) {
                if (taskInfo.childTaskIds[i] == mPipTaskId) {
                    // PIP task is still alive.
                    hasPip = true;
                    break;
                }
            }
            if (!hasPip) {
                // PIP task doesn't exist anymore in PINNED_STACK.
                closePipInternal(true);
                return;
            }
        }
        if (getState() == STATE_PIP) {
            if (!Objects.equals(mPipBoundsState.getBounds(), mPipBoundsState.getNormalBounds())) {
                resizePinnedStack(STATE_PIP);
            }
        }
    }

    /**
     * Resize the Pip to the appropriate size for the input state.
     *
     * @param state In Pip state also used to determine the new size for the Pip.
     */
    public void resizePinnedStack(int state) {
        if (DEBUG) {
            Log.d(TAG, "resizePinnedStack() state=" + stateToName(state) + ", current state="
                    + getStateDescription(), new Exception());
        }
        final boolean wasStateNoPip = (mState == STATE_NO_PIP);
        mTvPipMenuController.hideMenu();
        mState = state;
        final Rect newBounds;
        switch (mState) {
            case STATE_NO_PIP:
                newBounds = null;
                // If the state was already STATE_NO_PIP, then do not resize the stack below as it
                // will not exist
                if (wasStateNoPip) {
                    return;
                }
                break;
            case STATE_PIP_MENU:
                newBounds = mPipBoundsState.getExpandedBounds();
                break;
            case STATE_PIP: // fallthrough
            default:
                newBounds = mPipBoundsState.getNormalBounds();
                break;
        }
        if (newBounds != null) {
            mPipTaskOrganizer.scheduleAnimateResizePip(newBounds, mResizeAnimationDuration, null);
        } else {
            mPipTaskOrganizer.exitPip(mResizeAnimationDuration);
        }
    }

    /**
     * @return the current state.
     */
    private int getState() {
        return mState;
    }

    private void showPipMenu() {
        if (DEBUG) Log.d(TAG, "showPipMenu(), current state=" + getStateDescription());

        mState = STATE_PIP_MENU;
        mTvPipMenuController.showMenu();
    }

    /**
     * Returns {@code true} if PIP is shown.
     */
    public boolean isPipShown() {
        return mState != STATE_NO_PIP;
    }

    private RootTaskInfo getPinnedTaskInfo() {
        RootTaskInfo taskInfo = null;
        try {
            taskInfo = ActivityTaskManager.getService().getRootTaskInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
        } catch (RemoteException e) {
            Log.e(TAG, "getRootTaskInfo failed", e);
        }
        if (DEBUG) Log.d(TAG, "getPinnedTaskInfo(), taskInfo=" + taskInfo);
        return taskInfo;
    }

    private void handleMediaResourceGranted(String[] packageNames) {
        if (getState() == STATE_NO_PIP) {
            mLastPackagesResourceGranted = packageNames;
        } else {
            boolean requestedFromLastPackages = false;
            if (mLastPackagesResourceGranted != null) {
                for (String packageName : mLastPackagesResourceGranted) {
                    for (String newPackageName : packageNames) {
                        if (TextUtils.equals(newPackageName, packageName)) {
                            requestedFromLastPackages = true;
                            break;
                        }
                    }
                }
            }
            mLastPackagesResourceGranted = packageNames;
            if (!requestedFromLastPackages) {
                closePip();
            }
        }
    }

    @Override
    public void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback) {
    }

    PipMediaController getPipMediaController() {
        return mPipMediaController;
    }

    @Override
    public void onPipTransitionStarted(ComponentName activity, int direction, Rect pipBounds) {
    }

    @Override
    public void onPipTransitionFinished(ComponentName activity, int direction) {
        onPipTransitionFinishedOrCanceled();
    }

    @Override
    public void onPipTransitionCanceled(ComponentName activity, int direction) {
        onPipTransitionFinishedOrCanceled();
    }

    private void onPipTransitionFinishedOrCanceled() {
        if (DEBUG) Log.d(TAG, "onPipTransitionFinishedOrCanceled()");

        if (getState() == STATE_PIP_MENU) {
            showPipMenu();
        }
    }

    private String getStateDescription() {
        return stateToName(mState);
    }

    private static String stateToName(int state) {
        switch (state) {
            case STATE_NO_PIP:
                return "NO_PIP";

            case STATE_PIP:
                return "PIP";

            case STATE_PIP_MENU:
                return "PIP_MENU";

            default:
                return "UNKNOWN(" + state + ")";
        }
    }
}
