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
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;
import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.widget.mainswitch.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MainSwitchPreferenceTest {

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private View mRootView;
    private PreferenceViewHolder mHolder;
    private MainSwitchPreference mPreference;

    @Before
    public void setUp() {
        mRootView = View.inflate(mContext, R.layout.settingslib_main_switch_layout,
                null /* parent */);
        mHolder = PreferenceViewHolder.createInstanceForTests(mRootView);
        mPreference = new MainSwitchPreference(mContext);
    }

    @Test
    public void onBindViewHolder_title() {
        final String defaultOnText = "Test title";

        mPreference.setTitle(defaultOnText);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.<TextView>requireViewById(
                R.id.switch_text).getText().toString()).isEqualTo(defaultOnText);
    }

    @Test
    public void onBindViewHolder_checked() {
        mPreference.setChecked(true);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mRootView.<MainSwitchBar>requireViewById(
                R.id.settingslib_main_switch_bar).isChecked()).isTrue();
    }
}
