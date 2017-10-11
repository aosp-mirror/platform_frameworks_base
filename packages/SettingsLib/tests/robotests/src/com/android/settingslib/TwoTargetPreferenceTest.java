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

import android.content.Context;
import android.support.v7.preference.PreferenceViewHolder;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(SettingLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class TwoTargetPreferenceTest {

    private PreferenceViewHolder mViewHolder;
    @Mock
    private View mDivider;
    @Mock
    private View mWidgetFrame;
    private TwoTargetPreference mPreference;
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mPreference = spy(new TwoTargetPreference(mContext));
        mViewHolder = PreferenceViewHolder.createInstanceForTests(mock(View.class));
        when(mViewHolder.findViewById(R.id.two_target_divider))
                .thenReturn(mDivider);
        when(mViewHolder.findViewById(android.R.id.widget_frame))
                .thenReturn(mWidgetFrame);
    }

    @Test
    public void bind_noSecondTarget_shouldNotDrawDivider() {
        assertThat(mPreference.shouldHideSecondTarget()).isTrue();

        mPreference.onBindViewHolder(mViewHolder);

        verify(mDivider).setVisibility(View.GONE);
        verify(mWidgetFrame).setVisibility(View.GONE);
    }

    @Test
    public void bind_hasSecondTarget_shouldNotDrawDivider() {
        doReturn(false).when(mPreference).shouldHideSecondTarget();

        mPreference.onBindViewHolder(mViewHolder);

        verify(mDivider).setVisibility(View.VISIBLE);
        verify(mWidgetFrame).setVisibility(View.VISIBLE);
    }
}
