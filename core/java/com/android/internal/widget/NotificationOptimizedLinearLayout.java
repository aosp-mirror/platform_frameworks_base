/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.widget;

import static android.widget.flags.Flags.notifLinearlayoutOptimized;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Trace;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * This LinearLayout customizes the measurement behavior of LinearLayout for Notification layouts.
 * When there is exactly
 * one child View with <code>layout_weight</code>. onMeasure methods of this LinearLayout will:
 * 1. Measure all other children.
 * 2. Calculate the remaining space for the View with <code>layout_weight</code>
 * 3. Measure the weighted View using the calculated remaining width or height (based on
 * Orientation).
 * This ensures that the weighted View fills the remaining space in LinearLayout with only single
 * measure.
 *
 * **Assumptions:**
 * - There is *exactly one* child view with non-zero <code>layout_weight</code>.
 * - Other views should not have weight.
 * - LinearLayout doesn't have <code>weightSum</code>.
 * - Horizontal LinearLayout's width should be measured EXACTLY.
 * - Horizontal LinearLayout shouldn't need baseLineAlignment.
 * - Horizontal LinearLayout shouldn't have any child that has negative left or right margin.
 * - Vertical LinearLayout shouldn't have MATCH_PARENT children when it is not measured EXACTLY.
 *
 * @hide
 */
@RemoteViews.RemoteView
public class NotificationOptimizedLinearLayout extends LinearLayout {
    private static final boolean DEBUG_LAYOUT = false;
    private static final boolean TRACE_ONMEASURE = Build.isDebuggable();
    private static final String TAG = "NotifOptimizedLinearLayout";

    private boolean mShouldUseOptimizedLayout = false;

    public NotificationOptimizedLinearLayout(Context context) {
        super(context);
    }

    public NotificationOptimizedLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationOptimizedLinearLayout(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationOptimizedLinearLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final View weightedChildView = getSingleWeightedChild();
        mShouldUseOptimizedLayout =
                isUseOptimizedLinearLayoutFlagEnabled() && weightedChildView != null
                        && isOptimizationPossible(widthMeasureSpec, heightMeasureSpec);

        if (mShouldUseOptimizedLayout) {
            onMeasureOptimized(weightedChildView, widthMeasureSpec, heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private boolean isUseOptimizedLinearLayoutFlagEnabled() {
        final boolean enabled = notifLinearlayoutOptimized();
        if (!enabled) {
            logSkipOptimizedOnMeasure("enableNotifLinearlayoutOptimized flag is off.");
        }
        return enabled;
    }

    /**
     * Checks if optimizations can be safely applied to this LinearLayout during layout
     * calculations. Optimizations might be disabled in the following cases:
     *
     * **weightSum**: When LinearLayout has weightSum
     * ** MATCH_PARENT children in non EXACT dimension**
     * **Horizontal LinearLayout with non-EXACT width**
     * **Baseline Alignment:**  If views need to align their baselines in Horizontal LinearLayout
     *
     * @param widthMeasureSpec  The width measurement specification.
     * @param heightMeasureSpec The height measurement specification.
     * @return `true` if optimization is possible, `false` otherwise.
     */
    private boolean isOptimizationPossible(int widthMeasureSpec, int heightMeasureSpec) {
        final boolean hasWeightSum = getWeightSum() > 0.0f;
        if (hasWeightSum) {
            logSkipOptimizedOnMeasure("Has weightSum.");
            return false;
        }

        if (requiresMatchParentRemeasureForVerticalLinearLayout(widthMeasureSpec)) {
            logSkipOptimizedOnMeasure(
                    "Vertical LinearLayout requires children width MATCH_PARENT remeasure ");
            return false;
        }

        final boolean isHorizontal = getOrientation() == HORIZONTAL;
        if (isHorizontal && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            logSkipOptimizedOnMeasure("Horizontal LinearLayout's width should be "
                    + "measured EXACTLY");
            return false;
        }

        if (requiresBaselineAlignmentForHorizontalLinearLayout()) {
            logSkipOptimizedOnMeasure("Need to apply baseline.");
            return false;
        }

        if (requiresNegativeMarginHandlingForHorizontalLinearLayout()) {
            logSkipOptimizedOnMeasure("Need to handle negative margins.");
            return false;
        }
        return true;
    }

    /**
     * @return if the horizontal linearlayout requires to handle negative margins in its children.
     * In that case, we can't use excessSpace because LinearLayout negative margin handling for
     * excess space and WRAP_CONTENT is different.
     */
    private boolean requiresNegativeMarginHandlingForHorizontalLinearLayout() {
        if (getOrientation() == VERTICAL) {
            return false;
        }

        final List<View> activeChildren = getActiveChildren();
        for (int i = 0; i < activeChildren.size(); i++) {
            final View child = activeChildren.get(i);
            final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            if (lp.leftMargin < 0 || lp.rightMargin < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return if the vertical linearlayout requires match_parent children remeasure
     */
    private boolean requiresMatchParentRemeasureForVerticalLinearLayout(int widthMeasureSpec) {
        // HORIZONTAL measuring is handled by LinearLayout. That's why we don't need to check it
        // here.
        if (getOrientation() == HORIZONTAL) {
            return false;
        }

        // When the width is not EXACT, children with MATCH_PARENT width need to be double measured.
        // This needs to be handled in LinearLayout because NotificationOptimizedLinearLayout
        final boolean nonExactWidth =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY;
        final List<View> activeChildren = getActiveChildren();
        for (int i = 0; i < activeChildren.size(); i++) {
            final View child = activeChildren.get(i);
            final ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (nonExactWidth && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return if this layout needs to apply baseLineAlignment.
     */
    private boolean requiresBaselineAlignmentForHorizontalLinearLayout() {
        // baseLineAlignment is not important for Vertical LinearLayout.
        if (getOrientation() == VERTICAL) {
            return false;
        }
        // Early return, if it is already disabled
        if (!isBaselineAligned()) {
            return false;
        }

        final List<View> activeChildren = getActiveChildren();
        final int minorGravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;

        for (int i = 0; i < activeChildren.size(); i++) {
            final View child = activeChildren.get(i);
            if (child.getLayoutParams() instanceof LayoutParams) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int childBaseline = -1;

                if (lp.height != LayoutParams.MATCH_PARENT) {
                    childBaseline = child.getBaseline();
                }
                if (childBaseline == -1) {
                    // This child doesn't have a baseline.
                    continue;
                }
                int gravity = lp.gravity;
                if (gravity < 0) {
                    gravity = minorGravity;
                }

                final int result = gravity & Gravity.VERTICAL_GRAVITY_MASK;
                if (result == Gravity.TOP || result == Gravity.BOTTOM) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Finds the single child view within this layout that has a non-zero weight assigned to its
     * LayoutParams.
     *
     * @return The weighted child view, or null if multiple weighted children exist or no weighted
     * children are found.
     */
    @Nullable
    private View getSingleWeightedChild() {
        final boolean isVertical = getOrientation() == VERTICAL;
        final List<View> activeChildren = getActiveChildren();
        View singleWeightedChild = null;
        for (int i = 0; i < activeChildren.size(); i++) {
            final View child = activeChildren.get(i);
            if (child.getLayoutParams() instanceof LayoutParams) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if ((!isVertical && lp.width == ViewGroup.LayoutParams.MATCH_PARENT)
                        || (isVertical && lp.height == ViewGroup.LayoutParams.MATCH_PARENT)) {
                    logSkipOptimizedOnMeasure(
                            "There is a match parent child in the related orientation.");
                    return null;
                }
                if (lp.weight != 0) {
                    if (singleWeightedChild == null) {
                        singleWeightedChild = child;
                    } else {
                        logSkipOptimizedOnMeasure("There is more than one weighted child.");
                        return null;
                    }
                }
            }
        }
        if (singleWeightedChild == null) {
            logSkipOptimizedOnMeasure("There is no weighted child in this layout.");
        } else {
            final LayoutParams lp = (LayoutParams) singleWeightedChild.getLayoutParams();
            boolean isHeightWrapContentOrZero =
                    lp.height == ViewGroup.LayoutParams.WRAP_CONTENT || lp.height == 0;
            boolean isWidthWrapContentOrZero =
                    lp.width == ViewGroup.LayoutParams.WRAP_CONTENT || lp.width == 0;
            if ((isVertical && !isHeightWrapContentOrZero)
                    || (!isVertical && !isWidthWrapContentOrZero)) {
                logSkipOptimizedOnMeasure(
                        "Single weighted child should be either WRAP_CONTENT or 0"
                                + " in the related orientation");
                singleWeightedChild = null;
            }
        }

        return singleWeightedChild;
    }

    /**
     * Optimized measurement for the single weighted child in this LinearLayout.
     * Measures other children, calculates remaining space, then measures the weighted
     * child using the remaining width (or height).
     *
     * Note: Horizontal LinearLayout doesn't need to apply baseline in optimized case @see
     * {@link #requiresBaselineAlignmentForHorizontalLinearLayout}.
     *
     * @param weightedChildView The weighted child view(with `layout_weight!=0`)
     * @param widthMeasureSpec  The width MeasureSpec to use for measurement
     * @param heightMeasureSpec The height MeasureSpec to use for measurement.
     */
    private void onMeasureOptimized(@NonNull View weightedChildView, int widthMeasureSpec,
            int heightMeasureSpec) {
        try {
            if (TRACE_ONMEASURE) {
                Trace.beginSection("NotifOptimizedLinearLayout#onMeasure");
            }

            if (getOrientation() == LinearLayout.HORIZONTAL) {
                final ViewGroup.LayoutParams lp = weightedChildView.getLayoutParams();
                final int childWidth = lp.width;
                final boolean isBaselineAligned = isBaselineAligned();
                // It should be marked 0 so that it use excessSpace in LinearLayout's onMeasure
                lp.width = 0;

                // It doesn't need to apply baseline. So disable it.
                setBaselineAligned(false);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);

                // restore values.
                lp.width = childWidth;
                setBaselineAligned(isBaselineAligned);
            } else {
                measureVerticalOptimized(weightedChildView, widthMeasureSpec, heightMeasureSpec);
            }
        } finally {
            if (TRACE_ONMEASURE) {
                trackShouldUseOptimizedLayout();
                Trace.endSection();
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (mShouldUseOptimizedLayout) {
            onLayoutOptimized(changed, l, t, r, b);
        } else {
            super.onLayout(changed, l, t, r, b);
        }
    }

    private void onLayoutOptimized(boolean changed, int l, int t, int r, int b) {
        if (getOrientation() == LinearLayout.HORIZONTAL) {
            super.onLayout(changed, l, t, r, b);
        } else {
            layoutVerticalOptimized(l, t, r, b);
        }
    }

    /**
     * Optimized measurement for the single weighted child in this LinearLayout.
     * Measures other children, calculates remaining space, then measures the weighted
     * child using the exact remaining height.
     *
     * @param weightedChildView The weighted child view(with `layout_weight=1`
     * @param widthMeasureSpec  The width MeasureSpec to use for measurement
     * @param heightMeasureSpec The height MeasureSpec to use for measurement.
     */
    private void measureVerticalOptimized(@NonNull View weightedChildView, int widthMeasureSpec,
            int heightMeasureSpec) {
        int totalLength = 0;
        int maxWidth = 0;
        final int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        // 1. Measure all unweighted children
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child == null || child.getVisibility() == GONE) {
                continue;
            }

            final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

            if (child == weightedChildView) {
                // In excessMode, LinearLayout add  weighted child top and bottom margins to
                // totalLength when their sum is positive.
                if (lp.height == 0 && heightMode == MeasureSpec.EXACTLY) {
                    totalLength = Math.max(totalLength, totalLength + lp.topMargin
                            + lp.bottomMargin);
                }
                continue;
            }

            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            // LinearLayout only adds measured children heights and its top and bottom margins
            // to totalLength when their sum is positive.
            totalLength = Math.max(totalLength,
                    totalLength + child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
            maxWidth = Math.max(maxWidth,
                    child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
        }

        // Add padding to totalLength that we are going to use for remaining space.
        totalLength += mPaddingTop + mPaddingBottom;

        // 2. generate measure spec for weightedChildView.
        final MarginLayoutParams lp = (MarginLayoutParams) weightedChildView.getLayoutParams();
        // height should be AT_MOST for non EXACT cases.
        final int childHeightMeasureMode =
                heightMode == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
        final int childHeightMeasureSpec;

        // In excess mode, LinearLayout measures weighted children with remaining space. Otherwise,
        // it is measured with remaining space just like other children.
        if (lp.height == 0 && heightMode == MeasureSpec.EXACTLY) {
            childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                    Math.max(0, availableHeight - totalLength), childHeightMeasureMode);
        } else {
            final int usedHeight = lp.topMargin + lp.bottomMargin + totalLength;
            childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                    Math.max(0, availableHeight - usedHeight), childHeightMeasureMode);
        }
        final int childWidthMeasureSpec = getChildMeasureSpec(widthMeasureSpec,
                mPaddingLeft + mPaddingRight + lp.leftMargin + lp.rightMargin, lp.width);

        // 3. Measure weightedChildView with the remaining space.
        weightedChildView.measure(childWidthMeasureSpec, childHeightMeasureSpec);

        totalLength = Math.max(totalLength,
                totalLength + weightedChildView.getMeasuredHeight() + lp.topMargin
                        + lp.bottomMargin);

        maxWidth = Math.max(maxWidth,
                weightedChildView.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);

        // Add padding to width
        maxWidth += getPaddingLeft() + getPaddingRight();

        // Resolve final dimensions
        final int finalWidth = resolveSizeAndState(Math.max(maxWidth, getSuggestedMinimumWidth()),
                widthMeasureSpec, 0);
        final int finalHeight = resolveSizeAndState(
                Math.max(totalLength, getSuggestedMinimumHeight()), heightMeasureSpec, 0);
        setMeasuredDimension(finalWidth, finalHeight);
    }

    @NonNull
    private List<View> getActiveChildren() {
        final int childCount = getChildCount();
        final List<View> activeChildren = new ArrayList<>();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) {
                continue;
            }
            activeChildren.add(child);
        }
        return activeChildren;
    }

    //region LinearLayout copy methods

    /**
     * layoutVerticalOptimized is a version of LinearLayout's layoutVertical method that
     * excludes
     * TableRow-related functionalities.
     *
     * @see LinearLayout#onLayout(boolean, int, int, int, int)
     */
    private void layoutVerticalOptimized(int left, int top, int right,
            int bottom) {
        final int paddingLeft = mPaddingLeft;
        final int mTotalLength = getMeasuredHeight();
        int childTop;
        int childLeft;

        // Where right end of child should go
        final int width = right - left;
        int childRight = width - mPaddingRight;

        // Space available for child
        int childSpace = width - paddingLeft - mPaddingRight;

        final int count = getChildCount();

        final int majorGravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        final int minorGravity = getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;

        switch (majorGravity) {
            case Gravity.BOTTOM:
                // mTotalLength contains the padding already
                childTop = mPaddingTop + bottom - top - mTotalLength;
                break;

            // mTotalLength contains the padding already
            case Gravity.CENTER_VERTICAL:
                childTop = mPaddingTop + (bottom - top - mTotalLength) / 2;
                break;

            case Gravity.TOP:
            default:
                childTop = mPaddingTop;
                break;
        }
        final int dividerHeight = getDividerHeight();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child != null && child.getVisibility() != GONE) {
                final int childWidth = child.getMeasuredWidth();
                final int childHeight = child.getMeasuredHeight();

                final LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) child.getLayoutParams();

                int gravity = lp.gravity;
                if (gravity < 0) {
                    gravity = minorGravity;
                }
                final int layoutDirection = getLayoutDirection();
                final int absoluteGravity = Gravity.getAbsoluteGravity(gravity,
                        layoutDirection);
                switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                    case Gravity.CENTER_HORIZONTAL:
                        childLeft =
                                paddingLeft + ((childSpace - childWidth) / 2) + lp.leftMargin
                                        - lp.rightMargin;
                        break;

                    case Gravity.RIGHT:
                        childLeft = childRight - childWidth - lp.rightMargin;
                        break;

                    case Gravity.LEFT:
                    default:
                        childLeft = paddingLeft + lp.leftMargin;
                        break;
                }

                if (hasDividerBeforeChildAt(i)) {
                    childTop += dividerHeight;
                }

                childTop += lp.topMargin;
                child.layout(childLeft, childTop, childLeft + childWidth,
                        childTop + childHeight);
                childTop += childHeight + lp.bottomMargin;

            }
        }
    }

    /**
     * Used in laying out views vertically.
     *
     * @see #layoutVerticalOptimized
     * @see LinearLayout#onLayout(boolean, int, int, int, int)
     */
    private int getDividerHeight() {
        final Drawable dividerDrawable = getDividerDrawable();
        if (dividerDrawable == null) {
            return 0;
        } else {
            return dividerDrawable.getIntrinsicHeight();
        }
    }
    //endregion

    //region Logging&Tracing
    private void trackShouldUseOptimizedLayout() {
        if (TRACE_ONMEASURE) {
            Trace.setCounter("NotifOptimizedLinearLayout#shouldUseOptimizedLayout",
                    mShouldUseOptimizedLayout ? 1 : 0);
        }
    }

    private void logSkipOptimizedOnMeasure(String reason) {
        if (DEBUG_LAYOUT) {
            final StringBuilder logMessage = new StringBuilder();
            int layoutId = getId();
            if (layoutId != NO_ID) {
                final Resources resources = getResources();
                if (resources != null) {
                    logMessage.append("[");
                    logMessage.append(resources.getResourceName(layoutId));
                    logMessage.append("] ");
                }
            }
            logMessage.append("Going to skip onMeasureOptimized reason:");
            logMessage.append(reason);

            Log.d(TAG, logMessage.toString());
        }
    }
    //endregion
}
