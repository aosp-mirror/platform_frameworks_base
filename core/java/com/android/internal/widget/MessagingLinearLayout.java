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
 * limitations under the License
 */

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import com.android.internal.R;

/**
 * A custom-built layout for the Notification.MessagingStyle.
 *
 * Evicts children until they all fit.
 */
@RemoteViews.RemoteView
public class MessagingLinearLayout extends ViewGroup {

    /**
     * Spacing to be applied between views.
     */
    private int mSpacing;

    private int mMaxDisplayedLines = Integer.MAX_VALUE;

    private IMessagingLayout mMessagingLayout;

    public MessagingLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.MessagingLinearLayout, 0,
                0);

        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.MessagingLinearLayout_spacing:
                    mSpacing = a.getDimensionPixelSize(i, 0);
                    break;
            }
        }

        a.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // This is essentially a bottom-up linear layout that only adds children that fit entirely
        // up to a maximum height.
        int targetHeight = MeasureSpec.getSize(heightMeasureSpec);
        switch (MeasureSpec.getMode(heightMeasureSpec)) {
            case MeasureSpec.UNSPECIFIED:
                targetHeight = Integer.MAX_VALUE;
                break;
        }

        // Now that we know which views to take, fix up the indents and see what width we get.
        int measuredWidth = mPaddingLeft + mPaddingRight;
        final int count = getChildCount();
        int totalHeight;
        for (int i = 0; i < count; ++i) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.hide = true;
            if (child instanceof MessagingChild) {
                MessagingChild messagingChild = (MessagingChild) child;
                // Whenever we encounter the message first, it's always first in the layout
                messagingChild.setIsFirstInLayout(true);
            }
        }

        totalHeight = mPaddingTop + mPaddingBottom;
        boolean first = true;
        int linesRemaining = mMaxDisplayedLines;
        // Starting from the bottom: we measure every view as if it were the only one. If it still
        // fits, we take it, otherwise we stop there.
        MessagingChild previousChild = null;
        View previousView = null;
        int previousChildHeight = 0;
        int previousTotalHeight = 0;
        int previousLinesConsumed = 0;
        for (int i = count - 1; i >= 0 && totalHeight < targetHeight; i--) {
            if (getChildAt(i).getVisibility() == GONE) {
                continue;
            }
            final View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) getChildAt(i).getLayoutParams();
            MessagingChild messagingChild = null;
            int spacing = mSpacing;
            int previousChildIncrease = 0;
            if (child instanceof MessagingChild) {
                // We need to remeasure the previous child again if it's not the first anymore
                if (previousChild != null && previousChild.hasDifferentHeightWhenFirst()) {
                    previousChild.setIsFirstInLayout(false);
                    measureChildWithMargins(previousView, widthMeasureSpec, 0, heightMeasureSpec,
                            previousTotalHeight - previousChildHeight);
                    previousChildIncrease = previousView.getMeasuredHeight() - previousChildHeight;
                    linesRemaining -= previousChild.getConsumedLines() - previousLinesConsumed;
                }
                messagingChild = (MessagingChild) child;
                messagingChild.setMaxDisplayedLines(linesRemaining);
                spacing += messagingChild.getExtraSpacing();
            }
            spacing = first ? 0 : spacing;
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, totalHeight
                    - mPaddingTop - mPaddingBottom + spacing);

            final int childHeight = child.getMeasuredHeight();
            int newHeight = Math.max(totalHeight, totalHeight + childHeight + lp.topMargin +
                    lp.bottomMargin + spacing + previousChildIncrease);
            int measureType = MessagingChild.MEASURED_NORMAL;
            if (messagingChild != null) {
                measureType = messagingChild.getMeasuredType();
            }

            // We never measure the first item as too small, we want to at least show something.
            boolean isTooSmall = measureType == MessagingChild.MEASURED_TOO_SMALL && !first;
            boolean isShortened = measureType == MessagingChild.MEASURED_SHORTENED
                    || measureType == MessagingChild.MEASURED_TOO_SMALL && first;
            boolean showView = newHeight <= targetHeight && !isTooSmall;
            if (showView) {
                if (messagingChild != null) {
                    previousLinesConsumed = messagingChild.getConsumedLines();
                    linesRemaining -= previousLinesConsumed;
                    previousChild = messagingChild;
                    previousView = child;
                    previousChildHeight = childHeight;
                    previousTotalHeight = totalHeight;
                }
                totalHeight = newHeight;
                measuredWidth = Math.max(measuredWidth,
                        child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin
                                + mPaddingLeft + mPaddingRight);
                lp.hide = false;
                if (isShortened || linesRemaining <= 0) {
                    break;
                }
            } else {
                // We now became too short, let's make sure to reset any previous views to be first
                // and remeasure it.
                if (previousChild != null && previousChild.hasDifferentHeightWhenFirst()) {
                    previousChild.setIsFirstInLayout(true);
                    // We need to remeasure the previous child again since it became first
                    measureChildWithMargins(previousView, widthMeasureSpec, 0, heightMeasureSpec,
                            previousTotalHeight - previousChildHeight);
                    // The totalHeight is already correct here since we only set it during the
                    // first pass
                }
                break;
            }
            first = false;
        }

        setMeasuredDimension(
                resolveSize(Math.max(getSuggestedMinimumWidth(), measuredWidth),
                        widthMeasureSpec),
                Math.max(getSuggestedMinimumHeight(), totalHeight));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int paddingLeft = mPaddingLeft;

        int childTop;

        // Where right end of child should go
        final int width = right - left;
        final int childRight = width - mPaddingRight;

        final int layoutDirection = getLayoutDirection();
        final int count = getChildCount();

        childTop = mPaddingTop;

        boolean first = true;
        final boolean shown = isShown();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            MessagingChild messagingChild = (MessagingChild) child;

            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();

            int childLeft;
            if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                childLeft = childRight - childWidth - lp.rightMargin;
            } else {
                childLeft = paddingLeft + lp.leftMargin;
            }
            if (lp.hide) {
                if (shown && lp.visibleBefore) {
                    // We still want to lay out the child to have great animations
                    child.layout(childLeft, childTop, childLeft + childWidth,
                            childTop + lp.lastVisibleHeight);
                    messagingChild.hideAnimated();
                }
                lp.visibleBefore = false;
                continue;
            } else {
                lp.visibleBefore = true;
                lp.lastVisibleHeight = childHeight;
            }

            if (!first) {
                childTop += mSpacing;
            }

            childTop += lp.topMargin;
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);

            childTop += childHeight + lp.bottomMargin;

            first = false;
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        final LayoutParams lp = (LayoutParams) child.getLayoutParams();
        if (lp.hide) {
            MessagingChild messagingChild = (MessagingChild) child;
            if (!messagingChild.isHidingAnimated()) {
                return true;
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(mContext, attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);

    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
        LayoutParams copy = new LayoutParams(lp.width, lp.height);
        if (lp instanceof MarginLayoutParams) {
            copy.copyMarginsFrom((MarginLayoutParams) lp);
        }
        return copy;
    }

    public static boolean isGone(View view) {
        if (view.getVisibility() == View.GONE) {
            return true;
        }
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof MessagingLinearLayout.LayoutParams
                && ((MessagingLinearLayout.LayoutParams) lp).hide) {
            return true;
        }
        return false;
    }

    /**
     * Sets how many lines should be displayed at most
     */
    @RemotableViewMethod
    public void setMaxDisplayedLines(int numberLines) {
        mMaxDisplayedLines = numberLines;
    }

    public void setMessagingLayout(IMessagingLayout layout) {
        mMessagingLayout = layout;
    }

    public IMessagingLayout getMessagingLayout() {
        return mMessagingLayout;
    }

    public interface MessagingChild {
        int MEASURED_NORMAL = 0;
        int MEASURED_SHORTENED = 1;
        int MEASURED_TOO_SMALL = 2;

        int getMeasuredType();
        int getConsumedLines();
        void setMaxDisplayedLines(int lines);
        void hideAnimated();
        boolean isHidingAnimated();

        /**
         * Set that this view is first in layout. Relevant and only set if
         * {@link #hasDifferentHeightWhenFirst()}.
         * @param first is this first?
         */
        default void setIsFirstInLayout(boolean first) {}

        /**
         * @return if this layout has different height it is first in the layout
         */
        default boolean hasDifferentHeightWhenFirst() {
            return false;
        }
        default int getExtraSpacing() {
            return 0;
        }
    }

    public static class LayoutParams extends MarginLayoutParams {

        public boolean hide = false;
        public boolean visibleBefore = false;
        public int lastVisibleHeight;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
