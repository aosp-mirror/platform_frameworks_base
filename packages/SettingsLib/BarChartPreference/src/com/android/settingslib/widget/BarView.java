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
 * A extension view for bar chart.
 */
public class BarView extends LinearLayout {

    private static final String TAG = "BarView";

    private View mBarView;
    private ImageView mIcon;
    private TextView mBarTitle;
    private TextView mBarSummary;

    /**
     * Constructs a new BarView with the given context's theme.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     */
    public BarView(Context context) {
        super(context);
        init();
    }

    /**
     * Constructs a new BarView with the given context's theme and the supplied
     * attribute set.
     *
     * @param context the Context the view is running in
     * @param attrs the attributes of the XML tag that is inflating the view.
     */
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
     * This helps update the bar view UI with a {@link BarViewInfo}.
     *
     * @param barViewInfo A {@link BarViewInfo} saves bar view status.
     */
    public void updateBarViewUI(BarViewInfo barViewInfo) {
        //Set height of bar view
        mBarView.getLayoutParams().height = barViewInfo.getBarHeight();
        mIcon.setImageDrawable(barViewInfo.getIcon());
        // For now, we use the bar number as title.
        mBarTitle.setText(Integer.toString(barViewInfo.getBarNumber()));
        mBarSummary.setText(barViewInfo.getSummaryRes());
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
        setGravity(Gravity.CENTER);

        mBarView = findViewById(R.id.bar_view);
        mIcon = (ImageView) findViewById(R.id.icon_view);
        mBarTitle = (TextView) findViewById(R.id.bar_title);
        mBarSummary = (TextView) findViewById(R.id.bar_summary);
    }

    private void setOnClickListner(View.OnClickListener listener) {
        mBarView.setOnClickListener(listener);
    }
}
