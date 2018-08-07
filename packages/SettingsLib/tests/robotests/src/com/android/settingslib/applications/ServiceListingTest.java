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

package com.android.settingslib.applications;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.provider.Settings;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class ServiceListingTest {

    private static final String TEST_SETTING = "testSetting";
    private static final String TEST_INTENT = "com.example.intent";

    private ServiceListing mServiceListing;

    @Before
    public void setUp() {
        mServiceListing = new ServiceListing.Builder(RuntimeEnvironment.application)
                .setTag("testTag")
                .setSetting(TEST_SETTING)
                .setNoun("testNoun")
                .setIntentAction(TEST_INTENT)
                .setPermission("testPermission")
                .build();
    }

    @Test
    public void testCallback() {
        ServiceListing.Callback callback = mock(ServiceListing.Callback.class);
        mServiceListing.addCallback(callback);
        mServiceListing.reload();
        verify(callback, times(1)).onServicesReloaded(anyList());
        mServiceListing.removeCallback(callback);
        mServiceListing.reload();
        verify(callback, times(1)).onServicesReloaded(anyList());
    }

    @Test
    public void testSaveLoad() {
        ComponentName testComponent1 = new ComponentName("testPackage1", "testClass1");
        ComponentName testComponent2 = new ComponentName("testPackage2", "testClass2");
        Settings.Secure.putString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING,
                testComponent1.flattenToString() + ":" + testComponent2.flattenToString());

        mServiceListing.reload();

        assertThat(mServiceListing.isEnabled(testComponent1)).isTrue();
        assertThat(mServiceListing.isEnabled(testComponent2)).isTrue();
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent1.flattenToString());
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent2.flattenToString());

        mServiceListing.setEnabled(testComponent1, false);

        assertThat(mServiceListing.isEnabled(testComponent1)).isFalse();
        assertThat(mServiceListing.isEnabled(testComponent2)).isTrue();
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).doesNotContain(testComponent1.flattenToString());
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent2.flattenToString());

        mServiceListing.setEnabled(testComponent1, true);

        assertThat(mServiceListing.isEnabled(testComponent1)).isTrue();
        assertThat(mServiceListing.isEnabled(testComponent2)).isTrue();
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent1.flattenToString());
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent2.flattenToString());
    }
}
