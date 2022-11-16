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

import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_OK;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Non-SDK tests. See CTS for SDK API tests. */
public class TimeZoneProviderStatusTest {

    @Test
    public void parseProviderStatus() {
        TimeZoneProviderStatus status = new TimeZoneProviderStatus.Builder()
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                .build();

        assertEquals(status, TimeZoneProviderStatus.parseProviderStatus(status.toString()));
    }
}
