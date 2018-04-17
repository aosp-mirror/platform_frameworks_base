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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import androidx.lifecycle.LifecycleOwner;
import android.os.SystemProperties;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class LogpersistPreferenceControllerTest {

    private LifecycleOwner mLifecycleOwner;
    private Lifecycle mLifecycle;

    @Mock
    private ListPreference mListPreference;
    @Mock
    private PreferenceScreen mPreferenceScreen;

    private AbstractLogpersistPreferenceController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        SystemProperties.set("ro.debuggable", "1");
        mLifecycleOwner = () -> mLifecycle;
        mLifecycle = new Lifecycle(mLifecycleOwner);
        mController = new AbstractLogpersistPreferenceController(RuntimeEnvironment.application,
                mLifecycle) {
            @Override
            public void showConfirmationDialog(Preference preference) {}

            @Override
            public void dismissConfirmationDialog() {}

            @Override
            public boolean isConfirmationDialogShowing() {
                return false;
            }
        };

        doReturn(mListPreference).when(mPreferenceScreen)
                .findPreference(mController.getPreferenceKey());

        mController.displayPreference(mPreferenceScreen);
    }

    @Test
    public void testAvailable() {
        SystemProperties.set("ro.debuggable", "");
        assertThat(mController.isAvailable()).isFalse();
        SystemProperties.set("ro.debuggable", "1");
        assertThat(mController.isAvailable()).isTrue();
        SystemProperties.set("ro.debuggable", "0");
        assertThat(mController.isAvailable()).isFalse();
    }

    @Test
    public void testUpdateLogpersistValues_null() {
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY,
                AbstractLogpersistPreferenceController.SELECT_LOGPERSIST_PROPERTY_SERVICE);
        mController.updateLogpersistValues();
        verify(mListPreference, atLeastOnce()).setValue("all");
    }

    @Test
    public void testUpdateLogpersistValues_all() {
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY,
                AbstractLogpersistPreferenceController.SELECT_LOGPERSIST_PROPERTY_SERVICE);
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY_BUFFER,
                "all");
        mController.updateLogpersistValues();
        verify(mListPreference, atLeastOnce()).setValue("all");
    }

    @Test
    public void testUpdateLogpersistValues_defaultSecurityKernel() {
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY,
                AbstractLogpersistPreferenceController.SELECT_LOGPERSIST_PROPERTY_SERVICE);
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY_BUFFER,
                "default,security,kernel");
        mController.updateLogpersistValues();
        verify(mListPreference, atLeastOnce()).setValue("default,security,kernel");
    }

    @Test
    public void testUpdateLogpersistValues_kernel() {
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY,
                AbstractLogpersistPreferenceController.SELECT_LOGPERSIST_PROPERTY_SERVICE);
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY_BUFFER,
                "kernel");
        mController.updateLogpersistValues();
        verify(mListPreference, atLeastOnce()).setValue("kernel");
    }

    @Test
    public void testUpdateLogpersistValues_mainSecuritykernel() {
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY,
                AbstractLogpersistPreferenceController.SELECT_LOGPERSIST_PROPERTY_SERVICE);
        SystemProperties.set(
                AbstractLogpersistPreferenceController.ACTUAL_LOGPERSIST_PROPERTY_BUFFER,
                "main,security,kernel");
        mController.updateLogpersistValues();
        verify(mListPreference, atLeastOnce()).setValue("all");
    }
}

