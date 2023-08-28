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
import static android.window.TaskConstants.TASK_CHILD_LAYER_COMPAT_UI;

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

/**
 * Window manager for the reachability education
 */
class ReachabilityEduWindowManager extends CompatUIWindowManagerAbstract {

    // The time to wait before hiding the education
    private static final long DISAPPEAR_DELAY_MS = 4000L;

    private static final int REACHABILITY_LEFT_OR_UP_POSITION = 0;
    private static final int REACHABILITY_RIGHT_OR_BOTTOM_POSITION = 2;

    private final CompatUIConfiguration mCompatUIConfiguration;

    private final ShellExecutor mMainExecutor;

    private boolean mIsActivityLetterboxed;

    private int mLetterboxVerticalPosition;

    private int mLetterboxHorizontalPosition;

    private int mTopActivityLetterboxWidth;

    private int mTopActivityLetterboxHeight;

    private long mNextHideTime = -1L;

    private boolean mForceUpdate = false;

    // We decided to force the visualization of the double-tap animated icons every time the user
    // double-taps.
    private boolean mHasUserDoubleTapped;

    // When the size of the letterboxed app changes and the icons are visible
    // we need to animate them.
    private boolean mHasLetterboxSizeChanged;

    @Nullable
    @VisibleForTesting
    ReachabilityEduLayout mLayout;

    ReachabilityEduWindowManager(Context context, TaskInfo taskInfo,
            SyncTransactionQueue syncQueue,
            ShellTaskOrganizer.TaskListener taskListener, DisplayLayout displayLayout,
            CompatUIConfiguration compatUIConfiguration, ShellExecutor mainExecutor) {
        super(context, taskInfo, syncQueue, taskListener, displayLayout);
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
        return TASK_CHILD_LAYER_COMPAT_UI + 1;
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
        return mIsActivityLetterboxed
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
        mHasUserDoubleTapped = taskInfo.isFromLetterboxDoubleTap;

        if (!super.updateCompatInfo(taskInfo, taskListener, canShow)) {
            return false;
        }

        mHasLetterboxSizeChanged = prevTopActivityLetterboxWidth != mTopActivityLetterboxWidth
                || prevTopActivityLetterboxHeight != mTopActivityLetterboxHeight;

        if (mHasUserDoubleTapped || prevIsActivityLetterboxed != mIsActivityLetterboxed
                || prevLetterboxVerticalPosition != mLetterboxVerticalPosition
                || prevLetterboxHorizontalPosition != mLetterboxHorizontalPosition
                || prevTopActivityLetterboxWidth != mTopActivityLetterboxWidth
                || prevTopActivityLetterboxHeight != mTopActivityLetterboxHeight) {
            updateVisibilityOfViews();
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
        final TaskInfo lastTaskInfo = getLastTaskInfo();
        final boolean eligibleForDisplayHorizontalEducation = mForceUpdate
                || !mCompatUIConfiguration.hasSeenHorizontalReachabilityEducation(lastTaskInfo)
                || (mHasUserDoubleTapped
                    && (mLetterboxHorizontalPosition == REACHABILITY_LEFT_OR_UP_POSITION
                        || mLetterboxHorizontalPosition == REACHABILITY_RIGHT_OR_BOTTOM_POSITION));
        final boolean eligibleForDisplayVerticalEducation = mForceUpdate
                || !mCompatUIConfiguration.hasSeenVerticalReachabilityEducation(lastTaskInfo)
                || (mHasUserDoubleTapped
                    && (mLetterboxVerticalPosition == REACHABILITY_LEFT_OR_UP_POSITION
                        || mLetterboxVerticalPosition == REACHABILITY_RIGHT_OR_BOTTOM_POSITION));

        if (mIsActivityLetterboxed && (eligibleForDisplayHorizontalEducation
                || eligibleForDisplayVerticalEducation)) {
            int availableWidth = getTaskBounds().width() - mTopActivityLetterboxWidth;
            int availableHeight = getTaskBounds().height() - mTopActivityLetterboxHeight;
            mLayout.handleVisibility(eligibleForDisplayHorizontalEducation,
                    eligibleForDisplayVerticalEducation,
                    mLetterboxVerticalPosition, mLetterboxHorizontalPosition, availableWidth,
                    availableHeight, mCompatUIConfiguration, lastTaskInfo);
            if (!mHasLetterboxSizeChanged) {
                updateHideTime();
                mMainExecutor.executeDelayed(this::hideReachability, DISAPPEAR_DELAY_MS);
            }
            mHasUserDoubleTapped = false;
        } else {
            mLayout.hideAllImmediately();
        }
    }

    private void hideReachability() {
        if (mLayout == null || !shouldHideEducation()) {
            return;
        }
        mLayout.hideAllImmediately();
    }

    private boolean shouldHideEducation() {
        return SystemClock.uptimeMillis() >= mNextHideTime;
    }
}
