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

import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__ACTION_ENTRY;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__AUTHENTICATION_ENTRY;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__CREDENTIAL_ENTRY;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__REMOTE_ENTRY;
import static com.android.internal.util.FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__UNKNOWN;
import static com.android.server.credentials.ProviderGetSession.ACTION_ENTRY_KEY;
import static com.android.server.credentials.ProviderGetSession.AUTHENTICATION_ACTION_ENTRY_KEY;
import static com.android.server.credentials.ProviderGetSession.CREDENTIAL_ENTRY_KEY;
import static com.android.server.credentials.ProviderGetSession.REMOTE_ENTRY_KEY;

import android.util.Log;

import java.util.AbstractMap;
import java.util.Map;

public enum EntryEnum {
    UNKNOWN(CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__UNKNOWN),
    ACTION_ENTRY(CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__ACTION_ENTRY),
    CREDENTIAL_ENTRY(CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__CREDENTIAL_ENTRY),
    REMOTE_ENTRY(CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__REMOTE_ENTRY),
    AUTHENTICATION_ENTRY(
            CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED__CLICKED_ENTRIES__AUTHENTICATION_ENTRY
    );

    private static final String TAG = "EntryEnum";

    private final int mInnerMetricCode;

    private static final Map<String, Integer> sKeyToEntryCode = Map.ofEntries(
            new AbstractMap.SimpleEntry<>(ACTION_ENTRY_KEY,
                    ACTION_ENTRY.mInnerMetricCode),
            new AbstractMap.SimpleEntry<>(AUTHENTICATION_ACTION_ENTRY_KEY,
                    AUTHENTICATION_ENTRY.mInnerMetricCode),
            new AbstractMap.SimpleEntry<>(REMOTE_ENTRY_KEY,
                    REMOTE_ENTRY.mInnerMetricCode),
            new AbstractMap.SimpleEntry<>(CREDENTIAL_ENTRY_KEY,
                    CREDENTIAL_ENTRY.mInnerMetricCode)
    );

    EntryEnum(int innerMetricCode) {
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

    /**
     * Given a string key type known to the framework, this returns the known metric code associated
     * with that string.
     *
     * @param stringKey a string key type for a particular entry
     * @return the metric code associated with this enum
     */
    public static int getMetricCodeFromString(String stringKey) {
        if (!sKeyToEntryCode.containsKey(stringKey)) {
            Log.w(TAG, "Attempted to use an unsupported string key entry type");
            return UNKNOWN.mInnerMetricCode;
        }
        return sKeyToEntryCode.get(stringKey);
    }
}
