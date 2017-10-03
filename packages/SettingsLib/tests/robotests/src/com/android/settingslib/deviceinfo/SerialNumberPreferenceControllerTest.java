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

import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.TestConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;

@RunWith(SettingsLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class SerialNumberPreferenceControllerTest {

    @Mock(answer = RETURNS_DEEP_STUBS)
    private Context mContext;
    @Mock(answer = RETURNS_DEEP_STUBS)
    private PreferenceScreen mScreen;

    private AbstractSerialNumberPreferenceController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testIsAvaiable_noSerial_shouldReturnFalse() {
        mController = new ConcreteSerialNumberPreferenceController(mContext, null);

        assertThat(mController.isAvailable()).isFalse();
    }

    @Test
    public void testIsAvaiable_hasSerial_shouldReturnTrue() {
        mController = new ConcreteSerialNumberPreferenceController(mContext, "123");

        assertThat(mController.isAvailable()).isTrue();
    }

    @Test
    public void testDisplay_noSerial_shouldHidePreference() {
        final Preference preference = mock(Preference.class);
        when(mScreen.getPreferenceCount()).thenReturn(1);
        when(mScreen.getPreference(0)).thenReturn(preference);
        mController = new ConcreteSerialNumberPreferenceController(mContext, null);
        when(preference.getKey()).thenReturn(mController.getPreferenceKey());

        mController.displayPreference(mScreen);

        verify(mScreen).removePreference(any(Preference.class));
    }

    @Test
    public void testDisplay_hasSerial_shouldSetSummary() {
        final String serial = "123";
        final Preference preference = mock(Preference.class);
        when(mScreen.findPreference(anyString())).thenReturn(preference);

        mController = new ConcreteSerialNumberPreferenceController(mContext, serial);
        mController.displayPreference(mScreen);

        verify(mScreen, never()).removePreference(any(Preference.class));
        verify(preference).setSummary(serial);
    }

    private static class ConcreteSerialNumberPreferenceController
            extends AbstractSerialNumberPreferenceController {

        ConcreteSerialNumberPreferenceController(Context context, String serialNumber) {
            super(context, serialNumber);
        }
    }
}
