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

package com.android.settingslib.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 * <p>This code sample demonstrates how to initialize the contents of the BarChartPreference
 * defined in the previous XML layout:
 *
 * <pre>
 * BarChartPreference preference = ((BarChartPreference) findPreference("bar_chart"));
 *
 * BarChartInfo info = new BarChartInfo.Builder()
 *     .setTitle(R.string.permission_bar_chart_title)
 *     .setDetails(R.string.permission_bar_chart_details)
 *     .setEmptyText(R.string.permission_bar_chart_empty_text)
 *     .addBarViewInfo(new barViewInfo(...))
 *     .addBarViewInfo(new barViewInfo(...))
 *     .addBarViewInfo(new barViewInfo(...))
 *     .addBarViewInfo(new barViewInfo(...))
 *     .setDetailsOnClickListener(v -> doSomething())
 *     .build();
 *
 * preference.initializeBarChart(info);
 * </pre>
 *
 *
 * <p>You also can update new information for bar views by
 * {@link BarChartPreference#setBarViewInfos(BarViewInfo[])}
 *
 * <pre>
 * BarViewInfo[] barViewsInfo = new BarViewInfo [] {
 *     new BarViewInfo(...),
 *     new BarViewInfo(...),
 *     new BarViewInfo(...),
 *     new BarViewInfo(...),
 * };
 *
 * preference.setBarViewInfos(barViewsInfo);
 * </pre>
 */
public class BarChartPreference extends Preference {

    public static final int MAXIMUM_BAR_VIEWS = 4;
    private static final String TAG = "BarChartPreference";
    private static final int[] BAR_VIEWS = {
            R.id.bar_view1,
            R.id.bar_view2,
            R.id.bar_view3,
            R.id.bar_view4
    };

    private int mMaxBarHeight;
    private boolean mIsLoading;
    private BarChartInfo mBarChartInfo;

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
     * According to the information in {@link BarChartInfo} to initialize bar chart.
     *
     * @param barChartInfo The barChartInfo contains title, details, empty text, click listener
     *                     attached on details view and four bar views.
     */
    public void initializeBarChart(@NonNull BarChartInfo barChartInfo) {
        mBarChartInfo = barChartInfo;
        notifyChanged();
    }

    /**
     * Sets all bar view information which you'd like to show in preference.
     *
     * @param barViewInfos the barViewInfos contain at least one {@link BarViewInfo}.
     */
    public void setBarViewInfos(@Nullable BarViewInfo[] barViewInfos) {
        if (barViewInfos != null && barViewInfos.length > MAXIMUM_BAR_VIEWS) {
            throw new IllegalStateException("We only support up to four bar views");
        }
        mBarChartInfo.setBarViewInfos(barViewInfos);
        notifyChanged();
    }

    /**
     * Set loading state for {@link BarChartPreference}.
     *
     * By default, {@link BarChartPreference} doesn't care about it.
     *
     * But if user sets loading state to true explicitly, it means {@link BarChartPreference}
     * needs to take some time to load data. So we won't initialize any view now.
     *
     * Once the state is updated to false, we will start to initialize view again.
     *
     * @param isLoading whether or not {@link BarChartPreference} is in loading state.
     */
    public void updateLoadingState(boolean isLoading) {
        mIsLoading = isLoading;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(true);
        holder.setDividerAllowedBelow(true);

        // We bind title and details early so that we can preserve the correct height for chart
        // view.
        bindChartTitleView(holder);
        bindChartDetailsView(holder);

        // If the state is loading, we just show a blank view.
        if (mIsLoading) {
            holder.itemView.setVisibility(View.INVISIBLE);
            return;
        }
        holder.itemView.setVisibility(View.VISIBLE);

        final BarViewInfo[] barViewInfos = mBarChartInfo.getBarViewInfos();
        // If there is no any bar view, we just show an empty text.
        if (barViewInfos == null || barViewInfos.length == 0) {
            setEmptyViewVisible(holder, true /* visible */);
            return;
        }
        setEmptyViewVisible(holder, false /* visible */);
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
        titleView.setText(mBarChartInfo.getTitle());
    }

    private void bindChartDetailsView(PreferenceViewHolder holder) {
        final Button detailsView = (Button) holder.findViewById(R.id.bar_chart_details);
        final int details = mBarChartInfo.getDetails();
        if (details == 0) {
            detailsView.setVisibility(View.GONE);
        } else {
            detailsView.setVisibility(View.VISIBLE);
            detailsView.setText(details);
            detailsView.setOnClickListener(mBarChartInfo.getDetailsOnClickListener());
        }
    }

    private void updateBarChart(PreferenceViewHolder holder) {
        normalizeBarViewHeights();

        final BarViewInfo[] barViewInfos = mBarChartInfo.getBarViewInfos();

        for (int index = 0; index < MAXIMUM_BAR_VIEWS; index++) {
            final BarView barView = (BarView) holder.findViewById(BAR_VIEWS[index]);

            // If there is no bar view info can be shown.
            if (barViewInfos == null || index >= barViewInfos.length) {
                barView.setVisibility(View.GONE);
                continue;
            }
            barView.setVisibility(View.VISIBLE);
            barView.updateView(barViewInfos[index]);
        }
    }

    private void normalizeBarViewHeights() {
        final BarViewInfo[] barViewInfos = mBarChartInfo.getBarViewInfos();
        // If there is no any bar view info, we don't need to calculate the height of all bar views.
        if (barViewInfos == null || barViewInfos.length == 0) {
            return;
        }
        // Do a sort in descending order, the first element would have max {@link
        // BarViewInfo#mHeight}
        Arrays.sort(barViewInfos);
        // Since we sorted this array in advance, the first element must have the max {@link
        // BarViewInfo#mHeight}.
        final int maxBarHeight = barViewInfos[0].getHeight();
        // If the max number of bar view is zero, then we don't calculate the unit for bar height.
        final int unit = maxBarHeight == 0 ? 0 : mMaxBarHeight / maxBarHeight;

        for (BarViewInfo barView : barViewInfos) {
            barView.setNormalizedHeight(barView.getHeight() * unit);
        }
    }

    private void setEmptyViewVisible(PreferenceViewHolder holder, boolean visible) {
        final View barViewsContainer = holder.findViewById(R.id.bar_views_container);
        final TextView emptyView = (TextView) holder.findViewById(R.id.empty_view);
        final int emptyTextRes = mBarChartInfo.getEmptyText();

        if (emptyTextRes != 0) {
            emptyView.setText(emptyTextRes);
        }
        emptyView.setVisibility(visible ? View.VISIBLE : View.GONE);
        barViewsContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
    }
}
