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

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.Display.DEFAULT_DISPLAY;

import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.pip.BasePipManager;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.component.ExpandPipEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.SystemServicesProxy.TaskStackListener;
import com.android.systemui.statusbar.CommandQueue;

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

    private InputConsumerController mInputConsumerController;
    private PipMenuActivityController mMenuController;
    private PipMediaController mMediaController;
    private PipNotificationController mNotificationController;
    private PipTouchHandler mTouchHandler;

    /**
     * Handler for system task stack changes.
     */
    TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onActivityPinned(String packageName, int userId, int taskId) {
            mTouchHandler.onActivityPinned();
            mMediaController.onActivityPinned();
            mMenuController.onActivityPinned();
            mNotificationController.onActivityPinned(packageName, userId,
                    true /* deferUntilAnimationEnds */);

            SystemServicesProxy.getInstance(mContext).setPipVisibility(true);
        }

        @Override
        public void onActivityUnpinned() {
            final Pair<ComponentName, Integer> topPipActivityInfo = PipUtils.getTopPinnedActivity(
                    mContext, mActivityManager);
            final ComponentName topActivity = topPipActivityInfo.first;
            final int userId = topActivity != null ? topPipActivityInfo.second : 0;
            mMenuController.onActivityUnpinned();
            mTouchHandler.onActivityUnpinned(topActivity);
            mNotificationController.onActivityUnpinned(topActivity, userId);

            SystemServicesProxy.getInstance(mContext).setPipVisibility(topActivity != null);
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
            mTouchHandler.onPinnedStackAnimationEnded();
            mMenuController.onPinnedStackAnimationEnded();
            mNotificationController.onPinnedStackAnimationEnded();
        }

        @Override
        public void onPinnedActivityRestartAttempt(boolean clearedTask) {
            mTouchHandler.getMotionHelper().expandPip(clearedTask /* skipAnimation */);
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
                Rect animatingBounds, boolean fromImeAdjustement, int displayRotation) {
            mHandler.post(() -> {
                mTouchHandler.onMovementBoundsChanged(insetBounds, normalBounds, animatingBounds,
                        fromImeAdjustement, displayRotation);
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

        mInputConsumerController = new InputConsumerController(mWindowManager);
        mMediaController = new PipMediaController(context, mActivityManager);
        mMenuController = new PipMenuActivityController(context, mActivityManager, mMediaController,
                mInputConsumerController);
        mTouchHandler = new PipTouchHandler(context, mActivityManager, mMenuController,
                mInputConsumerController);
        mNotificationController = new PipNotificationController(context, mActivityManager,
                mTouchHandler.getMotionHelper());
        EventBus.getDefault().register(this);
    }

    /**
     * Updates the PIP per configuration changed.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        mTouchHandler.onConfigurationChanged();
    }

    /**
     * Expands the PIP.
     */
    public final void onBusEvent(ExpandPipEvent event) {
        if (event.clearThumbnailWindows) {
            try {
                StackInfo stackInfo = mActivityManager.getStackInfo(PINNED_STACK_ID);
                if (stackInfo != null && stackInfo.taskIds != null) {
                    SystemServicesProxy ssp = SystemServicesProxy.getInstance(mContext);
                    for (int taskId : stackInfo.taskIds) {
                        ssp.cancelThumbnailTransition(taskId);
                    }
                }
            } catch (RemoteException e) {
                // Do nothing
            }
        }
        mTouchHandler.getMotionHelper().expandPip(false /* skipAnimation */);
    }

    /**
     * Sent from KEYCODE_WINDOW handler in PhoneWindowManager, to request the menu to be shown.
     */
    public void showPictureInPictureMenu() {
        mTouchHandler.showPictureInPictureMenu();
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
        mInputConsumerController.dump(pw, innerPrefix);
        mMenuController.dump(pw, innerPrefix);
        mTouchHandler.dump(pw, innerPrefix);
    }
}
