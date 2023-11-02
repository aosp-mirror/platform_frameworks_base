/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_IS_CERTAIN;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_PRESENT;

import static com.android.server.timezonedetector.MetricsTimeZoneDetectorState.DETECTION_MODE_GEO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.UserIdInt;
import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;

import com.android.server.timezonedetector.MetricsTimeZoneDetectorState.MetricsTimeZoneSuggestion;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** Tests for {@link MetricsTimeZoneDetectorState}. */
public class MetricsTimeZoneDetectorStateTest {

    private static final @UserIdInt int ARBITRARY_USER_ID = 1;
    private static final @ElapsedRealtimeLong long ARBITRARY_ELAPSED_REALTIME_MILLIS = 1234L;
    private static final LocationTimeZoneAlgorithmStatus ARBITRARY_CERTAIN_STATUS =
            new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                    PROVIDER_STATUS_IS_CERTAIN, null, PROVIDER_STATUS_NOT_PRESENT, null);
    private static final String DEVICE_TIME_ZONE_ID = "DeviceTimeZoneId";

    private static final ManualTimeZoneSuggestion MANUAL_TIME_ZONE_SUGGESTION =
            new ManualTimeZoneSuggestion("ManualTimeZoneId");

    private static final TelephonyTimeZoneSuggestion TELEPHONY_TIME_ZONE_SUGGESTION =
            new TelephonyTimeZoneSuggestion.Builder(0)
                    .setZoneId("TelephonyZoneId")
                    .setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY)
                    .setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE)
                    .build();

    public static final GeolocationTimeZoneSuggestion GEOLOCATION_SUGGESTION_CERTAIN =
            GeolocationTimeZoneSuggestion.createCertainSuggestion(
                    ARBITRARY_ELAPSED_REALTIME_MILLIS,
                    Arrays.asList("GeoTimeZoneId1", "GeoTimeZoneId2"));

    private static final LocationAlgorithmEvent LOCATION_ALGORITHM_EVENT =
            new LocationAlgorithmEvent(ARBITRARY_CERTAIN_STATUS, GEOLOCATION_SUGGESTION_CERTAIN);

    private final OrdinalGenerator<String> mOrdinalGenerator =
            new OrdinalGenerator<>(Function.identity());

    @Test
    public void enhancedMetricsCollectionEnabled() {
        final boolean enhancedMetricsCollectionEnabled = true;
        ConfigurationInternal configurationInternal =
                createConfigurationInternal(enhancedMetricsCollectionEnabled);

        // Create the object.
        MetricsTimeZoneDetectorState metricsTimeZoneDetectorState =
                MetricsTimeZoneDetectorState.create(mOrdinalGenerator, configurationInternal,
                        DEVICE_TIME_ZONE_ID, MANUAL_TIME_ZONE_SUGGESTION,
                        TELEPHONY_TIME_ZONE_SUGGESTION, LOCATION_ALGORITHM_EVENT);

        // Assert the content.
        assertCommonConfiguration(configurationInternal, metricsTimeZoneDetectorState);

        assertEquals(DEVICE_TIME_ZONE_ID, metricsTimeZoneDetectorState.getDeviceTimeZoneId());
        MetricsTimeZoneSuggestion expectedManualSuggestion =
                MetricsTimeZoneSuggestion.createCertain(
                        new String[] { MANUAL_TIME_ZONE_SUGGESTION.getZoneId() },
                        new int[] { 1 });
        assertEquals(expectedManualSuggestion,
                metricsTimeZoneDetectorState.getLatestManualSuggestion());

        MetricsTimeZoneSuggestion expectedTelephonySuggestion =
                MetricsTimeZoneSuggestion.createCertain(
                        new String[] { TELEPHONY_TIME_ZONE_SUGGESTION.getZoneId() },
                        new int[] { 2 });
        assertEquals(expectedTelephonySuggestion,
                metricsTimeZoneDetectorState.getLatestTelephonySuggestion());

        List<String> expectedZoneIds = LOCATION_ALGORITHM_EVENT.getSuggestion().getZoneIds();
        MetricsTimeZoneSuggestion expectedGeoSuggestion =
                MetricsTimeZoneSuggestion.createCertain(
                        expectedZoneIds.toArray(new String[0]),
                        new int[] { 3, 4 });
        assertEquals(expectedGeoSuggestion,
                metricsTimeZoneDetectorState.getLatestGeolocationSuggestion());
    }

    @Test
    public void enhancedMetricsCollectionDisabled() {
        final boolean enhancedMetricsCollectionEnabled = false;
        ConfigurationInternal configurationInternal =
                createConfigurationInternal(enhancedMetricsCollectionEnabled);

        // Create the object.
        MetricsTimeZoneDetectorState metricsTimeZoneDetectorState =
                MetricsTimeZoneDetectorState.create(mOrdinalGenerator, configurationInternal,
                        DEVICE_TIME_ZONE_ID, MANUAL_TIME_ZONE_SUGGESTION,
                        TELEPHONY_TIME_ZONE_SUGGESTION, LOCATION_ALGORITHM_EVENT);

        // Assert the content.
        assertCommonConfiguration(configurationInternal, metricsTimeZoneDetectorState);

        // When enhancedMetricsCollectionEnabled == false, no time zone IDs should be included.
        assertNull(metricsTimeZoneDetectorState.getDeviceTimeZoneId());
        final String[] omittedZoneIds = null;

        MetricsTimeZoneSuggestion expectedManualSuggestion =
                MetricsTimeZoneSuggestion.createCertain(
                        omittedZoneIds,
                        new int[] { 1 });
        assertEquals(expectedManualSuggestion,
                metricsTimeZoneDetectorState.getLatestManualSuggestion());

        MetricsTimeZoneSuggestion expectedTelephonySuggestion =
                MetricsTimeZoneSuggestion.createCertain(
                        omittedZoneIds,
                        new int[] { 2 });
        assertEquals(expectedTelephonySuggestion,
                metricsTimeZoneDetectorState.getLatestTelephonySuggestion());

        MetricsTimeZoneSuggestion expectedGeoSuggestion =
                MetricsTimeZoneSuggestion.createCertain(
                        omittedZoneIds,
                        new int[] { 3, 4 });
        assertEquals(expectedGeoSuggestion,
                metricsTimeZoneDetectorState.getLatestGeolocationSuggestion());
    }

    private static void assertCommonConfiguration(ConfigurationInternal configurationInternal,
            MetricsTimeZoneDetectorState metricsTimeZoneDetectorState) {
        assertEquals(configurationInternal.isTelephonyDetectionSupported(),
                metricsTimeZoneDetectorState.isTelephonyDetectionSupported());
        assertEquals(configurationInternal.isGeoDetectionSupported(),
                metricsTimeZoneDetectorState.isGeoDetectionSupported());
        assertEquals(configurationInternal.isTelephonyFallbackSupported(),
                metricsTimeZoneDetectorState.isTelephonyTimeZoneFallbackSupported());
        assertEquals(configurationInternal.getGeoDetectionRunInBackgroundEnabledSetting(),
                metricsTimeZoneDetectorState.getGeoDetectionRunInBackgroundEnabled());
        assertEquals(configurationInternal.isEnhancedMetricsCollectionEnabled(),
                metricsTimeZoneDetectorState.isEnhancedMetricsCollectionEnabled());
        assertEquals(configurationInternal.getAutoDetectionEnabledSetting(),
                metricsTimeZoneDetectorState.getAutoDetectionEnabledSetting());
        assertEquals(configurationInternal.getLocationEnabledSetting(),
                metricsTimeZoneDetectorState.getUserLocationEnabledSetting());
        assertEquals(configurationInternal.getGeoDetectionEnabledSetting(),
                metricsTimeZoneDetectorState.getGeoDetectionEnabledSetting());
        assertEquals(0, metricsTimeZoneDetectorState.getDeviceTimeZoneIdOrdinal());
        assertEquals(DETECTION_MODE_GEO, metricsTimeZoneDetectorState.getDetectionMode());
    }

    private static ConfigurationInternal createConfigurationInternal(
            boolean enhancedMetricsCollectionEnabled) {
        return new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
                .setTelephonyFallbackSupported(false)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setEnhancedMetricsCollectionEnabled(enhancedMetricsCollectionEnabled)
                .setAutoDetectionEnabledSetting(true)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(true)
                .build();
    }
}
