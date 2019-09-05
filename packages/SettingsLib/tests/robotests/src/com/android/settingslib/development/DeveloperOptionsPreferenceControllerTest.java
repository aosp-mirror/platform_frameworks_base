/*
 * Copyright (C) 2018 The Android Open Source Project
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

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class DeveloperOptionsPreferenceControllerTest {

    private static final String TEST_KEY = "Test_pref_key";

    @Mock
    private Preference mPreference;
    @Mock
    private PreferenceScreen mPreferenceScreen;

    private DeveloperOptionsPreferenceController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mController = new DeveloperOptionsPreferenceControllerTestable();
        doReturn(mPreference).when(mPreferenceScreen).findPreference(TEST_KEY);
        mController.displayPreference(mPreferenceScreen);
    }

    @Test
    public void onDeveloperOptionsEnabled_shouldEnablePreference() {
        mController.onDeveloperOptionsEnabled();

        verify(mPreference).setEnabled(true);
    }

    @Test
    public void onDeveloperOptionsDisabled_shouldDisablePreference() {
        mController.onDeveloperOptionsDisabled();

        verify(mPreference).setEnabled(false);
    }

    private class DeveloperOptionsPreferenceControllerTestable extends
            DeveloperOptionsPreferenceController {
        DeveloperOptionsPreferenceControllerTestable() {
            super(RuntimeEnvironment.application);
        }

        @Override
        public String getPreferenceKey() {
            return TEST_KEY;
        }
    }
}

