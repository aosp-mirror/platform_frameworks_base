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
import android.app.admin.DnsEvent;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.net.metrics.IpConnectivityLog;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.net.BaseNetdEventCallback;

import java.util.concurrent.atomic.AtomicBoolean;

public class NetworkLogSource implements DataSource {

    private static final String TAG = "IntrusionDetectionEvent NetworkLogSource";
    private final AtomicBoolean mIsNetworkLoggingEnabled = new AtomicBoolean(false);
    private final PackageManagerInternal mPm;

    private DataAggregator mDataAggregator;

    private IIpConnectivityMetrics mIpConnectivityMetrics;
    private long mId;

    public NetworkLogSource(Context context, DataAggregator dataAggregator)
            throws SecurityException {
        mDataAggregator = dataAggregator;
        mPm = LocalServices.getService(PackageManagerInternal.class);
        mId = 0;
        initIpConnectivityMetrics();
    }

    private void initIpConnectivityMetrics() {
        mIpConnectivityMetrics =
                (IIpConnectivityMetrics)
                        IIpConnectivityMetrics.Stub.asInterface(
                                ServiceManager.getService(IpConnectivityLog.SERVICE_NAME));
    }

    @Override
    public void enable() {
        if (mIsNetworkLoggingEnabled.get()) {
            Slog.w(TAG, "Network logging is already enabled");
            return;
        }
        try {
            if (mIpConnectivityMetrics.addNetdEventCallback(
                    INetdEventCallback.CALLBACK_CALLER_DEVICE_POLICY, mNetdEventCallback)) {
                mIsNetworkLoggingEnabled.set(true);
            } else {
                Slog.e(TAG, "Failed to enable network logging; invalid callback");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to enable network logging; ", e);
        }
    }

    @Override
    public void disable() {
        if (!mIsNetworkLoggingEnabled.get()) {
            Slog.w(TAG, "Network logging is already disabled");
            return;
        }
        try {
            if (!mIpConnectivityMetrics.removeNetdEventCallback(
                    INetdEventCallback.CALLBACK_CALLER_NETWORK_WATCHLIST)) {

                mIsNetworkLoggingEnabled.set(false);
            } else {
                Slog.e(TAG, "Failed to enable network logging; invalid callback");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to disable network logging; ", e);
        }
    }

    private void incrementEventID() {
        if (mId == Long.MAX_VALUE) {
            Slog.i(TAG, "Reached maximum id value; wrapping around.");
            mId = 0;
        } else {
            mId++;
        }
    }

    private final INetdEventCallback mNetdEventCallback =
            new BaseNetdEventCallback() {
                @Override
                public void onDnsEvent(
                        int netId,
                        int eventType,
                        int returnCode,
                        String hostname,
                        String[] ipAddresses,
                        int ipAddressesCount,
                        long timestamp,
                        int uid) {
                    if (!mIsNetworkLoggingEnabled.get()) {
                        return;
                    }
                    DnsEvent dnsEvent =
                            new DnsEvent(
                                    hostname,
                                    ipAddresses,
                                    ipAddressesCount,
                                    mPm.getNameForUid(uid),
                                    timestamp);
                    dnsEvent.setId(mId);
                    incrementEventID();
                    mDataAggregator.addSingleData(new IntrusionDetectionEvent(dnsEvent));
                }

                @Override
                public void onConnectEvent(String ipAddr, int port, long timestamp, int uid) {
                    if (!mIsNetworkLoggingEnabled.get()) {
                        return;
                    }
                    ConnectEvent connectEvent =
                            new ConnectEvent(ipAddr, port, mPm.getNameForUid(uid), timestamp);
                    connectEvent.setId(mId);
                    incrementEventID();
                    mDataAggregator.addSingleData(new IntrusionDetectionEvent(connectEvent));
                }
            };
}
