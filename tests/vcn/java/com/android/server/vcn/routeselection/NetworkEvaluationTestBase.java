/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.vcn.routeselection;

import static com.android.server.vcn.VcnTestUtils.setupSystemService;
import static com.android.server.vcn.routeselection.UnderlyingNetworkControllerTest.getLinkPropertiesWithName;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.IpSecConfig;
import android.net.IpSecTransform;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.FeatureFlags;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.test.TestLooper;
import android.telephony.TelephonyManager;

import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.VcnContext;
import com.android.server.vcn.VcnNetworkProvider;

import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;
import java.util.UUID;

public abstract class NetworkEvaluationTestBase {
    protected static final String SSID = "TestWifi";
    protected static final String SSID_OTHER = "TestWifiOther";
    protected static final String PLMN_ID = "123456";
    protected static final String PLMN_ID_OTHER = "234567";

    protected static final int SUB_ID = 1;
    protected static final int WIFI_RSSI = -60;
    protected static final int WIFI_RSSI_HIGH = -50;
    protected static final int WIFI_RSSI_LOW = -80;
    protected static final int CARRIER_ID = 1;
    protected static final int CARRIER_ID_OTHER = 2;

    protected static final int LINK_UPSTREAM_BANDWIDTH_KBPS = 1024;
    protected static final int LINK_DOWNSTREAM_BANDWIDTH_KBPS = 2048;

    protected static final int TEST_MIN_UPSTREAM_BANDWIDTH_KBPS = 100;
    protected static final int TEST_MIN_DOWNSTREAM_BANDWIDTH_KBPS = 200;

    protected static final ParcelUuid SUB_GROUP = new ParcelUuid(new UUID(0, 0));

    protected static final NetworkCapabilities WIFI_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setSignalStrength(WIFI_RSSI)
                    .setSsid(SSID)
                    .setLinkUpstreamBandwidthKbps(LINK_UPSTREAM_BANDWIDTH_KBPS)
                    .setLinkDownstreamBandwidthKbps(LINK_DOWNSTREAM_BANDWIDTH_KBPS)
                    .build();

    protected static final TelephonyNetworkSpecifier TEL_NETWORK_SPECIFIER =
            new TelephonyNetworkSpecifier.Builder().setSubscriptionId(SUB_ID).build();
    protected static final NetworkCapabilities CELL_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_DUN)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .setSubscriptionIds(Set.of(SUB_ID))
                    .setNetworkSpecifier(TEL_NETWORK_SPECIFIER)
                    .setLinkUpstreamBandwidthKbps(LINK_UPSTREAM_BANDWIDTH_KBPS)
                    .setLinkDownstreamBandwidthKbps(LINK_DOWNSTREAM_BANDWIDTH_KBPS)
                    .build();

    protected static final LinkProperties LINK_PROPERTIES = getLinkPropertiesWithName("test_iface");

    @Mock protected Context mContext;
    @Mock protected Network mNetwork;
    @Mock protected FeatureFlags mFeatureFlags;
    @Mock protected com.android.net.flags.FeatureFlags mCoreNetFeatureFlags;
    @Mock protected TelephonySubscriptionSnapshot mSubscriptionSnapshot;
    @Mock protected TelephonyManager mTelephonyManager;
    @Mock protected IPowerManager mPowerManagerService;

    protected TestLooper mTestLooper;
    protected VcnContext mVcnContext;
    protected PowerManager mPowerManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mNetwork.getNetId()).thenReturn(-1);

        mTestLooper = new TestLooper();
        mVcnContext =
                spy(
                        new VcnContext(
                                mContext,
                                mTestLooper.getLooper(),
                                mock(VcnNetworkProvider.class),
                                false /* isInTestMode */));
        doNothing().when(mVcnContext).ensureRunningOnLooperThread();

        doReturn(true).when(mVcnContext).isFlagNetworkMetricMonitorEnabled();
        doReturn(true).when(mVcnContext).isFlagIpSecTransformStateEnabled();

        setupSystemService(
                mContext, mTelephonyManager, Context.TELEPHONY_SERVICE, TelephonyManager.class);
        when(mTelephonyManager.createForSubscriptionId(SUB_ID)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getNetworkOperator()).thenReturn(PLMN_ID);
        when(mTelephonyManager.getSimSpecificCarrierId()).thenReturn(CARRIER_ID);

        mPowerManager =
                new PowerManager(
                        mContext,
                        mPowerManagerService,
                        mock(IThermalService.class),
                        mock(Handler.class));
        setupSystemService(mContext, mPowerManager, Context.POWER_SERVICE, PowerManager.class);
    }

    protected IpSecTransform makeDummyIpSecTransform() throws Exception {
        return new IpSecTransform(mContext, new IpSecConfig());
    }
}
