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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
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

import com.android.systemui.Dependency;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.pip.BasePipManager;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.WindowManagerWrapper;

import java.io.PrintWriter;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipManager implements BasePipManager {
    private static final String TAG = "PipManager";

    private static PipManager sPipController;

    private Context mContext;
    private IActivityManager mActivityManager;
    private IActivityTaskManager mActivityTaskManager;
    private Handler mHandler = new Handler();

    private final PinnedStackListener mPinnedStackListener = new PinnedStackListener();

    private InputConsumerController mInputConsumerController;
    private PipMenuActivityController mMenuController;
    private PipMediaController mMediaController;
    private PipTouchHandler mTouchHandler;
    private PipAppOpsListener mAppOpsListener;

    /**
     * Handler for system task stack changes.
     */
    TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
            mTouchHandler.onActivityPinned();
            mMediaController.onActivityPinned();
            mMenuController.onActivityPinned();
            mAppOpsListener.onActivityPinned(packageName);

            Dependency.get(UiOffloadThread.class).submit(() -> {
                WindowManagerWrapper.getInstance().setPipVisibility(true);
            });
        }

        @Override
        public void onActivityUnpinned() {
            final Pair<ComponentName, Integer> topPipActivityInfo = PipUtils.getTopPinnedActivity(
                    mContext, mActivityManager);
            final ComponentName topActivity = topPipActivityInfo.first;
            mMenuController.onActivityUnpinned();
            mTouchHandler.onActivityUnpinned(topActivity);
            mAppOpsListener.onActivityUnpinned();

            Dependency.get(UiOffloadThread.class).submit(() -> {
                WindowManagerWrapper.getInstance().setPipVisibility(topActivity != null);
            });
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
        public void onShelfVisibilityChanged(boolean shelfVisible, int shelfHeight) {
            mHandler.post(() -> {
               mTouchHandler.onShelfVisibilityChanged(shelfVisible, shelfHeight);
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
                Rect animatingBounds, boolean fromImeAdjustment, boolean fromShelfAdjustment,
                int displayRotation) {
            mHandler.post(() -> {
                mTouchHandler.onMovementBoundsChanged(insetBounds, normalBounds, animatingBounds,
                        fromImeAdjustment, fromShelfAdjustment, displayRotation);
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
        mActivityTaskManager = ActivityTaskManager.getService();

        try {
            WindowManagerWrapper.getInstance().addPinnedStackListener(mPinnedStackListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);

        mInputConsumerController = InputConsumerController.getPipInputConsumer();
        mMediaController = new PipMediaController(context, mActivityManager);
        mMenuController = new PipMenuActivityController(context, mActivityManager, mMediaController,
                mInputConsumerController);
        mTouchHandler = new PipTouchHandler(context, mActivityManager, mActivityTaskManager,
                mMenuController, mInputConsumerController);
        mAppOpsListener = new PipAppOpsListener(context, mActivityManager,
                mTouchHandler.getMotionHelper());

        // If SystemUI restart, and it already existed a pinned stack,
        // register the pip input consumer to ensure touch can send to it.
        try {
            ActivityManager.StackInfo stackInfo = mActivityTaskManager.getStackInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (stackInfo != null) {
                mInputConsumerController.registerInputConsumer();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
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
    @Override
    public void expandPip() {
        mTouchHandler.getMotionHelper().expandPip(false /* skipAnimation */);
    }

    /**
     * Hides the PIP menu.
     */
    @Override
    public void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback) {
        mMenuController.hideMenu(onStartCallback, onEndCallback);
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
