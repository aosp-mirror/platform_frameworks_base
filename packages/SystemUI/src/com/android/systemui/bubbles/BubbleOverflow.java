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

package com.android.systemui.bubbles;

import static android.view.View.GONE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.R;

/**
 * Class for showing aged out bubbles.
 */
public class BubbleOverflow implements BubbleViewProvider {

    private ImageView mOverflowBtn;
    private BubbleExpandedView mOverflowExpandedView;
    private LayoutInflater mInflater;
    private Context mContext;

    public BubbleOverflow(Context context) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
    }

    public void setUpOverflow(ViewGroup parentViewGroup) {
        mOverflowExpandedView = (BubbleExpandedView) mInflater.inflate(
                R.layout.bubble_expanded_view, parentViewGroup /* root */,
                false /* attachToRoot */);
        mOverflowExpandedView.setOverflow(true);

        mOverflowBtn = (ImageView) mInflater.inflate(R.layout.bubble_overflow_button,
                parentViewGroup /* root */,
                false /* attachToRoot */);

        setOverflowBtnTheme();
        mOverflowBtn.setVisibility(GONE);
    }

    ImageView getBtn() {
        return mOverflowBtn;
    }

    void setBtnVisible(int visible) {
        mOverflowBtn.setVisibility(visible);
    }

    // TODO(b/149146374) Propagate theme change to bubbles in overflow.
    void setOverflowBtnTheme() {
        TypedArray ta = mContext.obtainStyledAttributes(
                new int[]{android.R.attr.colorBackgroundFloating});
        int bgColor = ta.getColor(0, Color.WHITE /* default */);
        ta.recycle();

        InsetDrawable fg = new InsetDrawable(mOverflowBtn.getDrawable(), 28);
        ColorDrawable bg = new ColorDrawable(bgColor);
        AdaptiveIconDrawable adaptiveIcon = new AdaptiveIconDrawable(bg, fg);
        mOverflowBtn.setImageDrawable(adaptiveIcon);
    }


    public BubbleExpandedView getExpandedView() {
        return mOverflowExpandedView;
    }

    public void setContentVisibility(boolean visible) {
        mOverflowExpandedView.setContentVisibility(visible);
    }

    public void logUIEvent(int bubbleCount, int action, float normalX, float normalY,
            int index) {
        // TODO(b/149133814) Log overflow UI events.
    }

    public View getIconView() {
        return mOverflowBtn;
    }

    public String getKey() {
        return BubbleOverflowActivity.KEY;
    }
}
