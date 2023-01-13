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

import static android.view.View.LAYOUT_DIRECTION_RTL;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.Surface;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.VisibleForTesting;

import com.android.launcher3.icons.IconNormalizer;
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

    /** When the bubbles are collapsed in a stack only some of them are shown, this is how many. **/
    public static final int NUM_VISIBLE_WHEN_RESTING = 2;
    /** Indicates a bubble's height should be the maximum available space. **/
    public static final int MAX_HEIGHT = -1;
    /** The max percent of screen width to use for the flyout on large screens. */
    public static final float FLYOUT_MAX_WIDTH_PERCENT_LARGE_SCREEN = 0.3f;
    /** The max percent of screen width to use for the flyout on phone. */
    public static final float FLYOUT_MAX_WIDTH_PERCENT = 0.6f;
    /** The percent of screen width that should be used for the expanded view on a large screen. **/
    private static final float EXPANDED_VIEW_LARGE_SCREEN_LANDSCAPE_WIDTH_PERCENT = 0.48f;
    /** The percent of screen width that should be used for the expanded view on a large screen. **/
    private static final float EXPANDED_VIEW_LARGE_SCREEN_PORTRAIT_WIDTH_PERCENT = 0.70f;
    /** The percent of screen width that should be used for the expanded view on a small tablet. **/
    private static final float EXPANDED_VIEW_SMALL_TABLET_WIDTH_PERCENT = 0.72f;

    private Context mContext;
    private WindowManager mWindowManager;
    private Rect mScreenRect;
    private @Surface.Rotation int mRotation = Surface.ROTATION_0;
    private Insets mInsets;
    private boolean mImeVisible;
    private int mImeHeight;
    private boolean mIsLargeScreen;
    private boolean mIsSmallTablet;

    private Rect mPositionRect;
    private int mDefaultMaxBubbles;
    private int mMaxBubbles;
    private int mBubbleSize;
    private int mSpacingBetweenBubbles;
    private int mBubblePaddingTop;
    private int mBubbleOffscreenAmount;
    private int mStackOffset;

    private int mExpandedViewMinHeight;
    private int mExpandedViewLargeScreenWidth;
    private int mExpandedViewLargeScreenInsetClosestEdge;
    private int mExpandedViewLargeScreenInsetFurthestEdge;

    private int mOverflowWidth;
    private int mExpandedViewPadding;
    private int mPointerMargin;
    private int mPointerWidth;
    private int mPointerHeight;
    private int mPointerOverlap;
    private int mManageButtonHeight;
    private int mOverflowHeight;
    private int mMinimumFlyoutWidthLargeScreen;

    private PointF mPinLocation;
    private PointF mRestingStackPosition;
    private int[] mPaddings = new int[4];

    private boolean mShowingInTaskbar;
    private @TaskbarPosition int mTaskbarPosition = TASKBAR_POSITION_NONE;
    private int mTaskbarIconSize;
    private int mTaskbarSize;

    public BubblePositioner(Context context, WindowManager windowManager) {
        mContext = context;
        mWindowManager = windowManager;
        update();
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

        final Rect bounds = windowMetrics.getBounds();
        Configuration config = mContext.getResources().getConfiguration();
        mIsLargeScreen = config.smallestScreenWidthDp >= 600;
        if (mIsLargeScreen) {
            float largestEdgeDp = Math.max(config.screenWidthDp, config.screenHeightDp);
            mIsSmallTablet = largestEdgeDp < 960;
        } else {
            mIsSmallTablet = false;
        }

        if (BubbleDebugConfig.DEBUG_POSITIONER) {
            Log.w(TAG, "update positioner:"
                    + " rotation: " + mRotation
                    + " insets: " + insets
                    + " isLargeScreen: " + mIsLargeScreen
                    + " isSmallTablet: " + mIsSmallTablet
                    + " bounds: " + bounds
                    + " showingInTaskbar: " + mShowingInTaskbar);
        }
        updateInternal(mRotation, insets, bounds);
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

        mScreenRect = new Rect(bounds);
        mPositionRect = new Rect(bounds);
        mPositionRect.left += mInsets.left;
        mPositionRect.top += mInsets.top;
        mPositionRect.right -= mInsets.right;
        mPositionRect.bottom -= mInsets.bottom;

        Resources res = mContext.getResources();
        mBubbleSize = res.getDimensionPixelSize(R.dimen.bubble_size);
        mSpacingBetweenBubbles = res.getDimensionPixelSize(R.dimen.bubble_spacing);
        mDefaultMaxBubbles = res.getInteger(R.integer.bubbles_max_rendered);
        mExpandedViewPadding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mBubbleOffscreenAmount = res.getDimensionPixelSize(R.dimen.bubble_stack_offscreen);
        mStackOffset = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);

        if (mIsSmallTablet) {
            mExpandedViewLargeScreenWidth = (int) (bounds.width()
                    * EXPANDED_VIEW_SMALL_TABLET_WIDTH_PERCENT);
        } else {
            mExpandedViewLargeScreenWidth = isLandscape()
                    ? (int) (bounds.width() * EXPANDED_VIEW_LARGE_SCREEN_LANDSCAPE_WIDTH_PERCENT)
                    : (int) (bounds.width() * EXPANDED_VIEW_LARGE_SCREEN_PORTRAIT_WIDTH_PERCENT);
        }
        if (mIsLargeScreen) {
            if (isLandscape() && !mIsSmallTablet) {
                mExpandedViewLargeScreenInsetClosestEdge = res.getDimensionPixelSize(
                        R.dimen.bubble_expanded_view_largescreen_landscape_padding);
                mExpandedViewLargeScreenInsetFurthestEdge = bounds.width()
                        - mExpandedViewLargeScreenInsetClosestEdge
                        - mExpandedViewLargeScreenWidth;
            } else {
                final int centeredInset = (bounds.width() - mExpandedViewLargeScreenWidth) / 2;
                mExpandedViewLargeScreenInsetClosestEdge = centeredInset;
                mExpandedViewLargeScreenInsetFurthestEdge = centeredInset;
            }
        } else {
            mExpandedViewLargeScreenInsetClosestEdge = mExpandedViewPadding;
            mExpandedViewLargeScreenInsetFurthestEdge = mExpandedViewPadding;
        }

        mOverflowWidth = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_overflow_width);
        mPointerWidth = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        mPointerHeight = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);
        mPointerMargin = res.getDimensionPixelSize(R.dimen.bubble_pointer_margin);
        mPointerOverlap = res.getDimensionPixelSize(R.dimen.bubble_pointer_overlap);
        mManageButtonHeight = res.getDimensionPixelSize(R.dimen.bubble_manage_button_total_height);
        mExpandedViewMinHeight = res.getDimensionPixelSize(R.dimen.bubble_expanded_default_height);
        mOverflowHeight = res.getDimensionPixelSize(R.dimen.bubble_overflow_height);
        mMinimumFlyoutWidthLargeScreen = res.getDimensionPixelSize(
                R.dimen.bubbles_flyout_min_width_large_screen);

        mMaxBubbles = calculateMaxBubbles();

        if (mShowingInTaskbar) {
            adjustForTaskbar();
        }
    }

    /**
     * @return the maximum number of bubbles that can fit on the screen when expanded. If the
     * screen size / screen density is too small to support the default maximum number, then
     * the number will be adjust to something lower to ensure everything is presented nicely.
     */
    private int calculateMaxBubbles() {
        // Use the shortest edge.
        // In portrait the bubbles should align with the expanded view so subtract its padding.
        // We always show the overflow so subtract one bubble size.
        int padding = showBubblesVertically() ? 0 : (mExpandedViewPadding * 2);
        int availableSpace = Math.min(mPositionRect.width(), mPositionRect.height())
                - padding
                - mBubbleSize;
        // Each of the bubbles have spacing because the overflow is at the end.
        int howManyFit = availableSpace / (mBubbleSize + mSpacingBetweenBubbles);
        if (howManyFit < mDefaultMaxBubbles) {
            // Not enough space for the default.
            return howManyFit;
        }
        return mDefaultMaxBubbles;
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
     * @return a rect of the screen size.
     */
    public Rect getScreenRect() {
        return mScreenRect;
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
        return mContext.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
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

    /** Size of the bubble. */
    public int getBubbleSize() {
        return (mShowingInTaskbar && mTaskbarIconSize > 0)
                ? mTaskbarIconSize
                : mBubbleSize;
    }

    /** The amount of padding at the top of the screen that the bubbles avoid when being placed. */
    public int getBubblePaddingTop() {
        return mBubblePaddingTop;
    }

    /** The amount the stack hang off of the screen when collapsed. */
    public int getStackOffScreenAmount() {
        return mBubbleOffscreenAmount;
    }

    /** Offset of bubbles in the stack (i.e. how much they overlap). */
    public int getStackOffset() {
        return mStackOffset;
    }

    /** Size of the visible (non-overlapping) part of the pointer. */
    public int getPointerSize() {
        return mPointerHeight - mPointerOverlap;
    }

    /** The maximum number of bubbles that can be displayed comfortably on screen. */
    public int getMaxBubbles() {
        return mMaxBubbles;
    }

    /** The height for the IME if it's visible. **/
    public int getImeHeight() {
        return mImeVisible ? mImeHeight : 0;
    }

    /** Return top position of the IME if it's visible */
    public int getImeTop() {
        if (mImeVisible) {
            return getScreenRect().bottom - getImeHeight() - getInsets().bottom;
        }
        return 0;
    }

    /** Sets whether the IME is visible. **/
    public void setImeVisible(boolean visible, int height) {
        mImeVisible = visible;
        mImeHeight = height;
    }

    private int getExpandedViewLargeScreenInsetFurthestEdge(boolean isOverflow) {
        if (isOverflow && mIsLargeScreen) {
            return mScreenRect.width()
                    - mExpandedViewLargeScreenInsetClosestEdge
                    - mOverflowWidth;
        }
        return mExpandedViewLargeScreenInsetFurthestEdge;
    }

    /**
     * Calculates the padding for the bubble expanded view.
     *
     * Some specifics:
     * On large screens the width of the expanded view is restricted via this padding.
     * On phone landscape the bubble overflow expanded view is also restricted via this padding.
     * On large screens & landscape no top padding is set, the top position is set via translation.
     * On phone portrait top padding is set as the space between the tip of the pointer and the
     * bubble.
     * When the overflow is shown it doesn't have the manage button to pad out the bottom so
     * padding is added.
     */
    public int[] getExpandedViewContainerPadding(boolean onLeft, boolean isOverflow) {
        final int pointerTotalHeight = getPointerSize();
        final int expandedViewLargeScreenInsetFurthestEdge =
                getExpandedViewLargeScreenInsetFurthestEdge(isOverflow);
        if (mIsLargeScreen) {
            // Note:
            // If we're in portrait OR if we're a small tablet, then the two insets values will
            // be equal. If we're landscape and a large tablet, the two values will be different.
            // [left, top, right, bottom]
            mPaddings[0] = onLeft
                    ? mExpandedViewLargeScreenInsetClosestEdge - pointerTotalHeight
                    : expandedViewLargeScreenInsetFurthestEdge;
            mPaddings[1] = 0;
            mPaddings[2] = onLeft
                    ? expandedViewLargeScreenInsetFurthestEdge
                    : mExpandedViewLargeScreenInsetClosestEdge - pointerTotalHeight;
            // Overflow doesn't show manage button / get padding from it so add padding here
            mPaddings[3] = isOverflow ? mExpandedViewPadding : 0;
            return mPaddings;
        } else {
            int leftPadding = mInsets.left + mExpandedViewPadding;
            int rightPadding = mInsets.right + mExpandedViewPadding;
            final float expandedViewWidth = isOverflow
                    ? mOverflowWidth
                    : mExpandedViewLargeScreenWidth;
            if (showBubblesVertically()) {
                if (!onLeft) {
                    rightPadding += mBubbleSize - pointerTotalHeight;
                    leftPadding += isOverflow
                            ? (mPositionRect.width() - rightPadding - expandedViewWidth)
                            : 0;
                } else {
                    leftPadding += mBubbleSize - pointerTotalHeight;
                    rightPadding += isOverflow
                            ? (mPositionRect.width() - leftPadding - expandedViewWidth)
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
    }

    /** Gets the y position of the expanded view if it was top-aligned. */
    public float getExpandedViewYTopAligned() {
        final int top = getAvailableRect().top;
        if (showBubblesVertically()) {
            return top - mPointerWidth + mExpandedViewPadding;
        } else {
            return top + mBubbleSize + mPointerMargin;
        }
    }

    /**
     * Calculate the maximum height the expanded view can be depending on where it's placed on
     * the screen and the size of the elements around it (e.g. padding, pointer, manage button).
     */
    public int getMaxExpandedViewHeight(boolean isOverflow) {
        // Subtract top insets because availableRect.height would account for that
        int expandedContainerY = (int) getExpandedViewYTopAligned() - getInsets().top;
        int paddingTop = showBubblesVertically()
                ? 0
                : mPointerHeight;
        // Subtract pointer size because it's laid out in LinearLayout with the expanded view.
        int pointerSize = showBubblesVertically()
                ? mPointerWidth
                : (mPointerHeight + mPointerMargin);
        int bottomPadding = isOverflow ? mExpandedViewPadding : mManageButtonHeight;
        return getAvailableRect().height()
                - expandedContainerY
                - paddingTop
                - pointerSize
                - bottomPadding;
    }

    /**
     * Determines the height for the bubble, ensuring a minimum height. If the height should be as
     * big as available, returns {@link #MAX_HEIGHT}.
     */
    public float getExpandedViewHeight(BubbleViewProvider bubble) {
        boolean isOverflow = bubble == null || BubbleOverflow.KEY.equals(bubble.getKey());
        if (isOverflow && showBubblesVertically() && !mIsLargeScreen) {
            // overflow in landscape on phone is max
            return MAX_HEIGHT;
        }
        float desiredHeight = isOverflow
                ? mOverflowHeight
                : ((Bubble) bubble).getDesiredHeight(mContext);
        desiredHeight = Math.max(desiredHeight, mExpandedViewMinHeight);
        if (desiredHeight > getMaxExpandedViewHeight(isOverflow)) {
            return MAX_HEIGHT;
        }
        return desiredHeight;
    }

    /**
     * Gets the y position for the expanded view. This is the position on screen of the top
     * horizontal line of the expanded view.
     *
     * @param bubble the bubble being positioned.
     * @param bubblePosition the x position of the bubble if showing on top, the y position of the
     *                       bubble if showing vertically.
     * @return the y position for the expanded view.
     */
    public float getExpandedViewY(BubbleViewProvider bubble, float bubblePosition) {
        boolean isOverflow = bubble == null || BubbleOverflow.KEY.equals(bubble.getKey());
        float expandedViewHeight = getExpandedViewHeight(bubble);
        float topAlignment = getExpandedViewYTopAligned();
        if (!showBubblesVertically() || expandedViewHeight == MAX_HEIGHT) {
            // Top-align when bubbles are shown at the top or are max size.
            return topAlignment;
        }
        // If we're here, we're showing vertically & developer has made height less than maximum.
        int manageButtonHeight = isOverflow ? mExpandedViewPadding : mManageButtonHeight;
        float pointerPosition = getPointerPosition(bubblePosition);
        float bottomIfCentered = pointerPosition + (expandedViewHeight / 2) + manageButtonHeight;
        float topIfCentered = pointerPosition - (expandedViewHeight / 2);
        if (topIfCentered > mPositionRect.top && mPositionRect.bottom > bottomIfCentered) {
            // Center it
            return pointerPosition - mPointerWidth - (expandedViewHeight / 2f);
        } else if (topIfCentered <= mPositionRect.top) {
            // Top align
            return topAlignment;
        } else {
            // Bottom align
            return mPositionRect.bottom - manageButtonHeight - expandedViewHeight - mPointerWidth;
        }
    }

    /**
     * The position the pointer points to, the center of the bubble.
     *
     * @param bubblePosition the x position of the bubble if showing on top, the y position of the
     *                       bubble if showing vertically.
     * @return the position the tip of the pointer points to. The x position if showing on top, the
     * y position if showing vertically.
     */
    public float getPointerPosition(float bubblePosition) {
        // TODO: I don't understand why it works but it does - why normalized in portrait
        //  & not in landscape? Am I missing ~2dp in the portrait expandedViewY calculation?
        final float normalizedSize = IconNormalizer.getNormalizedCircleSize(
                getBubbleSize());
        return showBubblesVertically()
                ? bubblePosition + (getBubbleSize() / 2f)
                : bubblePosition + (normalizedSize / 2f) - mPointerWidth;
    }

    private int getExpandedStackSize(int numberOfBubbles) {
        return (numberOfBubbles * mBubbleSize)
                + ((numberOfBubbles - 1) * mSpacingBetweenBubbles);
    }

    /**
     * Returns the position of the bubble on-screen when the stack is expanded.
     *
     * @param index the index of the bubble in the stack.
     * @param state state information about the stack to help with calculations.
     * @return the position of the bubble on-screen when the stack is expanded.
     */
    public PointF getExpandedBubbleXY(int index, BubbleStackView.StackViewState state) {
        boolean showBubblesVertically = showBubblesVertically();
        boolean isRtl = mContext.getResources().getConfiguration().getLayoutDirection()
                == LAYOUT_DIRECTION_RTL;

        int onScreenIndex;
        if (showBubblesVertically || !isRtl) {
            onScreenIndex = index;
        } else {
            // If bubbles are shown horizontally, check if RTL language is used.
            // If RTL is active, position first bubble on the right and last on the left.
            // Last bubble has screen index 0 and first bubble has max screen index value.
            onScreenIndex = state.numberOfBubbles - 1 - index;
        }

        final float positionInRow = onScreenIndex * (mBubbleSize + mSpacingBetweenBubbles);
        final float expandedStackSize = getExpandedStackSize(state.numberOfBubbles);
        final float centerPosition = showBubblesVertically
                ? mPositionRect.centerY()
                : mPositionRect.centerX();
        // alignment - centered on the edge
        final float rowStart = centerPosition - (expandedStackSize / 2f);
        float x;
        float y;
        if (showBubblesVertically) {
            int inset = mExpandedViewLargeScreenInsetClosestEdge;
            y = rowStart + positionInRow;
            int left = mIsLargeScreen
                    ? inset - mExpandedViewPadding - mBubbleSize
                    : mPositionRect.left;
            int right = mIsLargeScreen
                    ? mPositionRect.right - inset + mExpandedViewPadding
                    : mPositionRect.right - mBubbleSize;
            x = state.onLeft
                    ? left
                    : right;
        } else {
            y = mPositionRect.top + mExpandedViewPadding;
            x = rowStart + positionInRow;
        }

        if (showBubblesVertically && mImeVisible) {
            return new PointF(x, getExpandedBubbleYForIme(onScreenIndex, state));
        }
        return new PointF(x, y);
    }

    /**
     * Returns the position of the bubble on-screen when the stack is expanded and the IME
     * is showing.
     *
     * @param index the index of the bubble in the stack.
     * @param state information about the stack state (# of bubbles, selected bubble).
     * @return y position of the bubble on-screen when the stack is expanded.
     */
    private float getExpandedBubbleYForIme(int index, BubbleStackView.StackViewState state) {
        final float top = getAvailableRect().top + mExpandedViewPadding;
        if (!showBubblesVertically()) {
            // Showing horizontally: align to top
            return top;
        }

        // Showing vertically: might need to translate the bubbles above the IME.
        // Add spacing here to provide a margin between top of IME and bottom of bubble row.
        final float bottomHeight = getImeHeight() + mInsets.bottom + (mSpacingBetweenBubbles * 2);
        final float bottomInset = mScreenRect.bottom - bottomHeight;
        final float expandedStackSize = getExpandedStackSize(state.numberOfBubbles);
        final float centerPosition = mPositionRect.centerY();
        final float rowBottom = centerPosition + (expandedStackSize / 2f);
        final float rowTop = centerPosition - (expandedStackSize / 2f);
        float rowTopForIme = rowTop;
        if (rowBottom > bottomInset) {
            // We overlap with IME, must shift the bubbles
            float translationY = rowBottom - bottomInset;
            rowTopForIme = Math.max(rowTop - translationY, top);
            if (rowTop - translationY < top) {
                // Even if we shift the bubbles, they will still overlap with the IME.
                // Hide the overflow for a lil more space:
                final float expandedStackSizeNoO = getExpandedStackSize(state.numberOfBubbles - 1);
                final float centerPositionNoO = showBubblesVertically()
                        ? mPositionRect.centerY()
                        : mPositionRect.centerX();
                final float rowBottomNoO = centerPositionNoO + (expandedStackSizeNoO / 2f);
                final float rowTopNoO = centerPositionNoO - (expandedStackSizeNoO / 2f);
                translationY = rowBottomNoO - bottomInset;
                rowTopForIme = rowTopNoO - translationY;
            }
        }
        // Check if the selected bubble is within the appropriate space
        final float selectedPosition = rowTopForIme
                + (state.selectedIndex * (mBubbleSize + mSpacingBetweenBubbles));
        if (selectedPosition < top) {
            // We must always keep the selected bubble in view so we'll have to allow more overlap.
            rowTopForIme = top;
        }
        return rowTopForIme + (index * (mBubbleSize + mSpacingBetweenBubbles));
    }

    /**
     * @return the width of the bubble flyout (message originating from the bubble).
     */
    public float getMaxFlyoutSize() {
        if (isLargeScreen()) {
            return Math.max(mScreenRect.width() * FLYOUT_MAX_WIDTH_PERCENT_LARGE_SCREEN,
                    mMinimumFlyoutWidthLargeScreen);
        }
        return mScreenRect.width() * FLYOUT_MAX_WIDTH_PERCENT;
    }

    /**
     * @return whether the stack is considered on the left side of the screen.
     */
    public boolean isStackOnLeft(PointF currentStackPosition) {
        if (currentStackPosition == null) {
            currentStackPosition = getRestingPosition();
        }
        final int stackCenter = (int) currentStackPosition.x + mBubbleSize / 2;
        return stackCenter < mScreenRect.width() / 2;
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
        if (mPinLocation != null) {
            return mPinLocation;
        }
        // Start on the left if we're in LTR, right otherwise.
        final boolean startOnLeft =
                mContext.getResources().getConfiguration().getLayoutDirection()
                        != LAYOUT_DIRECTION_RTL;
        final float startingVerticalOffset = mContext.getResources().getDimensionPixelOffset(
                R.dimen.bubble_stack_starting_offset_y);
        // TODO: placement bug here because mPositionRect doesn't handle the overhanging edge
        return new BubbleStackView.RelativeStackPosition(
                startOnLeft,
                startingVerticalOffset / mPositionRect.height())
                .getAbsolutePositionInRegion(getAllowableStackPositionRegion(
                        1 /* default starts with 1 bubble */));
    }


    /**
     * Returns the region that the stack position must stay within. This goes slightly off the left
     * and right sides of the screen, below the status bar/cutout and above the navigation bar.
     * While the stack position is not allowed to rest outside of these bounds, it can temporarily
     * be animated or dragged beyond them.
     */
    public RectF getAllowableStackPositionRegion(int bubbleCount) {
        final RectF allowableRegion = new RectF(getAvailableRect());
        final int imeHeight = getImeHeight();
        final float bottomPadding = bubbleCount > 1
                ? mBubblePaddingTop + mStackOffset
                : mBubblePaddingTop;
        allowableRegion.left -= mBubbleOffscreenAmount;
        allowableRegion.top += mBubblePaddingTop;
        allowableRegion.right += mBubbleOffscreenAmount - mBubbleSize;
        allowableRegion.bottom -= imeHeight + bottomPadding + mBubbleSize;
        return allowableRegion;
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
     * In some situations bubbles will be pinned to a specific onscreen location. This sets whether
     * bubbles should be pinned or not.
     */
    public void setUsePinnedLocation(boolean usePinnedLocation) {
        if (usePinnedLocation) {
            mShowingInTaskbar = true;
            mPinLocation = new PointF(mPositionRect.right - mBubbleSize,
                    mPositionRect.bottom - mBubbleSize);
        } else {
            mPinLocation = null;
            mShowingInTaskbar = false;
        }
    }

    /**
     * Navigation bar has an area where system gestures can be started from.
     *
     * @return {@link Rect} for system navigation bar gesture zone
     */
    public Rect getNavBarGestureZone() {
        // Gesture zone height from the bottom
        int gestureZoneHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_gesture_height);
        Rect screen = getScreenRect();
        return new Rect(
                screen.left,
                screen.bottom - gestureZoneHeight,
                screen.right,
                screen.bottom);
    }
}
