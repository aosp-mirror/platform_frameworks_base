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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AbstractPreferenceControllerTest {

    @Mock
    private PreferenceScreen mScreen;

    private Context mContext;
    private Preference mPreference;
    private TestPrefController mTestPrefController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mPreference = new Preference(mContext);
        mPreference.setKey(TestPrefController.KEY_PREF);
        when(mScreen.findPreference(TestPrefController.KEY_PREF)).thenReturn(mPreference);
        mTestPrefController = new TestPrefController(mContext);
    }

    @Test
    public void displayPref_ifAvailable() {
        mTestPrefController.isAvailable = true;

        mTestPrefController.displayPreference(mScreen);

        assertThat(mPreference.isVisible()).isTrue();
    }

    @Test
    public void setVisible_prefIsVisible_shouldSetToVisible() {
        mTestPrefController.setVisible(mScreen, TestPrefController.KEY_PREF, true /* visible */);

        assertThat(mPreference.isVisible()).isTrue();
    }

    @Test
    public void setVisible_prefNotVisible_shouldSetToInvisible() {
        mTestPrefController.setVisible(mScreen, TestPrefController.KEY_PREF, false /* visible */);

        assertThat(mPreference.isVisible()).isFalse();
    }

    @Test
    public void doNotDisplayPref_ifNotAvailable() {
        mTestPrefController.isAvailable = false;

        mTestPrefController.displayPreference(mScreen);

        assertThat(mPreference.isVisible()).isFalse();
    }

    @Test
    public void updateState_hasSummary_shouldSetSummary() {
        mTestPrefController.updateState(mPreference);

        assertThat(mPreference.getSummary()).isEqualTo(TestPrefController.TEST_SUMMARY);
    }

    private static class TestPrefController extends AbstractPreferenceController {
        private static final String KEY_PREF = "test_pref";
        private static final CharSequence TEST_SUMMARY = "Test";

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

        @Override
        public CharSequence getSummary() {
            return TEST_SUMMARY;
        }
    }

}
