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

import android.content.pm.PackageManagerInternal;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.server.ServiceThread;

import java.util.ArrayList;
import java.util.List;

/**
 * A class for managing network logging.
 * This class is not thread-safe, callers should synchronize access.
 */
final class NetworkLogger {

    private static final String TAG = NetworkLogger.class.getSimpleName();

    private final DevicePolicyManagerService mDpm;
    private final PackageManagerInternal mPm;

    private IIpConnectivityMetrics mIpConnectivityMetrics;
    private boolean mIsLoggingEnabled;

    private final INetdEventCallback mNetdEventCallback = new INetdEventCallback.Stub() {
        @Override
        public void onDnsEvent(String hostname, String[] ipAddresses, int ipAddressesCount,
                long timestamp, int uid) {
            if (!mIsLoggingEnabled) {
                return;
            }
            // TODO(mkarpinski): send msg with data to Handler
        }

        @Override
        public void onConnectEvent(String ipAddr, int port, long timestamp, int uid) {
            if (!mIsLoggingEnabled) {
                return;
            }
            // TODO(mkarpinski): send msg with data to Handler
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
                // TODO(mkarpinski): start a new ServiceThread, instantiate a Handler etc.
                mIsLoggingEnabled = true;
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
        // stop the logging regardless of whether we failed to unregister listener
        mIsLoggingEnabled = false;
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
            // TODO(mkarpinski): quitSafely() the Handler
            return true;
        }
    }
}
