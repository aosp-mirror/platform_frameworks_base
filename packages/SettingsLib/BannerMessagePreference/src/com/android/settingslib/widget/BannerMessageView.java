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

package com.android.settingslib.widget;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.TouchDelegate;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.android.settingslib.widget.preference.banner.R;

/**
 * The view providing {@link BannerMessagePreference}.
 *
 * <p>Callers should not instantiate this view directly but rather through adding a
 * {@link BannerMessagePreference} to a {@code PreferenceScreen}.
 */
public class BannerMessageView extends LinearLayout {
    private Rect mTouchTargetForDismissButton;

    public BannerMessageView(Context context) {
        super(context);
    }

    public BannerMessageView(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BannerMessageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public BannerMessageView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        setupIncreaseTouchTargetForDismissButton();
    }

    private void setupIncreaseTouchTargetForDismissButton() {
        if (mTouchTargetForDismissButton != null) {
            // Already set up
            return;
        }

        // The dismiss button is in the 'top row' RelativeLayout for positioning, but this element
        // does not have enough space to provide large touch targets.  We therefore set the top
        // target on this view.
        View topRow = findViewById(R.id.top_row);
        View dismissButton = findViewById(R.id.banner_dismiss_btn);
        if (topRow == null || dismissButton == null || dismissButton.getVisibility() != VISIBLE) {
            return;
        }

        int minimum =
                getResources()
                        .getDimensionPixelSize(com.android.settingslib.widget.theme.R.dimen.settingslib_preferred_minimum_touch_target);
        int width = dismissButton.getWidth();
        int height = dismissButton.getHeight();
        int widthIncrease = width < minimum ? minimum - width : 0;
        int heightIncrease = height < minimum ? minimum - height : 0;

        // Compute the hit rect of dismissButton within the local co-orindate reference of this view
        // (rather than it's direct parent topRow).
        Rect hitRectWithinTopRow = new Rect();
        dismissButton.getHitRect(hitRectWithinTopRow);
        Rect hitRectOfTopRowWithinThis = new Rect();
        topRow.getHitRect(hitRectOfTopRowWithinThis);
        mTouchTargetForDismissButton = new Rect();
        mTouchTargetForDismissButton.left =
                hitRectOfTopRowWithinThis.left + hitRectWithinTopRow.left;
        mTouchTargetForDismissButton.right =
                hitRectOfTopRowWithinThis.left + hitRectWithinTopRow.right;
        mTouchTargetForDismissButton.top =
                hitRectOfTopRowWithinThis.top + hitRectWithinTopRow.top;
        mTouchTargetForDismissButton.bottom =
                hitRectOfTopRowWithinThis.top + hitRectWithinTopRow.bottom;

        // Adjust the touch target rect to apply the necessary increase in width and height.
        mTouchTargetForDismissButton.left -=
                widthIncrease % 2 == 1 ? (widthIncrease / 2) + 1 : widthIncrease / 2;
        mTouchTargetForDismissButton.top -=
                heightIncrease % 2 == 1 ? (heightIncrease / 2) + 1 : heightIncrease / 2;
        mTouchTargetForDismissButton.right += widthIncrease / 2;
        mTouchTargetForDismissButton.bottom += heightIncrease / 2;

        setTouchDelegate(new TouchDelegate(mTouchTargetForDismissButton, dismissButton));
    }

}
