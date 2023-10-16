/*
 * Copyright 2022 The Android Open Source Project
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
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_READY;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_NOT_APPLICABLE;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_OK;

import static com.android.server.timezonedetector.ShellCommandTestSupport.createShellCommandWithArgsAndOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.os.ShellCommand;
import android.service.timezone.TimeZoneProviderStatus;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LocationAlgorithmEventTest {

    public static final TimeZoneProviderStatus ARBITRARY_PROVIDER_STATUS =
            new TimeZoneProviderStatus.Builder()
                    .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                    .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_NOT_APPLICABLE)
                    .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                    .build();

    public static final LocationTimeZoneAlgorithmStatus ARBITRARY_LOCATION_ALGORITHM_STATUS =
            new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                    PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_STATUS,
                    PROVIDER_STATUS_NOT_PRESENT, null);

    @Test
    public void testEquals() {
        GeolocationTimeZoneSuggestion suggestion1 =
                GeolocationTimeZoneSuggestion.createUncertainSuggestion(1111L);
        LocationTimeZoneAlgorithmStatus status1 = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_NOT_PRESENT, null, PROVIDER_STATUS_NOT_PRESENT, null);
        LocationAlgorithmEvent event1v1 = new LocationAlgorithmEvent(status1, suggestion1);
        assertEqualsAndHashCode(event1v1, event1v1);

        LocationAlgorithmEvent event1v2 = new LocationAlgorithmEvent(status1, suggestion1);
        assertEqualsAndHashCode(event1v1, event1v2);

        GeolocationTimeZoneSuggestion suggestion2 =
                GeolocationTimeZoneSuggestion.createUncertainSuggestion(2222L);
        LocationAlgorithmEvent event2 = new LocationAlgorithmEvent(status1, suggestion2);
        assertNotEquals(event1v1, event2);

        LocationTimeZoneAlgorithmStatus status2 = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_NOT_PRESENT, null, PROVIDER_STATUS_NOT_READY, null);
        LocationAlgorithmEvent event3 = new LocationAlgorithmEvent(status2, suggestion1);
        assertNotEquals(event1v1, event3);

        // DebugInfo must not be considered in equals().
        event1v1.addDebugInfo("Debug info 1");
        event1v2.addDebugInfo("Debug info 2");
        assertEquals(event1v1, event1v2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noStatus() {
        GeolocationTimeZoneSuggestion suggestion =
                GeolocationTimeZoneSuggestion.createUncertainSuggestion(1111L);
        ShellCommand testShellCommand =
                createShellCommandWithArgsAndOptions(
                        Arrays.asList("--suggestion", suggestion.toString()));

        LocationAlgorithmEvent.parseCommandLineArg(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_noSuggestion() {
        GeolocationTimeZoneSuggestion suggestion = null;
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(
                ARBITRARY_LOCATION_ALGORITHM_STATUS, suggestion);
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                Arrays.asList("--status", event.getAlgorithmStatus().toString()));

        assertEquals(event, LocationAlgorithmEvent.parseCommandLineArg(testShellCommand));
    }

    @Test
    public void testParseCommandLineArg_suggestionUncertain() {
        GeolocationTimeZoneSuggestion suggestion =
                GeolocationTimeZoneSuggestion.createUncertainSuggestion(1111L);
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(
                ARBITRARY_LOCATION_ALGORITHM_STATUS, suggestion);
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                Arrays.asList("--status", event.getAlgorithmStatus().toString(),
                        "--suggestion", "UNCERTAIN"));

        LocationAlgorithmEvent parsedEvent =
                LocationAlgorithmEvent.parseCommandLineArg(testShellCommand);
        assertEquals(event.getAlgorithmStatus(), parsedEvent.getAlgorithmStatus());
        assertEquals(event.getSuggestion().getZoneIds(), parsedEvent.getSuggestion().getZoneIds());
    }

    @Test
    public void testParseCommandLineArg_suggestionEmpty() {
        GeolocationTimeZoneSuggestion suggestion =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(
                        1111L, Collections.emptyList());
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(
                ARBITRARY_LOCATION_ALGORITHM_STATUS, suggestion);
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                Arrays.asList("--status", event.getAlgorithmStatus().toString(),
                        "--suggestion", "EMPTY"));

        LocationAlgorithmEvent parsedEvent =
                LocationAlgorithmEvent.parseCommandLineArg(testShellCommand);
        assertEquals(event.getAlgorithmStatus(), parsedEvent.getAlgorithmStatus());
        assertEquals(event.getSuggestion().getZoneIds(), parsedEvent.getSuggestion().getZoneIds());
    }

    @Test
    public void testParseCommandLineArg_suggestionPresent() {
        GeolocationTimeZoneSuggestion suggestion =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(
                        1111L, Arrays.asList("Europe/London", "Europe/Paris"));
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(
                ARBITRARY_LOCATION_ALGORITHM_STATUS, suggestion);
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                Arrays.asList("--status", event.getAlgorithmStatus().toString(),
                        "--suggestion", "Europe/London,Europe/Paris"));

        LocationAlgorithmEvent parsedEvent =
                LocationAlgorithmEvent.parseCommandLineArg(testShellCommand);
        assertEquals(event.getAlgorithmStatus(), parsedEvent.getAlgorithmStatus());
        assertEquals(event.getSuggestion().getZoneIds(), parsedEvent.getSuggestion().getZoneIds());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        GeolocationTimeZoneSuggestion suggestion =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(
                        1111L, Arrays.asList("Europe/London", "Europe/Paris"));
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(
                ARBITRARY_LOCATION_ALGORITHM_STATUS, suggestion);
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                Arrays.asList("--status", event.getAlgorithmStatus().toString(),
                        "--suggestion", "Europe/London,Europe/Paris", "--bad_arg"));
        LocationAlgorithmEvent.parseCommandLineArg(testShellCommand);
    }

    private static void assertEqualsAndHashCode(Object one, Object two) {
        assertEquals(one, two);
        assertEquals(two, one);
        assertEquals(one.hashCode(), two.hashCode());
    }
}
