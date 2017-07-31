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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.os.SystemProperties;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.R;
import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.TestConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(SettingsLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION,
        shadows = SystemPropertiesTestImpl.class)
public class LogdSizePreferenceControllerTest {

    @Mock
    private ListPreference mListPreference;
    @Mock
    private PreferenceScreen mPreferenceScreen;

    private AbstractLogdSizePreferenceController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mController = new AbstractLogdSizePreferenceController(RuntimeEnvironment.application) {};

        doReturn(mListPreference).when(mPreferenceScreen)
                .findPreference(mController.getPreferenceKey());

        mController.displayPreference(mPreferenceScreen);
    }

    @Test
    public void testUpateLogdSizeValues_lowRamEntries() {
        SystemProperties.set("ro.config.low_ram", "true");
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
}
