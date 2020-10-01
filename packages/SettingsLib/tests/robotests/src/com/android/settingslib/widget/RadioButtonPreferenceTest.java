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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;

import androidx.preference.PreferenceViewHolder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class RadioButtonPreferenceTest {

    private Application mContext;
    private RadioButtonPreference mPreference;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mPreference = new RadioButtonPreference(mContext);
    }

    @Test
    public void shouldHaveRadioPreferenceLayout() {
        assertThat(mPreference.getLayoutResource()).isEqualTo(R.layout.preference_radio);
    }

    @Test
    public void iconSpaceReservedShouldBeFalse() {
        assertThat(mPreference.isIconSpaceReserved()).isFalse();
    }

    @Test
    public void summary_containerShouldBeVisible() {
        mPreference.setSummary("some summary");
        View summaryContainer = new View(mContext);
        View view = mock(View.class);
        when(view.findViewById(R.id.summary_container)).thenReturn(summaryContainer);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);
        mPreference.onBindViewHolder(preferenceViewHolder);
        assertEquals(View.VISIBLE, summaryContainer.getVisibility());
    }

    @Test
    public void emptySummary_containerShouldBeGone() {
        mPreference.setSummary("");
        View summaryContainer = new View(mContext);
        View view = mock(View.class);
        when(view.findViewById(R.id.summary_container)).thenReturn(summaryContainer);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);
        mPreference.onBindViewHolder(preferenceViewHolder);
        assertEquals(View.GONE, summaryContainer.getVisibility());
    }

    @Test
    public void nullSummary_containerShouldBeGone() {
        mPreference.setSummary(null);
        View summaryContainer = new View(mContext);
        View view = mock(View.class);
        when(view.findViewById(R.id.summary_container)).thenReturn(summaryContainer);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);
        mPreference.onBindViewHolder(preferenceViewHolder);
        assertEquals(View.GONE, summaryContainer.getVisibility());
    }

    @Test
    public void hideAppendix_shouldBeGone() {
        mPreference.setAppendixVisibility(View.GONE);
        View view = LayoutInflater.from(mContext).inflate(R.layout.preference_radio, null);
        PreferenceViewHolder holder = PreferenceViewHolder.createInstanceForTests(view);
        mPreference.onBindViewHolder(holder);
        assertThat(holder.findViewById(R.id.appendix).getVisibility()).isEqualTo(View.GONE);
    }
}
