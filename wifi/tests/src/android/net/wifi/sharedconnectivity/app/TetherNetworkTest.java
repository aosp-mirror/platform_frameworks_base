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

package android.net.wifi.sharedconnectivity.app;

import static android.net.wifi.WifiInfo.SECURITY_TYPE_EAP;
import static android.net.wifi.WifiInfo.SECURITY_TYPE_PSK;
import static android.net.wifi.WifiInfo.SECURITY_TYPE_WEP;
import static android.net.wifi.sharedconnectivity.app.DeviceInfo.DEVICE_TYPE_PHONE;
import static android.net.wifi.sharedconnectivity.app.DeviceInfo.DEVICE_TYPE_TABLET;
import static android.net.wifi.sharedconnectivity.app.TetherNetwork.NETWORK_TYPE_CELLULAR;
import static android.net.wifi.sharedconnectivity.app.TetherNetwork.NETWORK_TYPE_WIFI;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.sharedconnectivity.app.TetherNetwork}.
 */
@SmallTest
public class TetherNetworkTest {
    private static final long DEVICE_ID = 11L;
    private static final DeviceInfo DEVICE_INFO = new DeviceInfo.Builder()
            .setDeviceType(DEVICE_TYPE_TABLET).setDeviceName("TEST_NAME").setModelName("TEST_MODEL")
            .setConnectionStrength(2).setBatteryPercentage(50).build();
    private static final int NETWORK_TYPE = NETWORK_TYPE_CELLULAR;
    private static final String NETWORK_NAME = "TEST_NETWORK";
    private static final String HOTSPOT_SSID = "TEST_SSID";
    private static final String HOTSPOT_BSSID = "TEST _BSSID";
    private static final int[] HOTSPOT_SECURITY_TYPES = {SECURITY_TYPE_WEP, SECURITY_TYPE_EAP};

    private static final long DEVICE_ID_1 = 111L;
    private static final DeviceInfo DEVICE_INFO_1 = new DeviceInfo.Builder()
            .setDeviceType(DEVICE_TYPE_PHONE).setDeviceName("TEST_NAME").setModelName("TEST_MODEL")
            .setConnectionStrength(2).setBatteryPercentage(50).build();
    private static final int NETWORK_TYPE_1 = NETWORK_TYPE_WIFI;
    private static final String NETWORK_NAME_1 = "TEST_NETWORK1";
    private static final String HOTSPOT_SSID_1 = "TEST_SSID1";
    private static final String HOTSPOT_BSSID_1 = "TEST _BSSID1";
    private static final int[] HOTSPOT_SECURITY_TYPES_1 = {SECURITY_TYPE_PSK, SECURITY_TYPE_EAP};

    /**
     * Verifies parcel serialization/deserialization.
     */
    @Test
    public void testParcelOperation() {
        TetherNetwork network = buildTetherNetworkBuilder().build();

        Parcel parcelW = Parcel.obtain();
        network.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        TetherNetwork fromParcel = TetherNetwork.CREATOR.createFromParcel(parcelR);

        assertEquals(network, fromParcel);
        assertEquals(network.hashCode(), fromParcel.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        TetherNetwork network1 = buildTetherNetworkBuilder().build();
        TetherNetwork network2 = buildTetherNetworkBuilder().build();
        assertEquals(network1, network2);

        TetherNetwork.Builder builder = buildTetherNetworkBuilder().setDeviceId(DEVICE_ID_1);
        assertNotEquals(network1, builder.build());

        builder = buildTetherNetworkBuilder().setDeviceInfo(DEVICE_INFO_1);
        assertNotEquals(network1, builder.build());

        builder = buildTetherNetworkBuilder().setNetworkType(NETWORK_TYPE_1);
        assertNotEquals(network1, builder.build());

        builder = buildTetherNetworkBuilder().setNetworkName(NETWORK_NAME_1);
        assertNotEquals(network1, builder.build());

        builder = buildTetherNetworkBuilder().setHotspotSsid(HOTSPOT_SSID_1);
        assertNotEquals(network1, builder.build());

        builder = buildTetherNetworkBuilder().setHotspotBssid(HOTSPOT_BSSID_1);
        assertNotEquals(network1, builder.build());

        builder = buildTetherNetworkBuilder().setHotspotSecurityTypes(HOTSPOT_SECURITY_TYPES_1);
        assertNotEquals(network1, builder.build());
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        TetherNetwork network = buildTetherNetworkBuilder().build();
        assertEquals(network.getDeviceId(), DEVICE_ID);
        assertEquals(network.getDeviceInfo(), DEVICE_INFO);
        assertEquals(network.getNetworkType(), NETWORK_TYPE);
        assertEquals(network.getNetworkName(), NETWORK_NAME);
        assertEquals(network.getHotspotSsid(), HOTSPOT_SSID);
        assertEquals(network.getHotspotBssid(), HOTSPOT_BSSID);
        assertArrayEquals(network.getHotspotSecurityTypes(), HOTSPOT_SECURITY_TYPES);
    }

    private TetherNetwork.Builder buildTetherNetworkBuilder() {
        return new TetherNetwork.Builder()
                .setDeviceId(DEVICE_ID)
                .setDeviceInfo(DEVICE_INFO)
                .setNetworkType(NETWORK_TYPE)
                .setNetworkName(NETWORK_NAME)
                .setHotspotSsid(HOTSPOT_SSID)
                .setHotspotBssid(HOTSPOT_BSSID)
                .setHotspotSecurityTypes(HOTSPOT_SECURITY_TYPES);
    }
}
