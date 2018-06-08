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


import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceViewHolder;
import android.view.View;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class RestrictedPreferenceHelperTest {

    @Mock
    private Context mContext;
    @Mock
    private Preference mPreference;

    private PreferenceViewHolder mViewHolder;
    private RestrictedPreferenceHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mViewHolder = PreferenceViewHolder.createInstanceForTests(mock(View.class));
        mHelper = new RestrictedPreferenceHelper(mContext, mPreference, null);
    }

    @Test
    public void bindPreference_disabled_shouldDisplayDisabledSummary() {
        final TextView summaryView = mock(TextView.class, RETURNS_DEEP_STUBS);
        when(mViewHolder.itemView.findViewById(android.R.id.summary))
                .thenReturn(summaryView);
        when(summaryView.getContext().getText(R.string.disabled_by_admin_summary_text))
                .thenReturn("test");

        mHelper.useAdminDisabledSummary(true);
        mHelper.setDisabledByAdmin(new RestrictedLockUtils.EnforcedAdmin());
        mHelper.onBindViewHolder(mViewHolder);

        verify(summaryView).setText("test");
        verify(summaryView, never()).setVisibility(View.GONE);
    }

    @Test
    public void bindPreference_notDisabled_shouldNotHideSummary() {
        final TextView summaryView = mock(TextView.class, RETURNS_DEEP_STUBS);
        when(mViewHolder.itemView.findViewById(android.R.id.summary))
                .thenReturn(summaryView);
        when(summaryView.getContext().getText(R.string.disabled_by_admin_summary_text))
                .thenReturn("test");
        when(summaryView.getText()).thenReturn("test");

        mHelper.useAdminDisabledSummary(true);
        mHelper.setDisabledByAdmin(null);
        mHelper.onBindViewHolder(mViewHolder);

        verify(summaryView).setText(null);
        verify(summaryView, never()).setVisibility(View.GONE);
    }
}
