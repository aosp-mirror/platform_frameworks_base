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

package com.android.settingslib.development;

import static com.android.settingslib.development.AbstractLogdSizePreferenceController
        .DEFAULT_SNET_TAG;
import static com.android.settingslib.development.AbstractLogdSizePreferenceController
        .LOW_RAM_CONFIG_PROPERTY_KEY;
import static com.android.settingslib.development.AbstractLogdSizePreferenceController
        .SELECT_LOGD_MINIMUM_SIZE_VALUE;
import static com.android.settingslib.development.AbstractLogdSizePreferenceController
        .SELECT_LOGD_OFF_SIZE_MARKER_VALUE;
import static com.android.settingslib.development.AbstractLogdSizePreferenceController
        .SELECT_LOGD_SIZE_PROPERTY;
import static com.android.settingslib.development.AbstractLogdSizePreferenceController
        .SELECT_LOGD_SNET_TAG_PROPERTY;
import static com.android.settingslib.development.AbstractLogdSizePreferenceController
        .SELECT_LOGD_TAG_PROPERTY;
import static com.android.settingslib.development.AbstractLogdSizePreferenceController
        .SELECT_LOGD_TAG_SILENCE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.SystemProperties;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.R;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class LogdSizePreferenceControllerTest {

    @Mock
    private ListPreference mListPreference;
    @Mock
    private PreferenceScreen mPreferenceScreen;

    /**
     * List Values
     *
     * 0: off
     * 1: 64k
     * 2: 256k
     * 3: 1M
     * 4: 4M
     * 5: 16M
     */
    private String[] mListValues;
    private String[] mListSummaries;
    private Context mContext;
    private AbstractLogdSizePreferenceController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mController = new AbstractLogdSizePreferenceController(RuntimeEnvironment.application) {
        };
        mListValues = mContext.getResources().getStringArray(R.array.select_logd_size_values);
        mListSummaries = mContext.getResources().getStringArray(R.array.select_logd_size_summaries);
        doReturn(mListPreference).when(mPreferenceScreen)
                .findPreference(mController.getPreferenceKey());

        mController.displayPreference(mPreferenceScreen);
    }

    @Test
    public void testUpdateLogdSizeValues_lowRamEntries() {
        SystemProperties.set(LOW_RAM_CONFIG_PROPERTY_KEY, "true");
        mController.updateLogdSizeValues();
        verify(mListPreference).setEntries(R.array.select_logd_size_lowram_titles);
    }

    @Test
    public void testUpdateLogdSizeValues_silence() {
        SystemProperties.set(
                AbstractLogdSizePreferenceController.SELECT_LOGD_TAG_PROPERTY,
                AbstractLogdSizePreferenceController.SELECT_LOGD_TAG_SILENCE);
        SystemProperties.set(
                AbstractLogdSizePreferenceController.SELECT_LOGD_SIZE_PROPERTY,
                AbstractLogdSizePreferenceController.SELECT_LOGD_DEFAULT_SIZE_VALUE);
        mController.updateLogdSizeValues();
        verify(mListPreference).setValue(
                AbstractLogdSizePreferenceController.SELECT_LOGD_OFF_SIZE_MARKER_VALUE);
    }

    @Test
    public void onPreferenceChange_noTagsSizeValueOff_shouldSetTagAndSnetTagAndSet64KSize() {
        mController.onPreferenceChange(mListPreference, SELECT_LOGD_OFF_SIZE_MARKER_VALUE);

        final String tag = SystemProperties.get(SELECT_LOGD_TAG_PROPERTY);
        final String logSize = SystemProperties.get(SELECT_LOGD_SIZE_PROPERTY);
        final String snetTag = SystemProperties.get(SELECT_LOGD_SNET_TAG_PROPERTY);

        assertThat(tag).isEqualTo(SELECT_LOGD_TAG_SILENCE);
        assertThat(logSize).isEqualTo(SELECT_LOGD_MINIMUM_SIZE_VALUE);
        assertThat(snetTag).isEqualTo(DEFAULT_SNET_TAG);
    }

    @Test
    public void onPreferenceChange_noTagsSizeValue64K_shouldNotSetTagAndSet64KSize() {
        mController.onPreferenceChange(mListPreference, SELECT_LOGD_MINIMUM_SIZE_VALUE);

        final String tag = SystemProperties.get(SELECT_LOGD_TAG_PROPERTY);
        final String logSize = SystemProperties.get(SELECT_LOGD_SIZE_PROPERTY);
        final String snetTag = SystemProperties.get(SELECT_LOGD_SNET_TAG_PROPERTY);

        assertThat(tag).isEmpty();
        assertThat(logSize).isEqualTo(SELECT_LOGD_MINIMUM_SIZE_VALUE);
        assertThat(snetTag).isEmpty();
    }

    @Test
    public void onPreferenceChange_set1M_shouldUpdateSettingLogSizeTo1M() {
        mController.onPreferenceChange(mListPreference, mListValues[3]);

        final String logSize = SystemProperties.get(SELECT_LOGD_SIZE_PROPERTY);

        assertThat(logSize).isEqualTo(mListValues[3]);
    }

    @Test
    public void onPreferenceChange_noValue_shouldUpdateSettingToEmpty() {
        mController.onPreferenceChange(mListPreference, "" /* new value */);

        final String logSize = SystemProperties.get(SELECT_LOGD_SIZE_PROPERTY);

        assertThat(logSize).isEmpty();
    }

    @Test
    public void updateLogdSizeValues_noValueSet_shouldSetDefaultTo64K() {
        SystemProperties.set(SELECT_LOGD_SIZE_PROPERTY, "" /* new value */);

        mController.updateLogdSizeValues();

        verify(mListPreference).setValue(mListValues[2]);
        verify(mListPreference).setSummary(mListSummaries[2]);
    }

    @Test
    public void updateLogdSizeValues_noValueSetLowRamSet_shouldSetDefaultTo64K() {
        SystemProperties.set(LOW_RAM_CONFIG_PROPERTY_KEY, Boolean.toString(true));
        SystemProperties.set(SELECT_LOGD_SIZE_PROPERTY, "" /* new value */);

        mController.updateLogdSizeValues();

        verify(mListPreference).setValue(mListValues[1]);
        verify(mListPreference).setSummary(mListSummaries[1]);
    }

    @Test
    public void updateLogdSizeValues_64KSet_shouldSet64K() {
        SystemProperties.set(SELECT_LOGD_SIZE_PROPERTY, mListValues[1]);

        mController.updateLogdSizeValues();

        verify(mListPreference).setValue(mListValues[1]);
        verify(mListPreference).setSummary(mListSummaries[1]);
    }
}
