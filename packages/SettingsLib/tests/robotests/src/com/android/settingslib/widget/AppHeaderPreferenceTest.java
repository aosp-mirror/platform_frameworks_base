/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.app.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AppHeaderPreferenceTest {

    private Context mContext;
    private View mRootView;
    private AppHeaderPreference mPreference;
    private PreferenceViewHolder mHolder;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mRootView = View.inflate(mContext, R.layout.app_header_preference, /* parent */ null);
        mHolder = PreferenceViewHolder.createInstanceForTests(mRootView);
        mPreference = new AppHeaderPreference(mContext);
    }

    @Test
    public void setNonSelectable_viewShouldNotBeSelectable() {
        mPreference.onBindViewHolder(mHolder);

        assertThat(mHolder.itemView.isClickable()).isFalse();
    }

    @Test
    public void defaultInstallType_viewShouldNotBeVisible() {
        mPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.findViewById(R.id.install_type).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void setIsInstantApp_shouldUpdateInstallType() {
        mPreference.onBindViewHolder(mHolder);
        mPreference.setIsInstantApp(true);

        assertThat(((TextView) mRootView.findViewById(R.id.install_type)).getText().toString())
                .isEqualTo(mContext.getResources().getString(R.string.install_type_instant));
    }

    @Test
    public void setSecondSummary_shouldUpdateSecondSummary() {
        final String defaultTestText = "Test second summary";

        mPreference.setSecondSummary(defaultTestText);
        mPreference.onBindViewHolder(mHolder);

        assertThat(((TextView) mRootView.findViewById(R.id.second_summary)).getText().toString())
                .isEqualTo(defaultTestText);
    }
}
