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

import com.android.internal.R;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

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

    /**
     * The maximum height allowed.
     */
    private int mMaxHeight;

    private int mIndentLines;

    /**
     * Id of the child that's also visible in the contracted layout.
     */
    private int mContractedChildId;

    public MessagingLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.MessagingLinearLayout, 0,
                0);

        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.MessagingLinearLayout_maxHeight:
                    mMaxHeight = a.getDimensionPixelSize(i, 0);
                    break;
                case R.styleable.MessagingLinearLayout_spacing:
                    mSpacing = a.getDimensionPixelSize(i, 0);
                    break;
            }
        }

        if (mMaxHeight <= 0) {
            throw new IllegalStateException(
                    "MessagingLinearLayout: Must specify positive maxHeight");
        }

        a.recycle();
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // This is essentially a bottom-up linear layout that only adds children that fit entirely
        // up to a maximum height.

        switch (MeasureSpec.getMode(heightMeasureSpec)) {
            case MeasureSpec.AT_MOST:
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        Math.min(mMaxHeight, MeasureSpec.getSize(heightMeasureSpec)),
                        MeasureSpec.AT_MOST);
                break;
            case MeasureSpec.UNSPECIFIED:
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        mMaxHeight,
                        MeasureSpec.AT_MOST);
                break;
            case MeasureSpec.EXACTLY:
                break;
        }
        final int targetHeight = MeasureSpec.getSize(heightMeasureSpec);
        final int count = getChildCount();

        for (int i = 0; i < count; ++i) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.hide = true;
        }

        int totalHeight = mPaddingTop + mPaddingBottom;
        boolean first = true;

        // Starting from the bottom: we measure every view as if it were the only one. If it still
        // fits, we take it, otherwise we stop there.
        for (int i = count - 1; i >= 0 && totalHeight < targetHeight; i--) {
            if (getChildAt(i).getVisibility() == GONE) {
                continue;
            }
            final View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) getChildAt(i).getLayoutParams();

            if (child instanceof ImageFloatingTextView) {
                // Pretend we need the image padding for all views, we don't know which
                // one will end up needing to do this (might end up not using all the space,
                // but calculating this exactly would be more expensive).
                ((ImageFloatingTextView) child).setNumIndentLines(
                        mIndentLines == 2 ? 3 : mIndentLines);
            }

            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);

            final int childHeight = child.getMeasuredHeight();
            int newHeight = Math.max(totalHeight, totalHeight + childHeight + lp.topMargin +
                    lp.bottomMargin + (first ? 0 : mSpacing));
            first = false;

            if (newHeight <= targetHeight) {
                totalHeight = newHeight;
                lp.hide = false;
            } else {
                break;
            }
        }

        // Now that we know which views to take, fix up the indents and see what width we get.
        int measuredWidth = mPaddingLeft + mPaddingRight;
        int imageLines = mIndentLines;
        // Need to redo the height because it may change due to changing indents.
        totalHeight = mPaddingTop + mPaddingBottom;
        first = true;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (child.getVisibility() == GONE || lp.hide) {
                continue;
            }

            if (child instanceof ImageFloatingTextView) {
                ImageFloatingTextView textChild = (ImageFloatingTextView) child;
                if (imageLines == 2 && textChild.getLineCount() > 2) {
                    // HACK: If we need indent for two lines, and they're coming from the same
                    // view, we need extra spacing to compensate for the lack of margins,
                    // so add an extra line of indent.
                    imageLines = 3;
                }
                boolean changed = textChild.setNumIndentLines(Math.max(0, imageLines));
                if (changed) {
                    measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                }
                imageLines -= textChild.getLineCount();
            }

            measuredWidth = Math.max(measuredWidth,
                    child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin
                            + mPaddingLeft + mPaddingRight);
            totalHeight = Math.max(totalHeight, totalHeight + child.getMeasuredHeight() +
                    lp.topMargin + lp.bottomMargin + (first ? 0 : mSpacing));
            first = false;
        }


        setMeasuredDimension(
                resolveSize(Math.max(getSuggestedMinimumWidth(), measuredWidth),
                        widthMeasureSpec),
                resolveSize(Math.max(getSuggestedMinimumHeight(), totalHeight),
                        heightMeasureSpec));
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

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (child.getVisibility() == GONE || lp.hide) {
                continue;
            }

            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();

            int childLeft;
            if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                childLeft = childRight - childWidth - lp.rightMargin;
            } else {
                childLeft = paddingLeft + lp.leftMargin;
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
            return true;
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

    /**
     * Sets how many lines should be indented to avoid a floating image.
     */
    @RemotableViewMethod
    public void setNumIndentLines(int numberLines) {
        mIndentLines = numberLines;
    }

    /**
     * Set id of the child that's also visible in the contracted layout.
     */
    @RemotableViewMethod
    public void setContractedChildId(int contractedChildId) {
        mContractedChildId = contractedChildId;
    }

    /**
     * Get id of the child that's also visible in the contracted layout.
     */
    public int getContractedChildId() {
        return mContractedChildId;
    }

    public static class LayoutParams extends MarginLayoutParams {

        boolean hide = false;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
