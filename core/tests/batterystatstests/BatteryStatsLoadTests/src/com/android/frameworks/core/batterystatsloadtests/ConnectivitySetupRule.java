/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.frameworks.core.batterystatsloadtests;

import static org.junit.Assert.assertEquals;

import android.app.Instrumentation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ConnectivitySetupRule implements TestRule {

    private final boolean mWifiEnabled;
    private final ConnectivityManager mConnectivityManager;
    private final WifiManager mWifiManager;
    private boolean mInitialWifiState;

    public ConnectivitySetupRule(boolean wifiEnabled) {
        mWifiEnabled = wifiEnabled;

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getContext();
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mWifiManager = context.getSystemService(WifiManager.class);
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    mInitialWifiState = isWiFiConnected();
                    setWiFiState(mWifiEnabled);
                    base.evaluate();
                } finally {
                    setWiFiState(mInitialWifiState);
                }
            }
        };
    }

    private void setWiFiState(final boolean enable) throws InterruptedException {
        boolean wiFiConnected = isWiFiConnected();
        if (enable == wiFiConnected) {
            return;
        }

        NetworkTracker tracker = new NetworkTracker(!mWifiEnabled);
        mConnectivityManager.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).build(),
                tracker);

        if (enable) {
            SystemUtil.runShellCommand("svc wifi enable");
            //noinspection deprecation
            SystemUtil.runWithShellPermissionIdentity(mWifiManager::reconnect,
                    android.Manifest.permission.NETWORK_SETTINGS);
        } else {
            SystemUtil.runShellCommand("svc wifi disable");
        }

        tracker.waitForExpectedState();

        assertEquals("Wifi must be " + (enable ? "connected to" : "disconnected from")
                + " an access point for this test.", enable, isWiFiConnected());

        mConnectivityManager.unregisterNetworkCallback(tracker);
    }

    private boolean isWiFiConnected() {
        return mWifiManager.isWifiEnabled() && mConnectivityManager.getActiveNetwork() != null
                && !mConnectivityManager.isActiveNetworkMetered();
    }

    private class NetworkTracker extends ConnectivityManager.NetworkCallback {
        private static final int MSG_CHECK_ACTIVE_NETWORK = 1;

        private final CountDownLatch mReceiveLatch = new CountDownLatch(1);

        private final boolean mExpectedMetered;

        private final Handler mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_CHECK_ACTIVE_NETWORK) {
                    checkActiveNetwork();
                }
            }
        };

        private NetworkTracker(boolean expectedMetered) {
            mExpectedMetered = expectedMetered;
        }

        @Override
        public void onAvailable(Network network) {
            checkActiveNetwork();
        }

        @Override
        public void onLost(Network network) {
            checkActiveNetwork();
        }

        boolean waitForExpectedState() throws InterruptedException {
            checkActiveNetwork();
            return mReceiveLatch.await(60, TimeUnit.SECONDS);
        }

        private void checkActiveNetwork() {
            if (mReceiveLatch.getCount() == 0) {
                return;
            }

            if (mConnectivityManager.getActiveNetwork() != null
                    && mConnectivityManager.isActiveNetworkMetered() == mExpectedMetered) {
                mReceiveLatch.countDown();
            } else {
                mHandler.sendEmptyMessageDelayed(MSG_CHECK_ACTIVE_NETWORK, 5000);
            }
        }
    }
}
