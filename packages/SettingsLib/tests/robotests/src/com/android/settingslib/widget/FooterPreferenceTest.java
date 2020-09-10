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

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.R;

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
    public void bindPreference_shouldLinkifyContent() {
        final PreferenceViewHolder holder = PreferenceViewHolder.createInstanceForTests(
                LayoutInflater.from(mContext).inflate(R.layout.preference_footer, null));

        mFooterPreference.onBindViewHolder(holder);

        assertThat(((TextView) holder.findViewById(android.R.id.title)).getMovementMethod())
                .isInstanceOf(LinkMovementMethod.class);
    }

    @Test
    public void setSummary_summarySet_shouldSetAsTitle() {
        mFooterPreference.setSummary("summary");

        assertThat(mFooterPreference.getTitle()).isEqualTo("summary");
    }
}
