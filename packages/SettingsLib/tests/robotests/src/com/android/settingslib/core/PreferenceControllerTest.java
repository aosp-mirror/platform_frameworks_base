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
 * limitations under the License
 */
package com.android.settingslib.core;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;
import com.android.settingslib.TestConfig;
import com.android.settingslib.core.AbstractPreferenceController;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class PreferenceControllerTest {

    @Mock
    private Context mContext;
    @Mock
    private PreferenceScreen mScreen;
    @Mock
    private Preference mPreference;

    private TestPrefController mTestPrefController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestPrefController = new TestPrefController(mContext);
    }

    @Test
    public void removeExistingPref_shouldBeRemoved() {
        when(mScreen.findPreference(TestPrefController.KEY_PREF)).thenReturn(mPreference);

        mTestPrefController.removePreference(mScreen, TestPrefController.KEY_PREF);

        verify(mScreen).removePreference(mPreference);
    }

    @Test
    public void removeNonExistingPref_shouldNotRemoveAnything() {
        mTestPrefController.removePreference(mScreen, TestPrefController.KEY_PREF);

        verify(mScreen, never()).removePreference(any(Preference.class));
    }

    @Test
    public void displayPref_ifAvailable() {
        mTestPrefController.isAvailable = true;

        mTestPrefController.displayPreference(mScreen);

        verify(mScreen, never()).removePreference(any(Preference.class));
    }

    @Test
    public void doNotDisplayPref_ifNotAvailable() {
        when(mScreen.findPreference(TestPrefController.KEY_PREF)).thenReturn(mPreference);
        mTestPrefController.isAvailable = false;

        mTestPrefController.displayPreference(mScreen);

        verify(mScreen).removePreference(any(Preference.class));
    }

    private class TestPrefController extends AbstractPreferenceController {
        private static final String KEY_PREF = "test_pref";
        public boolean isAvailable;

        public TestPrefController(Context context) {
            super(context);
        }

        @Override
        public boolean handlePreferenceTreeClick(Preference preference) {
            return false;
        }

        @Override
        public boolean isAvailable() {
            return isAvailable;
        }

        @Override
        public String getPreferenceKey() {
            return KEY_PREF;
        }
    }

}
