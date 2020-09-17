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

package com.android.settingslib;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.preference.PreferenceViewHolder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class RestrictedSwitchPreferenceTest {

    private static final int SIZE = 50;

    private RestrictedSwitchPreference mPreference;
    private Context mContext;
    private PreferenceViewHolder mViewHolder;
    private View mRootView;
    private ImageView mImageView;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mPreference = new RestrictedSwitchPreference(mContext);
        mRootView = View.inflate(mContext, R.layout.restricted_switch_preference,
                null /* parent */);
        mViewHolder = PreferenceViewHolder.createInstanceForTests(mRootView);
        mImageView = (ImageView) mViewHolder.findViewById(android.R.id.icon);
    }

    @Test
    public void onBindViewHolder_setIconSize_shouldHaveCorrectLayoutParam() {
        mPreference.setIconSize(SIZE);

        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mImageView.getLayoutParams().height).isEqualTo(SIZE);
        assertThat(mImageView.getLayoutParams().width).isEqualTo(SIZE);
    }

    @Test
    public void onBindViewHolder_notSetIconSize_shouldHaveCorrectLayoutParam() {
        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mImageView.getLayoutParams().height).isEqualTo(
                ViewGroup.LayoutParams.WRAP_CONTENT);
        assertThat(mImageView.getLayoutParams().width).isEqualTo(
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
