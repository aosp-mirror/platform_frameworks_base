/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.footer.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class FooterPreferenceTest {

    private Context mContext;
    private FooterPreference mFooterPreference;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mFooterPreference = new FooterPreference(mContext);
    }

    @Test
    public void setSummary_summarySet_shouldSetAsTitle() {
        mFooterPreference.setSummary("summary");

        assertThat(mFooterPreference.getTitle()).isEqualTo("summary");
    }

    @Test
    public void setLearnMoreText_shouldSetAsTextInLearnMore() {
        final PreferenceViewHolder holder = PreferenceViewHolder.createInstanceForTests(
                LayoutInflater.from(mContext).inflate(R.layout.preference_footer, null));
        mFooterPreference.setLearnMoreText("Custom learn more");
        mFooterPreference.setLearnMoreAction(view -> { /* do nothing */ } /* listener */);

        mFooterPreference.onBindViewHolder(holder);

        TextView learnMoreView = (TextView) holder.findViewById(R.id.settingslib_learn_more);
        assertThat(learnMoreView.getText().toString()).isEqualTo("Custom learn more");
    }

    @Test
    public void setContentDescription_contentSet_shouldGetSameContentDescription() {
        mFooterPreference.setContentDescription("test");

        assertThat(mFooterPreference.getContentDescription()).isEqualTo("test");
    }

    @Test
    public void setLearnMoreAction_actionSet_shouldGetAction() {
        mFooterPreference.setLearnMoreAction(v -> {
        });

        assertThat(mFooterPreference.mLearnMoreListener).isNotNull();
    }

    @Test
    public void setIconVisibility_shouldReturnSameVisibilityType() {
        mFooterPreference.setIconVisibility(View.GONE);

        assertThat(mFooterPreference.mIconVisibility).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_whenTitleIsNull_shouldNotRaiseNpe() {
        PreferenceViewHolder viewHolder = spy(PreferenceViewHolder.createInstanceForTests(
                LayoutInflater.from(mContext).inflate(R.layout.preference_footer, null)));
        when(viewHolder.findViewById(androidx.core.R.id.title)).thenReturn(null);

        Throwable actualThrowable = null;
        try {
            mFooterPreference.onBindViewHolder(viewHolder);
        } catch (Throwable throwable) {
            actualThrowable = throwable;
        }

        assertThat(actualThrowable).isNull();
    }

    @Test
    public void onBindViewHolder_whenLearnMoreIsNull_shouldNotRaiseNpe() {
        PreferenceViewHolder viewHolder = spy(PreferenceViewHolder.createInstanceForTests(
                LayoutInflater.from(mContext).inflate(R.layout.preference_footer, null)));
        when(viewHolder.findViewById(R.id.settingslib_learn_more)).thenReturn(null);

        Throwable actualThrowable = null;
        try {
            mFooterPreference.onBindViewHolder(viewHolder);
        } catch (Throwable throwable) {
            actualThrowable = throwable;
        }

        assertThat(actualThrowable).isNull();
    }

    @Test
    public void onBindViewHolder_whenIconFrameIsNull_shouldNotRaiseNpe() {
        PreferenceViewHolder viewHolder = spy(PreferenceViewHolder.createInstanceForTests(
                LayoutInflater.from(mContext).inflate(R.layout.preference_footer, null)));
        when(viewHolder.findViewById(R.id.icon_frame)).thenReturn(null);

        Throwable actualThrowable = null;
        try {
            mFooterPreference.onBindViewHolder(viewHolder);
        } catch (Throwable throwable) {
            actualThrowable = throwable;
        }

        assertThat(actualThrowable).isNull();
    }
}
