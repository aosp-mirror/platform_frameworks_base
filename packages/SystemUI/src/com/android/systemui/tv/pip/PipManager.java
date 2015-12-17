/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.tv.pip;

import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;

/**
 * Manages the picture-in-picture (PIP) UI and states.
 */
public class PipManager {
    private static final String TAG = "PipManager";
    private static final boolean DEBUG = false;

    private static PipManager sPipManager;

    private static final int STATE_NO_PIP = 0;
    private static final int STATE_PIP_OVERLAY = 1;
    private static final int STATE_PIP_MENU = 2;

    private Context mContext;
    private IActivityManager mActivityManager;
    private int mState = STATE_NO_PIP;
    private final Handler mHandler = new Handler();
    private List<Listener> mListeners = new ArrayList<>();
    private Rect mPipBound;
    private Rect mMenuModePipBound;
    private boolean mInitialized;
    private final Runnable mOnActivityPinnedRunnable = new Runnable() {
        @Override
        public void run() {
            StackInfo stackInfo = null;
            try {
                stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
                if (stackInfo == null) {
                    Log.w(TAG, "There is no pinned stack");
                    return;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "getStackInfo failed", e);
                return;
            }
            if (DEBUG) Log.d(TAG, "PINNED_STACK:" + stackInfo);
            mState = STATE_PIP_OVERLAY;
            launchPipOverlayActivity();
        }
    };
    private final Runnable mOnTaskStackChanged = new Runnable() {
        @Override
        public void run() {
            if (mState != STATE_NO_PIP) {
                // TODO: check whether PIP task is closed.
            }
        }
    };

    private final BroadcastReceiver mPipButtonReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "PIP button pressed");
            if (!hasPipTasks()) {
                startPip();
            } else if (mState == STATE_PIP_OVERLAY) {
                showPipMenu();
            }
        }
    };

    private PipManager() { }

    /**
     * Initializes {@link PipManager}.
     */
    public void initialize(Context context) {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mContext = context;
        Resources res = context.getResources();
        mPipBound = Rect.unflattenFromString(res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureBounds));
        mMenuModePipBound = Rect.unflattenFromString(res.getString(
                com.android.internal.R.string.config_centeredPictureInPictureBounds));

        mActivityManager = ActivityManagerNative.getDefault();
        TaskStackListener taskStackListener = new TaskStackListener();
        IActivityManager iam = ActivityManagerNative.getDefault();
        try {
            iam.registerTaskStackListener(taskStackListener);
        } catch (RemoteException e) {
            Log.e(TAG, "registerTaskStackListener failed", e);
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PICTURE_IN_PICTURE_BUTTON);
        mContext.registerReceiver(mPipButtonReceiver, intentFilter);
    }

    private void startPip() {
        try {
            mActivityManager.moveTopActivityToPinnedStack(FULLSCREEN_WORKSPACE_STACK_ID, mPipBound);
        } catch (RemoteException|IllegalArgumentException e) {
            Log.e(TAG, "moveTopActivityToPinnedStack failed", e);
        }

    }

    /**
     * Closes PIP (PIPped activity and PIP system UI).
     */
    public void closePip() {
        mState = STATE_NO_PIP;
        StackInfo stackInfo = null;
        try {
            stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
            if (stackInfo == null) {
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getStackInfo failed", e);
            return;
        }
        for (int taskId : stackInfo.taskIds) {
            try {
                mActivityManager.removeTask(taskId);
            } catch (RemoteException e) {
                Log.e(TAG, "removeTask failed", e);
            }
        }
    }

    /**
     * Moves the PIPped activity to the fullscreen and closes PIP system UI.
     */
    public void movePipToFullscreen() {
        mState = STATE_NO_PIP;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onMoveToFullscreen();
        }
        try {
            mActivityManager.moveTasksToFullscreenStack(PINNED_STACK_ID, true);
        } catch (RemoteException e) {
            Log.e(TAG, "moveTasksToFullscreenStack failed", e);
        }
    }

    /**
     * Shows PIP overlay UI by launching {@link PipOverlayActivity}. It also locates the pinned
     * stack to the default PIP bound {@link com.android.internal.R.string
     * .config_defaultPictureInPictureBounds}.
     */
    public void showPipOverlay() {
        if (DEBUG) Log.d(TAG, "showPipOverlay()");
        try {
            mActivityManager.resizeStack(PINNED_STACK_ID, mPipBound, false);
        } catch (Exception e) {
            Log.e(TAG, "resizeStack failed", e);
            closePip();
            return;
        }
        mState = STATE_PIP_OVERLAY;
        launchPipOverlayActivity();
    }

    /**
     * Shows PIP menu UI by launching {@link PipMenuActivity}. It also locates the pinned
     * stack to the centered PIP bound {@link com.android.internal.R.string
     * .config_centeredPictureInPictureBounds}.
     */
    public void showPipMenu() {
        if (DEBUG) Log.d(TAG, "showPipMenu()");
        try {
            mActivityManager.resizeStack(PINNED_STACK_ID, mMenuModePipBound, false);
        } catch (Exception e) {
            Log.e(TAG, "resizeStack failed", e);
            closePip();
            return;
        }
        mState = STATE_PIP_MENU;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onShowPipMenu();
        }
        Intent intent = new Intent(mContext, PipMenuActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchStackId(PINNED_STACK_ID);
        mContext.startActivity(intent, options.toBundle());
    }

    /**
     * Adds {@link Listener}.
     */
    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes {@link Listener}.
     */
    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void launchPipOverlayActivity() {
        Intent intent = new Intent(mContext, PipOverlayActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchStackId(PINNED_STACK_ID);
        mContext.startActivity(intent, options.toBundle());
    }

    private boolean hasPipTasks() {
        try {
            StackInfo stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
            return stackInfo != null;
        } catch (RemoteException e) {
            Log.e(TAG, "getStackInfo failed", e);
            return false;
        }
    }

    private class TaskStackListener extends ITaskStackListener.Stub {
        @Override
        public void onTaskStackChanged() throws RemoteException {
            // Post the message back to the UI thread.
            mHandler.post(mOnTaskStackChanged);
        }

        @Override
        public void onActivityPinned()  throws RemoteException {
            // Post the message back to the UI thread.
            mHandler.post(mOnActivityPinnedRunnable);
        }
    }

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Invoked when a PIPped activity is closed.
         */
        void onPipActivityClosed();
        /**
         * Invoked when the PIP menu gets shown.
         */
        void onShowPipMenu();
        /**
         * Invoked when the PIPped activity is returned back to the fullscreen.
         */
        void onMoveToFullscreen();
    }

    /**
     * Gets an instance of {@link PipManager}.
     */
    public static PipManager getInstance() {
        if (sPipManager == null) {
            sPipManager = new PipManager();
        }
        return sPipManager;
    }
}
