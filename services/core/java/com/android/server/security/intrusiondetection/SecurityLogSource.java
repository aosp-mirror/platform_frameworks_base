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

package com.android.server.security.intrusiondetection;

import android.Manifest.permission;
import android.annotation.RequiresPermission;
import android.app.admin.DevicePolicyManager;
import android.app.admin.SecurityLog.SecurityEvent;
import android.content.Context;
import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.util.Slog;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SecurityLogSource implements DataSource {

    private static final String TAG = "IntrusionDetection SecurityLogSource";

    private SecurityEventCallback mEventCallback;
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

    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    private boolean isAuditLogEnabled() {
        return mDpm.isAuditLogEnabled();
    }

    private class SecurityEventCallback implements Consumer<List<SecurityEvent>> {

        @Override
        public void accept(List<SecurityEvent> events) {
            if (events.size() == 0) {
                Slog.w(TAG, "No events received; caller may not be authorized");
                return;
            }
            List<IntrusionDetectionEvent> intrusionDetectionEvents =
                    events.stream()
                            .filter(event -> event != null)
                            .map(event -> new IntrusionDetectionEvent(event))
                            .collect(Collectors.toList());
            mDataAggregator.addBatchData(intrusionDetectionEvents);
        }
    }
}
