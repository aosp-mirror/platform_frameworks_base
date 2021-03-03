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
 * calculate proper bounds when configuration or UI position changes.
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
    final SizeCompatUIWindowManager mButtonWindowManager;
    @VisibleForTesting
    @Nullable
    SizeCompatUIWindowManager mHintWindowManager;
    @VisibleForTesting
    @Nullable
    SizeCompatRestartButton mButton;
    @VisibleForTesting
    @Nullable
    SizeCompatHintPopup mHint;
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
        mButtonWindowManager = new SizeCompatUIWindowManager(mContext, taskConfig, this);

        mButtonSize =
                mContext.getResources().getDimensionPixelSize(R.dimen.size_compat_button_size);
        mPopupOffsetX = mButtonSize / 4;
        mPopupOffsetY = mButtonSize;
    }

    /** Creates the activity restart button window. */
    void createSizeCompatButton(boolean isImeShowing) {
        if (isImeShowing || mButton != null) {
            // When ime is showing, wait until ime is dismiss to create UI.
            return;
        }
        mButton = mButtonWindowManager.createSizeCompatButton();
        updateButtonSurfacePosition();

        if (mShouldShowHint) {
            // Only show by default for the first time.
            mShouldShowHint = false;
            createSizeCompatHint();
        }
    }

    /** Creates the restart button hint window. */
    private void createSizeCompatHint() {
        if (mHint != null) {
            // Hint already shown.
            return;
        }
        mHintWindowManager = createHintWindowManager();
        mHint = mHintWindowManager.createSizeCompatHint();
        updateHintSurfacePosition();
    }

    @VisibleForTesting
    SizeCompatUIWindowManager createHintWindowManager() {
        return new SizeCompatUIWindowManager(mContext, mTaskConfig, this);
    }

    /** Dismisses the hint window. */
    void dismissHint() {
        mHint = null;
        if (mHintWindowManager != null) {
            mHintWindowManager.release();
            mHintWindowManager = null;
        }
    }

    /** Releases the UI windows. */
    void release() {
        dismissHint();
        mButton = null;
        mButtonWindowManager.release();
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
        mButtonWindowManager.setConfiguration(taskConfig);
        if (mHintWindowManager != null) {
            mHintWindowManager.setConfiguration(taskConfig);
        }

        if (mButton == null || prevTaskListener != taskListener) {
            // TaskListener changed, recreate the button for new surface parent.
            release();
            createSizeCompatButton(isImeShowing);
            return;
        }

        if (!taskConfig.windowConfiguration.getBounds()
                .equals(prevTaskConfig.windowConfiguration.getBounds())) {
            // Reposition the UI surfaces.
            updateButtonSurfacePosition();
            updateHintSurfacePosition();
        }

        if (taskConfig.getLayoutDirection() != prevTaskConfig.getLayoutDirection()) {
            // Update layout for RTL.
            mButton.setLayoutDirection(taskConfig.getLayoutDirection());
            updateButtonSurfacePosition();
            if (mHint != null) {
                mHint.setLayoutDirection(taskConfig.getLayoutDirection());
                updateHintSurfacePosition();
            }
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
            // Stable bounds changed, update UI surface positions.
            updateButtonSurfacePosition();
            updateHintSurfacePosition();
        }
    }

    /** Called when IME visibility changed. */
    void updateImeVisibility(boolean isImeShowing) {
        if (mButton == null) {
            // Button may not be created because ime is previous showing.
            createSizeCompatButton(isImeShowing);
            return;
        }

        // Hide size compat UIs when IME is showing.
        final int newVisibility = isImeShowing ? View.GONE : View.VISIBLE;
        if (mButton.getVisibility() != newVisibility) {
            mButton.setVisibility(newVisibility);
        }
        if (mHint != null && mHint.getVisibility() != newVisibility) {
            mHint.setVisibility(newVisibility);
        }
    }

    /** Gets the layout params for restart button. */
    WindowManager.LayoutParams getButtonWindowLayoutParams() {
        final WindowManager.LayoutParams winParams = new WindowManager.LayoutParams(
                // Cannot be wrap_content as this determines the actual window size
                mButtonSize, mButtonSize,
                TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        winParams.token = new Binder();
        winParams.setTitle(SizeCompatRestartButton.class.getSimpleName() + getTaskId());
        winParams.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        return winParams;
    }

    /** Gets the layout params for hint popup. */
    WindowManager.LayoutParams getHintWindowLayoutParams(SizeCompatHintPopup hint) {
        final WindowManager.LayoutParams winParams = new WindowManager.LayoutParams(
                // Cannot be wrap_content as this determines the actual window size
                hint.getMeasuredWidth(), hint.getMeasuredHeight(),
                TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        winParams.token = new Binder();
        winParams.setTitle(SizeCompatHintPopup.class.getSimpleName() + getTaskId());
        winParams.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        winParams.windowAnimations = android.R.style.Animation_InputMethod;
        return winParams;
    }

    /** Called when it is ready to be placed size compat UI surface. */
    void attachToParentSurface(SurfaceControl.Builder b) {
        mTaskListener.attachChildSurfaceToTask(mTaskId, b);
    }

    /** Called when the restart button is clicked. */
    void onRestartButtonClicked() {
        ActivityClient.getInstance().restartActivityProcessIfVisible(mActivityToken);
    }

    /** Called when the restart button is long clicked. */
    void onRestartButtonLongClicked() {
        createSizeCompatHint();
    }

    @VisibleForTesting
    void updateButtonSurfacePosition() {
        if (mButton == null || mButtonWindowManager.getSurfaceControl() == null) {
            return;
        }
        final SurfaceControl leash = mButtonWindowManager.getSurfaceControl();

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

        updateSurfacePosition(leash, positionX, positionY);
    }

    void updateHintSurfacePosition() {
        if (mHint == null || mHintWindowManager == null
                || mHintWindowManager.getSurfaceControl() == null) {
            return;
        }
        final SurfaceControl leash = mHintWindowManager.getSurfaceControl();

        // Use stable bounds to prevent the hint from overlapping with system bars.
        final Rect taskBounds = mTaskConfig.windowConfiguration.getBounds();
        final Rect stableBounds = new Rect();
        mDisplayLayout.getStableBounds(stableBounds);
        stableBounds.intersect(taskBounds);

        // Position of the hint in the container coordinate.
        final int positionX = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? stableBounds.left - taskBounds.left + mPopupOffsetX
                : stableBounds.right - taskBounds.left - mPopupOffsetX - mHint.getMeasuredWidth();
        final int positionY =
                stableBounds.bottom - taskBounds.top - mPopupOffsetY - mHint.getMeasuredHeight();

        updateSurfacePosition(leash, positionX, positionY);
    }

    private void updateSurfacePosition(SurfaceControl leash, int positionX, int positionY) {
        mSyncQueue.runInSync(t -> {
            t.setPosition(leash, positionX, positionY);
            // The size compat UI should be the topmost child of the Task in case there can be more
            // than one children.
            t.setLayer(leash, Integer.MAX_VALUE);
        });
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
}
