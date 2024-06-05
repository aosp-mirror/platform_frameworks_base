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

import static com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_NONE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Grid-based implementation of the button layout created by the global actions dialog.
 */
public class GlobalActionsGridLayout extends GlobalActionsLayout {
    public GlobalActionsGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @VisibleForTesting
    protected void setupListView() {
        ListGridLayout listView = getListView();
        listView.setExpectedCount(mAdapter.countListItems());
        listView.setReverseSublists(shouldReverseSublists());
        listView.setReverseItems(shouldReverseListItems());
        listView.setSwapRowsAndColumns(shouldSwapRowsAndColumns());
    }

    @Override
    public void onUpdateList() {
        setupListView();
        super.onUpdateList();
        updateSeparatedItemSize();
    }

    /**
     * If the separated view contains only one item, expand the bounds of that item to take up the
     * entire view, so that the whole thing is touch-able.
     */
    @VisibleForTesting
    protected void updateSeparatedItemSize() {
        ViewGroup separated = getSeparatedView();
        if (separated.getChildCount() == 0) {
            return;
        }
        View firstChild = separated.getChildAt(0);
        ViewGroup.LayoutParams childParams = firstChild.getLayoutParams();

        if (separated.getChildCount() == 1) {
            childParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            childParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            childParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            childParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
    }

    @Override
    protected ListGridLayout getListView() {
        return (ListGridLayout) super.getListView();
    }

    @Override
    protected void removeAllListViews() {
        ListGridLayout list = getListView();
        if (list != null) {
            list.removeAllItems();
        }
    }

    @Override
    protected void addToListView(View v, boolean reverse) {
        ListGridLayout list = getListView();
        if (list != null) {
            list.addItem(v);
        }
    }

    @Override
    public void removeAllItems() {
        ViewGroup separatedList = getSeparatedView();
        ListGridLayout list = getListView();
        if (separatedList != null) {
            separatedList.removeAllViews();
        }
        if (list != null) {
            list.removeAllItems();
        }
    }

    /**
     * Determines whether the ListGridLayout should fill sublists in the reverse order.
     * Used to account for sublist ordering changing between landscape and seascape views.
     */
    @VisibleForTesting
    protected boolean shouldReverseSublists() {
        if (getCurrentRotation() == ROTATION_SEASCAPE) {
            return true;
        }
        return false;
    }

    /**
     * Determines whether the ListGridLayout should fill rows first instead of columns.
     * Used to account for vertical/horizontal changes due to landscape or seascape rotations.
     */
    @VisibleForTesting
    protected boolean shouldSwapRowsAndColumns() {
        if (getCurrentRotation() == ROTATION_NONE) {
            return false;
        }
        return true;
    }

    @Override
    protected boolean shouldReverseListItems() {
        int rotation = getCurrentRotation();
        boolean reverse = false; // should we add items to parents in the reverse order?
        if (rotation == ROTATION_NONE
                || rotation == ROTATION_SEASCAPE) {
            reverse = !reverse; // if we're in portrait or seascape, reverse items
        }
        if (getCurrentLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            reverse = !reverse; // if we're in an RTL language, reverse items (again)
        }
        return reverse;
    }

    @VisibleForTesting
    protected float getAnimationDistance() {
        int rows = getListView().getRowCount();
        float gridItemSize = getContext().getResources().getDimension(
                com.android.systemui.res.R.dimen.global_actions_grid_item_height);
        return rows * gridItemSize / 2;
    }

    @Override
    public float getAnimationOffsetX() {
        switch (getCurrentRotation()) {
            case ROTATION_LANDSCAPE:
                return getAnimationDistance();
            case ROTATION_SEASCAPE:
                return -getAnimationDistance();
            default: // Portrait
                return 0;
        }
    }

    @Override
    public float getAnimationOffsetY() {
        if (getCurrentRotation() == ROTATION_NONE) {
            return getAnimationDistance();
        }
        return 0;
    }
}
