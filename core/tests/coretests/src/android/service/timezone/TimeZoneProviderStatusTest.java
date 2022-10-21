/*
 * Copyright 2021 The Android Open Source Project
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

package android.service.timezone;

import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_WORKING;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_FAILED;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_WORKING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TimeZoneProviderStatusTest {

    @Test
    public void testStatusValidation() {
        TimeZoneProviderStatus status = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionStatus(DEPENDENCY_STATUS_WORKING)
                .setConnectivityStatus(DEPENDENCY_STATUS_WORKING)
                .setTimeZoneResolutionStatus(DEPENDENCY_STATUS_WORKING)
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> new TimeZoneProviderStatus.Builder(status)
                        .setLocationDetectionStatus(-1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> new TimeZoneProviderStatus.Builder(status)
                        .setConnectivityStatus(-1)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> new TimeZoneProviderStatus.Builder(status)
                        .setTimeZoneResolutionStatus(-1)
                        .build());
    }

    @Test
    public void testEqualsAndHashcode() {
        TimeZoneProviderStatus status1_1 = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionStatus(DEPENDENCY_STATUS_WORKING)
                .setConnectivityStatus(DEPENDENCY_STATUS_WORKING)
                .setTimeZoneResolutionStatus(OPERATION_STATUS_WORKING)
                .build();
        assertEqualsAndHashcode(status1_1, status1_1);
        assertNotEquals(status1_1, null);

        {
            TimeZoneProviderStatus status1_2 =
                    new TimeZoneProviderStatus.Builder(status1_1).build();
            assertEqualsAndHashcode(status1_1, status1_2);
            assertNotSame(status1_1, status1_2);
        }

        {
            TimeZoneProviderStatus status2 = new TimeZoneProviderStatus.Builder(status1_1)
                    .setLocationDetectionStatus(DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT)
                    .build();
            assertNotEquals(status1_1, status2);
        }

        {
            TimeZoneProviderStatus status2 = new TimeZoneProviderStatus.Builder(status1_1)
                    .setConnectivityStatus(DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT)
                    .build();
            assertNotEquals(status1_1, status2);
        }

        {
            TimeZoneProviderStatus status2 = new TimeZoneProviderStatus.Builder(status1_1)
                    .setTimeZoneResolutionStatus(OPERATION_STATUS_FAILED)
                    .build();
            assertNotEquals(status1_1, status2);
        }
    }

    private static void assertEqualsAndHashcode(Object one, Object two) {
        assertEquals(one, two);
        assertEquals(two, one);
        assertEquals(one.hashCode(), two.hashCode());
    }

    @Test
    public void testParcelable() {
        TimeZoneProviderStatus status = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionStatus(DEPENDENCY_STATUS_WORKING)
                .setConnectivityStatus(DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT)
                .setTimeZoneResolutionStatus(OPERATION_STATUS_FAILED)
                .build();
        assertRoundTripParcelable(status);
    }
}
