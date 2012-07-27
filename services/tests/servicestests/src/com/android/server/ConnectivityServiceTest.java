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

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.NetworkStateTracker.EVENT_STATE_CHANGED;
import static com.google.testing.littlemock.LittleMock.anyInt;
import static com.google.testing.littlemock.LittleMock.createCaptor;
import static com.google.testing.littlemock.LittleMock.doNothing;
import static com.google.testing.littlemock.LittleMock.doReturn;
import static com.google.testing.littlemock.LittleMock.doThrow;
import static com.google.testing.littlemock.LittleMock.eq;
import static com.google.testing.littlemock.LittleMock.isA;
import static com.google.testing.littlemock.LittleMock.mock;
import static com.google.testing.littlemock.LittleMock.reset;
import static com.google.testing.littlemock.LittleMock.verify;

import android.content.Context;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkStateTracker;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.util.LogPrinter;

import com.google.testing.littlemock.ArgumentCaptor;

import java.net.InetAddress;
import java.util.concurrent.Future;

/**
 * Tests for {@link ConnectivityService}.
 */
@LargeTest
public class ConnectivityServiceTest extends AndroidTestCase {
    private static final String TAG = "ConnectivityServiceTest";

    private static final String MOBILE_IFACE = "rmnet3";
    private static final String WIFI_IFACE = "wlan6";

    private static final RouteInfo MOBILE_ROUTE_V4 = RouteInfo.makeHostRoute(parse("10.0.0.33"));
    private static final RouteInfo MOBILE_ROUTE_V6 = RouteInfo.makeHostRoute(parse("fd00::33"));

    private static final RouteInfo WIFI_ROUTE_V4 = RouteInfo.makeHostRoute(
            parse("192.168.0.66"), parse("192.168.0.1"));
    private static final RouteInfo WIFI_ROUTE_V6 = RouteInfo.makeHostRoute(
            parse("fd00::66"), parse("fd00::"));

    private INetworkManagementService mNetManager;
    private INetworkStatsService mStatsService;
    private INetworkPolicyManager mPolicyService;
    private ConnectivityService.NetworkFactory mNetFactory;

    private BroadcastInterceptingContext mServiceContext;
    private ConnectivityService mService;

    private MockNetwork mMobile;
    private MockNetwork mWifi;

    private Handler mTrackerHandler;

    private static class MockNetwork {
        public NetworkStateTracker tracker;
        public NetworkInfo info;
        public LinkProperties link;

        public MockNetwork(int type) {
            tracker = mock(NetworkStateTracker.class);
            info = new NetworkInfo(type, -1, getNetworkTypeName(type), null);
            link = new LinkProperties();
        }

        public void doReturnDefaults() {
            // TODO: eventually CS should make defensive copies
            doReturn(new NetworkInfo(info)).when(tracker).getNetworkInfo();
            doReturn(new LinkProperties(link)).when(tracker).getLinkProperties();

            // fallback to default TCP buffers
            doReturn("").when(tracker).getTcpBufferSizesPropName();
        }
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mServiceContext = new BroadcastInterceptingContext(getContext());

        mNetManager = mock(INetworkManagementService.class);
        mStatsService = mock(INetworkStatsService.class);
        mPolicyService = mock(INetworkPolicyManager.class);
        mNetFactory = mock(ConnectivityService.NetworkFactory.class);

        mMobile = new MockNetwork(TYPE_MOBILE);
        mWifi = new MockNetwork(TYPE_WIFI);

        // omit most network trackers
        doThrow(new IllegalArgumentException("Not supported in test environment"))
                .when(mNetFactory).createTracker(anyInt(), isA(NetworkConfig.class));

        doReturn(mMobile.tracker)
                .when(mNetFactory).createTracker(eq(TYPE_MOBILE), isA(NetworkConfig.class));
        doReturn(mWifi.tracker)
                .when(mNetFactory).createTracker(eq(TYPE_WIFI), isA(NetworkConfig.class));

        final ArgumentCaptor<Handler> trackerHandler = createCaptor();
        doNothing().when(mMobile.tracker)
                .startMonitoring(isA(Context.class), trackerHandler.capture());

        mService = new ConnectivityService(
                mServiceContext, mNetManager, mStatsService, mPolicyService, mNetFactory);
        mService.systemReady();

        mTrackerHandler = trackerHandler.getValue();
        mTrackerHandler.getLooper().setMessageLogging(new LogPrinter(Log.INFO, TAG));
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    public void testMobileConnectedAddedRoutes() throws Exception {
        Future<?> nextConnBroadcast;

        // bring up mobile network
        mMobile.info.setDetailedState(DetailedState.CONNECTED, null, null);
        mMobile.link.setInterfaceName(MOBILE_IFACE);
        mMobile.link.addRoute(MOBILE_ROUTE_V4);
        mMobile.link.addRoute(MOBILE_ROUTE_V6);
        mMobile.doReturnDefaults();

        nextConnBroadcast = mServiceContext.nextBroadcastIntent(CONNECTIVITY_ACTION_IMMEDIATE);
        mTrackerHandler.obtainMessage(EVENT_STATE_CHANGED, mMobile.info).sendToTarget();
        nextConnBroadcast.get();

        // verify that both routes were added and DNS was flushed
        verify(mNetManager).addRoute(eq(MOBILE_IFACE), eq(MOBILE_ROUTE_V4));
        verify(mNetManager).addRoute(eq(MOBILE_IFACE), eq(MOBILE_ROUTE_V6));
        verify(mNetManager).flushInterfaceDnsCache(MOBILE_IFACE);

    }

    public void testMobileWifiHandoff() throws Exception {
        Future<?> nextConnBroadcast;

        // bring up mobile network
        mMobile.info.setDetailedState(DetailedState.CONNECTED, null, null);
        mMobile.link.setInterfaceName(MOBILE_IFACE);
        mMobile.link.addRoute(MOBILE_ROUTE_V4);
        mMobile.link.addRoute(MOBILE_ROUTE_V6);
        mMobile.doReturnDefaults();

        nextConnBroadcast = mServiceContext.nextBroadcastIntent(CONNECTIVITY_ACTION_IMMEDIATE);
        mTrackerHandler.obtainMessage(EVENT_STATE_CHANGED, mMobile.info).sendToTarget();
        nextConnBroadcast.get();

        reset(mNetManager);

        // now bring up wifi network
        mWifi.info.setDetailedState(DetailedState.CONNECTED, null, null);
        mWifi.link.setInterfaceName(WIFI_IFACE);
        mWifi.link.addRoute(WIFI_ROUTE_V4);
        mWifi.link.addRoute(WIFI_ROUTE_V6);
        mWifi.doReturnDefaults();

        // expect that mobile will be torn down
        doReturn(true).when(mMobile.tracker).teardown();

        nextConnBroadcast = mServiceContext.nextBroadcastIntent(CONNECTIVITY_ACTION_IMMEDIATE);
        mTrackerHandler.obtainMessage(EVENT_STATE_CHANGED, mWifi.info).sendToTarget();
        nextConnBroadcast.get();

        // verify that wifi routes added, and teardown requested
        verify(mNetManager).addRoute(eq(WIFI_IFACE), eq(WIFI_ROUTE_V4));
        verify(mNetManager).addRoute(eq(WIFI_IFACE), eq(WIFI_ROUTE_V6));
        verify(mNetManager).flushInterfaceDnsCache(WIFI_IFACE);
        verify(mMobile.tracker).teardown();

        reset(mNetManager, mMobile.tracker);

        // tear down mobile network, as requested
        mMobile.info.setDetailedState(DetailedState.DISCONNECTED, null, null);
        mMobile.link.clear();
        mMobile.doReturnDefaults();

        nextConnBroadcast = mServiceContext.nextBroadcastIntent(CONNECTIVITY_ACTION_IMMEDIATE);
        mTrackerHandler.obtainMessage(EVENT_STATE_CHANGED, mMobile.info).sendToTarget();
        nextConnBroadcast.get();

        verify(mNetManager).removeRoute(eq(MOBILE_IFACE), eq(MOBILE_ROUTE_V4));
        verify(mNetManager).removeRoute(eq(MOBILE_IFACE), eq(MOBILE_ROUTE_V6));

    }

    private static InetAddress parse(String addr) {
        return InetAddress.parseNumericAddress(addr);
    }
}
