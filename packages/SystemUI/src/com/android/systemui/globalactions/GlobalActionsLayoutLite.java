/*
 * Copyright (C) 2021 The Android Open Source Project
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

import androidx.constraintlayout.helper.widget.Flow;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.res.R;

/**
 * ConstraintLayout implementation of the button layout created by the global actions dialog.
 */
public class GlobalActionsLayoutLite extends GlobalActionsLayout {

    public GlobalActionsLayoutLite(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnClickListener(v -> { }); // Prevent parent onClickListener from triggering
    }

    @VisibleForTesting
    @Override
    protected boolean shouldReverseListItems() {
        // Handled in XML
        return false;
    }

    @Override
    protected HardwareBgDrawable getBackgroundDrawable(int backgroundColor) {
        return null;
    }

    @Override
    public void onUpdateList() {
        super.onUpdateList();
        int nElementsWrap = getResources().getInteger(
                com.android.systemui.res.R.integer.power_menu_lite_max_columns);
        int nChildren = getListView().getChildCount() - 1; // don't count flow element

        // Avoid having just one action on the last row if there are more than 2 columns because
        // it looks unbalanced. Instead, bring the column size down to balance better.
        if (nChildren == nElementsWrap + 1 && nElementsWrap > 2) {
            nElementsWrap -= 1;
        }
        Flow flow = findViewById(R.id.list_flow);
        flow.setMaxElementsWrap(nElementsWrap);
    }

    @Override
    protected void addToListView(View v, boolean reverse) {
        super.addToListView(v, reverse);
        Flow flow = findViewById(R.id.list_flow);
        flow.addView(v);
    }

    @Override
    protected void removeAllListViews() {
        View flow = findViewById(R.id.list_flow);
        super.removeAllListViews();

        // Add flow element back after clearing the list view
        super.addToListView(flow, false);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        boolean anyTruncated = false;
        ViewGroup listView = getListView();

        // Check to see if any of the GlobalActionsItems have had their messages truncated
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof GlobalActionsItem) {
                GlobalActionsItem item = (GlobalActionsItem) child;
                anyTruncated = anyTruncated || item.isTruncated();
            }
        }
        // If any of the items have been truncated, set the all to single-line marquee
        if (anyTruncated) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof GlobalActionsItem) {
                    GlobalActionsItem item = (GlobalActionsItem) child;
                    item.setMarquee(true);
                }
            }
        }
    }

    @VisibleForTesting
    protected float getGridItemSize() {
        return getContext().getResources().getDimension(R.dimen.global_actions_grid_item_height);
    }

    @VisibleForTesting
    protected float getAnimationDistance() {
        return getGridItemSize() / 2;
    }

    @Override
    public float getAnimationOffsetX() {
        return getAnimationDistance();
    }

    @Override
    public float getAnimationOffsetY() {
        return 0f;
    }
}
