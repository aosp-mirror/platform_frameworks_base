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
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipManager {
    private static final String TAG = "PipManager";

    private static PipManager sPipController;

    private Context mContext;
    private IActivityManager mActivityManager;
    private IWindowManager mWindowManager;
    private Handler mHandler = new Handler();

    private final PinnedStackListener mPinnedStackListener = new PinnedStackListener();

    private PipMenuActivityController mMenuController;
    private PipTouchHandler mTouchHandler;

    /**
     * Handler for system task stack changes.
     */
    TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onActivityPinned() {
            mTouchHandler.onActivityPinned();
        }

        @Override
        public void onPinnedStackAnimationEnded() {
            // TODO(winsonc): Disable touch interaction with the PiP until the animation ends
        }

        @Override
        public void onPinnedActivityRestartAttempt() {
            // TODO(winsonc): Hide the menu and expand the PiP
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
        public void onBoundsChanged(boolean adjustedForIme) {
            // Do nothing
        }

        @Override
        public void onActionsChanged(ParceledListSlice actions) {
            mHandler.post(() -> {
                mMenuController.setActions(actions);
            });
        }

        @Override
        public void onMinimizedStateChanged(boolean isMinimized) {
            mHandler.post(() -> {
                mTouchHandler.onMinimizedStateChanged(isMinimized);
            });
        }

        @Override
        public void onSnapToEdgeStateChanged(boolean isSnapToEdge) {
            mHandler.post(() -> {
                mTouchHandler.onSnapToEdgeStateChanged(isSnapToEdge);
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

        mMenuController = new PipMenuActivityController(context, mActivityManager, mWindowManager);
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
}
