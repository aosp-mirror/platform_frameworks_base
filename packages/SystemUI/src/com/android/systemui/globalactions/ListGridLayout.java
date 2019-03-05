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
    private int mRows;
    private int mColumns;

    public ListGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Remove all items from this grid.
     */
    public void removeAllItems() {
        for (int i = 0; i < getChildCount(); i++) {
            ViewGroup subList = (ViewGroup) getChildAt(i);
            if (subList != null) {
                subList.removeAllViews();
            }
        }
    }

    /**
     * Get the parent view associated with the item which should be placed at the given position.
     */
    public ViewGroup getParentView(int index, boolean reverseSublists, boolean swapRowsAndColumns) {
        if (mRows == 0) {
            return null;
        }
        int column = getParentViewIndex(index, reverseSublists, swapRowsAndColumns);
        return (ViewGroup) getChildAt(column);
    }

    private int reverseSublistIndex(int index) {
        return getChildCount() - (index + 1);
    }

    private int getParentViewIndex(int index, boolean reverseSublists, boolean swapRowsAndColumns) {
        int sublistIndex;
        ViewGroup row;
        if (swapRowsAndColumns) {
            sublistIndex = (int) Math.floor(index / mRows);
        } else {
            sublistIndex = index % mRows;
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
        mRows = getRowCount();
        mColumns = getColumnCount();

        for (int i = 0; i < getChildCount(); i++) {
            if (i <= mColumns) {
                setSublistVisibility(i, true);
            } else {
                setSublistVisibility(i, false);
            }
        }
    }

    private void setSublistVisibility(int index, boolean visible) {
        View subList = getChildAt(index);
        if (subList != null) {
            subList.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private int getRowCount() {
        return (int) Math.ceil(Math.sqrt(mExpectedCount));
    }

    private int getColumnCount() {
        return (int) Math.round(Math.sqrt(mExpectedCount));
    }
}
