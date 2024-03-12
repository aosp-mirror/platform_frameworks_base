/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_NOT_SPECIFIED;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_SPECIFIED_BUT_NOT_FOUND;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_SPECIFIED_BUT_NOT_ENABLED;


public enum OemUiUsageStatus {
    UNKNOWN(CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_UNKNOWN),
    SUCCESS(CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_SUCCESS),
    FAILURE_NOT_SPECIFIED(CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_NOT_SPECIFIED),
    FAILURE_SPECIFIED_BUT_NOT_FOUND(CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_SPECIFIED_BUT_NOT_FOUND),
    FAILURE_SPECIFIED_BUT_NOT_ENABLED(CREDENTIAL_MANAGER_FINAL_NO_UID_REPORTED__OEM_UI_USAGE_STATUS__OEM_UI_USAGE_STATUS_SPECIFIED_BUT_NOT_ENABLED);

    private final int mLoggingInt;

    OemUiUsageStatus(int loggingInt) {
        mLoggingInt = loggingInt;
    }

    public int getLoggingInt() {
        return mLoggingInt;
    }
}
