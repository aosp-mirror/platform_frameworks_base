/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.globalactions;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Layout which uses nested LinearLayouts to create a grid with the following behavior:
 *
 * * Try to maintain a 'square' grid (equal number of columns and rows) based on the expected item
 *   count.
 * * Determine the position and parent of any item by its index and the total item count.
 * * Display and hide sub-lists as needed, depending on the expected item count.
 *
 * While we could implement this behavior with a GridLayout, it would take significantly more
 * time and effort, and would require more substantial refactoring of the existing code in
 * GlobalActionsDialog, since it would require manipulation of layout properties on the child items
 * themselves.
 *
 */

public class ListGridLayout extends LinearLayout {
    private static final String TAG = "ListGridLayout";
    private int mExpectedCount;
    private int mCurrentCount = 0;
    private boolean mSwapRowsAndColumns;
    private boolean mReverseSublists;
    private boolean mReverseItems;

    // number of rows and columns to use for different numbers of items
    private final int[][] mConfigs = {
            // {rows, columns}
            {0, 0}, // 0 items
            {1, 1}, // 1 item
            {1, 2}, // 2 items
            {1, 3}, // 3 items
            {2, 2}, // 4 items
            {2, 3}, // 5 items
            {2, 3}, // 6 items
            {3, 3}, // 7 items
            {3, 3}, // 8 items
            {3, 3}  // 9 items
    };

    public ListGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Sets whether this grid should prioritize filling rows or columns first.
     */
    public void setSwapRowsAndColumns(boolean swap) {
        mSwapRowsAndColumns = swap;
    }

    /**
     * Sets whether this grid should fill sublists in reverse order.
     */
    public void setReverseSublists(boolean reverse) {
        mReverseSublists = reverse;
    }

    /**
     * Sets whether this grid should add items to sublists in reverse order.
     * @param reverse
     */
    public void setReverseItems(boolean reverse) {
        mReverseItems = reverse;
    }

    /**
     * Remove all items from this grid.
     */
    public void removeAllItems() {
        for (int i = 0; i < getChildCount(); i++) {
            ViewGroup subList = getSublist(i);
            if (subList != null) {
                subList.removeAllViews();
                subList.setVisibility(View.GONE);
            }
        }
        mCurrentCount = 0;
    }

    /**
     * Adds a view item to this grid, placing it in the correct sublist and ensuring that the
     * sublist is visible.
     *
     * This function is stateful, since it tracks how many items have been added thus far, to
     * determine which sublist they should be added to. To ensure that this works correctly, call
     * removeAllItems() instead of removing views individually with removeView() to ensure that the
     * counter gets reset correctly.
     * @param item
     */
    public void addItem(View item) {
        ViewGroup parent = getParentView(mCurrentCount, mReverseSublists, mSwapRowsAndColumns);
        if (mReverseItems) {
            parent.addView(item, 0);
        } else {
            parent.addView(item);
        }
        parent.setVisibility(View.VISIBLE);
        mCurrentCount++;
    }

    /**
     * Get the parent view associated with the item which should be placed at the given position.
     * @param index The index of the item.
     * @param reverseSublists Reverse the order of sublists. Ordinarily, sublists fill from first to
     *                        last, whereas setting this to true will fill them last to first.
     * @param swapRowsAndColumns Swap the order in which rows and columns are filled. By default,
     *                           columns fill first, adding one item to each row. Setting this to
     *                           true will cause rows to fill first, adding one item to each column.
     * @return
     */
    @VisibleForTesting
    protected ViewGroup getParentView(int index, boolean reverseSublists,
            boolean swapRowsAndColumns) {
        if (getRowCount() == 0 || index < 0) {
            return null;
        }
        int targetIndex = Math.min(index, getMaxElementCount() - 1);
        int row = getParentViewIndex(targetIndex, reverseSublists, swapRowsAndColumns);
        return getSublist(row);
    }

    @VisibleForTesting
    protected ViewGroup getSublist(int index) {
        return (ViewGroup) getChildAt(index);
    }

    private int reverseSublistIndex(int index) {
        return getChildCount() - (index + 1);
    }

    private int getParentViewIndex(int index, boolean reverseSublists, boolean swapRowsAndColumns) {
        int sublistIndex;
        int rows = getRowCount();
        if (swapRowsAndColumns) {
            sublistIndex = (int) Math.floor(index / rows);
        } else {
            sublistIndex = index % rows;
        }
        if (reverseSublists) {
            sublistIndex = reverseSublistIndex(sublistIndex);
        }
        return sublistIndex;
    }

    /**
     * Sets the expected number of items that this grid will be responsible for rendering.
     */
    public void setExpectedCount(int count) {
        mExpectedCount = count;
    }

    private int getMaxElementCount() {
        return mConfigs.length - 1;
    }

    private int[] getConfig() {
        if (mExpectedCount < 0) {
            return mConfigs[0];
        }
        int targetElements = Math.min(getMaxElementCount(), mExpectedCount);
        return mConfigs[targetElements];
    }

    /**
     * Get the number of rows which will be used to render children.
     */
    public int getRowCount() {
        return getConfig()[0];
    }

    /**
     * Get the number of columns which will be used to render children.
     */
    public int getColumnCount() {
        return getConfig()[1];
    }
}
