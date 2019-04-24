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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.MultiListLayout;
import com.android.systemui.util.leak.RotationUtils;

/**
 * Grid-based implementation of the button layout created by the global actions dialog.
 */
public class GlobalActionsGridLayout extends MultiListLayout {

    boolean mBackgroundsSet;

    public GlobalActionsGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void setBackgrounds() {
        int gridBackgroundColor = getResources().getColor(
                com.android.systemui.R.color.global_actions_grid_background, null);
        int separatedBackgroundColor = getResources().getColor(
                com.android.systemui.R.color.global_actions_separated_background, null);
        HardwareBgDrawable listBackground  = new HardwareBgDrawable(true, true, getContext());
        HardwareBgDrawable separatedBackground = new HardwareBgDrawable(true, true, getContext());
        listBackground.setTint(gridBackgroundColor);
        separatedBackground.setTint(separatedBackgroundColor);
        getListView().setBackground(listBackground);
        getSeparatedView().setBackground(separatedBackground);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // backgrounds set only once, the first time onMeasure is called after inflation
        if (getListView() != null && !mBackgroundsSet) {
            setBackgrounds();
            mBackgroundsSet = true;
        }
    }

    @VisibleForTesting
    protected int getCurrentRotation() {
        return RotationUtils.getRotation(mContext);
    }

    @VisibleForTesting
    protected void setupListView(ListGridLayout listView, int itemCount) {
        listView.setExpectedCount(itemCount);
        listView.setReverseSublists(shouldReverseSublists());
        listView.setReverseItems(shouldReverseListItems());
        listView.setSwapRowsAndColumns(shouldSwapRowsAndColumns());
    }

    @Override
    public void onUpdateList() {
        super.onUpdateList();

        ViewGroup separatedView = getSeparatedView();
        ListGridLayout listView = getListView();
        setupListView(listView, mAdapter.countListItems());

        for (int i = 0; i < mAdapter.getCount(); i++) {
            // generate the view item
            View v;
            boolean separated = mAdapter.shouldBeSeparated(i);
            if (separated) {
                v = mAdapter.getView(i, null, separatedView);
            } else {
                v = mAdapter.getView(i, null, listView);
            }
            Log.d("GlobalActionsGridLayout", "View: " + v);

            if (separated) {
                separatedView.addView(v);
            } else {
                listView.addItem(v);
            }
        }
        updateSeparatedItemSize();
    }

    /**
     * If the separated view contains only one item, expand the bounds of that item to take up the
     * entire view, so that the whole thing is touch-able.
     */
    private void updateSeparatedItemSize() {
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
    protected ViewGroup getSeparatedView() {
        return findViewById(com.android.systemui.R.id.separated_button);
    }

    @Override
    protected ListGridLayout getListView() {
        return findViewById(android.R.id.list);
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

    /**
     * Determines whether the ListGridLayout should reverse the ordering of items within sublists.
     * Used for RTL languages to ensure that items appear in the same positions, without having to
     * override layoutDirection, which breaks Talkback ordering.
     */
    @VisibleForTesting
    protected boolean shouldReverseListItems() {
        int rotation = getCurrentRotation();
        boolean reverse = false; // should we add items to parents in the reverse order?
        if (rotation == ROTATION_NONE
                || rotation == ROTATION_SEASCAPE) {
            reverse = !reverse; // if we're in portrait or seascape, reverse items
        }
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            reverse = !reverse; // if we're in an RTL language, reverse items (again)
        }
        return reverse;
    }

    /**
     * Not ued in this implementation of the Global Actions Menu, but necessary for some others.
     */
    @Override
    public void setDivisionView(View v) {
        // do nothing
    }

    protected float getAnimationDistance() {
        int rows = getListView().getRowCount();
        float gridItemSize = getContext().getResources().getDimension(
                com.android.systemui.R.dimen.global_actions_grid_item_height);
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
