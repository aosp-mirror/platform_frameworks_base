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

package com.android.server.vcn;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.content.Context;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiInfo;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.test.TestLooper;
import android.telephony.SubscriptionInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkRecord;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tests for TelephonySubscriptionTracker */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionTest {
    private static final int TEST_UID = Process.myUid();
    private static final ParcelUuid TEST_PARCEL_UUID = new ParcelUuid(UUID.randomUUID());
    private static final int TEST_SIM_SLOT_INDEX = 1;
    private static final int TEST_SUBSCRIPTION_ID_1 = 2;
    private static final SubscriptionInfo TEST_SUBINFO_1 = mock(SubscriptionInfo.class);
    private static final int TEST_SUBSCRIPTION_ID_2 = 3;
    private static final SubscriptionInfo TEST_SUBINFO_2 = mock(SubscriptionInfo.class);
    private static final Map<Integer, ParcelUuid> TEST_SUBID_TO_GROUP_MAP;

    static {
        final Map<Integer, ParcelUuid> subIdToGroupMap = new HashMap<>();
        subIdToGroupMap.put(TEST_SUBSCRIPTION_ID_1, TEST_PARCEL_UUID);
        subIdToGroupMap.put(TEST_SUBSCRIPTION_ID_2, TEST_PARCEL_UUID);
        TEST_SUBID_TO_GROUP_MAP = Collections.unmodifiableMap(subIdToGroupMap);
    }

    @NonNull private final Context mContext;
    @NonNull private final TestLooper mTestLooper;
    @NonNull private final VcnNetworkProvider mVcnNetworkProvider;
    @NonNull private final VcnGatewayConnection.Dependencies mDeps;
    @NonNull private final WifiInfo mWifiInfo;

    public VcnGatewayConnectionTest() {
        mContext = mock(Context.class);
        mTestLooper = new TestLooper();
        mVcnNetworkProvider = mock(VcnNetworkProvider.class);
        mDeps = mock(VcnGatewayConnection.Dependencies.class);
        mWifiInfo = mock(WifiInfo.class);
    }

    private void verifyBuildNetworkCapabilitiesCommon(int transportType) {
        final NetworkCapabilities underlyingCaps = new NetworkCapabilities();
        underlyingCaps.addTransportType(transportType);
        underlyingCaps.addCapability(NET_CAPABILITY_NOT_METERED);
        underlyingCaps.addCapability(NET_CAPABILITY_NOT_ROAMING);

        if (transportType == TRANSPORT_WIFI) {
            underlyingCaps.setTransportInfo(mWifiInfo);
            underlyingCaps.setOwnerUid(TEST_UID);
        } else if (transportType == TRANSPORT_CELLULAR) {
            underlyingCaps.setAdministratorUids(new int[] {TEST_UID});
            underlyingCaps.setNetworkSpecifier(
                    new TelephonyNetworkSpecifier(TEST_SUBSCRIPTION_ID_1));
        }

        UnderlyingNetworkRecord record =
                new UnderlyingNetworkRecord(
                        new Network(0), underlyingCaps, new LinkProperties(), false);
        final NetworkCapabilities vcnCaps =
                VcnGatewayConnection.buildNetworkCapabilities(
                        VcnGatewayConnectionConfigTest.buildTestConfig(), record);

        assertTrue(vcnCaps.hasTransport(TRANSPORT_CELLULAR));
        assertTrue(vcnCaps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(vcnCaps.hasCapability(NET_CAPABILITY_NOT_ROAMING));
        assertArrayEquals(new int[] {TEST_UID}, vcnCaps.getAdministratorUids());
        assertTrue(vcnCaps.getTransportInfo() instanceof VcnTransportInfo);

        final VcnTransportInfo info = (VcnTransportInfo) vcnCaps.getTransportInfo();
        if (transportType == TRANSPORT_WIFI) {
            assertEquals(mWifiInfo, info.getWifiInfo());
        } else if (transportType == TRANSPORT_CELLULAR) {
            assertEquals(TEST_SUBSCRIPTION_ID_1, info.getSubId());
        }
    }

    @Test
    public void testBuildNetworkCapabilitiesUnderlyingWifi() throws Exception {
        verifyBuildNetworkCapabilitiesCommon(TRANSPORT_WIFI);
    }

    @Test
    public void testBuildNetworkCapabilitiesUnderlyingCell() throws Exception {
        verifyBuildNetworkCapabilitiesCommon(TRANSPORT_CELLULAR);
    }
}
