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

import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_CLEAR_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_CREATE_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_GET_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_UNKNOWN;

public enum ApiName {
    UNKNOWN(CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_UNKNOWN),
    GET_CREDENTIAL(CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_GET_CREDENTIAL),
    CREATE_CREDENTIAL(CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_CREATE_CREDENTIAL),
    CLEAR_CREDENTIAL(CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_CLEAR_CREDENTIAL),
    IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE(
        CREDENTIAL_MANAGER_INITIAL_PHASE__API_NAME__API_NAME_IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE
    );

    private final int mInnerMetricCode;

    ApiName(int innerMetricCode) {
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
