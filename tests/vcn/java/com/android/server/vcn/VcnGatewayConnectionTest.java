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

import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.net.vcn.VcnGatewayConnectionConfigTest;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiInfo;
import android.os.ParcelUuid;
import android.os.Process;
import android.telephony.SubscriptionInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;
import com.android.server.vcn.UnderlyingNetworkTracker.UnderlyingNetworkRecord;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tests for TelephonySubscriptionTracker */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionTest extends VcnGatewayConnectionTestBase {
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

    private WifiInfo mWifiInfo;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mWifiInfo = mock(WifiInfo.class);
        doReturn(mWifiInfo).when(mWifiInfo).makeCopy(anyLong());
    }

    private void verifyBuildNetworkCapabilitiesCommon(
            int transportType, boolean isMobileDataEnabled) {
        final NetworkCapabilities.Builder capBuilder = new NetworkCapabilities.Builder();
        capBuilder.addTransportType(transportType);
        capBuilder.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        capBuilder.addCapability(NET_CAPABILITY_NOT_METERED);
        capBuilder.addCapability(NET_CAPABILITY_NOT_ROAMING);

        if (transportType == TRANSPORT_WIFI) {
            capBuilder.setTransportInfo(mWifiInfo);
            capBuilder.setOwnerUid(TEST_UID);
        } else if (transportType == TRANSPORT_CELLULAR) {
            capBuilder.setNetworkSpecifier(
                    new TelephonyNetworkSpecifier(TEST_SUBSCRIPTION_ID_1));
        }
        capBuilder.setAdministratorUids(new int[] {TEST_UID});
        UnderlyingNetworkRecord record = new UnderlyingNetworkRecord(
                mock(Network.class, CALLS_REAL_METHODS),
                capBuilder.build(), new LinkProperties(), false);
        final NetworkCapabilities vcnCaps =
                VcnGatewayConnection.buildNetworkCapabilities(
                        VcnGatewayConnectionConfigTest.buildTestConfig(),
                        record,
                        isMobileDataEnabled);

        assertTrue(vcnCaps.hasTransport(TRANSPORT_CELLULAR));
        assertTrue(vcnCaps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(vcnCaps.hasCapability(NET_CAPABILITY_NOT_ROAMING));

        for (int cap : VcnGatewayConnectionConfigTest.EXPOSED_CAPS) {
            if (cap == NET_CAPABILITY_INTERNET || cap == NET_CAPABILITY_DUN) {
                assertEquals(isMobileDataEnabled, vcnCaps.hasCapability(cap));
            } else {
                assertTrue(vcnCaps.hasCapability(cap));
            }
        }

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
        verifyBuildNetworkCapabilitiesCommon(TRANSPORT_WIFI, true /* isMobileDataEnabled */);
    }

    @Test
    public void testBuildNetworkCapabilitiesUnderlyingCell() throws Exception {
        verifyBuildNetworkCapabilitiesCommon(TRANSPORT_CELLULAR, true /* isMobileDataEnabled */);
    }

    @Test
    public void testBuildNetworkCapabilitiesMobileDataDisabled() throws Exception {
        verifyBuildNetworkCapabilitiesCommon(TRANSPORT_CELLULAR, false /* isMobileDataEnabled */);
    }

    @Test
    public void testSubscriptionSnapshotUpdateNotifiesUnderlyingNetworkTracker() {
        verifyWakeLockSetUp();

        final TelephonySubscriptionSnapshot updatedSnapshot =
                mock(TelephonySubscriptionSnapshot.class);
        mGatewayConnection.updateSubscriptionSnapshot(updatedSnapshot);

        verify(mUnderlyingNetworkTracker).updateSubscriptionSnapshot(eq(updatedSnapshot));
        verifyWakeLockAcquired();

        mTestLooper.dispatchAll();

        verifyWakeLockReleased();
    }

    @Test
    public void testNonNullUnderlyingNetworkRecordUpdateCancelsAlarm() {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(null);

        verifyDisconnectRequestAlarmAndGetCallback(false /* expectCanceled */);

        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_1);

        verify(mDisconnectRequestAlarm).cancel();
    }
}
