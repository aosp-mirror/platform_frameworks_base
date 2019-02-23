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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class BarChartPreferenceTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    private Context mContext;
    private View mBarChartView;
    private Drawable mIcon;
    private BarView mBarView1;
    private BarView mBarView2;
    private BarView mBarView3;
    private BarView mBarView4;
    private TextView mTitleView;
    private TextView mDetailsView;
    private PreferenceViewHolder mHolder;
    private BarChartPreference mPreference;
    private BarChartInfo mBarChartInfo;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mBarChartView = View.inflate(mContext, R.layout.settings_bar_chart, null /* parent */);
        mHolder = PreferenceViewHolder.createInstanceForTests(mBarChartView);
        mPreference = new BarChartPreference(mContext, null /* attrs */);

        mIcon = mContext.getDrawable(com.android.internal.R.drawable.ic_menu);
        mBarView1 = mBarChartView.findViewById(R.id.bar_view1);
        mBarView2 = mBarChartView.findViewById(R.id.bar_view2);
        mBarView3 = mBarChartView.findViewById(R.id.bar_view3);
        mBarView4 = mBarChartView.findViewById(R.id.bar_view4);
        mTitleView = mBarChartView.findViewById(R.id.bar_chart_title);
        mDetailsView = mBarChartView.findViewById(R.id.bar_chart_details);

        mBarChartInfo = new BarChartInfo.Builder()
                .setTitle(R.string.debug_app)
                .setDetails(R.string.debug_app)
                .setEmptyText(R.string.debug_app)
                .setDetailsOnClickListener(v -> {
                })
                .build();
    }

    @Test
    public void initializeBarChart_titleSet_shouldSetTitleInChartView() {
        final BarChartInfo barChartInfo = new BarChartInfo.Builder()
                .setTitle(R.string.debug_app)
                .build();

        mPreference.initializeBarChart(barChartInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mTitleView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mTitleView.getText()).isEqualTo(mContext.getText(R.string.debug_app));
    }

    @Test
    public void initializeBarChart_noBarViewSet_shouldShowTitleAndEmptyView() {
        final BarChartInfo barChartInfo = new BarChartInfo.Builder()
                .setTitle(R.string.debug_app)
                .setEmptyText(R.string.debug_app)
                .build();

        mPreference.initializeBarChart(barChartInfo);
        // We don't add any bar view yet.
        mPreference.onBindViewHolder(mHolder);

        assertThat(mTitleView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarChartView.findViewById(R.id.empty_view).getVisibility())
                .isEqualTo(View.VISIBLE);
        assertThat(mBarChartView.findViewById(R.id.bar_views_container).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void initializeBarChart_detailsSet_shouldShowBarChartDetailsView() {
        final BarChartInfo barChartInfo = new BarChartInfo.Builder()
                .setTitle(R.string.debug_app)
                .setDetails(R.string.debug_app)
                .addBarViewInfo(new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app))
                .build();

        mPreference.initializeBarChart(barChartInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mDetailsView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mDetailsView.getText()).isEqualTo(mContext.getText(R.string.debug_app));
    }

    @Test
    public void initializeBarChart_detailsNotSet_shouldHideBarChartDetailsView() {
        // We don't call BarChartInfo.Builder#setDetails yet.
        final BarChartInfo barChartInfo = new BarChartInfo.Builder()
                .setTitle(R.string.debug_app)
                .addBarViewInfo(new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app))
                .build();

        mPreference.initializeBarChart(barChartInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mDetailsView.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void initializeBarChart_clickListenerSet_shouldSetClickListenerOnDetailsView() {
        final BarChartInfo barChartInfo = new BarChartInfo.Builder()
                .setTitle(R.string.debug_app)
                .setDetails(R.string.debug_app)
                .setDetailsOnClickListener(v -> {
                })
                .addBarViewInfo(new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app))
                .build();

        mPreference.initializeBarChart(barChartInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mDetailsView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mDetailsView.hasOnClickListeners()).isTrue();
    }

    @Test
    public void setBarViewInfos_oneBarViewInfoSet_shouldShowOneBarView() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app)
        };

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("10");

        assertThat(mBarView2.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBarView3.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBarView4.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setBarViewInfos_twoBarViewInfosSet_shouldShowTwoBarViews() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 20 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app)
        };

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("20");
        assertThat(mBarView2.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView2.getTitle()).isEqualTo("10");

        assertThat(mBarView3.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBarView4.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setBarViewInfos_threeBarViewInfosSet_shouldShowThreeBarViews() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 20 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 5 /* barNumber */, R.string.debug_app)
        };

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("20");
        assertThat(mBarView2.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView2.getTitle()).isEqualTo("10");
        assertThat(mBarView3.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView3.getTitle()).isEqualTo("5");

        assertThat(mBarView4.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setBarViewInfos_fourBarViewInfosSet_shouldShowFourBarViews() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 20 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 5 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 2 /* barNumber */, R.string.debug_app),
        };

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("20");
        assertThat(mBarView2.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView2.getTitle()).isEqualTo("10");
        assertThat(mBarView3.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView3.getTitle()).isEqualTo("5");
        assertThat(mBarView4.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView4.getTitle()).isEqualTo("2");
    }

    @Test
    public void setBarViewInfos_moreInfosThanMaxAllowed_shouldThrowIllegalStateException() {
        thrown.expect(IllegalStateException.class);

        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 30 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 50 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 5 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 70 /* barNumber */, R.string.debug_app),
        };

        mPreference.setBarViewInfos(barViewsInfo);
    }

    @Test
    public void setBarViewInfos_barViewInfosSet_shouldBeSortedInDescending() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 30 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 50 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 5 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
        };

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("50");
        assertThat(mBarView2.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView2.getTitle()).isEqualTo("30");
        assertThat(mBarView3.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView3.getTitle()).isEqualTo("10");
        assertThat(mBarView4.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView4.getTitle()).isEqualTo("5");
    }

    @Test
    public void setBarViewInfos_validBarViewSummarySet_barViewShouldShowSummary() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
        };

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getSummary()).isEqualTo(mContext.getText(R.string.debug_app));
    }

    @Test
    public void setBarViewInfos_clickListenerForBarViewSet_barViewShouldHaveClickListener() {
        final BarViewInfo viewInfo = new BarViewInfo(mIcon, 30 /* barNumber */, R.string.debug_app);
        viewInfo.setClickListener(v -> {
        });
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{viewInfo};

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.hasOnClickListeners()).isTrue();
    }

    @Test
    public void onBindViewHolder_loadingStateIsTrue_shouldHideAllViews() {
        final BarViewInfo viewInfo = new BarViewInfo(mIcon, 30 /* barNumber */, R.string.debug_app);
        viewInfo.setClickListener(v -> {
        });
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{viewInfo};

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.updateLoadingState(true /* isLoading */);

        mPreference.onBindViewHolder(mHolder);

        assertThat(mHolder.itemView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void onBindViewHolder_loadingStateIsFalse_shouldInitAnyView() {
        final BarViewInfo viewInfo = new BarViewInfo(mIcon, 30 /* barNumber */, R.string.debug_app);
        viewInfo.setClickListener(v -> {
        });
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{viewInfo};

        mPreference.initializeBarChart(mBarChartInfo);
        mPreference.setBarViewInfos(barViewsInfo);
        mPreference.updateLoadingState(false /* isLoading */);

        mPreference.onBindViewHolder(mHolder);

        assertThat(mHolder.itemView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(TextUtils.isEmpty(mTitleView.getText())).isFalse();
        assertThat(TextUtils.isEmpty(mDetailsView.getText())).isFalse();
    }
}
