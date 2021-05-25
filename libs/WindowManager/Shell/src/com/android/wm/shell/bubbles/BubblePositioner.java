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

package com.android.wm.shell.bubbles;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.R;

import java.lang.annotation.Retention;

/**
 * Keeps track of display size, configuration, and specific bubble sizes. One place for all
 * placement and positioning calculations to refer to.
 */
public class BubblePositioner {
    private static final String TAG = BubbleDebugConfig.TAG_WITH_CLASS_NAME
            ? "BubblePositioner"
            : BubbleDebugConfig.TAG_BUBBLES;

    @Retention(SOURCE)
    @IntDef({TASKBAR_POSITION_NONE, TASKBAR_POSITION_RIGHT, TASKBAR_POSITION_LEFT,
            TASKBAR_POSITION_BOTTOM})
    @interface TaskbarPosition {}
    public static final int TASKBAR_POSITION_NONE = -1;
    public static final int TASKBAR_POSITION_RIGHT = 0;
    public static final int TASKBAR_POSITION_LEFT = 1;
    public static final int TASKBAR_POSITION_BOTTOM = 2;

    /**
     * The bitmap in the bubble is slightly smaller than the overall size of the bubble.
     * This is the percentage to scale the image down based on the overall bubble size.
     */
    private static final float BUBBLE_BITMAP_SIZE_PERCENT = 0.86f;

    private Context mContext;
    private WindowManager mWindowManager;
    private Rect mPositionRect;
    private @Surface.Rotation int mRotation = Surface.ROTATION_0;
    private Insets mInsets;

    private int mBubbleSize;
    private int mBubbleBitmapSize;
    private int mExpandedViewLargeScreenWidth;
    private int mExpandedViewPadding;
    private int mPointerMargin;
    private float mPointerWidth;
    private float mPointerHeight;

    private PointF mPinLocation;
    private PointF mRestingStackPosition;
    private int[] mPaddings = new int[4];

    private boolean mIsLargeScreen;
    private boolean mShowingInTaskbar;
    private @TaskbarPosition int mTaskbarPosition = TASKBAR_POSITION_NONE;
    private int mTaskbarIconSize;
    private int mTaskbarSize;

    public BubblePositioner(Context context, WindowManager windowManager) {
        mContext = context;
        mWindowManager = windowManager;
        update();
    }

    public void setRotation(int rotation) {
        mRotation = rotation;
    }

    /**
     * Available space and inset information. Call this when config changes
     * occur or when added to a window.
     */
    public void update() {
        WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        if (windowMetrics == null) {
            return;
        }
        WindowInsets metricInsets = windowMetrics.getWindowInsets();
        Insets insets = metricInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()
                | WindowInsets.Type.statusBars()
                | WindowInsets.Type.displayCutout());

        mIsLargeScreen = mContext.getResources().getConfiguration().smallestScreenWidthDp >= 600;

        if (BubbleDebugConfig.DEBUG_POSITIONER) {
            Log.w(TAG, "update positioner:"
                    + " rotation: " + mRotation
                    + " insets: " + insets
                    + " isLargeScreen: " + mIsLargeScreen
                    + " bounds: " + windowMetrics.getBounds()
                    + " showingInTaskbar: " + mShowingInTaskbar);
        }
        updateInternal(mRotation, insets, windowMetrics.getBounds());
    }

    /**
     * Updates position information to account for taskbar state.
     *
     * @param taskbarPosition which position the taskbar is displayed in.
     * @param showingInTaskbar whether the taskbar is being shown.
     */
    public void updateForTaskbar(int iconSize,
            @TaskbarPosition int taskbarPosition, boolean showingInTaskbar, int taskbarSize) {
        mShowingInTaskbar = showingInTaskbar;
        mTaskbarIconSize =  iconSize;
        mTaskbarPosition = taskbarPosition;
        mTaskbarSize = taskbarSize;
        update();
    }

    @VisibleForTesting
    public void updateInternal(int rotation, Insets insets, Rect bounds) {
        mRotation = rotation;
        mInsets = insets;

        mPositionRect = new Rect(bounds);
        mPositionRect.left += mInsets.left;
        mPositionRect.top += mInsets.top;
        mPositionRect.right -= mInsets.right;
        mPositionRect.bottom -= mInsets.bottom;

        Resources res = mContext.getResources();
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubbleBitmapSize = res.getDimensionPixelSize(R.dimen.bubble_bitmap_size);
        mExpandedViewLargeScreenWidth = res.getDimensionPixelSize(
                R.dimen.bubble_expanded_view_tablet_width);
        mExpandedViewPadding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        mPointerWidth = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        mPointerHeight = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);
        mPointerMargin = res.getDimensionPixelSize(R.dimen.bubble_pointer_margin);
        if (mShowingInTaskbar) {
            adjustForTaskbar();
        }
    }

    /**
     * Taskbar insets appear as navigationBar insets, however, unlike navigationBar this should
     * not inset bubbles UI as bubbles floats above the taskbar. This adjust the available space
     * and insets to account for the taskbar.
     */
    // TODO(b/171559950): When the insets are reported correctly we can remove this logic
    private void adjustForTaskbar() {
        // When bar is showing on edges... subtract that inset because we appear on top
        if (mShowingInTaskbar && mTaskbarPosition != TASKBAR_POSITION_BOTTOM) {
            WindowInsets metricInsets = mWindowManager.getCurrentWindowMetrics().getWindowInsets();
            Insets navBarInsets = metricInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars());
            int newInsetLeft = mInsets.left;
            int newInsetRight = mInsets.right;
            if (mTaskbarPosition == TASKBAR_POSITION_LEFT) {
                mPositionRect.left -= navBarInsets.left;
                newInsetLeft -= navBarInsets.left;
            } else if (mTaskbarPosition == TASKBAR_POSITION_RIGHT) {
                mPositionRect.right += navBarInsets.right;
                newInsetRight -= navBarInsets.right;
            }
            mInsets = Insets.of(newInsetLeft, mInsets.top, newInsetRight, mInsets.bottom);
        }
    }

    /**
     * @return a rect of available screen space accounting for orientation, system bars and cutouts.
     * Does not account for IME.
     */
    public Rect getAvailableRect() {
        return mPositionRect;
    }

    /**
     * @return the relevant insets (status bar, nav bar, cutouts). If taskbar is showing, its
     * inset is not included here.
     */
    public Insets getInsets() {
        return mInsets;
    }

    /** @return whether the device is in landscape orientation. */
    public boolean isLandscape() {
        return mRotation == Surface.ROTATION_90 || mRotation == Surface.ROTATION_270;
    }

    /** @return whether the screen is considered large. */
    public boolean isLargeScreen() {
        return mIsLargeScreen;
    }

    /**
     * Indicates how bubbles appear when expanded.
     *
     * When false, bubbles display at the top of the screen with the expanded view
     * below them. When true, bubbles display at the edges of the screen with the expanded view
     * to the left or right side.
     */
    public boolean showBubblesVertically() {
        return isLandscape() || mShowingInTaskbar || mIsLargeScreen;
    }

    /** Size of the bubble account for badge & dot. */
    public int getBubbleSize() {
        int bsize = (mShowingInTaskbar && mTaskbarIconSize > 0)
                ? mTaskbarIconSize
                : mBubbleSize;
        return bsize;
    }

    /** Size of the bitmap within the bubble */
    public int getBubbleBitmapSize() {
        float size =  (mShowingInTaskbar && mTaskbarIconSize > 0)
                ? (mTaskbarIconSize * BUBBLE_BITMAP_SIZE_PERCENT)
                : mBubbleBitmapSize;
        return (int) size;
    }

    /**
     * Calculates the left & right padding for the bubble expanded view.
     *
     * On larger screens the width of the expanded view is restricted via this padding.
     * On landscape the bubble overflow expanded view is also restricted via this padding.
     */
    public int[] getExpandedViewPadding(boolean onLeft, boolean isOverflow) {
        int leftPadding = mInsets.left + mExpandedViewPadding;
        int rightPadding = mInsets.right + mExpandedViewPadding;
        final boolean isLargeOrOverflow = mIsLargeScreen || isOverflow;
        if (showBubblesVertically()) {
            if (!onLeft) {
                rightPadding += mBubbleSize - mPointerHeight;
                leftPadding += isLargeOrOverflow
                        ? (mPositionRect.width() - rightPadding - mExpandedViewLargeScreenWidth)
                        : 0;
            } else {
                leftPadding += mBubbleSize - mPointerHeight;
                rightPadding += isLargeOrOverflow
                        ? (mPositionRect.width() - leftPadding - mExpandedViewLargeScreenWidth)
                        : 0;
            }
        }
        // [left, top, right, bottom]
        mPaddings[0] = leftPadding;
        mPaddings[1] = showBubblesVertically() ? 0 : mPointerMargin;
        mPaddings[2] = rightPadding;
        mPaddings[3] = 0;
        return mPaddings;
    }

    /** Calculates the y position of the expanded view when it is expanded. */
    public float getExpandedViewY() {
        final int top = getAvailableRect().top;
        if (showBubblesVertically()) {
            return top - mPointerWidth;
        } else {
            return top + mBubbleSize + mPointerMargin;
        }
    }

    /**
     * Sets the stack's most recent position along the edge of the screen. This is saved when the
     * last bubble is removed, so that the stack can be restored in its previous position.
     */
    public void setRestingPosition(PointF position) {
        if (mRestingStackPosition == null) {
            mRestingStackPosition = new PointF(position);
        } else {
            mRestingStackPosition.set(position);
        }
    }

    /** The position the bubble stack should rest at when collapsed. */
    public PointF getRestingPosition() {
        if (mPinLocation != null) {
            return mPinLocation;
        }
        if (mRestingStackPosition == null) {
            return getDefaultStartPosition();
        }
        return mRestingStackPosition;
    }

    /**
     * @return the stack position to use if we don't have a saved location or if user education
     * is being shown.
     */
    public PointF getDefaultStartPosition() {
        // Start on the left if we're in LTR, right otherwise.
        final boolean startOnLeft =
                mContext.getResources().getConfiguration().getLayoutDirection()
                        != View.LAYOUT_DIRECTION_RTL;
        final float startingVerticalOffset = mContext.getResources().getDimensionPixelOffset(
                R.dimen.bubble_stack_starting_offset_y);
        // TODO: placement bug here because mPositionRect doesn't handle the overhanging edge
        return new BubbleStackView.RelativeStackPosition(
                startOnLeft,
                startingVerticalOffset / mPositionRect.height())
                .getAbsolutePositionInRegion(new RectF(mPositionRect));
    }

    /**
     * @return whether the bubble stack is pinned to the taskbar.
     */
    public boolean showingInTaskbar() {
        return mShowingInTaskbar;
    }

    /**
     * @return the taskbar position if set.
     */
    public int getTaskbarPosition() {
        return mTaskbarPosition;
    }

    public int getTaskbarSize() {
        return mTaskbarSize;
    }

    /**
     * In some situations bubbles will be pinned to a specific onscreen location. This sets the
     * location to anchor the stack to.
     */
    public void setPinnedLocation(PointF point) {
        mPinLocation = point;
    }
}
