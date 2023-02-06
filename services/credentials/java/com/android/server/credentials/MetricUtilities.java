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

package com.android.server.credentials;

import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__API_NAME__API_NAME_CLEAR_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__API_NAME__API_NAME_CREATE_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__API_NAME__API_NAME_GET_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__API_NAME__API_NAME_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__API_STATUS__API_STATUS_CLIENT_CANCELED;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__API_STATUS__API_STATUS_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__API_STATUS__API_STATUS_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__API_STATUS__API_STATUS_USER_CANCELED;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_FINAL_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_FINAL_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_QUERY_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_QUERY_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_UNKNOWN;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * For all future metric additions, this will contain their names for local usage after importing
 * from {@link com.android.internal.util.FrameworkStatsLog}.
 */
public class MetricUtilities {

    private static final String TAG = "MetricUtilities";

    // Metrics constants
    protected static final int METRICS_API_NAME_UNKNOWN =
            CREDENTIAL_MANAGER_API_CALLED__API_NAME__API_NAME_UNKNOWN;
    protected static final int METRICS_API_NAME_GET_CREDENTIAL =
            CREDENTIAL_MANAGER_API_CALLED__API_NAME__API_NAME_GET_CREDENTIAL;
    protected static final int METRICS_API_NAME_CREATE_CREDENTIAL =
            CREDENTIAL_MANAGER_API_CALLED__API_NAME__API_NAME_CREATE_CREDENTIAL;
    protected static final int METRICS_API_NAME_CLEAR_CREDENTIAL =
            CREDENTIAL_MANAGER_API_CALLED__API_NAME__API_NAME_CLEAR_CREDENTIAL;
    // TODO add isEnabled
    protected static final int METRICS_API_STATUS_SUCCESS =
            CREDENTIAL_MANAGER_API_CALLED__API_STATUS__API_STATUS_SUCCESS;
    protected static final int METRICS_API_STATUS_FAILURE =
            CREDENTIAL_MANAGER_API_CALLED__API_STATUS__API_STATUS_FAILURE;
    protected static final int METRICS_API_STATUS_CLIENT_CANCEL =
            CREDENTIAL_MANAGER_API_CALLED__API_STATUS__API_STATUS_CLIENT_CANCELED;
    protected static final int METRICS_API_STATUS_USER_CANCEL =
            CREDENTIAL_MANAGER_API_CALLED__API_STATUS__API_STATUS_USER_CANCELED;
    protected static final int METRICS_PROVIDER_STATUS_FINAL_FAILURE =
            CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_FINAL_FAILURE;
    protected static final int METRICS_PROVIDER_STATUS_QUERY_FAILURE =
            CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_QUERY_FAILURE;
    protected static final int METRICS_PROVIDER_STATUS_FINAL_SUCCESS =
            CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_FINAL_SUCCESS;
    protected static final int METRICS_PROVIDER_STATUS_QUERY_SUCCESS =
            CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_QUERY_SUCCESS;
    protected static final int METRICS_PROVIDER_STATUS_UNKNOWN =
            CREDENTIAL_MANAGER_API_CALLED__CANDIDATE_PROVIDER_STATUS__PROVIDER_UNKNOWN;


    /**
     * This retrieves the uid of any package name, given a context and a component name for the
     * package. By default, if the desired package uid cannot be found, it will fall back to a
     * bogus uid.
     * @return the uid of a given package
     */
    protected static int getPackageUid(Context context, ComponentName componentName) {
        int sessUid = -1;
        try {
            // Only for T and above, which is fine for our use case
            sessUid = context.getPackageManager().getApplicationInfo(
                    componentName.getPackageName(),
                    PackageManager.ApplicationInfoFlags.of(0)).uid;
        } catch (Throwable t) {
            Log.i(TAG, "Couldn't find required uid");
        }
        return sessUid;
    }

}
