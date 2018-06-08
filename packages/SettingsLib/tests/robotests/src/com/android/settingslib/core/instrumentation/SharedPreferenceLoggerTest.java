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
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_FLOAT_VALUE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_SETTINGS_PREFERENCE_CHANGE_NAME;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class SharedPreferenceLoggerTest {

    private static final String TEST_TAG = "tag";
    private static final String TEST_KEY = "key";

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mContext;

    private ArgumentMatcher<Pair<Integer, Object>> mNamePairMatcher;
    @Mock
    private MetricsFeatureProvider mMetricsFeature;
    private SharedPreferencesLogger mSharedPrefLogger;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        mSharedPrefLogger = new SharedPreferencesLogger(mContext, TEST_TAG, mMetricsFeature);
        mNamePairMatcher = pairMatches(FIELD_SETTINGS_PREFERENCE_CHANGE_NAME, String.class);
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

        verify(mMetricsFeature, times(6)).action(any(Context.class), anyInt(),
                argThat(mNamePairMatcher),
                argThat(pairMatches(FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE, Integer.class)));
    }

    @Test
    public void putBoolean_shouldNotLogInitialPut() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        editor.putBoolean(TEST_KEY, true);
        editor.putBoolean(TEST_KEY, true);
        editor.putBoolean(TEST_KEY, false);
        editor.putBoolean(TEST_KEY, false);
        editor.putBoolean(TEST_KEY, false);


        verify(mMetricsFeature).action(any(Context.class), anyInt(),
                argThat(mNamePairMatcher),
                argThat(pairMatches(FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE, true)));
        verify(mMetricsFeature, times(3)).action(any(Context.class), anyInt(),
                argThat(mNamePairMatcher),
                argThat(pairMatches(FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE, false)));
    }

    @Test
    public void putLong_shouldNotLogInitialPut() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, 2);

        verify(mMetricsFeature, times(4)).action(any(Context.class), anyInt(),
                argThat(mNamePairMatcher),
                argThat(pairMatches(FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE, Integer.class)));
    }

    @Test
    public void putLong_biggerThanIntMax_shouldLogIntMax() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        final long veryBigNumber = 500L + Integer.MAX_VALUE;
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, veryBigNumber);

        verify(mMetricsFeature).action(any(Context.class), anyInt(),
                argThat(mNamePairMatcher),
                argThat(pairMatches(
                        FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE, Integer.MAX_VALUE)));
    }

    @Test
    public void putLong_smallerThanIntMin_shouldLogIntMin() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        final long veryNegativeNumber = -500L + Integer.MIN_VALUE;
        editor.putLong(TEST_KEY, 1);
        editor.putLong(TEST_KEY, veryNegativeNumber);

        verify(mMetricsFeature).action(any(Context.class), anyInt(),
                argThat(mNamePairMatcher),
                argThat(pairMatches(
                        FIELD_SETTINGS_PREFERENCE_CHANGE_INT_VALUE, Integer.MIN_VALUE)));
    }

    @Test
    public void putFloat_shouldNotLogInitialPut() {
        final SharedPreferences.Editor editor = mSharedPrefLogger.edit();
        editor.putFloat(TEST_KEY, 1);
        editor.putFloat(TEST_KEY, 1);
        editor.putFloat(TEST_KEY, 1);
        editor.putFloat(TEST_KEY, 1);
        editor.putFloat(TEST_KEY, 2);

        verify(mMetricsFeature, times(4)).action(any(Context.class), anyInt(),
                argThat(mNamePairMatcher),
                argThat(pairMatches(FIELD_SETTINGS_PREFERENCE_CHANGE_FLOAT_VALUE, Float.class)));
    }

    @Test
    public void logPackage_shouldUseLogPackageApi() {
        mSharedPrefLogger.logPackageName("key", "com.android.settings");
        verify(mMetricsFeature).action(any(Context.class),
                eq(ACTION_SETTINGS_PREFERENCE_CHANGE),
                eq("com.android.settings"),
                any(Pair.class));
    }

    private ArgumentMatcher<Pair<Integer, Object>> pairMatches(int tag, Class clazz) {
        return pair -> pair.first == tag && isInstanceOfType(pair.second, clazz);
    }

    private ArgumentMatcher<Pair<Integer, Object>> pairMatches(int tag, boolean bool) {
        return pair -> pair.first == tag
                && isInstanceOfType(pair.second, Integer.class)
                && pair.second.equals((bool ? 1 : 0));
    }

    private ArgumentMatcher<Pair<Integer, Object>> pairMatches(int tag, int val) {
        return pair -> pair.first == tag
                && isInstanceOfType(pair.second, Integer.class)
                && pair.second.equals(val);
    }

    /** Returns true if the instance is assignable to the type Clazz. */
    private static boolean isInstanceOfType(Object instance, Class<?> clazz) {
        return clazz.isInstance(instance);
    }
}
