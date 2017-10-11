/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.providers.settings;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import org.junit.Test;

/**
* Performance tests for the SettingContentProvider.
*/
public class SettingsProviderPerformanceTest extends BaseSettingsProviderTest {
    private static final String LOG_TAG = "SettingsProviderPerformanceTest";

    private static final int ITERATION_COUNT = 100;

    private static final int MICRO_SECONDS_IN_MILLISECOND = 1000;

    private static final long MAX_AVERAGE_SET_AND_GET_SETTING_DURATION_MILLIS = 20;

    @Test
    public void testSetAndGetPerformanceForGlobalViaFrontEndApi() throws Exception {
        // Start with a clean slate.
        insertStringViaProviderApi(SETTING_TYPE_GLOBAL,
                FAKE_SETTING_NAME, FAKE_SETTING_VALUE, false);

        final long startTimeMicro = SystemClock.currentTimeMicro();

        try {
            for (int i = 0; i < ITERATION_COUNT; i++) {
                // Set the setting to its first value.
                updateStringViaProviderApiSetting(SETTING_TYPE_GLOBAL, FAKE_SETTING_NAME,
                        FAKE_SETTING_VALUE);

                // Make sure the setting changed.
                String firstValue = getStringViaFrontEndApiSetting(SETTING_TYPE_GLOBAL,
                        FAKE_SETTING_NAME, UserHandle.USER_SYSTEM);
                assertEquals("Setting value didn't change", FAKE_SETTING_VALUE, firstValue);

                // Set the setting to its second value.
                updateStringViaProviderApiSetting(SETTING_TYPE_GLOBAL, FAKE_SETTING_NAME,
                        FAKE_SETTING_VALUE_1);

                // Make sure the setting changed.
                String secondValue = getStringViaFrontEndApiSetting(SETTING_TYPE_GLOBAL,
                        FAKE_SETTING_NAME, UserHandle.USER_SYSTEM);
                assertEquals("Setting value didn't change", FAKE_SETTING_VALUE_1, secondValue);
            }
        } finally {
            // Clean up.
            deleteStringViaProviderApi(SETTING_TYPE_GLOBAL, FAKE_SETTING_NAME);
        }

        final long elapsedTimeMicro = SystemClock.currentTimeMicro() - startTimeMicro;

        final long averageTimePerIterationMillis = (long) ((((float) elapsedTimeMicro)
                / ITERATION_COUNT) / MICRO_SECONDS_IN_MILLISECOND);

        Log.i(LOG_TAG, "Average time to set and get setting via provider APIs: "
                + averageTimePerIterationMillis + " ms");

        assertTrue("Setting and getting a settings takes too long.", averageTimePerIterationMillis
                < MAX_AVERAGE_SET_AND_GET_SETTING_DURATION_MILLIS);
    }

    @Test
    public void testSetAndGetPerformanceForGlobalViaProviderApi() throws Exception {
        // Start with a clean slate.
        deleteStringViaProviderApi(SETTING_TYPE_GLOBAL, FAKE_SETTING_NAME);

        final long startTimeMicro = SystemClock.currentTimeMicro();

        try {
            for (int i = 0; i < ITERATION_COUNT; i++) {
                // Set the setting to its first value.
                setStringViaFrontEndApiSetting(SETTING_TYPE_GLOBAL, FAKE_SETTING_NAME,
                        FAKE_SETTING_VALUE, UserHandle.USER_SYSTEM);

                // Make sure the setting changed.
                String firstValue = getStringViaFrontEndApiSetting(SETTING_TYPE_GLOBAL,
                        FAKE_SETTING_NAME, UserHandle.USER_SYSTEM);
                assertEquals("Setting value didn't change", FAKE_SETTING_VALUE, firstValue);

                // Set the setting to its second value.
                setStringViaFrontEndApiSetting(SETTING_TYPE_GLOBAL, FAKE_SETTING_NAME,
                        FAKE_SETTING_VALUE_1, UserHandle.USER_SYSTEM);

                // Make sure the setting changed.
                String secondValue = getStringViaFrontEndApiSetting(SETTING_TYPE_GLOBAL,
                        FAKE_SETTING_NAME, UserHandle.USER_SYSTEM);
                assertEquals("Setting value didn't change", FAKE_SETTING_VALUE_1, secondValue);
            }
        } finally {
            // Clean up.
            deleteStringViaProviderApi(SETTING_TYPE_GLOBAL, FAKE_SETTING_NAME);
        }

        final long elapsedTimeMicro = SystemClock.currentTimeMicro() - startTimeMicro;

        final long averageTimePerIterationMillis = (long) ((((float) elapsedTimeMicro)
                / ITERATION_COUNT) / MICRO_SECONDS_IN_MILLISECOND);

        Log.i(LOG_TAG, "Average time to set and get setting via front-eng APIs: "
                + averageTimePerIterationMillis + " ms");

        assertTrue("Setting and getting a settings takes too long.", averageTimePerIterationMillis
                < MAX_AVERAGE_SET_AND_GET_SETTING_DURATION_MILLIS);
    }
}
