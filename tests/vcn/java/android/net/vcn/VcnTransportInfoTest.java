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

import static android.net.NetworkCapabilities.REDACT_ALL;
import static android.net.NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import android.net.wifi.WifiInfo;
import android.os.Parcel;

import org.junit.Test;

public class VcnTransportInfoTest {
    private static final int SUB_ID = 1;
    private static final int NETWORK_ID = 5;
    private static final WifiInfo WIFI_INFO =
            new WifiInfo.Builder().setNetworkId(NETWORK_ID).build();

    private static final VcnTransportInfo CELL_UNDERLYING_INFO = new VcnTransportInfo(SUB_ID);
    private static final VcnTransportInfo WIFI_UNDERLYING_INFO = new VcnTransportInfo(WIFI_INFO);

    @Test
    public void testRedactionDefaults() {
        assertEquals(REDACT_ALL, CELL_UNDERLYING_INFO.getRedaction());
        assertEquals(REDACT_ALL, WIFI_UNDERLYING_INFO.getRedaction());
    }

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
    public void testMakeCopySetsRedactions() {
        assertEquals(
                REDACT_FOR_NETWORK_SETTINGS,
                ((VcnTransportInfo) CELL_UNDERLYING_INFO.makeCopy(REDACT_FOR_NETWORK_SETTINGS))
                        .getRedaction());
        assertEquals(
                REDACT_FOR_NETWORK_SETTINGS,
                ((VcnTransportInfo) WIFI_UNDERLYING_INFO.makeCopy(REDACT_FOR_NETWORK_SETTINGS))
                        .getRedaction());
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
        // Verify redacted by default
        Parcel parcel = Parcel.obtain();
        vcnTransportInfo.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        assertNull(VcnTransportInfo.CREATOR.createFromParcel(parcel));
    }

    @Test
    public void testParcelUnparcelNotRedactedForSysUi() {
        verifyParcelingForSysUi(CELL_UNDERLYING_INFO);
        verifyParcelingForSysUi(WIFI_UNDERLYING_INFO);
    }

    private void verifyParcelingForSysUi(VcnTransportInfo vcnTransportInfo) {
        // Allow fully unredacted; SysUI will have all the relevant permissions.
        final VcnTransportInfo unRedacted = (VcnTransportInfo) vcnTransportInfo.makeCopy(0);
        final Parcel parcel = Parcel.obtain();
        unRedacted.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        final VcnTransportInfo unparceled = VcnTransportInfo.CREATOR.createFromParcel(parcel);
        assertEquals(vcnTransportInfo, unparceled);
        assertEquals(REDACT_ALL, unparceled.getRedaction());
    }
}
