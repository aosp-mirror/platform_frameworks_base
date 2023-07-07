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

package com.android.server.timezonedetector.location;

import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_FAILED;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_OK;

import static com.google.common.truth.Truth.assertWithMessage;

import android.platform.test.annotations.Presubmit;
import android.service.timezone.TimeZoneProviderEvent;
import android.service.timezone.TimeZoneProviderStatus;
import android.service.timezone.TimeZoneProviderSuggestion;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/** Tests for {@link ZoneInfoDbTimeZoneProviderEventPreProcessor}. */
@Presubmit
public class ZoneInfoDbTimeZoneProviderEventPreProcessorTest {

    private static final long ARBITRARY_TIME_MILLIS = 11223344;

    private final ZoneInfoDbTimeZoneProviderEventPreProcessor mPreProcessor =
            new ZoneInfoDbTimeZoneProviderEventPreProcessor();

    @Test
    public void timeZoneIdsFromZoneInfoDbAreValid() {
        for (String timeZone : TimeZone.getAvailableIDs()) {
            TimeZoneProviderEvent event = timeZoneProviderEvent(timeZone);
            assertWithMessage("Time zone %s should be supported", timeZone)
                    .that(mPreProcessor.preProcess(event)).isEqualTo(event);
        }
    }

    @Test
    public void eventWithNonExistingZones_areMappedToUncertainEvent() {
        List<String> nonExistingTimeZones = Arrays.asList(
                "SystemV/HST10", "Atlantic/Atlantis", "EUROPE/LONDON", "Etc/GMT-5:30");

        for (String timeZone : nonExistingTimeZones) {
            TimeZoneProviderEvent event = timeZoneProviderEvent(timeZone);

            TimeZoneProviderStatus expectedProviderStatus =
                    new TimeZoneProviderStatus.Builder(event.getTimeZoneProviderStatus())
                            .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_FAILED)
                            .build();

            TimeZoneProviderEvent expectedResultEvent =
                    TimeZoneProviderEvent.createUncertainEvent(
                            event.getCreationElapsedMillis(), expectedProviderStatus);
            assertWithMessage(timeZone + " is not a valid time zone")
                    .that(mPreProcessor.preProcess(event))
                    .isEqualTo(expectedResultEvent);
        }
    }

    private static TimeZoneProviderEvent timeZoneProviderEvent(String... timeZoneIds) {
        TimeZoneProviderStatus providerStatus = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                .build();
        TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                .setTimeZoneIds(Arrays.asList(timeZoneIds))
                .setElapsedRealtimeMillis(ARBITRARY_TIME_MILLIS)
                .build();
        return TimeZoneProviderEvent.createSuggestionEvent(
                ARBITRARY_TIME_MILLIS, suggestion, providerStatus);
    }

}
