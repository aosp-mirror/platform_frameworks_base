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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.preference.PreferenceViewHolder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SettingsSpinnerPreferenceTest {

    private Context mContext;
    private PreferenceViewHolder mViewHolder;
    private Spinner mSpinner;
    private SettingsSpinnerPreference mSpinnerPreference;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mSpinnerPreference = new SettingsSpinnerPreference(mContext);
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        final View rootView = inflater.inflate(mSpinnerPreference.getLayoutResource(),
                new LinearLayout(mContext), false /* attachToRoot */);
        mViewHolder = PreferenceViewHolder.createInstanceForTests(rootView);
        mSpinner = (Spinner) mViewHolder.findViewById(R.id.spinner);
    }

    @Test
    public void onBindViewHolder_noSetSelection_getDefaultItem() {
        final List<CharSequence> list = new ArrayList<>();
        list.add("TEST1");
        list.add("TEST2");
        list.add("TEST3");
        final SettingsSpinnerAdapter adapter = new SettingsSpinnerAdapter(mContext);
        adapter.addAll(list);
        mSpinnerPreference.setAdapter(adapter);

        mSpinnerPreference.onBindViewHolder(mViewHolder);

        assertThat(adapter).isEqualTo(mSpinner.getAdapter());
        assertThat(mSpinnerPreference.getSelectedItem())
                .isEqualTo(mSpinner.getAdapter().getItem(0));
    }

    @Test
    public void onBindViewHolder_setSelection_getSelectedItem() {
        final List<CharSequence> list = new ArrayList<>();
        list.add("TEST1");
        list.add("TEST2");
        list.add("TEST3");
        final SettingsSpinnerAdapter adapter = new SettingsSpinnerAdapter(mContext);
        adapter.addAll(list);
        mSpinnerPreference.setAdapter(adapter);
        mSpinnerPreference.setSelection(1);

        mSpinnerPreference.onBindViewHolder(mViewHolder);

        assertThat(mSpinnerPreference.getSelectedItem())
                .isEqualTo(mSpinner.getAdapter().getItem(1));
    }
}
