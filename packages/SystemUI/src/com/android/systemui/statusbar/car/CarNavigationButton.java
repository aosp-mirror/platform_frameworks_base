/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.car;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

import com.android.keyguard.AlphaOptimizedImageButton;
import com.android.systemui.R;

/**
 * A wrapper view for a car navigation facet, which includes a button icon and a drop down icon.
 */
public class CarNavigationButton extends RelativeLayout {
    private static final float SELECTED_ALPHA = 1;
    private static final float UNSELECTED_ALPHA = 0.7f;

    private AlphaOptimizedImageButton mIcon;
    private AlphaOptimizedImageButton mMoreIcon;

    public CarNavigationButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mIcon = (AlphaOptimizedImageButton) findViewById(R.id.car_nav_button_icon);
        mIcon.setClickable(false);
        mIcon.setBackgroundColor(android.R.color.transparent);
        mIcon.setAlpha(UNSELECTED_ALPHA);

        mMoreIcon = (AlphaOptimizedImageButton) findViewById(R.id.car_nav_button_more_icon);
        mMoreIcon.setClickable(false);
        mMoreIcon.setBackgroundColor(android.R.color.transparent);
        mMoreIcon.setVisibility(INVISIBLE);
        mMoreIcon.setImageDrawable(getContext().getDrawable(R.drawable.car_ic_arrow));
        mMoreIcon.setAlpha(UNSELECTED_ALPHA);
    }

    public void setResources(Drawable icon) {
        mIcon.setImageDrawable(icon);
    }

    public void setSelected(boolean selected, boolean showMoreIcon) {
        if (selected) {
            mMoreIcon.setVisibility(showMoreIcon ? VISIBLE : INVISIBLE);
            mMoreIcon.setAlpha(SELECTED_ALPHA);
            mIcon.setAlpha(SELECTED_ALPHA);
        } else {
            mMoreIcon.setVisibility(INVISIBLE);
            mIcon.setAlpha(UNSELECTED_ALPHA);
        }
    }
}
