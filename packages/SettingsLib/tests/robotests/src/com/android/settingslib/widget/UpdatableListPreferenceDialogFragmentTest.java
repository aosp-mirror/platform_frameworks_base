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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.widget.ArrayAdapter;

import androidx.preference.ListPreference;

import com.android.internal.logging.nano.MetricsProto;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class UpdatableListPreferenceDialogFragmentTest {

    private static final String KEY = "Test_Key";
    @Mock
    private UpdatableListPreferenceDialogFragment mUpdatableListPrefDlgFragment;
    private Context mContext;
    private ArrayAdapter mAdapter;
    private ArrayList<CharSequence> mEntries;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        mUpdatableListPrefDlgFragment = spy(UpdatableListPreferenceDialogFragment
                .newInstance(KEY, MetricsProto.MetricsEvent.DIALOG_SWITCH_A2DP_DEVICES));
        mEntries = new ArrayList<>();
        mUpdatableListPrefDlgFragment.setEntries(mEntries);
        mUpdatableListPrefDlgFragment
                .setMetricsCategory(mUpdatableListPrefDlgFragment.getArguments());
        initAdapter();
    }

    private void initAdapter() {
        mAdapter = spy(new ArrayAdapter<>(
                mContext,
                com.android.internal.R.layout.select_dialog_singlechoice,
                mEntries));
        mUpdatableListPrefDlgFragment.setAdapter(mAdapter);
    }

    @Test
    public void getMetricsCategory() {
        assertThat(mUpdatableListPrefDlgFragment.getMetricsCategory())
                .isEqualTo(MetricsProto.MetricsEvent.DIALOG_SWITCH_A2DP_DEVICES);
    }

    @Test
    public void onListPreferenceUpdated_verifyAdapterCanBeUpdate() {
        assertThat(mUpdatableListPrefDlgFragment.getAdapter().getCount()).isEqualTo(0);

        ListPreference listPreference = new ListPreference(mContext);
        final CharSequence[] charSequences = {"Test_DEVICE_1", "Test_DEVICE_2"};
        listPreference.setEntries(charSequences);
        mUpdatableListPrefDlgFragment.onListPreferenceUpdated(listPreference);

        assertThat(mUpdatableListPrefDlgFragment.getAdapter().getCount()).isEqualTo(2);
    }

    @Test
    public void onDialogClosed_emptyPreference() {
        mUpdatableListPrefDlgFragment.onDialogClosed(false);

        verify(mUpdatableListPrefDlgFragment, never()).getListPreference();
    }
}
