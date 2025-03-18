/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.vcn;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.net.wifi.WifiInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnUtilsTest {
    private static final int SUB_ID = 1;

    private static final WifiInfo WIFI_INFO = new WifiInfo.Builder().build();
    private static final TelephonyNetworkSpecifier TEL_NETWORK_SPECIFIER =
            new TelephonyNetworkSpecifier.Builder().setSubscriptionId(SUB_ID).build();
    private static final VcnTransportInfo VCN_TRANSPORT_INFO =
            new VcnTransportInfo.Builder().build();

    private ConnectivityManager mMockConnectivityManager;
    private Network mMockWifiNetwork;
    private Network mMockCellNetwork;

    private NetworkCapabilities mVcnCapsWithUnderlyingWifi;
    private NetworkCapabilities mVcnCapsWithUnderlyingCell;

    @Before
    public void setUp() {
        mMockConnectivityManager = mock(ConnectivityManager.class);

        mMockWifiNetwork = mock(Network.class);
        mVcnCapsWithUnderlyingWifi = newVcnCaps(VCN_TRANSPORT_INFO, mMockWifiNetwork);
        final NetworkCapabilities wifiCaps =
                new NetworkCapabilities.Builder()
                        .addTransportType(TRANSPORT_WIFI)
                        .setTransportInfo(WIFI_INFO)
                        .build();
        when(mMockConnectivityManager.getNetworkCapabilities(mMockWifiNetwork))
                .thenReturn(wifiCaps);

        mMockCellNetwork = mock(Network.class);
        mVcnCapsWithUnderlyingCell = newVcnCaps(VCN_TRANSPORT_INFO, mMockCellNetwork);
        final NetworkCapabilities cellCaps =
                new NetworkCapabilities.Builder()
                        .addTransportType(TRANSPORT_CELLULAR)
                        .setNetworkSpecifier(TEL_NETWORK_SPECIFIER)
                        .build();
        when(mMockConnectivityManager.getNetworkCapabilities(mMockCellNetwork))
                .thenReturn(cellCaps);
    }

    private static NetworkCapabilities newVcnCaps(
            VcnTransportInfo vcnTransportInfo, Network underlyingNetwork) {
        return new NetworkCapabilities.Builder()
                .setTransportInfo(vcnTransportInfo)
                .setUnderlyingNetworks(Collections.singletonList(underlyingNetwork))
                .build();
    }

    @Test
    public void getWifiInfoFromVcnCaps() {
        assertEquals(
                WIFI_INFO,
                VcnUtils.getWifiInfoFromVcnCaps(
                        mMockConnectivityManager, mVcnCapsWithUnderlyingWifi));
    }

    @Test
    public void getWifiInfoFromVcnCaps_onVcnWithUnderlyingCell() {
        assertNull(
                VcnUtils.getWifiInfoFromVcnCaps(
                        mMockConnectivityManager, mVcnCapsWithUnderlyingCell));
    }

    @Test
    public void getSubIdFromVcnCaps() {
        assertEquals(
                SUB_ID,
                VcnUtils.getSubIdFromVcnCaps(mMockConnectivityManager, mVcnCapsWithUnderlyingCell));
    }

    @Test
    public void getSubIdFromVcnCaps_onVcnWithUnderlyingWifi() {
        assertEquals(
                INVALID_SUBSCRIPTION_ID,
                VcnUtils.getSubIdFromVcnCaps(mMockConnectivityManager, mVcnCapsWithUnderlyingWifi));
    }

    @Test
    public void getSubIdFromVcnCaps_onNonVcnNetwork() {
        assertEquals(
                INVALID_SUBSCRIPTION_ID,
                VcnUtils.getSubIdFromVcnCaps(
                        mMockConnectivityManager, new NetworkCapabilities.Builder().build()));
    }

    @Test
    public void getSubIdFromVcnCaps_withMultipleUnderlyingNetworks() {
        final NetworkCapabilities vcnCaps =
                new NetworkCapabilities.Builder(mVcnCapsWithUnderlyingCell)
                        .setUnderlyingNetworks(
                                Arrays.asList(
                                        new Network[] {mMockCellNetwork, mock(Network.class)}))
                        .build();
        assertEquals(SUB_ID, VcnUtils.getSubIdFromVcnCaps(mMockConnectivityManager, vcnCaps));
    }
}
