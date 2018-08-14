/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib;

import static com.android.settingslib.TwoTargetPreference.ICON_SIZE_DEFAULT;
import static com.android.settingslib.TwoTargetPreference.ICON_SIZE_MEDIUM;
import static com.android.settingslib.TwoTargetPreference.ICON_SIZE_SMALL;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.support.v7.preference.PreferenceViewHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class TwoTargetPreferenceTest {

    private PreferenceViewHolder mViewHolder;
    private View mDivider;
    private View mWidgetFrame;
    private View mRootView;
    private TwoTargetPreference mPreference;
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mPreference = spy(new TwoTargetPreference(mContext));
        mRootView = View.inflate(mContext, R.layout.preference_two_target, null /* parent */);
        mViewHolder = PreferenceViewHolder.createInstanceForTests(mRootView);

        mDivider = mViewHolder.findViewById(R.id.two_target_divider);
        mWidgetFrame = mViewHolder.findViewById(android.R.id.widget_frame);
    }

    @Test
    public void bind_noSecondTarget_shouldNotDrawDivider() {
        assertThat(mPreference.shouldHideSecondTarget()).isTrue();

        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mWidgetFrame.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void bind_hasSecondTarget_shouldNotDrawDivider() {
        doReturn(false).when(mPreference).shouldHideSecondTarget();

        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mDivider.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mWidgetFrame.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void bind_smallIcon_shouldUseSmallIconSize() {
        mPreference.setIconSize(ICON_SIZE_SMALL);

        mPreference.onBindViewHolder(mViewHolder);

        final int smallIconSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.two_target_pref_small_icon_size);
        final LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) mViewHolder
                .findViewById(android.R.id.icon)
                .getLayoutParams();

        assertThat(layoutParams.width).isEqualTo(smallIconSize);
        assertThat(layoutParams.height).isEqualTo(smallIconSize);
    }

    @Test
    public void bind_mediumIcon_shouldUseMediumIconSize() {
        mPreference.setIconSize(ICON_SIZE_MEDIUM);

        mPreference.onBindViewHolder(mViewHolder);

        final int size = mContext.getResources().getDimensionPixelSize(
                R.dimen.two_target_pref_medium_icon_size);
        final LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) mViewHolder
                .findViewById(android.R.id.icon)
                .getLayoutParams();

        assertThat(layoutParams.width).isEqualTo(size);
        assertThat(layoutParams.height).isEqualTo(size);
    }

    @Test
    public void bind_defaultIcon_shouldUseDefaultIconSize() {
        mPreference.setIconSize(ICON_SIZE_DEFAULT);

        mPreference.onBindViewHolder(mViewHolder);

        final LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) mViewHolder
                .findViewById(android.R.id.icon)
                .getLayoutParams();

        assertThat(layoutParams.width).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT);
        assertThat(layoutParams.height).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
