/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tools.idea.editors.theme.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

/**
 * Custom layout used in the theme editor to display the component preview. It arranges the child
 * Views as a grid of cards.
 * <p/>
 * The Views are measured and the maximum width and height are used to dimension all the child
 * components. Any margin attributes from the children are ignored and only the item_margin element
 * is used.
 */
@SuppressWarnings("unused")
public class ThemePreviewLayout extends ViewGroup {
    private final int mMaxColumns;
    private final int mMaxColumnWidth;
    private final int mMinColumnWidth;
    private final int mItemHorizontalMargin;
    private final int mItemVerticalMargin;

    /** Item width to use for every card component. This includes margins. */
    private int mItemWidth;
    /** Item height to use for every card component. This includes margins. */
    private int mItemHeight;

    /** Calculated number of columns */
    private int mNumColumns;

    public ThemePreviewLayout(Context context) {
        this(context, null);
    }

    public ThemePreviewLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ThemePreviewLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        if (attrs == null) {
            mMaxColumnWidth = Integer.MAX_VALUE;
            mMinColumnWidth = 0;
            mMaxColumns = Integer.MAX_VALUE;
            mItemHorizontalMargin = 0;
            mItemVerticalMargin = 0;
            return;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxColumnWidth = attrs.getAttributeIntValue(null, "max_column_width", Integer
                .MAX_VALUE);
        int minColumnWidth = attrs.getAttributeIntValue(null, "min_column_width", 0);
        int itemHorizontalMargin = attrs.getAttributeIntValue(null, "item_horizontal_margin", 0);
        int itemVerticalMargin = attrs.getAttributeIntValue(null, "item_vertical_margin", 0);

        mMaxColumnWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                maxColumnWidth,
                dm);
        mMinColumnWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                minColumnWidth,
                dm);
        mItemHorizontalMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                itemHorizontalMargin,
                dm);
        mItemVerticalMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                itemVerticalMargin,
                dm);
        mMaxColumns = attrs.getAttributeIntValue(null, "max_columns", Integer.MAX_VALUE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Measure the column size.
        // The column has a minimum width that will be used to calculate the maximum number of
        // columns that we can fit in the available space.
        //
        // Once we have the maximum number of columns, we will span all columns width evenly to fill
        // all the available space.
        int wSize = MeasureSpec.getSize(widthMeasureSpec) - mPaddingLeft - mPaddingRight;

        // Calculate the desired width of all columns and take the maximum.
        // This step can be skipped if we have a fixed column height so we do not have to
        // dynamically calculate it.
        int childWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int itemWidth = 0;
        int itemHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);

            if (v.getVisibility() == GONE) {
                continue;
            }

            measureChild(v, childWidthSpec, childHeightSpec);

            itemWidth = Math.max(itemWidth, v.getMeasuredWidth());
            itemHeight = Math.max(itemHeight, v.getMeasuredHeight());
        }

        itemWidth = Math.min(Math.max(itemWidth, mMinColumnWidth), mMaxColumnWidth);
        mNumColumns = Math.min((int) Math.ceil((double) wSize / itemWidth), mMaxColumns);

        // Check how much space this distribution would take taking into account the margins.
        // If it's bigger than what we have, remove one column.
        int wSizeNeeded = mNumColumns * itemWidth + (mNumColumns - 1) * mItemHorizontalMargin;
        if (wSizeNeeded > wSize && mNumColumns > 1) {
            mNumColumns--;
        }

        if (getChildCount() < mNumColumns) {
            mNumColumns = getChildCount();
        }
        if (mNumColumns == 0) {
            mNumColumns = 1;
        }

        // Inform each child of the measurement
        childWidthSpec = MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY);
        childHeightSpec = MeasureSpec.makeMeasureSpec(itemHeight, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);

            if (v.getVisibility() == GONE) {
                continue;
            }

            measureChild(v, childWidthSpec, childHeightSpec);
        }

        // Calculate the height of the first column to measure our own size
        int firstColumnItems = getChildCount() / mNumColumns + ((getChildCount() % mNumColumns) > 0
                ? 1 : 0);

        int horizontalMarginsTotalWidth = (mNumColumns - 1) * mItemHorizontalMargin;
        int verticalMarginsTotalHeight = (firstColumnItems - 1) * mItemVerticalMargin;
        int totalWidth = mNumColumns * itemWidth + horizontalMarginsTotalWidth +
                mPaddingRight + mPaddingLeft;
        int totalHeight = firstColumnItems * itemHeight + verticalMarginsTotalHeight +
                mPaddingBottom + mPaddingTop;

        setMeasuredDimension(resolveSize(totalWidth, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec));

        mItemWidth = itemWidth;
        mItemHeight = itemHeight;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int itemsPerColumn = getChildCount() / mNumColumns;
        // The remainder items are distributed one per column.
        int remainderItems = getChildCount() % mNumColumns;

        int x = mPaddingLeft;
        int y = mPaddingTop;
        int position = 1;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            v.layout(x,
                    y,
                    x + mItemWidth,
                    y + mItemHeight);

            if (position == itemsPerColumn + (remainderItems > 0 ? 1 : 0)) {
                // Break column
                position = 1;
                remainderItems--;
                x += mItemWidth + mItemHorizontalMargin;
                y = mPaddingTop;
            } else {
                position++;
                y += mItemHeight + mItemVerticalMargin;
            }
        }
    }
}


