/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.timezonedetector;

import android.annotation.UserIdInt;

public final class ConfigInternalForTests {

    static final @UserIdInt int USER_ID = 9876;

    static final ConfigurationInternal CONFIG_USER_RESTRICTED_AUTO_DISABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(false)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    static final ConfigurationInternal CONFIG_USER_RESTRICTED_AUTO_ENABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(false)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(true)
                    .build();

    static final ConfigurationInternal CONFIG_AUTO_DETECT_NOT_SUPPORTED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(false)
                    .setGeoDetectionFeatureSupported(false)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    static final ConfigurationInternal CONFIG_AUTO_DISABLED_GEO_DISABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    static final ConfigurationInternal CONFIG_AUTO_ENABLED_GEO_DISABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    static final ConfigurationInternal CONFIG_AUTO_ENABLED_GEO_ENABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(true)
                    .build();
}
