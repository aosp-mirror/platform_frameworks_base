/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.credentials.metrics;

import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_FINAL_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_FINAL_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_QUERY_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_QUERY_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_UNKNOWN;

public enum ProviderStatusForMetrics {

    UNKNOWN(
            CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_UNKNOWN),
    FINAL_FAILURE(
        CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_FINAL_FAILURE),
    QUERY_FAILURE(
        CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_QUERY_FAILURE),
    FINAL_SUCCESS(
        CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_FINAL_SUCCESS),
    QUERY_SUCCESS(
        CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CHOSEN_PROVIDER_STATUS__PROVIDER_QUERY_SUCCESS);

    private final int mInnerMetricCode;

    ProviderStatusForMetrics(int innerMetricCode) {
        mInnerMetricCode = innerMetricCode;
    }

    /**
     * Gives the West-world version of the metric name.
     *
     * @return a code corresponding to the west world metric name
     */
    public int getMetricCode() {
        return mInnerMetricCode;
    }
}
