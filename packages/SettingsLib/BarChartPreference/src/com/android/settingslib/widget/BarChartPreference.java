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
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import java.util.Arrays;

/**
 * This BarChartPreference shows up to four bar views in this preference at most.
 *
 * <p>The following code sample shows a typical use, with an XML layout and code to initialize the
 * contents of the BarChartPreference:
 *
 * <pre>
 * &lt;com.android.settingslib.widget.BarChartPreference
 *        android:key="bar_chart"/&gt;
 * </pre>
 *
 * <p>This code sample demonstrates how to initialize the contents of the BarChartPreference defined
 * in the previous XML layout:
 *
 * <pre>
 * BarViewInfo[] viewsInfo = new BarViewInfo [] {
 *     new BarViewInfo(icon, 18, res of summary),
 *     new BarViewInfo(icon, 25, res of summary),
 *     new BarViewInfo(icon, 10, res of summary),
 *     new BarViewInfo(icon, 3, res of summary),
 *  };
 * </pre>
 *
 * <pre>
 * BarChartPreference preference = ((BarChartPreference) findPreference("bar_chart"));
 *
 * preference.setBarChartTitleRes(R.string.title_res);
 * preference.setBarChartDetailsRes(R.string.details_res);
 * preference.setBarChartDetailsClickListener(v -> doSomething());
 * preference.setAllBarViewsData(viewsInfo);
 * </pre>
 */
public class BarChartPreference extends Preference {

    private static final String TAG = "BarChartPreference";
    private static final int MAXIMUM_BAR_VIEWS = 4;
    private static final int[] BAR_VIEWS = {
            R.id.bar_view1,
            R.id.bar_view2,
            R.id.bar_view3,
            R.id.bar_view4
    };

    private int mMaxBarHeight;
    @StringRes
    private int mTitleId;
    @StringRes
    private int mDetailsId;
    private BarViewInfo[] mBarViewsInfo;
    private View.OnClickListener mDetailsOnClickListener;

    public BarChartPreference(Context context) {
        super(context);
        init();
    }

    public BarChartPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BarChartPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public BarChartPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    /**
     * Set the text resource for bar chart title.
     */
    public void setBarChartTitle(@StringRes int resId) {
        mTitleId = resId;
        notifyChanged();
    }

    /**
     * Set the text resource for bar chart details.
     */
    public void setBarChartDetails(@StringRes int resId) {
        mDetailsId = resId;
        notifyChanged();
    }

    /**
     * Register a callback to be invoked when bar chart details view is clicked.
     */
    public void setBarChartDetailsClickListener(@Nullable View.OnClickListener clickListener) {
        mDetailsOnClickListener = clickListener;
        notifyChanged();
    }

    /**
     * Set all bar view information which you'd like to show in preference.
     *
     * @param barViewsInfo the barViewsInfo contain at least one {@link BarViewInfo}.
     */
    public void setAllBarViewsInfo(@NonNull BarViewInfo[] barViewsInfo) {
        mBarViewsInfo = barViewsInfo;
        // Do a sort in descending order, the first element would have max {@link
        // BarViewInfo#mBarNumber}
        Arrays.sort(mBarViewsInfo);
        calculateAllBarViewHeights();
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(true);
        holder.setDividerAllowedBelow(true);

        bindChartTitleView(holder);
        bindChartDetailsView(holder);
        updateBarChart(holder);
    }

    private void init() {
        setSelectable(false);
        setLayoutResource(R.layout.settings_bar_chart);
        mMaxBarHeight = getContext().getResources().getDimensionPixelSize(
                R.dimen.settings_bar_view_max_height);
    }

    private void bindChartTitleView(PreferenceViewHolder holder) {
        final TextView titleView = (TextView) holder.findViewById(R.id.bar_chart_title);
        titleView.setText(mTitleId);
    }

    private void bindChartDetailsView(PreferenceViewHolder holder) {
        final Button detailsView = (Button) holder.findViewById(R.id.bar_chart_details);
        detailsView.setText(mDetailsId);
        detailsView.setOnClickListener(mDetailsOnClickListener);
    }

    private void updateBarChart(PreferenceViewHolder holder) {
        for (int index = 0; index < MAXIMUM_BAR_VIEWS; index++) {
            final BarView barView = (BarView) holder.findViewById(BAR_VIEWS[index]);

            // If there is no bar views data can be shown.
            if (mBarViewsInfo == null || index >= mBarViewsInfo.length) {
                barView.setVisibility(View.GONE);
                continue;
            }
            barView.setVisibility(View.VISIBLE);
            barView.updateView(mBarViewsInfo[index]);
        }
    }

    private void calculateAllBarViewHeights() {
        // Since we sorted this array in advance, the first element must have the max {@link
        // BarViewInfo#mHeight}.
        final int maxBarHeight = mBarViewsInfo[0].getHeight();
        // If the max number of bar view is zero, then we don't calculate the unit for bar height.
        final int unit = maxBarHeight == 0 ? 0 : mMaxBarHeight / maxBarHeight;

        for (BarViewInfo barView : mBarViewsInfo) {
            barView.setNormalizedHeight(barView.getHeight() * unit);
        }
    }
}
