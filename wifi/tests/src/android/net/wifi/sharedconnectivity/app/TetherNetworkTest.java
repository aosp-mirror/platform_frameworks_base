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

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.Arrays;

/**
 * Unit tests for {@link TetherNetwork}.
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

        assertThat(fromParcel).isEqualTo(network);
        assertThat(fromParcel.hashCode()).isEqualTo(network.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        TetherNetwork network1 = buildTetherNetworkBuilder().build();
        TetherNetwork network2 = buildTetherNetworkBuilder().build();
        assertThat(network1).isEqualTo(network2);

        TetherNetwork.Builder builder = buildTetherNetworkBuilder().setDeviceId(DEVICE_ID_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildTetherNetworkBuilder().setDeviceInfo(DEVICE_INFO_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildTetherNetworkBuilder().setNetworkType(NETWORK_TYPE_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildTetherNetworkBuilder().setNetworkName(NETWORK_NAME_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildTetherNetworkBuilder().setHotspotSsid(HOTSPOT_SSID_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildTetherNetworkBuilder().setHotspotBssid(HOTSPOT_BSSID_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildTetherNetworkBuilder();
        TetherNetwork.Builder builder1 = buildTetherNetworkBuilder();
        Arrays.stream(HOTSPOT_SECURITY_TYPES_1).forEach(builder1::addHotspotSecurityType);

        assertThat(builder1.build()).isNotEqualTo(builder.build());
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        TetherNetwork network = buildTetherNetworkBuilder().build();
        ArraySet<Integer> securityTypes = new ArraySet<>();
        Arrays.stream(HOTSPOT_SECURITY_TYPES).forEach(securityTypes::add);

        assertThat(network.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(network.getDeviceInfo()).isEqualTo(DEVICE_INFO);
        assertThat(network.getNetworkType()).isEqualTo(NETWORK_TYPE);
        assertThat(network.getNetworkName()).isEqualTo(NETWORK_NAME);
        assertThat(network.getHotspotSsid()).isEqualTo(HOTSPOT_SSID);
        assertThat(network.getHotspotBssid()).isEqualTo(HOTSPOT_BSSID);
        assertThat(network.getHotspotSecurityTypes()).containsExactlyElementsIn(securityTypes);
    }

    @Test
    public void testHashCode() {
        TetherNetwork network1 = buildTetherNetworkBuilder().build();
        TetherNetwork network2 = buildTetherNetworkBuilder().build();

        assertThat(network1.hashCode()).isEqualTo(network2.hashCode());
    }

    private TetherNetwork.Builder buildTetherNetworkBuilder() {
        TetherNetwork.Builder builder =  new TetherNetwork.Builder()
                .setDeviceId(DEVICE_ID)
                .setDeviceInfo(DEVICE_INFO)
                .setNetworkType(NETWORK_TYPE)
                .setNetworkName(NETWORK_NAME)
                .setHotspotSsid(HOTSPOT_SSID)
                .setHotspotBssid(HOTSPOT_BSSID);
        Arrays.stream(HOTSPOT_SECURITY_TYPES).forEach(builder::addHotspotSecurityType);
        return builder;
    }
}
