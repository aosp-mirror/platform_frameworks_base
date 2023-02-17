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

import static android.net.NetworkCapabilities.REDACT_FOR_ACCESS_FINE_LOCATION;
import static android.net.NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;
import static android.net.NetworkCapabilities.REDACT_NONE;
import static android.net.vcn.VcnGatewayConnectionConfig.MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.Parcel;

import org.junit.Test;

import java.util.Arrays;

public class VcnTransportInfoTest {
    private static final int SUB_ID = 1;
    private static final int NETWORK_ID = 5;
    private static final int MIN_UDP_PORT_4500_NAT_TIMEOUT = 120;
    private static final WifiInfo WIFI_INFO =
            new WifiInfo.Builder().setNetworkId(NETWORK_ID).build();

    private static final VcnTransportInfo CELL_UNDERLYING_INFO =
            new VcnTransportInfo(SUB_ID, MIN_UDP_PORT_4500_NAT_TIMEOUT);
    private static final VcnTransportInfo WIFI_UNDERLYING_INFO =
            new VcnTransportInfo(WIFI_INFO, MIN_UDP_PORT_4500_NAT_TIMEOUT);

    @Test
    public void testGetWifiInfo() {
        assertEquals(WIFI_INFO, WIFI_UNDERLYING_INFO.getWifiInfo());

        assertNull(CELL_UNDERLYING_INFO.getWifiInfo());
    }

    @Test
    public void testGetSubId() {
        assertEquals(SUB_ID, CELL_UNDERLYING_INFO.getSubId());

        assertEquals(INVALID_SUBSCRIPTION_ID, WIFI_UNDERLYING_INFO.getSubId());
    }

    @Test
    public void testGetMinUdpPort4500NatTimeoutSeconds() {
        assertEquals(
                MIN_UDP_PORT_4500_NAT_TIMEOUT,
                CELL_UNDERLYING_INFO.getMinUdpPort4500NatTimeoutSeconds());
        assertEquals(
                MIN_UDP_PORT_4500_NAT_TIMEOUT,
                WIFI_UNDERLYING_INFO.getMinUdpPort4500NatTimeoutSeconds());
    }

    @Test
    public void testMakeCopyRedactForNetworkSettings() {
        for (VcnTransportInfo info : Arrays.asList(CELL_UNDERLYING_INFO, WIFI_UNDERLYING_INFO)) {
            assertEquals(
                    INVALID_SUBSCRIPTION_ID,
                    ((VcnTransportInfo) info.makeCopy(REDACT_FOR_NETWORK_SETTINGS))
                            .getSubId());
            assertNull(
                    ((VcnTransportInfo) info.makeCopy(REDACT_FOR_NETWORK_SETTINGS))
                            .getWifiInfo());
            assertEquals(
                    MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET,
                    ((VcnTransportInfo) info.makeCopy(REDACT_FOR_NETWORK_SETTINGS))
                            .getMinUdpPort4500NatTimeoutSeconds());
        }
    }

    @Test
    public void testMakeCopyRedactForAccessFineLocation() {
        assertEquals(
                SUB_ID,
                ((VcnTransportInfo) CELL_UNDERLYING_INFO.makeCopy(REDACT_FOR_ACCESS_FINE_LOCATION))
                        .getSubId());
        assertEquals(
                MIN_UDP_PORT_4500_NAT_TIMEOUT,
                ((VcnTransportInfo) CELL_UNDERLYING_INFO.makeCopy(REDACT_FOR_ACCESS_FINE_LOCATION))
                        .getMinUdpPort4500NatTimeoutSeconds());
        assertEquals(
                WifiConfiguration.INVALID_NETWORK_ID,
                ((VcnTransportInfo) WIFI_UNDERLYING_INFO.makeCopy(REDACT_FOR_ACCESS_FINE_LOCATION))
                        .getWifiInfo().getNetworkId());
        assertEquals(
                MIN_UDP_PORT_4500_NAT_TIMEOUT,
                ((VcnTransportInfo) WIFI_UNDERLYING_INFO.makeCopy(REDACT_FOR_ACCESS_FINE_LOCATION))
                        .getMinUdpPort4500NatTimeoutSeconds());
    }

    @Test
    public void testEquals() {
        assertEquals(CELL_UNDERLYING_INFO, CELL_UNDERLYING_INFO);
        assertEquals(WIFI_UNDERLYING_INFO, WIFI_UNDERLYING_INFO);
        assertNotEquals(CELL_UNDERLYING_INFO, WIFI_UNDERLYING_INFO);
    }

    @Test
    public void testParcelUnparcel() {
        verifyParcelingIsNull(CELL_UNDERLYING_INFO);
        verifyParcelingIsNull(WIFI_UNDERLYING_INFO);
    }

    private void verifyParcelingIsNull(VcnTransportInfo vcnTransportInfo) {
        VcnTransportInfo redacted = (VcnTransportInfo) vcnTransportInfo.makeCopy(
                NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS);

        Parcel parcel = Parcel.obtain();
        redacted.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        assertNull(VcnTransportInfo.CREATOR.createFromParcel(parcel));
    }

    @Test
    public void testParcelNotRedactedForSysUi() {
        VcnTransportInfo cellRedacted = parcelForSysUi(CELL_UNDERLYING_INFO);
        assertEquals(SUB_ID, cellRedacted.getSubId());
        assertEquals(
                MIN_UDP_PORT_4500_NAT_TIMEOUT, cellRedacted.getMinUdpPort4500NatTimeoutSeconds());
        VcnTransportInfo wifiRedacted = parcelForSysUi(WIFI_UNDERLYING_INFO);
        assertEquals(NETWORK_ID, wifiRedacted.getWifiInfo().getNetworkId());
        assertEquals(
                MIN_UDP_PORT_4500_NAT_TIMEOUT, wifiRedacted.getMinUdpPort4500NatTimeoutSeconds());
    }

    private VcnTransportInfo parcelForSysUi(VcnTransportInfo vcnTransportInfo) {
        // Allow fully unredacted; SysUI will have all the relevant permissions.
        final VcnTransportInfo unRedacted = (VcnTransportInfo) vcnTransportInfo.makeCopy(
                REDACT_NONE);
        final Parcel parcel = Parcel.obtain();
        unRedacted.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        final VcnTransportInfo unparceled = VcnTransportInfo.CREATOR.createFromParcel(parcel);
        assertEquals(vcnTransportInfo, unparceled);
        return unparceled;
    }
}
