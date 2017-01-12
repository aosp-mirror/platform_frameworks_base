/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.app.admin.ConnectEvent;
import android.app.admin.DnsEvent;
import android.app.admin.NetworkEvent;
import android.content.pm.PackageManagerInternal;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.server.ServiceThread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class for managing network logging.
 * This class is not thread-safe, callers should synchronize access.
 */
final class NetworkLogger {

    private static final String TAG = NetworkLogger.class.getSimpleName();

    private final DevicePolicyManagerService mDpm;
    private final PackageManagerInternal mPm;
    private final AtomicBoolean mIsLoggingEnabled = new AtomicBoolean(false);

    private IIpConnectivityMetrics mIpConnectivityMetrics;
    private ServiceThread mHandlerThread;
    private NetworkLoggingHandler mNetworkLoggingHandler;

    private final INetdEventCallback mNetdEventCallback = new INetdEventCallback.Stub() {
        @Override
        public void onDnsEvent(String hostname, String[] ipAddresses, int ipAddressesCount,
                long timestamp, int uid) {
            if (!mIsLoggingEnabled.get()) {
                return;
            }
            DnsEvent dnsEvent = new DnsEvent(hostname, ipAddresses, ipAddressesCount,
                    mPm.getNameForUid(uid), timestamp);
            sendNetworkEvent(dnsEvent);
        }

        @Override
        public void onConnectEvent(String ipAddr, int port, long timestamp, int uid) {
            if (!mIsLoggingEnabled.get()) {
                return;
            }
            ConnectEvent connectEvent = new ConnectEvent(ipAddr, port, mPm.getNameForUid(uid),
                    timestamp);
            sendNetworkEvent(connectEvent);
        }

        private void sendNetworkEvent(NetworkEvent event) {
            Message msg = mNetworkLoggingHandler.obtainMessage(
                    NetworkLoggingHandler.LOG_NETWORK_EVENT_MSG);
            Bundle bundle = new Bundle();
            bundle.putParcelable(NetworkLoggingHandler.NETWORK_EVENT_KEY, event);
            msg.setData(bundle);
            mNetworkLoggingHandler.sendMessage(msg);
        }
    };

    NetworkLogger(DevicePolicyManagerService dpm, PackageManagerInternal pm) {
        mDpm = dpm;
        mPm = pm;
    }

    private boolean checkIpConnectivityMetricsService() {
        if (mIpConnectivityMetrics != null) {
            return true;
        }
        final IIpConnectivityMetrics service = mDpm.mInjector.getIIpConnectivityMetrics();
        if (service == null) {
            return false;
        }
        mIpConnectivityMetrics = service;
        return true;
    }

    boolean startNetworkLogging() {
        Log.d(TAG, "Starting network logging.");
        if (!checkIpConnectivityMetricsService()) {
            // the IIpConnectivityMetrics service should have been present at this point
            Slog.wtf(TAG, "Failed to register callback with IIpConnectivityMetrics.");
            return false;
        }
        try {
           if (mIpConnectivityMetrics.registerNetdEventCallback(mNetdEventCallback)) {
                mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                        /* allowIo */ false);
                mHandlerThread.start();
                mNetworkLoggingHandler = new NetworkLoggingHandler(mHandlerThread.getLooper(),
                        mDpm);
                mNetworkLoggingHandler.scheduleBatchFinalization();
                mIsLoggingEnabled.set(true);
                return true;
            } else {
                return false;
            }
        } catch (RemoteException re) {
            Slog.wtf(TAG, "Failed to make remote calls to register the callback", re);
            return false;
        }
    }

    boolean stopNetworkLogging() {
        Log.d(TAG, "Stopping network logging");
        // stop the logging regardless of whether we fail to unregister listener
        mIsLoggingEnabled.set(false);
        try {
            if (!checkIpConnectivityMetricsService()) {
                // the IIpConnectivityMetrics service should have been present at this point
                Slog.wtf(TAG, "Failed to unregister callback with IIpConnectivityMetrics.");
                // logging is forcefully disabled even if unregistering fails
                return true;
            }
            return mIpConnectivityMetrics.unregisterNetdEventCallback();
        } catch (RemoteException re) {
            Slog.wtf(TAG, "Failed to make remote calls to unregister the callback", re);
        } finally {
            mHandlerThread.quitSafely();
            return true;
        }
    }

    List<NetworkEvent> retrieveLogs(long batchToken) {
        return mNetworkLoggingHandler.retrieveFullLogBatch(batchToken);
    }
}
