/*
 * Copyright (C) 2020 The Android Open Source Project
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
import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.R;

/**
 * Flat, single-row implementation of the button layout created by the global actions dialog.
 */
public class GlobalActionsFlatLayout extends GlobalActionsLayout {
    public GlobalActionsFlatLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @VisibleForTesting
    protected boolean shouldReverseListItems() {
        int rotation = getCurrentRotation();
        if (rotation == ROTATION_NONE) {
            return false;
        }
        if (getCurrentLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            return rotation == ROTATION_LANDSCAPE;
        }
        return rotation == ROTATION_SEASCAPE;
    }

    @Override
    protected HardwareBgDrawable getBackgroundDrawable(int backgroundColor) {
        return null;
    }

    private View getOverflowButton() {
        return findViewById(com.android.systemui.R.id.global_actions_overflow_button);
    }

    @Override
    protected void addToListView(View v, boolean reverse) {
        super.addToListView(v, reverse);
        View overflowButton = getOverflowButton();
        // if there's an overflow button, make sure it stays at the end
        if (overflowButton != null) {
            getListView().removeView(overflowButton);
            super.addToListView(overflowButton, reverse);
        }
    }

    @Override
    protected void removeAllListViews() {
        View overflowButton = getOverflowButton();
        super.removeAllListViews();
        // if there's an overflow button, add it back after clearing the list views
        if (overflowButton != null) {
            super.addToListView(overflowButton, false);
        }
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
