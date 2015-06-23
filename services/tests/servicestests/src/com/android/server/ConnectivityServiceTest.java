/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkMisc;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.INetworkManagementService;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.util.LogPrinter;

import com.android.server.connectivity.NetworkMonitor;

import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for {@link ConnectivityService}.
 *
 * Build, install and run with:
 *  runtest frameworks-services -c com.android.server.ConnectivityServiceTest
 */
public class ConnectivityServiceTest extends AndroidTestCase {
    private static final String TAG = "ConnectivityServiceTest";

    private static final String MOBILE_IFACE = "rmnet3";
    private static final String WIFI_IFACE = "wlan6";

    private static final RouteInfo MOBILE_ROUTE_V4 = RouteInfo.makeHostRoute(parse("10.0.0.33"),
                                                                             MOBILE_IFACE);
    private static final RouteInfo MOBILE_ROUTE_V6 = RouteInfo.makeHostRoute(parse("fd00::33"),
                                                                             MOBILE_IFACE);

    private static final RouteInfo WIFI_ROUTE_V4 = RouteInfo.makeHostRoute(parse("192.168.0.66"),
                                                                           parse("192.168.0.1"),
                                                                           WIFI_IFACE);
    private static final RouteInfo WIFI_ROUTE_V6 = RouteInfo.makeHostRoute(parse("fd00::66"),
                                                                           parse("fd00::"),
                                                                           WIFI_IFACE);

    private INetworkManagementService mNetManager;
    private INetworkStatsService mStatsService;
    private INetworkPolicyManager mPolicyService;

    private BroadcastInterceptingContext mServiceContext;
    private ConnectivityService mService;
    private ConnectivityManager mCm;
    private MockNetworkAgent mWiFiNetworkAgent;
    private MockNetworkAgent mCellNetworkAgent;

    private class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            // PendingIntents sent by the AlarmManager are not intercepted by
            // BroadcastInterceptingContext so we must really register the receiver.
            // This shouldn't effect the real NetworkMonitors as the action contains a random token.
            if (filter.getAction(0).startsWith("android.net.netmon.lingerExpired")) {
                return getBaseContext().registerReceiver(receiver, filter);
            } else {
                return super.registerReceiver(receiver, filter);
            }
        }

        @Override
        public Object getSystemService (String name) {
            if (name == Context.CONNECTIVITY_SERVICE) return mCm;
            return super.getSystemService(name);
        }
    }

    private class MockNetworkAgent {
        private final NetworkInfo mNetworkInfo;
        private final NetworkCapabilities mNetworkCapabilities;
        private final Thread mThread;
        private int mScore;
        private NetworkAgent mNetworkAgent;

        MockNetworkAgent(int transport) {
            final int type = transportToLegacyType(transport);
            final String typeName = ConnectivityManager.getNetworkTypeName(type);
            mNetworkInfo = new NetworkInfo(type, 0, typeName, "Mock");
            mNetworkCapabilities = new NetworkCapabilities();
            mNetworkCapabilities.addTransportType(transport);
            switch (transport) {
                case TRANSPORT_WIFI:
                    mScore = 60;
                    break;
                case TRANSPORT_CELLULAR:
                    mScore = 50;
                    break;
                default:
                    throw new UnsupportedOperationException("unimplemented network type");
            }
            final ConditionVariable initComplete = new ConditionVariable();
            mThread = new Thread() {
                public void run() {
                    Looper.prepare();
                    mNetworkAgent = new NetworkAgent(Looper.myLooper(), mServiceContext,
                            "Mock" + typeName, mNetworkInfo, mNetworkCapabilities,
                            new LinkProperties(), mScore, new NetworkMisc()) {
                        public void unwanted() {}
                    };
                    initComplete.open();
                    Looper.loop();
                }
            };
            mThread.start();
            waitFor(initComplete);
        }

        public void adjustScore(int change) {
            mScore += change;
            mNetworkAgent.sendNetworkScore(mScore);
        }

        /**
         * Transition this NetworkAgent to CONNECTED state.
         * @param validated Indicate if network should pretend to be validated.
         */
        public void connect(boolean validated) {
            assertEquals(mNetworkInfo.getDetailedState(), DetailedState.IDLE);
            assertFalse(mNetworkCapabilities.hasCapability(NET_CAPABILITY_INTERNET));

            // To pretend network is validated, we transition it to the CONNECTED state without
            // NET_CAPABILITY_INTERNET so NetworkMonitor doesn't bother trying to validate and
            // just rubber stamps it as validated.  Afterwards we add NET_CAPABILITY_INTERNET so
            // the network can satisfy the default request.
            NetworkCallback callback = null;
            final ConditionVariable validatedCv = new ConditionVariable();
            if (validated) {
                // If we connect a network without INTERNET capability, it'll get reaped.
                // Prevent the reaping by adding a NetworkRequest.
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(mNetworkCapabilities.getTransportTypes()[0])
                        .build();
                callback = new NetworkCallback() {
                    public void onCapabilitiesChanged(Network network,
                            NetworkCapabilities networkCapabilities) {
                        if (networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)) {
                            validatedCv.open();
                        }
                    }
                };
                mCm.requestNetwork(request, callback);
            } else {
                mNetworkCapabilities.addCapability(NET_CAPABILITY_INTERNET);
                mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
            }

            mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);

            if (validated) {
                // Wait for network to validate.
                waitFor(validatedCv);
                mNetworkCapabilities.addCapability(NET_CAPABILITY_INTERNET);
                mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
            }

            if (callback != null) mCm.unregisterNetworkCallback(callback);
        }

        public void disconnect() {
            mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, null);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }

        public Network getNetwork() {
            return new Network(mNetworkAgent.netId);
        }
    }

    private static class MockNetworkFactory extends NetworkFactory {
        final ConditionVariable mNetworkStartedCV = new ConditionVariable();
        final ConditionVariable mNetworkStoppedCV = new ConditionVariable();
        final ConditionVariable mNetworkRequestedCV = new ConditionVariable();
        final ConditionVariable mNetworkReleasedCV = new ConditionVariable();
        final AtomicBoolean mNetworkStarted = new AtomicBoolean(false);

        public MockNetworkFactory(Looper looper, Context context, String logTag,
                NetworkCapabilities filter) {
            super(looper, context, logTag, filter);
        }

        public int getMyRequestCount() {
            return getRequestCount();
        }

        protected void startNetwork() {
            mNetworkStarted.set(true);
            mNetworkStartedCV.open();
        }

        protected void stopNetwork() {
            mNetworkStarted.set(false);
            mNetworkStoppedCV.open();
        }

        public boolean getMyStartRequested() {
            return mNetworkStarted.get();
        }

        public ConditionVariable getNetworkStartedCV() {
            mNetworkStartedCV.close();
            return mNetworkStartedCV;
        }

        public ConditionVariable getNetworkStoppedCV() {
            mNetworkStoppedCV.close();
            return mNetworkStoppedCV;
        }

        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            super.needNetworkFor(networkRequest, score);
            mNetworkRequestedCV.open();
        }

        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            super.releaseNetworkFor(networkRequest);
            mNetworkReleasedCV.open();
        }

        public ConditionVariable getNetworkRequestedCV() {
            mNetworkRequestedCV.close();
            return mNetworkRequestedCV;
        }

        public ConditionVariable getNetworkReleasedCV() {
            mNetworkReleasedCV.close();
            return mNetworkReleasedCV;
        }

        public void waitForNetworkRequests(final int count) {
            waitFor(new Criteria() { public boolean get() { return count == getRequestCount(); } });
        }
    }

    private class WrappedConnectivityService extends ConnectivityService {
        public WrappedConnectivityService(Context context, INetworkManagementService netManager,
                INetworkStatsService statsService, INetworkPolicyManager policyManager) {
            super(context, netManager, statsService, policyManager);
        }

        @Override
        protected int getDefaultTcpRwnd() {
            // Prevent wrapped ConnectivityService from trying to write to SystemProperties.
            return 0;
        }

        @Override
        protected int reserveNetId() {
            while (true) {
                final int netId = super.reserveNetId();

                // Don't overlap test NetIDs with real NetIDs as binding sockets to real networks
                // can have odd side-effects, like network validations succeeding.
                final Network[] networks = ConnectivityManager.from(getContext()).getAllNetworks();
                boolean overlaps = false;
                for (Network network : networks) {
                    if (netId == network.netId) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                return netId;
            }
        }
    }

    private interface Criteria {
        public boolean get();
    }

    /**
     * Wait up to 500ms for {@code criteria.get()} to become true, polling.
     * Fails if 500ms goes by before {@code criteria.get()} to become true.
     */
    static private void waitFor(Criteria criteria) {
        int delays = 0;
        while (!criteria.get()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
            if (++delays == 5) fail();
        }
    }

    /**
     * Wait up to 500ms for {@code conditonVariable} to open.
     * Fails if 500ms goes by before {@code conditionVariable} opens.
     */
    static private void waitFor(ConditionVariable conditionVariable) {
        assertTrue(conditionVariable.block(500));
    }

    /**
     * This should only be used to verify that nothing happens, in other words that no unexpected
     * changes occur.  It should never be used to wait for a specific positive signal to occur.
     */
    private void shortSleep() {
        // TODO: Instead of sleeping, instead wait for all message loops to idle.
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
        }
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mServiceContext = new MockContext(getContext());

        mNetManager = mock(INetworkManagementService.class);
        mStatsService = mock(INetworkStatsService.class);
        mPolicyService = mock(INetworkPolicyManager.class);

        mService = new WrappedConnectivityService(
                mServiceContext, mNetManager, mStatsService, mPolicyService);
        mService.systemReady();
        mCm = new ConnectivityManager(mService);
    }

    private int transportToLegacyType(int transport) {
        switch (transport) {
            case TRANSPORT_WIFI:
                return TYPE_WIFI;
            case TRANSPORT_CELLULAR:
                return TYPE_MOBILE;
            default:
                throw new IllegalStateException("Unknown transport" + transport);
        }
    }

    private void verifyActiveNetwork(int transport) {
        // Test getActiveNetworkInfo()
        assertNotNull(mCm.getActiveNetworkInfo());
        assertEquals(transportToLegacyType(transport), mCm.getActiveNetworkInfo().getType());
        // Test getActiveNetwork()
        assertNotNull(mCm.getActiveNetwork());
        switch (transport) {
            case TRANSPORT_WIFI:
                assertEquals(mCm.getActiveNetwork(), mWiFiNetworkAgent.getNetwork());
                break;
            case TRANSPORT_CELLULAR:
                assertEquals(mCm.getActiveNetwork(), mCellNetworkAgent.getNetwork());
                break;
            default:
                throw new IllegalStateException("Unknown transport" + transport);
        }
        // Test getNetworkInfo(Network)
        assertNotNull(mCm.getNetworkInfo(mCm.getActiveNetwork()));
        assertEquals(transportToLegacyType(transport), mCm.getNetworkInfo(mCm.getActiveNetwork()).getType());
        // Test getNetworkCapabilities(Network)
        assertNotNull(mCm.getNetworkCapabilities(mCm.getActiveNetwork()));
        assertTrue(mCm.getNetworkCapabilities(mCm.getActiveNetwork()).hasTransport(transport));
    }

    private void verifyNoNetwork() {
        // Test getActiveNetworkInfo()
        assertNull(mCm.getActiveNetworkInfo());
        // Test getActiveNetwork()
        assertNull(mCm.getActiveNetwork());
        // Test getAllNetworks()
        assertEquals(0, mCm.getAllNetworks().length);
    }

    /**
     * Return a ConditionVariable that opens when {@code count} numbers of CONNECTIVITY_ACTION
     * broadcasts are received.
     */
    private ConditionVariable waitForConnectivityBroadcasts(final int count) {
        final ConditionVariable cv = new ConditionVariable();
        mServiceContext.registerReceiver(new BroadcastReceiver() {
                    private int remaining = count;
                    public void onReceive(Context context, Intent intent) {
                        if (--remaining == 0) {
                            cv.open();
                            mServiceContext.unregisterReceiver(this);
                        }
                    }
                }, new IntentFilter(CONNECTIVITY_ACTION));
        return cv;
    }

    @LargeTest
    public void testLingering() throws Exception {
        // Decrease linger timeout to the minimum allowed by AlarmManagerService.
        NetworkMonitor.SetDefaultLingerTime(5000);
        verifyNoNetwork();
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        assertNull(mCm.getActiveNetworkInfo());
        assertNull(mCm.getActiveNetwork());
        // Test bringing up validated cellular.
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertEquals(2, mCm.getAllNetworks().length);
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mWiFiNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mWiFiNetworkAgent.getNetwork()));
        // Test bringing up validated WiFi.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertEquals(2, mCm.getAllNetworks().length);
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mCellNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCellNetworkAgent.getNetwork()));
        // Test cellular linger timeout.
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
        }
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertEquals(1, mCm.getAllNetworks().length);
        assertEquals(mCm.getAllNetworks()[0], mCm.getActiveNetwork());
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @LargeTest
    public void testValidatedCellularOutscoresUnvalidatedWiFi() throws Exception {
        // Test bringing up unvalidated WiFi
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up unvalidated cellular
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        shortSleep();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test cellular disconnect.
        mCellNetworkAgent.disconnect();
        shortSleep();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up validated cellular
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        cv = waitForConnectivityBroadcasts(2);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @LargeTest
    public void testUnvalidatedWifiOutscoresUnvalidatedCellular() throws Exception {
        // Test bringing up unvalidated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @LargeTest
    public void testCellularOutscoresWeakWifi() throws Exception {
        // Test bringing up validated cellular.
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi getting really weak.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.adjustScore(-11);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test WiFi restoring signal strength.
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.adjustScore(11);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        mCellNetworkAgent.disconnect();
        mWiFiNetworkAgent.disconnect();
    }

    enum CallbackState {
        NONE,
        AVAILABLE,
        LOSING,
        LOST
    }

    private class TestNetworkCallback extends NetworkCallback {
        private final ConditionVariable mConditionVariable = new ConditionVariable();
        private CallbackState mLastCallback = CallbackState.NONE;

        public void onAvailable(Network network) {
            assertEquals(CallbackState.NONE, mLastCallback);
            mLastCallback = CallbackState.AVAILABLE;
            mConditionVariable.open();
        }

        public void onLosing(Network network, int maxMsToLive) {
            assertEquals(CallbackState.NONE, mLastCallback);
            mLastCallback = CallbackState.LOSING;
            mConditionVariable.open();
        }

        public void onLost(Network network) {
            assertEquals(CallbackState.NONE, mLastCallback);
            mLastCallback = CallbackState.LOST;
            mConditionVariable.open();
        }

        ConditionVariable getConditionVariable() {
            mLastCallback = CallbackState.NONE;
            mConditionVariable.close();
            return mConditionVariable;
        }

        CallbackState getLastCallback() {
            return mLastCallback;
        }
    }

    @LargeTest
    public void testStateChangeNetworkCallbacks() throws Exception {
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.registerNetworkCallback(wifiRequest, wifiNetworkCallback);
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        // Test unvalidated networks
        ConditionVariable cellCv = cellNetworkCallback.getConditionVariable();
        ConditionVariable wifiCv = wifiNetworkCallback.getConditionVariable();
        ConditionVariable cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        waitFor(cellCv);
        assertEquals(CallbackState.AVAILABLE, cellNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, wifiNetworkCallback.getLastCallback());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        waitFor(cv);

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        shortSleep();
        assertEquals(CallbackState.NONE, wifiNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, cellNetworkCallback.getLastCallback());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        waitFor(wifiCv);
        assertEquals(CallbackState.AVAILABLE, wifiNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, cellNetworkCallback.getLastCallback());
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        waitFor(cv);

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        cv = waitForConnectivityBroadcasts(2);
        mWiFiNetworkAgent.disconnect();
        waitFor(wifiCv);
        assertEquals(CallbackState.LOST, wifiNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, cellNetworkCallback.getLastCallback());
        waitFor(cv);

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent.disconnect();
        waitFor(cellCv);
        assertEquals(CallbackState.LOST, cellNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, wifiNetworkCallback.getLastCallback());
        waitFor(cv);

        // Test validated networks

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        // Our method for faking successful validation generates an additional callback, so wait
        // for broadcast instead.
        cv = waitForConnectivityBroadcasts(1);
        mCellNetworkAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        waitFor(cellCv);
        assertEquals(CallbackState.AVAILABLE, cellNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, wifiNetworkCallback.getLastCallback());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        shortSleep();
        assertEquals(CallbackState.NONE, wifiNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, cellNetworkCallback.getLastCallback());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        // Our method for faking successful validation generates an additional callback, so wait
        // for broadcast instead.
        cv = waitForConnectivityBroadcasts(1);
        mWiFiNetworkAgent = new MockNetworkAgent(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        waitFor(wifiCv);
        assertEquals(CallbackState.AVAILABLE, wifiNetworkCallback.getLastCallback());
        waitFor(cellCv);
        assertEquals(CallbackState.LOSING, cellNetworkCallback.getLastCallback());
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        mWiFiNetworkAgent.disconnect();
        waitFor(wifiCv);
        assertEquals(CallbackState.LOST, wifiNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, cellNetworkCallback.getLastCallback());

        cellCv = cellNetworkCallback.getConditionVariable();
        wifiCv = wifiNetworkCallback.getConditionVariable();
        mCellNetworkAgent.disconnect();
        waitFor(cellCv);
        assertEquals(CallbackState.LOST, cellNetworkCallback.getLastCallback());
        assertEquals(CallbackState.NONE, wifiNetworkCallback.getLastCallback());
    }

    @LargeTest
    public void testNetworkFactoryRequests() throws Exception {
        NetworkCapabilities filter = new NetworkCapabilities();
        filter.addCapability(NET_CAPABILITY_INTERNET);
        final HandlerThread handlerThread = new HandlerThread("testNetworkFactoryRequests");
        handlerThread.start();
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);
        ConditionVariable cv = testFactory.getNetworkStartedCV();
        testFactory.register();
        waitFor(cv);
        assertEquals(1, testFactory.getMyRequestCount());
        assertEquals(true, testFactory.getMyStartRequested());

        // now bring in a higher scored network
        MockNetworkAgent testAgent = new MockNetworkAgent(TRANSPORT_CELLULAR);
        cv = waitForConnectivityBroadcasts(1);
        ConditionVariable cvRelease = testFactory.getNetworkStoppedCV();
        testAgent.connect(true);
        waitFor(cv);
        // part of the bringup makes another network request and then releases it
        // wait for the release
        waitFor(cvRelease);
        assertEquals(false, testFactory.getMyStartRequested());
        testFactory.waitForNetworkRequests(1);

        // bring in a bunch of requests..
        ConnectivityManager.NetworkCallback[] networkCallbacks =
                new ConnectivityManager.NetworkCallback[10];
        for (int i = 0; i< networkCallbacks.length; i++) {
            networkCallbacks[i] = new ConnectivityManager.NetworkCallback();
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            mCm.requestNetwork(builder.build(), networkCallbacks[i]);
        }
        testFactory.waitForNetworkRequests(11);
        assertEquals(false, testFactory.getMyStartRequested());

        // remove the requests
        for (int i = 0; i < networkCallbacks.length; i++) {
            mCm.unregisterNetworkCallback(networkCallbacks[i]);
        }
        testFactory.waitForNetworkRequests(1);
        assertEquals(false, testFactory.getMyStartRequested());

        // drop the higher scored network
        cv = waitForConnectivityBroadcasts(1);
        testAgent.disconnect();
        waitFor(cv);
        assertEquals(1, testFactory.getMyRequestCount());
        assertEquals(true, testFactory.getMyStartRequested());

        testFactory.unregister();
        handlerThread.quit();
    }

    @LargeTest
    public void testNoMutableNetworkRequests() throws Exception {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, new Intent("a"), 0);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        try {
            mCm.requestNetwork(builder.build(), new NetworkCallback());
            fail();
        } catch (IllegalArgumentException expected) {}
        try {
            mCm.requestNetwork(builder.build(), pendingIntent);
            fail();
        } catch (IllegalArgumentException expected) {}
        builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        try {
            mCm.requestNetwork(builder.build(), new NetworkCallback());
            fail();
        } catch (IllegalArgumentException expected) {}
        try {
            mCm.requestNetwork(builder.build(), pendingIntent);
            fail();
        } catch (IllegalArgumentException expected) {}
    }

//    @Override
//    public void tearDown() throws Exception {
//        super.tearDown();
//    }
//
//    public void testMobileConnectedAddedRoutes() throws Exception {
//        Future<?> nextConnBroadcast;
//
//        // bring up mobile network
//        mMobile.info.setDetailedState(DetailedState.CONNECTED, null, null);
//        mMobile.link.setInterfaceName(MOBILE_IFACE);
//        mMobile.link.addRoute(MOBILE_ROUTE_V4);
//        mMobile.link.addRoute(MOBILE_ROUTE_V6);
//        mMobile.doReturnDefaults();
//
//        cv = waitForConnectivityBroadcasts(1);
//        mTrackerHandler.obtainMessage(EVENT_STATE_CHANGED, mMobile.info).sendToTarget();
//        waitFor(cv);
//
//        // verify that both routes were added
//        int mobileNetId = mMobile.tracker.getNetwork().netId;
//        verify(mNetManager).addRoute(eq(mobileNetId), eq(MOBILE_ROUTE_V4));
//        verify(mNetManager).addRoute(eq(mobileNetId), eq(MOBILE_ROUTE_V6));
//    }
//
//    public void testMobileWifiHandoff() throws Exception {
//        Future<?> nextConnBroadcast;
//
//        // bring up mobile network
//        mMobile.info.setDetailedState(DetailedState.CONNECTED, null, null);
//        mMobile.link.setInterfaceName(MOBILE_IFACE);
//        mMobile.link.addRoute(MOBILE_ROUTE_V4);
//        mMobile.link.addRoute(MOBILE_ROUTE_V6);
//        mMobile.doReturnDefaults();
//
//        cv = waitForConnectivityBroadcasts(1);
//        mTrackerHandler.obtainMessage(EVENT_STATE_CHANGED, mMobile.info).sendToTarget();
//        waitFor(cv);
//
//        reset(mNetManager);
//
//        // now bring up wifi network
//        mWifi.info.setDetailedState(DetailedState.CONNECTED, null, null);
//        mWifi.link.setInterfaceName(WIFI_IFACE);
//        mWifi.link.addRoute(WIFI_ROUTE_V4);
//        mWifi.link.addRoute(WIFI_ROUTE_V6);
//        mWifi.doReturnDefaults();
//
//        // expect that mobile will be torn down
//        doReturn(true).when(mMobile.tracker).teardown();
//
//        cv = waitForConnectivityBroadcasts(1);
//        mTrackerHandler.obtainMessage(EVENT_STATE_CHANGED, mWifi.info).sendToTarget();
//        waitFor(cv);
//
//        // verify that wifi routes added, and teardown requested
//        int wifiNetId = mWifi.tracker.getNetwork().netId;
//        verify(mNetManager).addRoute(eq(wifiNetId), eq(WIFI_ROUTE_V4));
//        verify(mNetManager).addRoute(eq(wifiNetId), eq(WIFI_ROUTE_V6));
//        verify(mMobile.tracker).teardown();
//
//        int mobileNetId = mMobile.tracker.getNetwork().netId;
//
//        reset(mNetManager, mMobile.tracker);
//
//        // tear down mobile network, as requested
//        mMobile.info.setDetailedState(DetailedState.DISCONNECTED, null, null);
//        mMobile.link.clear();
//        mMobile.doReturnDefaults();
//
//        cv = waitForConnectivityBroadcasts(1);
//        mTrackerHandler.obtainMessage(EVENT_STATE_CHANGED, mMobile.info).sendToTarget();
//        waitFor(cv);
//
//        verify(mNetManager).removeRoute(eq(mobileNetId), eq(MOBILE_ROUTE_V4));
//        verify(mNetManager).removeRoute(eq(mobileNetId), eq(MOBILE_ROUTE_V6));
//
//    }

    private static InetAddress parse(String addr) {
        return InetAddress.parseNumericAddress(addr);
    }

}
