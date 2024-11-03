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

package com.android.server.security.forensic;

import android.Manifest.permission;
import android.annotation.RequiresPermission;
import android.app.admin.DevicePolicyManager;
import android.app.admin.SecurityLog.SecurityEvent;
import android.content.Context;
import android.security.forensic.ForensicEvent;
import android.util.ArrayMap;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SecurityLogSource implements DataSource {

    private static final String TAG = "Forensic SecurityLogSource";
    private static final String EVENT_TYPE = "SecurityEvent";
    private static final String EVENT_TAG = "TAG";
    private static final String EVENT_TIME = "TIME";
    private static final String EVENT_DATA = "DATA";

    private SecurityEventCallback mEventCallback = new SecurityEventCallback();
    private DevicePolicyManager mDpm;
    private Executor mExecutor;
    private DataAggregator mDataAggregator;

    public SecurityLogSource(Context context, DataAggregator dataAggregator) {
        mDataAggregator = dataAggregator;
        mDpm = context.getSystemService(DevicePolicyManager.class);
        mExecutor = Executors.newSingleThreadExecutor();
        mEventCallback = new SecurityEventCallback();
    }

    @Override
    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    public void enable() {
        enableAuditLog();
        mDpm.setAuditLogEventCallback(mExecutor, mEventCallback);
    }

    @Override
    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    public void disable() {
        disableAuditLog();
    }

    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    private void enableAuditLog() {
        if (!isAuditLogEnabled()) {
            mDpm.setAuditLogEnabled(true);
        }
    }

    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    private void disableAuditLog() {
        if (isAuditLogEnabled()) {
            mDpm.setAuditLogEnabled(false);
        }
    }

    /**
     * Check if security audit logging is enabled for the caller.
     *
     * @return Whether security audit logging is enabled.
     */
    public boolean isAuditLogEnabled() {
        return mDpm.isAuditLogEnabled();
    }

    private class SecurityEventCallback implements Consumer<List<SecurityEvent>> {

        @Override
        public void accept(List<SecurityEvent> events) {
            List<ForensicEvent> forensicEvents =
                    events.stream()
                            .filter(event -> event != null)
                            .map(event -> toForensicEvent(event))
                            .collect(Collectors.toList());
            mDataAggregator.addBatchData(forensicEvents);
        }

        private ForensicEvent toForensicEvent(SecurityEvent event) {
            ArrayMap<String, String> keyValuePairs = new ArrayMap<>();
            keyValuePairs.put(EVENT_TIME, String.valueOf(event.getTimeNanos()));
            // TODO: Map tag to corresponding string
            keyValuePairs.put(EVENT_TAG, String.valueOf(event.getTag()));
            keyValuePairs.put(EVENT_DATA, eventDataToString(event.getData()));
            return new ForensicEvent(EVENT_TYPE, keyValuePairs);
        }

        /**
         * Convert event data to a String.
         *
         * @param obj Object containing an Integer, Long, Float, String, null, or Object[] of the
         *     same.
         * @return String representation of event data.
         */
        private String eventDataToString(Object obj) {
            if (obj == null) {
                return "";
            } else if (obj instanceof Integer
                    || obj instanceof Long
                    || obj instanceof Float
                    || obj instanceof String) {
                return String.valueOf(obj);
            } else if (obj instanceof Object[]) {
                Object[] objArray = (Object[]) obj;
                String[] strArray = new String[objArray.length];
                for (int i = 0; i < objArray.length; ++i) {
                    strArray[i] = eventDataToString(objArray[i]);
                }
                return Arrays.toString((String[]) strArray);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported data type: " + obj.getClass().getSimpleName());
            }
        }
    }
}
