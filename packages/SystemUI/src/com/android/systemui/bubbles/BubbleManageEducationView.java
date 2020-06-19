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

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.R;

/**
 * Educational view to highlight the manage button that allows a user to configure the settings
 * for the bubble. Shown only the first time a user expands a bubble.
 */
public class BubbleManageEducationView extends LinearLayout {

    private View mManageView;
    private TextView mTitleTextView;
    private TextView mDescTextView;

    public BubbleManageEducationView(Context context) {
        this(context, null);
    }

    public BubbleManageEducationView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleManageEducationView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleManageEducationView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mManageView = findViewById(R.id.manage_education_view);
        mTitleTextView = findViewById(R.id.user_education_title);
        mDescTextView = findViewById(R.id.user_education_description);

        final TypedArray ta = mContext.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent,
                        android.R.attr.textColorPrimaryInverse});
        final int bgColor = ta.getColor(0, Color.BLACK);
        int textColor = ta.getColor(1, Color.WHITE);
        ta.recycle();

        textColor = ContrastColorUtil.ensureTextContrast(textColor, bgColor, true);
        mTitleTextView.setTextColor(textColor);
        mDescTextView.setTextColor(textColor);
    }

    /**
     * Specifies the position for the manage view.
     */
    public void setManageViewPosition(int x, int y) {
        mManageView.setTranslationX(x);
        mManageView.setTranslationY(y);
    }

    /**
     * @return the height of the view that shows the educational text and pointer.
     */
    public int getManageViewHeight() {
        return mManageView.getHeight();
    }

    @Override
    public void setLayoutDirection(int direction) {
        super.setLayoutDirection(direction);
        if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            mManageView.setBackgroundResource(R.drawable.bubble_stack_user_education_bg_rtl);
            mTitleTextView.setGravity(Gravity.RIGHT);
            mDescTextView.setGravity(Gravity.RIGHT);
        } else {
            mManageView.setBackgroundResource(R.drawable.bubble_stack_user_education_bg);
            mTitleTextView.setGravity(Gravity.LEFT);
            mDescTextView.setGravity(Gravity.LEFT);
        }
    }
}
