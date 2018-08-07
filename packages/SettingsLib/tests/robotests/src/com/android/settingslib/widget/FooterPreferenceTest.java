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
import android.support.v7.preference.PreferenceViewHolder;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.widget.TextView;

import com.android.settingslib.R;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowApplication;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class FooterPreferenceTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = ShadowApplication.getInstance().getApplicationContext();
    }

    @Test
    public void createNewPreference_shouldSetKeyAndOrder() {
        final FooterPreference preference = new FooterPreference(mContext);

        assertThat(preference.getKey()).isEqualTo(FooterPreference.KEY_FOOTER);
        assertThat(preference.getOrder()).isEqualTo(FooterPreference.ORDER_FOOTER);
    }

    @Test
    public void bindPreference_shouldLinkifyContent() {
        final FooterPreference preference = new FooterPreference(mContext);
        final PreferenceViewHolder holder = PreferenceViewHolder.createInstanceForTests(
                LayoutInflater.from(mContext).inflate(R.layout.preference_footer, null));

        preference.onBindViewHolder(holder);
        assertThat(((TextView) holder.findViewById(android.R.id.title)).getMovementMethod())
                .isInstanceOf(LinkMovementMethod.class);
    }
}
