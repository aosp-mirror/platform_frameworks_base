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

package com.android.settingslib.deviceinfo;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class SerialNumberPreferenceControllerTest {

    @Mock
    private PreferenceScreen mScreen;

    private Context mContext;
    private Preference mPreference;
    private AbstractSerialNumberPreferenceController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mPreference = new Preference(mContext);
        mPreference.setKey(AbstractSerialNumberPreferenceController.KEY_SERIAL_NUMBER);
        when(mScreen.findPreference(mPreference.getKey())).thenReturn(mPreference);
    }

    @Test
    public void testIsAvaiable_noSerial_shouldReturnFalse() {
        mController = new TestPreferenceController(mContext, null);

        assertThat(mController.isAvailable()).isFalse();
    }

    @Test
    public void testIsAvailable_hasSerial_shouldReturnTrue() {
        mController = new TestPreferenceController(mContext, "123");

        assertThat(mController.isAvailable()).isTrue();
    }

    @Test
    public void testDisplay_noSerial_shouldHidePreference() {
        mController = new TestPreferenceController(mContext, null);

        mController.displayPreference(mScreen);

        assertThat(mPreference.isVisible()).isFalse();
    }

    @Test
    public void testDisplay_hasSerial_shouldSetSummary() {
        final String serial = "123";

        mController = new TestPreferenceController(mContext, serial);
        mController.displayPreference(mScreen);

        assertThat(mPreference.isVisible()).isTrue();
        assertThat(mPreference.getSummary()).isEqualTo(serial);
    }

    private static class TestPreferenceController
            extends AbstractSerialNumberPreferenceController {

        TestPreferenceController(Context context, String serialNumber) {
            super(context, serialNumber);
        }
    }
}
