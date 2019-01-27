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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * Layout which uses nested LinearLayouts to create a grid with the following behavior:
 *
 * * Try to maintain a 'square' grid (equal number of columns and rows) based on the expected item
 *   count.
 * * Display and hide sub-lists as needed, depending on the expected item count.
 * * Favor bias toward having more rows or columns depending on the orientation of the device
 *   (TODO(123344999): Implement this, currently always favors adding more rows.)
 * * Change the orientation (horizontal vs. vertical) of the container and sub-lists to act as rows
 *   or columns depending on the orientation of the device.
 *   (TODO(123344999): Implement this, currently always columns.)
 *
 * While we could implement this behavior with a GridLayout, it would take significantly more
 * time and effort, and would require more substantial refactoring of the existing code in
 * GlobalActionsDialog, since it would require manipulation of the child items themselves.
 *
 */

public class ListGridLayout extends LinearLayout {
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
    public ViewGroup getParentView(int index) {
        ViewGroup firstParent = (ViewGroup) getChildAt(0);
        if (mRows == 0) {
            return firstParent;
        }
        int column = (int) Math.floor(index / mRows);
        ViewGroup parent = (ViewGroup) getChildAt(column);
        return parent != null ? parent : firstParent;
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
        Log.d("ListGrid", "index: " + index  + ", visibility: "  + visible);
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
