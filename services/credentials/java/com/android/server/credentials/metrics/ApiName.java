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

import static android.credentials.ui.RequestInfo.TYPE_CREATE;
import static android.credentials.ui.RequestInfo.TYPE_GET;
import static android.credentials.ui.RequestInfo.TYPE_GET_VIA_REGISTRY;
import static android.credentials.ui.RequestInfo.TYPE_UNDEFINED;

import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_CLEAR_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_CREATE_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_GET_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_GET_CREDENTIAL_VIA_REGISTRY;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_UNKNOWN;

import android.credentials.ui.RequestInfo;
import android.util.Slog;

import java.util.AbstractMap;
import java.util.Map;

public enum ApiName {
    UNKNOWN(CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_UNKNOWN),
    GET_CREDENTIAL(CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_GET_CREDENTIAL),
    GET_CREDENTIAL_VIA_REGISTRY(
CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_GET_CREDENTIAL_VIA_REGISTRY),
    CREATE_CREDENTIAL(
            CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_CREATE_CREDENTIAL),
    CLEAR_CREDENTIAL(
            CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_CLEAR_CREDENTIAL),
    IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE(
CREDENTIAL_MANAGER_INITIAL_PHASE_REPORTED__API_NAME__API_NAME_IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE
    );

    private static final String TAG = "ApiName";

    private final int mInnerMetricCode;

    private static final Map<String, Integer> sRequestInfoToMetric = Map.ofEntries(
            new AbstractMap.SimpleEntry<>(TYPE_CREATE,
                    CREATE_CREDENTIAL.mInnerMetricCode),
            new AbstractMap.SimpleEntry<>(TYPE_GET,
                    GET_CREDENTIAL.mInnerMetricCode),
            new AbstractMap.SimpleEntry<>(TYPE_GET_VIA_REGISTRY,
                    GET_CREDENTIAL_VIA_REGISTRY.mInnerMetricCode),
            new AbstractMap.SimpleEntry<>(TYPE_UNDEFINED,
                    CLEAR_CREDENTIAL.mInnerMetricCode)
    );

    ApiName(int innerMetricCode) {
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

    /**
     * Given a string key type known to the framework, this returns the known metric code associated
     * with that string. This is mainly used by {@link RequestSessionMetric} collection contexts.
     * This relies on {@link RequestInfo} string keys.
     *
     * @param stringKey a string key type for a particular request info
     * @return the metric code associated with this request info's api name counterpart
     */
    public static int getMetricCodeFromRequestInfo(String stringKey) {
        if (!sRequestInfoToMetric.containsKey(stringKey)) {
            Slog.i(TAG, "Attempted to use an unsupported string key request info");
            return UNKNOWN.mInnerMetricCode;
        }
        return sRequestInfoToMetric.get(stringKey);
    }
}
