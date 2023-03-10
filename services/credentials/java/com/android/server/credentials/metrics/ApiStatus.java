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

import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE__API_STATUS__API_STATUS_CLIENT_CANCELED;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE__API_STATUS__API_STATUS_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE__API_STATUS__API_STATUS_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE__API_STATUS__API_STATUS_USER_CANCELED;

public enum ApiStatus {
    SUCCESS(CREDENTIAL_MANAGER_FINAL_PHASE__API_STATUS__API_STATUS_SUCCESS),
    FAILURE(CREDENTIAL_MANAGER_FINAL_PHASE__API_STATUS__API_STATUS_FAILURE),
    CLIENT_CANCELED(
            CREDENTIAL_MANAGER_FINAL_PHASE__API_STATUS__API_STATUS_CLIENT_CANCELED),
    USER_CANCELED(
            CREDENTIAL_MANAGER_FINAL_PHASE__API_STATUS__API_STATUS_USER_CANCELED);

    private final int mInnerMetricCode;

    ApiStatus(int innerMetricCode) {
        this.mInnerMetricCode = innerMetricCode;
    }

    /**
     * Gives the West-world version of the metric name.
     *
     * @return a code corresponding to the west world metric name
     */
    public int getMetricCode() {
        return this.mInnerMetricCode;
    }
}
