/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.sizecompatui;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.annotation.Nullable;
import android.app.ActivityClient;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * Records and handles layout of size compat UI on a task with size compat activity. Helps to
 * calculate proper bounds when configuration or button position changes.
 */
class SizeCompatUILayout {
    private static final String TAG = "SizeCompatUILayout";

    private final SyncTransactionQueue mSyncQueue;
    private Context mContext;
    private Configuration mTaskConfig;
    private final int mDisplayId;
    private final int mTaskId;
    private IBinder mActivityToken;
    private ShellTaskOrganizer.TaskListener mTaskListener;
    private DisplayLayout mDisplayLayout;
    @VisibleForTesting
    final SizeCompatUIWindowManager mWindowManager;

    @VisibleForTesting
    @Nullable
    SizeCompatRestartButton mButton;
    final int mButtonSize;
    final int mPopupOffsetX;
    final int mPopupOffsetY;
    boolean mShouldShowHint;

    SizeCompatUILayout(SyncTransactionQueue syncQueue, Context context, Configuration taskConfig,
            int taskId, IBinder activityToken, ShellTaskOrganizer.TaskListener taskListener,
            DisplayLayout displayLayout, boolean hasShownHint) {
        mSyncQueue = syncQueue;
        mContext = context.createConfigurationContext(taskConfig);
        mTaskConfig = taskConfig;
        mDisplayId = mContext.getDisplayId();
        mTaskId = taskId;
        mActivityToken = activityToken;
        mTaskListener = taskListener;
        mDisplayLayout = displayLayout;
        mShouldShowHint = !hasShownHint;
        mWindowManager = new SizeCompatUIWindowManager(mContext, taskConfig, this);

        mButtonSize =
                mContext.getResources().getDimensionPixelSize(R.dimen.size_compat_button_size);
        mPopupOffsetX = mButtonSize / 4;
        mPopupOffsetY = mButtonSize;
    }

    /** Creates the button window. */
    void createSizeCompatButton(boolean isImeShowing) {
        if (isImeShowing || mButton != null) {
            // When ime is showing, wait until ime is dismiss to create UI.
            return;
        }
        mButton = mWindowManager.createSizeCompatUI();
        updateSurfacePosition();
    }

    /** Releases the button window. */
    void release() {
        mButton.remove();
        mButton = null;
        mWindowManager.release();
    }

    /** Called when size compat info changed. */
    void updateSizeCompatInfo(Configuration taskConfig, IBinder activityToken,
            ShellTaskOrganizer.TaskListener taskListener, boolean isImeShowing) {
        final Configuration prevTaskConfig = mTaskConfig;
        final ShellTaskOrganizer.TaskListener prevTaskListener = mTaskListener;
        mTaskConfig = taskConfig;
        mActivityToken = activityToken;
        mTaskListener = taskListener;

        // Update configuration.
        mContext = mContext.createConfigurationContext(taskConfig);
        mWindowManager.setConfiguration(taskConfig);

        if (mButton == null || prevTaskListener != taskListener) {
            // TaskListener changed, recreate the button for new surface parent.
            release();
            createSizeCompatButton(isImeShowing);
            return;
        }

        if (!taskConfig.windowConfiguration.getBounds()
                .equals(prevTaskConfig.windowConfiguration.getBounds())) {
            // Reposition the button surface.
            updateSurfacePosition();
        }

        if (taskConfig.getLayoutDirection() != prevTaskConfig.getLayoutDirection()) {
            // Update layout for RTL.
            mButton.setLayoutDirection(taskConfig.getLayoutDirection());
            updateSurfacePosition();
        }
    }

    /** Called when display layout changed. */
    void updateDisplayLayout(DisplayLayout displayLayout) {
        if (displayLayout == mDisplayLayout) {
            return;
        }

        final Rect prevStableBounds = new Rect();
        final Rect curStableBounds = new Rect();
        mDisplayLayout.getStableBounds(prevStableBounds);
        displayLayout.getStableBounds(curStableBounds);
        mDisplayLayout = displayLayout;
        if (!prevStableBounds.equals(curStableBounds)) {
            // Stable bounds changed, update button surface position.
            updateSurfacePosition();
        }
    }

    /** Called when IME visibility changed. */
    void updateImeVisibility(boolean isImeShowing) {
        if (mButton == null) {
            // Button may not be created because ime is previous showing.
            createSizeCompatButton(isImeShowing);
            return;
        }

        final int newVisibility = isImeShowing ? View.GONE : View.VISIBLE;
        if (mButton.getVisibility() != newVisibility) {
            mButton.setVisibility(newVisibility);
        }
    }

    /** Gets the layout params for restart button. */
    WindowManager.LayoutParams getWindowLayoutParams() {
        final WindowManager.LayoutParams winParams = new WindowManager.LayoutParams(
                mButtonSize, mButtonSize,
                TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        winParams.gravity = getGravity(getLayoutDirection());
        winParams.token = new Binder();
        winParams.setTitle(SizeCompatRestartButton.class.getSimpleName() + mContext.getDisplayId());
        winParams.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        return winParams;
    }

    /** Called when it is ready to be placed button surface button. */
    void attachToParentSurface(SurfaceControl.Builder b) {
        mTaskListener.attachChildSurfaceToTask(mTaskId, b);
    }

    /** Called when the restart button is clicked. */
    void onRestartButtonClicked() {
        ActivityClient.getInstance().restartActivityProcessIfVisible(mActivityToken);
    }

    @VisibleForTesting
    void updateSurfacePosition() {
        if (mButton == null || mWindowManager.getSurfaceControl() == null) {
            return;
        }
        // The hint popup won't be at the correct position.
        mButton.dismissHint();

        // Use stable bounds to prevent the button from overlapping with system bars.
        final Rect taskBounds = mTaskConfig.windowConfiguration.getBounds();
        final Rect stableBounds = new Rect();
        mDisplayLayout.getStableBounds(stableBounds);
        stableBounds.intersect(taskBounds);

        // Position of the button in the container coordinate.
        final int positionX = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? stableBounds.left - taskBounds.left
                : stableBounds.right - taskBounds.left - mButtonSize;
        final int positionY = stableBounds.bottom - taskBounds.top - mButtonSize;

        mSyncQueue.runInSync(t ->
                t.setPosition(mWindowManager.getSurfaceControl(), positionX, positionY));
    }

    int getDisplayId() {
        return mDisplayId;
    }

    int getTaskId() {
        return mTaskId;
    }

    private int getLayoutDirection() {
        return mContext.getResources().getConfiguration().getLayoutDirection();
    }

    static int getGravity(int layoutDirection) {
        return Gravity.BOTTOM
                | (layoutDirection == View.LAYOUT_DIRECTION_RTL ? Gravity.START : Gravity.END);
    }
}
