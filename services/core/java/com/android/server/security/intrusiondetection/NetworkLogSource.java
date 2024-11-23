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

import android.app.admin.ConnectEvent;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DnsEvent;
import android.app.admin.NetworkEvent;
import android.content.ComponentName;
import android.content.Context;
import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.util.Slog;

import java.util.List;
import java.util.stream.Collectors;

public class NetworkLogSource implements DataSource {

    private static final String TAG = "IntrusionDetectionEvent NetworkLogSource";

    private DevicePolicyManager mDpm;
    private ComponentName mAdmin;
    private DataAggregator mDataAggregator;

    public NetworkLogSource(Context context, DataAggregator dataAggregator) {
        mDataAggregator = dataAggregator;
        mDpm = context.getSystemService(DevicePolicyManager.class);
        mAdmin = new ComponentName(context, IntrusionDetectionAdminReceiver.class);
    }

    @Override
    public boolean initialize() {
        try {
            if (!mDpm.isAdminActive(mAdmin)) {
                Slog.e(TAG, "Admin " + mAdmin.flattenToString() + "is not active admin");
                return false;
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "Security exception in initialize: ", e);
            return false;
        }
        return true;
    }

    @Override
    public void enable() {
        enableNetworkLog();
    }

    @Override
    public void disable() {
        disableNetworkLog();
    }

    private void enableNetworkLog() {
        if (!isNetworkLogEnabled()) {
            mDpm.setNetworkLoggingEnabled(mAdmin, true);
        }
    }

    private void disableNetworkLog() {
        if (isNetworkLogEnabled()) {
            mDpm.setNetworkLoggingEnabled(mAdmin, false);
        }
    }

    private boolean isNetworkLogEnabled() {
        return mDpm.isNetworkLoggingEnabled(mAdmin);
    }

    /**
     * Retrieve network logs when onNetworkLogsAvailable callback is received.
     *
     * @param batchToken The token representing the current batch of network logs.
     */
    public void onNetworkLogsAvailable(long batchToken) {
        List<NetworkEvent> events;
        try {
            events = mDpm.retrieveNetworkLogs(mAdmin, batchToken);
        } catch (SecurityException e) {
            Slog.e(
                    TAG,
                    "Admin "
                            + mAdmin.flattenToString()
                            + "does not have permission to retrieve network logs",
                    e);
            return;
        }
        if (events == null) {
            if (!isNetworkLogEnabled()) {
                Slog.w(TAG, "Network logging is disabled");
            } else {
                Slog.e(TAG, "Invalid batch token: " + batchToken);
            }
            return;
        }

        List<IntrusionDetectionEvent> intrusionDetectionEvents =
                events.stream()
                        .filter(event -> event != null)
                        .map(event -> toIntrusionDetectionEvent(event))
                        .collect(Collectors.toList());
        mDataAggregator.addBatchData(intrusionDetectionEvents);
    }

    private IntrusionDetectionEvent toIntrusionDetectionEvent(NetworkEvent event) {
        if (event instanceof DnsEvent) {
            DnsEvent dnsEvent = (DnsEvent) event;
            return new IntrusionDetectionEvent(dnsEvent);
        } else if (event instanceof ConnectEvent) {
            ConnectEvent connectEvent = (ConnectEvent) event;
            return new IntrusionDetectionEvent(connectEvent);
        }
        throw new IllegalArgumentException(
                "Invalid event type with ID: "
                        + event.getId()
                        + "from package: "
                        + event.getPackageName());
    }
}
