/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.compatui;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.TaskInfo;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.compatui.CompatUIController.CompatUICallback;

/**
 * Window manager for the reachability education
 */
class ReachabilityEduWindowManager extends CompatUIWindowManagerAbstract {

    /**
     * The Compat UI should be below the Letterbox Education.
     */
    private static final int Z_ORDER = LetterboxEduWindowManager.Z_ORDER - 1;

    // The time to wait before hiding the education
    private static final long DISAPPEAR_DELAY_MS = 4000L;

    private final CompatUICallback mCallback;

    private final CompatUIConfiguration mCompatUIConfiguration;

    private final ShellExecutor mMainExecutor;

    @NonNull
    private TaskInfo mTaskInfo;

    private boolean mIsActivityLetterboxed;

    private int mLetterboxVerticalPosition;

    private int mLetterboxHorizontalPosition;

    private int mTopActivityLetterboxWidth;

    private int mTopActivityLetterboxHeight;

    private long mNextHideTime = -1L;

    private boolean mForceUpdate = false;

    // We decided to force the visualization of the double-tap animated icons every time the user
    // double-taps. We detect a double-tap checking the previous and current state of
    // mLetterboxVerticalPosition and mLetterboxHorizontalPosition saving the result in this
    // variable.
    private boolean mHasUserDoubleTapped;

    // When the size of the letterboxed app changes and the icons are visible
    // we need to animate them.
    private boolean mHasLetterboxSizeChanged;

    @Nullable
    @VisibleForTesting
    ReachabilityEduLayout mLayout;

    ReachabilityEduWindowManager(Context context, TaskInfo taskInfo,
            SyncTransactionQueue syncQueue, CompatUICallback callback,
            ShellTaskOrganizer.TaskListener taskListener, DisplayLayout displayLayout,
            CompatUIConfiguration compatUIConfiguration, ShellExecutor mainExecutor) {
        super(context, taskInfo, syncQueue, taskListener, displayLayout);
        mCallback = callback;
        mTaskInfo = taskInfo;
        mIsActivityLetterboxed = taskInfo.isLetterboxDoubleTapEnabled;
        mLetterboxVerticalPosition = taskInfo.topActivityLetterboxVerticalPosition;
        mLetterboxHorizontalPosition = taskInfo.topActivityLetterboxHorizontalPosition;
        mTopActivityLetterboxWidth = taskInfo.topActivityLetterboxWidth;
        mTopActivityLetterboxHeight = taskInfo.topActivityLetterboxHeight;
        mCompatUIConfiguration = compatUIConfiguration;
        mMainExecutor = mainExecutor;
    }

    @Override
    protected int getZOrder() {
        return Z_ORDER;
    }

    @Override
    protected @Nullable View getLayout() {
        return mLayout;
    }

    @Override
    protected void removeLayout() {
        mLayout = null;
    }

    @Override
    protected boolean eligibleToShowLayout() {
        return mCompatUIConfiguration.isReachabilityEducationEnabled()
                && mIsActivityLetterboxed
                && (mLetterboxVerticalPosition != -1 || mLetterboxHorizontalPosition != -1);
    }

    @Override
    protected View createLayout() {
        mLayout = inflateLayout();
        mLayout.inject(this);

        updateVisibilityOfViews();

        return mLayout;
    }

    @VisibleForTesting
    ReachabilityEduLayout inflateLayout() {
        return (ReachabilityEduLayout) LayoutInflater.from(mContext).inflate(
                R.layout.reachability_ui_layout, null);
    }

    @Override
    public boolean updateCompatInfo(TaskInfo taskInfo, ShellTaskOrganizer.TaskListener taskListener,
            boolean canShow) {
        mTaskInfo = taskInfo;
        final boolean prevIsActivityLetterboxed = mIsActivityLetterboxed;
        final int prevLetterboxVerticalPosition = mLetterboxVerticalPosition;
        final int prevLetterboxHorizontalPosition = mLetterboxHorizontalPosition;
        final int prevTopActivityLetterboxWidth = mTopActivityLetterboxWidth;
        final int prevTopActivityLetterboxHeight = mTopActivityLetterboxHeight;
        mIsActivityLetterboxed = taskInfo.isLetterboxDoubleTapEnabled;
        mLetterboxVerticalPosition = taskInfo.topActivityLetterboxVerticalPosition;
        mLetterboxHorizontalPosition = taskInfo.topActivityLetterboxHorizontalPosition;
        mTopActivityLetterboxWidth = taskInfo.topActivityLetterboxWidth;
        mTopActivityLetterboxHeight = taskInfo.topActivityLetterboxHeight;

        mHasUserDoubleTapped =
                mLetterboxVerticalPosition != prevLetterboxVerticalPosition
                        || prevLetterboxHorizontalPosition != mLetterboxHorizontalPosition;
        if (mHasUserDoubleTapped) {
            // In this case we disable the reachability for the following launch of
            // the current application. Anyway because a double tap event happened,
            // the reachability education is displayed
            mCompatUIConfiguration.setDontShowReachabilityEducationAgain(taskInfo);
        }
        if (!super.updateCompatInfo(taskInfo, taskListener, canShow)) {
            return false;
        }

        mHasLetterboxSizeChanged = prevTopActivityLetterboxWidth != mTopActivityLetterboxWidth
                || prevTopActivityLetterboxHeight != mTopActivityLetterboxHeight;

        if (mForceUpdate || prevIsActivityLetterboxed != mIsActivityLetterboxed
                || prevLetterboxVerticalPosition != mLetterboxVerticalPosition
                || prevLetterboxHorizontalPosition != mLetterboxHorizontalPosition
                || prevTopActivityLetterboxWidth != mTopActivityLetterboxWidth
                || prevTopActivityLetterboxHeight != mTopActivityLetterboxHeight) {
            updateVisibilityOfViews();
            mForceUpdate = false;
        }

        return true;
    }

    void forceUpdate(boolean forceUpdate) {
        mForceUpdate = forceUpdate;
    }

    @Override
    protected void onParentBoundsChanged() {
        if (mLayout == null) {
            return;
        }
        // Both the layout dimensions and dialog margins depend on the parent bounds.
        WindowManager.LayoutParams windowLayoutParams = getWindowLayoutParams();
        mLayout.setLayoutParams(windowLayoutParams);
        relayout(windowLayoutParams);
    }

    /** Gets the layout params. */
    protected WindowManager.LayoutParams getWindowLayoutParams() {
        View layout = getLayout();
        if (layout == null) {
            return new WindowManager.LayoutParams();
        }
        // Measure how big the hint is since its size depends on the text size.
        final Rect taskBounds = getTaskBounds();
        layout.measure(View.MeasureSpec.makeMeasureSpec(taskBounds.width(),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(taskBounds.height(),
                        View.MeasureSpec.EXACTLY));
        return getWindowLayoutParams(layout.getMeasuredWidth(), layout.getMeasuredHeight());
    }

    /**
     * @return Flags to use for the WindowManager layout
     */
    @Override
    protected int getWindowManagerLayoutParamsFlags() {
        return FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE;
    }

    @Override
    @VisibleForTesting
    public void updateSurfacePosition() {
        if (mLayout == null) {
            return;
        }
        updateSurfacePosition(0, 0);
    }

    void updateHideTime() {
        mNextHideTime = SystemClock.uptimeMillis() + DISAPPEAR_DELAY_MS;
    }

    private void updateVisibilityOfViews() {
        if (mLayout == null) {
            return;
        }
        if (shouldUpdateEducation()) {
            if (!mHasLetterboxSizeChanged) {
                mLayout.setIsLayoutActive(true);
            }
            int availableWidth = getTaskBounds().width() - mTopActivityLetterboxWidth;
            int availableHeight = getTaskBounds().height() - mTopActivityLetterboxHeight;
            mLayout.handleVisibility(mIsActivityLetterboxed, mLetterboxVerticalPosition,
                    mLetterboxHorizontalPosition, availableWidth, availableHeight,
                    mHasUserDoubleTapped);
            if (!mHasLetterboxSizeChanged) {
                updateHideTime();
                mMainExecutor.executeDelayed(this::hideReachability, DISAPPEAR_DELAY_MS);
            }
            mHasUserDoubleTapped = false;
        } else {
            hideReachability();
        }
    }

    private void hideReachability() {
        if (mLayout != null) {
            mLayout.setIsLayoutActive(false);
        }
        if (mLayout == null || !shouldHideEducation()) {
            return;
        }
        mLayout.hideAllImmediately();
        // We need this in case the icons disappear after the timeout without an explicit
        // double tap of the user.
        mCompatUIConfiguration.setDontShowReachabilityEducationAgain(mTaskInfo);
    }

    private boolean shouldUpdateEducation() {
        return mForceUpdate || mHasUserDoubleTapped || mHasLetterboxSizeChanged
                || mCompatUIConfiguration.shouldShowReachabilityEducation(mTaskInfo);
    }

    private boolean shouldHideEducation() {
        return SystemClock.uptimeMillis() >= mNextHideTime;
    }
}
