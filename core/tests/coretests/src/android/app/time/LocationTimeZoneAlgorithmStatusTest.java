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

package android.app.time;

import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_NOT_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_IS_CERTAIN;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_IS_UNCERTAIN;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_PRESENT;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_READY;
import static android.app.time.ParcelableTestSupport.assertEqualsAndHashCode;
import static android.app.time.ParcelableTestSupport.assertRoundTripParcelable;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_OK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import android.app.time.LocationTimeZoneAlgorithmStatus.ProviderStatus;
import android.service.timezone.TimeZoneProviderStatus;

import org.junit.Test;

public class LocationTimeZoneAlgorithmStatusTest {

    private static final TimeZoneProviderStatus ARBITRARY_PROVIDER_RUNNING_STATUS =
            new TimeZoneProviderStatus.Builder()
                    .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                    .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                    .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                    .build();

    @Test
    public void testConstructorValidation() {
        // Sample some invalid cases

        // There can't be a reported provider status if the algorithm isn't running.
        new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS,
                PROVIDER_STATUS_IS_UNCERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS);
        assertThrows(IllegalArgumentException.class,
                () -> new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
                        PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS,
                        PROVIDER_STATUS_IS_UNCERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS));

        new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS,
                PROVIDER_STATUS_NOT_PRESENT, null);
        assertThrows(IllegalArgumentException.class,
                () -> new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
                        PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS,
                        PROVIDER_STATUS_NOT_PRESENT, null));

        new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_NOT_PRESENT, null,
                PROVIDER_STATUS_IS_UNCERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS);
        assertThrows(IllegalArgumentException.class,
                () -> new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
                        PROVIDER_STATUS_NOT_PRESENT, null,
                        PROVIDER_STATUS_IS_UNCERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS));

        // No reported provider status expected if the associated provider isn't ready / present.
        new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_NOT_PRESENT, null,
                PROVIDER_STATUS_NOT_PRESENT, null);
        assertThrows(IllegalArgumentException.class,
                () -> new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                        PROVIDER_STATUS_NOT_PRESENT, ARBITRARY_PROVIDER_RUNNING_STATUS,
                        PROVIDER_STATUS_NOT_PRESENT, null));
        new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_NOT_READY, null,
                PROVIDER_STATUS_NOT_PRESENT, null);
        assertThrows(IllegalArgumentException.class,
                () -> new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                        PROVIDER_STATUS_NOT_READY, null,
                        PROVIDER_STATUS_NOT_PRESENT, ARBITRARY_PROVIDER_RUNNING_STATUS));
    }

    @Test
    public void testEquals() {
        LocationTimeZoneAlgorithmStatus one = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS,
                PROVIDER_STATUS_NOT_PRESENT, null);
        assertEqualsAndHashCode(one, one);

        {
            LocationTimeZoneAlgorithmStatus two = new LocationTimeZoneAlgorithmStatus(
                    DETECTION_ALGORITHM_STATUS_RUNNING,
                    PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS,
                    PROVIDER_STATUS_NOT_PRESENT, null);
            assertEqualsAndHashCode(one, two);
        }

        {
            LocationTimeZoneAlgorithmStatus three = new LocationTimeZoneAlgorithmStatus(
                    DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
                    PROVIDER_STATUS_NOT_READY, null,
                    PROVIDER_STATUS_NOT_PRESENT, null);
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }
    }

    @Test
    public void testParcelable() {
        // Primary provider only.
        {
            LocationTimeZoneAlgorithmStatus locationAlgorithmStatus =
                    new LocationTimeZoneAlgorithmStatus(
                            DETECTION_ALGORITHM_STATUS_RUNNING,
                            PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS,
                            PROVIDER_STATUS_NOT_PRESENT, null);
            assertRoundTripParcelable(locationAlgorithmStatus);
        }

        // Secondary provider only
        {
            LocationTimeZoneAlgorithmStatus locationAlgorithmStatus =
                    new LocationTimeZoneAlgorithmStatus(
                            DETECTION_ALGORITHM_STATUS_RUNNING,
                            PROVIDER_STATUS_NOT_PRESENT, null,
                            PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS);
            assertRoundTripParcelable(locationAlgorithmStatus);
        }

        // Algorithm not running.
        {
            LocationTimeZoneAlgorithmStatus locationAlgorithmStatus =
                    new LocationTimeZoneAlgorithmStatus(
                            DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
                            PROVIDER_STATUS_NOT_PRESENT, null,
                            PROVIDER_STATUS_NOT_PRESENT, null);
            assertRoundTripParcelable(locationAlgorithmStatus);
        }
    }

    @Test
    public void testRequireValidProviderStatus() {
        for (@ProviderStatus int status = PROVIDER_STATUS_NOT_PRESENT;
                status <= PROVIDER_STATUS_IS_UNCERTAIN; status++) {
            assertEquals(status,
                    LocationTimeZoneAlgorithmStatus.requireValidProviderStatus(status));
        }

        assertThrows(IllegalArgumentException.class,
                () -> LocationTimeZoneAlgorithmStatus.requireValidProviderStatus(
                        PROVIDER_STATUS_NOT_PRESENT - 1));
        assertThrows(IllegalArgumentException.class,
                () -> LocationTimeZoneAlgorithmStatus.requireValidProviderStatus(
                        PROVIDER_STATUS_IS_UNCERTAIN + 1));
    }

    @Test
    public void testFormatAndParseProviderStatus() {
        for (@ProviderStatus int status = PROVIDER_STATUS_NOT_PRESENT;
                status <= PROVIDER_STATUS_IS_UNCERTAIN; status++) {
            assertEquals(status, LocationTimeZoneAlgorithmStatus.providerStatusFromString(
                    LocationTimeZoneAlgorithmStatus.providerStatusToString(status)));
        }

        assertThrows(IllegalArgumentException.class,
                () -> LocationTimeZoneAlgorithmStatus.providerStatusToString(
                        PROVIDER_STATUS_NOT_PRESENT - 1));
        assertThrows(IllegalArgumentException.class,
                () -> LocationTimeZoneAlgorithmStatus.providerStatusToString(
                        PROVIDER_STATUS_IS_UNCERTAIN + 1));
        assertThrows(IllegalArgumentException.class,
                () -> LocationTimeZoneAlgorithmStatus.providerStatusFromString(null));
        assertThrows(IllegalArgumentException.class,
                () -> LocationTimeZoneAlgorithmStatus.providerStatusFromString(""));
        assertThrows(IllegalArgumentException.class,
                () -> LocationTimeZoneAlgorithmStatus.providerStatusFromString("FOO"));
    }

    @Test
    public void testParseCommandlineArg_noNullReportedStatuses() {
        LocationTimeZoneAlgorithmStatus status = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_IS_CERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS,
                PROVIDER_STATUS_IS_UNCERTAIN, ARBITRARY_PROVIDER_RUNNING_STATUS);
        assertEquals(status,
                LocationTimeZoneAlgorithmStatus.parseCommandlineArg(status.toString()));
    }

    @Test
    public void testParseCommandlineArg_withNullReportedStatuses() {
        LocationTimeZoneAlgorithmStatus status = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_IS_CERTAIN, null,
                PROVIDER_STATUS_IS_UNCERTAIN, null);
        assertEquals(status,
                LocationTimeZoneAlgorithmStatus.parseCommandlineArg(status.toString()));
    }
}
