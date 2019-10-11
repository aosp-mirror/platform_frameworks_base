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
 * limitations under the License.
 */
package com.android.settingslib.core.instrumentation;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_SETTINGS_PREFERENCE_CHANGE;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SharedPreferenceLoggerTest {

    private static final String TEST_TAG = "tag";
    private static final String TEST_KEY = "key";
    private static final String TEST_TAGGED_KEY = TEST_TAG + "/" + TEST_KEY;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mContext;

    @Mock
    private MetricsFeatureProvider mMetricsFeature;
    private SharedPreferencesLogger mSharedPrefLogger;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        mSharedPrefLogger = new SharedPreferencesLogger(mContext, TEST_TAG, mMetricsFeature);
    }

    @Test
    public void putInt_shouldNotLogInitialPut() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        editor.putInt(TEST_KEY, 1);
        editor.putInt(TEST_KEY, 1);
        editor.putInt(TEST_KEY, 1);
        editor.putInt(TEST_KEY, 2);
        editor.putInt(TEST_KEY, 2);
        editor.putInt(TEST_KEY, 2);
        editor.putInt(TEST_KEY, 2);

        verify(mMetricsFeature, times(6)).action(eq(SettingsEnums.PAGE_UNKNOWN),
                eq(SettingsEnums.ACTION_SETTINGS_PREFERENCE_CHANGE),
                eq(SettingsEnums.PAGE_UNKNOWN),
                eq(TEST_TAGGED_KEY),
                anyInt());
    }

    @Test
    public void putBoolean_shouldNotLogInitialPut() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        editor.putBoolean(TEST_KEY, true);
        editor.putBoolean(TEST_KEY, true);
        editor.putBoolean(TEST_KEY, false);
        editor.putBoolean(TEST_KEY, false);
        editor.putBoolean(TEST_KEY, false);


        verify(mMetricsFeature).action(SettingsEnums.PAGE_UNKNOWN,
                SettingsEnums.ACTION_SETTINGS_PREFERENCE_CHANGE,
                SettingsEnums.PAGE_UNKNOWN,
                TEST_TAGGED_KEY,
                1);
        verify(mMetricsFeature, times(3)).action(SettingsEnums.PAGE_UNKNOWN,
                SettingsEnums.ACTION_SETTINGS_PREFERENCE_CHANGE,
                SettingsEnums.PAGE_UNKNOWN,
                TEST_TAGGED_KEY,
                0);
    }

    @Test
    public void putLong_shouldNotLogInitialPut() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, 2);

        verify(mMetricsFeature, times(4)).action(eq(SettingsEnums.PAGE_UNKNOWN),
                eq(SettingsEnums.ACTION_SETTINGS_PREFERENCE_CHANGE),
                eq(SettingsEnums.PAGE_UNKNOWN),
                eq(TEST_TAGGED_KEY),
                anyInt());
    }

    @Test
    public void putLong_biggerThanIntMax_shouldLogIntMax() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        final long veryBigNumber = 500L + Integer.MAX_VALUE;
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, veryBigNumber);

        verify(mMetricsFeature).action(SettingsEnums.PAGE_UNKNOWN,
                SettingsEnums.ACTION_SETTINGS_PREFERENCE_CHANGE,
                SettingsEnums.PAGE_UNKNOWN,
                TEST_TAGGED_KEY,
                Integer.MAX_VALUE);
    }

    @Test
    public void putLong_smallerThanIntMin_shouldLogIntMin() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        final long veryNegativeNumber = -500L + Integer.MIN_VALUE;
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, veryNegativeNumber);

        verify(mMetricsFeature).action(SettingsEnums.PAGE_UNKNOWN,
                SettingsEnums.ACTION_SETTINGS_PREFERENCE_CHANGE,
                SettingsEnums.PAGE_UNKNOWN,
                TEST_TAGGED_KEY, Integer.MIN_VALUE);
    }

    @Test
    public void putFloat_shouldNotLogInitialPut() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        editor.putFloat(TEST_KEY, 1);
        editor.putFloat(TEST_KEY, 1);
        editor.putFloat(TEST_KEY, 1);
        editor.putFloat(TEST_KEY, 1);
        editor.putFloat(TEST_KEY, 2);

        verify(mMetricsFeature, times(4)).action(eq(SettingsEnums.PAGE_UNKNOWN),
                eq(SettingsEnums.ACTION_SETTINGS_PREFERENCE_CHANGE),
                eq(SettingsEnums.PAGE_UNKNOWN),
                eq(TEST_TAGGED_KEY),
                anyInt());
    }

    @Test
    public void logPackage_shouldUseLogPackageApi() {
        mSharedPrefLogger.logPackageName("key", "com.android.settings");
        verify(mMetricsFeature).action(SettingsEnums.PAGE_UNKNOWN,
                ACTION_SETTINGS_PREFERENCE_CHANGE,
                SettingsEnums.PAGE_UNKNOWN,
                "tag/key:com.android.settings",
                0);
    }
}
