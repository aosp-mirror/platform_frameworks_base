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

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.R;

/**
 * Flat, single-row implementation of the button layout created by the global actions dialog.
 */
public class GlobalActionsFlatLayout extends GlobalActionsLayout {
    private static final int MAX_ITEMS = 4;
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

    @Override
    protected void addToListView(View v, boolean reverse) {
        // only add items to the list view if we haven't hit our max yet
        if (getListView().getChildCount() < MAX_ITEMS) {
            super.addToListView(v, reverse);
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
        return 0;
    }

    @Override
    public float getAnimationOffsetY() {
        return -getAnimationDistance();
    }
}
