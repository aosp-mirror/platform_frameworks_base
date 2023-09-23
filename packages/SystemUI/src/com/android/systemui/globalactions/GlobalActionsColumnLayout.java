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
import android.view.Gravity;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.res.R;

/**
 * Grid-based implementation of the button layout created by the global actions dialog.
 */
public class GlobalActionsColumnLayout extends GlobalActionsLayout {
    private boolean mLastSnap;

    public GlobalActionsColumnLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        post(() -> updateSnap());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
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
    public void onUpdateList() {
        super.onUpdateList();
        updateChildOrdering();
    }

    private void updateChildOrdering() {
        if (shouldReverseListItems()) {
            getListView().bringToFront();
        } else {
            getSeparatedView().bringToFront();
        }
    }

    /**
     *  Snap this layout to align with the power button.
     */
    @VisibleForTesting
    protected void snapToPowerButton() {
        int offset = getPowerButtonOffsetDistance();
        switch (getCurrentRotation()) {
            case (ROTATION_LANDSCAPE):
                setPadding(offset, 0, 0, 0);
                setGravity(Gravity.LEFT | Gravity.TOP);
                break;
            case (ROTATION_SEASCAPE):
                setPadding(0, 0, offset, 0);
                setGravity(Gravity.RIGHT | Gravity.BOTTOM);
                break;
            default:
                setPadding(0, offset, 0, 0);
                setGravity(Gravity.TOP | Gravity.RIGHT);
                break;
        }
    }

    /**
     *  Detach this layout from snapping to the power button and instead center along that edge.
     */
    @VisibleForTesting
    protected void centerAlongEdge() {
        switch (getCurrentRotation()) {
            case (ROTATION_LANDSCAPE):
                setPadding(0, 0, 0, 0);
                setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                break;
            case (ROTATION_SEASCAPE):
                setPadding(0, 0, 0, 0);
                setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
                break;
            default:
                setPadding(0, 0, 0, 0);
                setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
                break;
        }
    }

    /**
     * Determines the distance from the top of the screen to the power button.
     */
    @VisibleForTesting
    protected int getPowerButtonOffsetDistance() {
        return Math.round(getContext().getResources().getDimension(
                R.dimen.global_actions_top_padding));
    }

    /**
     * Check whether there is enough extra space below the dialog such that we can offset the top
     * of the dialog from the top of the phone to line it up with the power button, then either
     * snap the dialog to the power button or center it along the edge with snapToPowerButton.
     */
    @VisibleForTesting
    protected boolean shouldSnapToPowerButton() {
        int offsetSize = getPowerButtonOffsetDistance();
        int dialogSize;
        int screenSize;
        View wrapper = getWrapper();
        int rotation = getCurrentRotation();
        if (rotation == ROTATION_NONE) {
            dialogSize = wrapper.getMeasuredHeight();
            screenSize = getMeasuredHeight();
        } else {
            dialogSize = wrapper.getMeasuredWidth();
            screenSize = getMeasuredWidth();
        }
        return dialogSize + offsetSize < screenSize;
    }

    @VisibleForTesting
    protected void updateSnap() {
        boolean snap = shouldSnapToPowerButton();
        if (snap != mLastSnap) {
            if (snap) {
                snapToPowerButton();
            } else {
                centerAlongEdge();
            }
        }
        mLastSnap = snap;
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
        if (getCurrentRotation() == ROTATION_NONE) {
            return getAnimationDistance();
        }
        return 0;
    }

    @Override
    public float getAnimationOffsetY() {
        switch (getCurrentRotation()) {
            case ROTATION_LANDSCAPE:
                return -getAnimationDistance();
            case ROTATION_SEASCAPE:
                return getAnimationDistance();
            default: // Portrait
                return 0;
        }
    }
}
