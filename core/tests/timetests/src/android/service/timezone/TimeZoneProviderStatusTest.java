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

import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_UNKNOWN;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_FAILED;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_UNKNOWN;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.service.timezone.TimeZoneProviderStatus.DependencyStatus;
import android.service.timezone.TimeZoneProviderStatus.OperationStatus;

import androidx.test.filters.SmallTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Non-SDK tests. See CTS for SDK API tests. */
@RunWith(JUnitParamsRunner.class)
@SmallTest
@Presubmit
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

    @Test
    @Parameters(method = "couldEnableTelephonyFallbackParams")
    public void couldEnableTelephonyFallback(@DependencyStatus int locationDetectionStatus,
            @DependencyStatus int connectivityStatus, @OperationStatus int tzResolutionStatus) {
        TimeZoneProviderStatus providerStatus =
                new TimeZoneProviderStatus.Builder()
                        .setLocationDetectionDependencyStatus(locationDetectionStatus)
                        .setConnectivityDependencyStatus(connectivityStatus)
                        .setTimeZoneResolutionOperationStatus(tzResolutionStatus)
                        .build();
        boolean locationDetectionStatusCouldEnableFallback =
                (locationDetectionStatus == DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT
                        || locationDetectionStatus == DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS);
        boolean connectivityStatusCouldEnableFallback =
                (connectivityStatus == DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT
                        || connectivityStatus == DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS);
        boolean tzResolutionStatusCouldEnableFallback = false;

        assertEquals(locationDetectionStatusCouldEnableFallback
                        || connectivityStatusCouldEnableFallback
                        || tzResolutionStatusCouldEnableFallback,
                providerStatus.couldEnableTelephonyFallback());
    }

    /** Parameters for {@link #couldEnableTelephonyFallback}. */
    public static Integer[][] couldEnableTelephonyFallbackParams() {
        List<Integer[]> params = new ArrayList<>();
        @DependencyStatus int[] dependencyStatuses =
                IntStream.rangeClosed(
                        DEPENDENCY_STATUS_UNKNOWN, DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS).toArray();
        @OperationStatus int[] operationStatuses =
                IntStream.rangeClosed(OPERATION_STATUS_UNKNOWN, OPERATION_STATUS_FAILED).toArray();

        // Cartesian product: dependencyStatus x dependencyStatus x operationStatus
        for (@DependencyStatus int locationDetectionStatus : dependencyStatuses) {
            for (@DependencyStatus int connectivityStatus : dependencyStatuses) {
                for (@OperationStatus int tzResolutionStatus : operationStatuses) {
                    params.add(new Integer[] {
                            locationDetectionStatus,
                            connectivityStatus,
                            tzResolutionStatus
                    });
                }
            }
        }
        return params.toArray(new Integer[0][0]);
    }
}
