/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.ext.services.watchdog;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.service.watchdog.ExplicitHealthCheckService;

import com.android.internal.annotations.GuardedBy;

/**
 * Observes the network stack via the ConnectivityManager.
 */
final class NetworkChecker extends ConnectivityManager.NetworkCallback
        implements ExplicitHealthChecker {
    private static final String TAG = "NetworkChecker";

    private final Object mLock = new Object();
    private final ExplicitHealthCheckService mService;
    private final String mPackageName;
    @GuardedBy("mLock")
    private boolean mIsPending;

    NetworkChecker(ExplicitHealthCheckService service, String packageName) {
        mService = service;
        mPackageName = packageName;
    }

    @Override
    public void request() {
        synchronized (mLock) {
            if (mIsPending) {
                return;
            }
            mService.getSystemService(ConnectivityManager.class).registerNetworkCallback(
                    new NetworkRequest.Builder().build(), this);
            mIsPending = true;
        }
    }

    @Override
    public void cancel() {
        synchronized (mLock) {
            if (!mIsPending) {
                return;
            }
            mService.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(this);
            mIsPending = false;
        }
    }

    @Override
    public boolean isPending() {
        synchronized (mLock) {
            return mIsPending;
        }
    }

    @Override
    public String getPackageName() {
        return mPackageName;
    }

    // TODO(b/120598832): Also monitor NetworkCallback#onAvailable to see if we have any
    // available networks that may be unusable. This could be additional signal to our heuristics
    @Override
    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
        synchronized (mLock) {
            if (mIsPending
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                mService.notifyHealthCheckPassed(mPackageName);
                cancel();
            }
        }
    }
}
