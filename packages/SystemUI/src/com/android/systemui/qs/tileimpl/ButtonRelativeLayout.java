/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tileimpl;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;

/**
 * Used for QS tile labels
 */
public class ButtonRelativeLayout extends RelativeLayout {

    private View mIgnoredView;

    public ButtonRelativeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return Button.class.getName();
    }

    /**
     * Set a view to be ignored for measure.
     *
     * The view will be measured and laid out, but its size will be subtracted from the total size
     * of this view. It assumes that this view only contributes vertical height.
     */
    public void setIgnoredView(View view) {
        if (mIgnoredView == null || mIgnoredView.getParent() == this) {
            mIgnoredView = view;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mIgnoredView != null && mIgnoredView.getVisibility() != GONE) {
            int height = mIgnoredView.getMeasuredHeight();
            MarginLayoutParams lp = (MarginLayoutParams) mIgnoredView.getLayoutParams();
            height = height - lp.bottomMargin - lp.topMargin;
            setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() - height);
        }
    }
}
