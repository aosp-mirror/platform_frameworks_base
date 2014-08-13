/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import com.android.printspooler.R;

/**
 * This class is a layout manager for the print options. The options are
 * arranged in a configurable number of columns and enough rows to fit all
 * the options given the column count.
 */
@SuppressWarnings("unused")
public final class PrintOptionsLayout extends ViewGroup {

    private int mColumnCount;

    public PrintOptionsLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray typedArray = context.obtainStyledAttributes(attrs,
                R.styleable.PrintOptionsLayout);
        mColumnCount = typedArray.getInteger(R.styleable.PrintOptionsLayout_columnCount, 0);
        typedArray.recycle();
    }

    public void setColumnCount(int columnCount) {
        if (mColumnCount != columnCount) {
            mColumnCount = columnCount;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        final int columnWidth = (widthSize != 0)
                ? (widthSize - mPaddingLeft - mPaddingRight) / mColumnCount : 0;

        int width = 0;
        int height = 0;
        int childState = 0;

        final int childCount = getChildCount();
        final int rowCount = childCount / mColumnCount + childCount % mColumnCount;

        for (int row = 0; row < rowCount; row++) {
            int rowWidth = 0;
            int rowHeight = 0;

            for (int col = 0; col < mColumnCount; col++) {
                final int childIndex = row * mColumnCount + col;

                if (childIndex >= childCount) {
                    break;
                }

                View child = getChildAt(childIndex);

                if (child.getVisibility() == GONE) {
                    continue;
                }

                MarginLayoutParams childParams = (MarginLayoutParams) child.getLayoutParams();

                final int childWidthMeasureSpec;
                if (columnWidth > 0) {
                    childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                            columnWidth - childParams.getMarginStart() - childParams.getMarginEnd(),
                            MeasureSpec.EXACTLY);
                } else {
                    childWidthMeasureSpec = getChildMeasureSpec(heightMeasureSpec,
                            getPaddingStart() + getPaddingEnd() + width, childParams.width);
                }

                final int childHeightMeasureSpec = getChildMeasureSpec(heightMeasureSpec,
                        getPaddingTop() + getPaddingBottom() + height, childParams.height);

                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);

                childState = combineMeasuredStates(childState, child.getMeasuredState());

                rowWidth += child.getMeasuredWidth() + childParams.getMarginStart()
                        + childParams.getMarginEnd();

                rowHeight = Math.max(rowHeight, child.getMeasuredHeight() + childParams.topMargin
                        + childParams.bottomMargin);
            }

            width = Math.max(width, rowWidth);
            height += rowHeight;
        }

        width += getPaddingStart() + getPaddingEnd();
        width = Math.max(width, getMinimumWidth());

        height += getPaddingTop() + getPaddingBottom();
        height = Math.max(height, getMinimumHeight());

        setMeasuredDimension(resolveSizeAndState(width, widthMeasureSpec, childState),
                resolveSizeAndState(height, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int childCount = getChildCount();
        final int rowCount = childCount / mColumnCount + childCount % mColumnCount;

        int cellStart = getPaddingStart();
        int cellTop = getPaddingTop();

        for (int row = 0; row < rowCount; row++) {
            int rowHeight = 0;

            for (int col = 0; col < mColumnCount; col++) {
                final int childIndex = row * mColumnCount + col;

                if (childIndex >= childCount) {
                    break;
                }

                View child = getChildAt(childIndex);

                if (child.getVisibility() == GONE) {
                    continue;
                }

                MarginLayoutParams childParams = (MarginLayoutParams) child.getLayoutParams();

                final int childLeft = cellStart + childParams.getMarginStart();
                final int childTop = cellTop + childParams.topMargin;
                final int childRight = childLeft + child.getMeasuredWidth();
                final int childBottom = childTop + child.getMeasuredHeight();

                child.layout(childLeft, childTop, childRight, childBottom);

                cellStart = childRight + childParams.getMarginEnd();

                rowHeight = Math.max(rowHeight, child.getMeasuredHeight()
                        + childParams.topMargin + childParams.bottomMargin);
            }

            cellStart = getPaddingStart();
            cellTop += rowHeight;
        }
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new ViewGroup.MarginLayoutParams(getContext(), attrs);
    }
}
