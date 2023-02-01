/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.floating.views;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.wm.shell.R;

/**
 * Displays the menu items for a floating task view (e.g. close).
 */
public class FloatingMenuView extends LinearLayout {

    private int mItemSize;
    private int mItemMargin;

    public FloatingMenuView(Context context) {
        super(context);
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER);

        mItemSize = context.getResources().getDimensionPixelSize(
                R.dimen.floating_task_menu_item_size);
        mItemMargin = context.getResources().getDimensionPixelSize(
                R.dimen.floating_task_menu_item_padding);
    }

    /** Adds a clickable item to the menu bar. Items are ordered as added. */
    public void addMenuItem(@Nullable Drawable drawable, View.OnClickListener listener) {
        ImageView itemView = new ImageView(getContext());
        itemView.setScaleType(ImageView.ScaleType.CENTER);
        if (drawable != null) {
            itemView.setImageDrawable(drawable);
        }
        LinearLayout.LayoutParams lp = new LayoutParams(mItemSize,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginStart(mItemMargin);
        lp.setMarginEnd(mItemMargin);
        addView(itemView, lp);

        itemView.setOnClickListener(listener);
    }

    /**
     * The menu extends past the top of the TaskView because of the rounded corners. This means
     * to center content in the menu we must subtract the radius (i.e. the amount of space covered
     * by TaskView).
     */
    public void setCornerRadius(float radius) {
        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), (int) radius);
    }
}
