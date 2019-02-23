/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.VisibleForTesting;

/**
 * {@link View} for a single vertical bar with icon and summary.
 */
public class BarView extends LinearLayout {

    private static final String TAG = "BarView";

    private View mBarView;
    private ImageView mIcon;
    private TextView mBarTitle;
    private TextView mBarSummary;

    public BarView(Context context) {
        super(context);
        init();
    }

    public BarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();

        // Get accent color
        TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.colorAccent});
        @ColorInt final int colorAccent = a.getColor(0, 0);

        // Get bar color from layout XML
        a = context.obtainStyledAttributes(attrs, R.styleable.SettingsBarView);
        @ColorInt final int barColor = a.getColor(R.styleable.SettingsBarView_barColor,
                colorAccent);
        a.recycle();

        mBarView.setBackgroundColor(barColor);
    }

    /**
     * Updates the view with a {@link BarViewInfo}.
     */
    void updateView(BarViewInfo barViewInfo) {
        setOnClickListener(barViewInfo.getClickListener());
        //Set height of bar view
        mBarView.getLayoutParams().height = barViewInfo.getNormalizedHeight();
        mIcon.setImageDrawable(barViewInfo.getIcon());
        // For now, we use the bar number as title.
        mBarTitle.setText(Integer.toString(barViewInfo.getHeight()));
        mBarSummary.setText(barViewInfo.getSummary());
    }

    @VisibleForTesting
    CharSequence getTitle() {
        return mBarTitle.getText();
    }

    @VisibleForTesting
    CharSequence getSummary() {
        return mBarSummary.getText();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.settings_bar_view, this);
        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER | Gravity.BOTTOM);

        mBarView = findViewById(R.id.bar_view);
        mIcon = findViewById(R.id.icon_view);
        mBarTitle = findViewById(R.id.bar_title);
        mBarSummary = findViewById(R.id.bar_summary);
    }

    private void setOnClickListner(View.OnClickListener listener) {
        mBarView.setOnClickListener(listener);
    }
}
