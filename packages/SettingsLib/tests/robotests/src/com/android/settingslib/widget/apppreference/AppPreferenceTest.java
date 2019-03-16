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

package com.android.settingslib.widget.apppreference;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;

import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AppPreferenceTest {

    private Context mContext;
    private View mRootView;
    private AppPreference mPref;
    private PreferenceViewHolder mHolder;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mRootView = View.inflate(mContext, R.layout.preference_app, null /* parent */);
        mHolder = PreferenceViewHolder.createInstanceForTests(mRootView);
        mPref = new AppPreference(mContext);
    }

    @Test
    public void setProgress_showProgress() {
        mPref.setProgress(1);
        mPref.onBindViewHolder(mHolder);

        assertThat(mHolder.findViewById(android.R.id.progress).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void setSummary_showSummaryContainer() {
        mPref.setSummary("test");
        mPref.onBindViewHolder(mHolder);

        assertThat(mHolder.findViewById(R.id.summary_container).getVisibility())
                .isEqualTo(View.VISIBLE);
    }

    @Test
    public void noSummary_hideSummaryContainer() {
        mPref.setSummary(null);
        mPref.onBindViewHolder(mHolder);

        assertThat(mHolder.findViewById(R.id.summary_container).getVisibility())
                .isEqualTo(View.GONE);
    }

    @Test
    public void foobar_testName() {
        float iconSize = mContext.getResources().getDimension(R.dimen.secondary_app_icon_size);
        assertThat(Float.floatToIntBits(iconSize)).isEqualTo(Float.floatToIntBits(32));
    }
}
