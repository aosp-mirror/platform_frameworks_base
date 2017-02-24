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

package com.android.systemui.pip.phone;

import static android.view.Display.DEFAULT_DISPLAY;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.pip.BasePipManager;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;

import java.io.PrintWriter;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipManager implements BasePipManager {
    private static final String TAG = "PipManager";

    private static PipManager sPipController;

    private Context mContext;
    private IActivityManager mActivityManager;
    private IWindowManager mWindowManager;
    private Handler mHandler = new Handler();

    private final PinnedStackListener mPinnedStackListener = new PinnedStackListener();

    private PipMenuActivityController mMenuController;
    private PipMediaController mMediaController;
    private PipTouchHandler mTouchHandler;

    /**
     * Handler for system task stack changes.
     */
    TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onActivityPinned() {
            if (!checkCurrentUserId(false /* debug */)) {
                return;
            }
            mTouchHandler.onActivityPinned();
            mMediaController.onActivityPinned();
        }

        @Override
        public void onPinnedStackAnimationStarted() {
            // Disable touches while the animation is running
            mTouchHandler.setTouchEnabled(false);
        }

        @Override
        public void onPinnedStackAnimationEnded() {
            // Re-enable touches after the animation completes
            mTouchHandler.setTouchEnabled(true);
        }

        @Override
        public void onPinnedActivityRestartAttempt(String launchedFromPackage) {
            if (!checkCurrentUserId(false /* debug */)) {
                return;
            }

            // Expand the activity back to fullscreen only if it was attempted to be restarted from
            // another package than the top activity in the stack
            boolean expandPipToFullscreen = true;
            if (launchedFromPackage != null) {
                ComponentName topActivity = PipUtils.getTopPinnedActivity(mContext,
                        mActivityManager);
                if (topActivity != null
                        && topActivity.getPackageName().equals(launchedFromPackage)) {
                    expandPipToFullscreen = false;
                }
            }
            if (expandPipToFullscreen) {
                mTouchHandler.getMotionHelper().expandPip();
            } else {
                Log.w(TAG, "Can not expand PiP to fullscreen via intent from the same package.");
            }
        }
    };

    /**
     * Handler for messages from the PIP controller.
     */
    private class PinnedStackListener extends IPinnedStackListener.Stub {

        @Override
        public void onListenerRegistered(IPinnedStackController controller) {
            mHandler.post(() -> {
                mTouchHandler.setPinnedStackController(controller);
            });
        }

        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            mHandler.post(() -> {
                mTouchHandler.onImeVisibilityChanged(imeVisible, imeHeight);
            });
        }

        @Override
        public void onMinimizedStateChanged(boolean isMinimized) {
            mHandler.post(() -> {
                mTouchHandler.setMinimizedState(isMinimized, true /* fromController */);
            });
        }

        @Override
        public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds,
                boolean fromImeAdjustement) {
            mHandler.post(() -> {
                mTouchHandler.onMovementBoundsChanged(insetBounds, normalBounds, fromImeAdjustement);
            });
        }

        @Override
        public void onActionsChanged(ParceledListSlice actions) {
            mHandler.post(() -> {
                mMenuController.setAppActions(actions);
            });
        }
    }

    private PipManager() {}

    /**
     * Initializes {@link PipManager}.
     */
    public void initialize(Context context) {
        mContext = context;
        mActivityManager = ActivityManager.getService();
        mWindowManager = WindowManagerGlobal.getWindowManagerService();

        try {
            mWindowManager.registerPinnedStackListener(DEFAULT_DISPLAY, mPinnedStackListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
        SystemServicesProxy.getInstance(mContext).registerTaskStackListener(mTaskStackListener);

        mMediaController = new PipMediaController(context, mActivityManager);
        mMenuController = new PipMenuActivityController(context, mActivityManager, mWindowManager,
                mMediaController);
        mTouchHandler = new PipTouchHandler(context, mMenuController, mActivityManager,
                mWindowManager);
    }

    /**
     * Updates the PIP per configuration changed.
     */
    public void onConfigurationChanged() {
        mTouchHandler.onConfigurationChanged();
    }

    /**
     * Gets an instance of {@link PipManager}.
     */
    public static PipManager getInstance() {
        if (sPipController == null) {
            sPipController = new PipManager();
        }
        return sPipController;
    }

    public void dump(PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG);
        mMenuController.dump(pw, innerPrefix);
        mTouchHandler.dump(pw, innerPrefix);
    }
}
