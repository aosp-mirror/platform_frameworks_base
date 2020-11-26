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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class BannerMessagePreferenceTest {

    private Context mContext;
    private View mRootView;
    private BannerMessagePreference mBannerPreference;
    private PreferenceViewHolder mHolder;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mRootView = View.inflate(mContext, R.layout.banner_message, null /* parent */);
        mHolder = PreferenceViewHolder.createInstanceForTests(mRootView);
        mBannerPreference = new BannerMessagePreference(mContext);
    }

    @Test
    public void onBindViewHolder_shouldSetTitle() {
        mBannerPreference.setTitle("test");

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((TextView) mRootView.findViewById(R.id.banner_title)).getText())
                .isEqualTo("test");
    }

    @Test
    public void onBindViewHolder_shouldSetSummary() {
        mBannerPreference.setSummary("test");

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((TextView) mRootView.findViewById(R.id.banner_summary)).getText())
                .isEqualTo("test");
    }

    @Test
    public void setPositiveButtonText_shouldShowPositiveButton() {
        mBannerPreference.setPositiveButtonText(R.string.tts_settings_title);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.banner_positive_btn)).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void setNegativeButtonText_shouldShowNegativeButton() {
        mBannerPreference.setNegativeButtonText(R.string.tts_settings_title);

        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.banner_negative_btn)).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void withoutSetPositiveButtonText_shouldHidePositiveButton() {
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.banner_positive_btn)).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void withoutSetNegativeButtonText_shouldHideNegativeButton() {
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.banner_negative_btn)).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void setPositiveButtonVisible_withTrue_shouldShowPositiveButton() {
        mBannerPreference.setPositiveButtonText(R.string.tts_settings_title);

        mBannerPreference.setPositiveButtonVisible(true);
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.banner_positive_btn)).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void setPositiveButtonVisible_withFalse_shouldHidePositiveButton() {
        mBannerPreference.setPositiveButtonText(R.string.tts_settings_title);

        mBannerPreference.setPositiveButtonVisible(false);
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.banner_positive_btn)).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void setNegativeButtonVisible_withTrue_shouldShowNegativeButton() {
        mBannerPreference.setNegativeButtonText(R.string.tts_settings_title);

        mBannerPreference.setNegativeButtonVisible(true);
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.banner_negative_btn)).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void setNegativeButtonVisible_withFalse_shouldHideNegativeButton() {
        mBannerPreference.setNegativeButtonText(R.string.tts_settings_title);

        mBannerPreference.setNegativeButtonVisible(false);
        mBannerPreference.onBindViewHolder(mHolder);

        assertThat(((Button) mRootView.findViewById(R.id.banner_negative_btn)).getVisibility())
                .isEqualTo(View.GONE);
    }
}
