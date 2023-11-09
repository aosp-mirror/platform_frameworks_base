/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.timezonedetector.location;

import android.annotation.UserIdInt;

import com.android.server.timezonedetector.ConfigurationInternal;

/** Shared test support code for this package. */
final class TestSupport {
    static final @UserIdInt int USER1_ID = 9999;

    static final ConfigurationInternal USER1_CONFIG_GEO_DETECTION_ENABLED =
            createUserConfig(USER1_ID, true);

    static final ConfigurationInternal USER1_CONFIG_GEO_DETECTION_DISABLED =
            createUserConfig(USER1_ID, false);

    static final @UserIdInt int USER2_ID = 1234567890;

    static final ConfigurationInternal USER2_CONFIG_GEO_DETECTION_ENABLED =
            createUserConfig(USER2_ID, true);

    static final ConfigurationInternal USER2_CONFIG_GEO_DETECTION_DISABLED =
            createUserConfig(USER2_ID, false);

    private TestSupport() {
    }

    private static ConfigurationInternal createUserConfig(
            @UserIdInt int userId, boolean geoDetectionEnabledSetting) {
        return new ConfigurationInternal.Builder()
                .setUserId(userId)
                .setUserConfigAllowed(true)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
                .setTelephonyFallbackSupported(false)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setEnhancedMetricsCollectionEnabled(false)
                .setAutoDetectionEnabledSetting(true)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(geoDetectionEnabledSetting)
                .build();
    }
}
