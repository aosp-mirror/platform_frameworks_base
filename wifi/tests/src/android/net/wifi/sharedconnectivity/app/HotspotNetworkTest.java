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
import static android.net.wifi.sharedconnectivity.app.HotspotNetwork.NETWORK_TYPE_CELLULAR;
import static android.net.wifi.sharedconnectivity.app.HotspotNetwork.NETWORK_TYPE_WIFI;
import static android.net.wifi.sharedconnectivity.app.NetworkProviderInfo.DEVICE_TYPE_PHONE;
import static android.net.wifi.sharedconnectivity.app.NetworkProviderInfo.DEVICE_TYPE_TABLET;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.Bundle;
import android.os.Parcel;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Unit tests for {@link HotspotNetwork}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HotspotNetworkTest {
    private static final long DEVICE_ID = 11L;
    private static final NetworkProviderInfo NETWORK_PROVIDER_INFO =
            new NetworkProviderInfo.Builder("TEST_NAME", "TEST_MODEL")
                    .setDeviceType(DEVICE_TYPE_TABLET).setConnectionStrength(2)
                    .setBatteryPercentage(50).build();
    private static final int NETWORK_TYPE = NETWORK_TYPE_CELLULAR;
    private static final String NETWORK_NAME = "TEST_NETWORK";
    private static final String HOTSPOT_SSID = "TEST_SSID";
    private static final String HOTSPOT_BSSID = "TEST _BSSID";
    private static final int[] HOTSPOT_SECURITY_TYPES = {SECURITY_TYPE_WEP, SECURITY_TYPE_EAP};
    private static final String BUNDLE_KEY = "INT-KEY";
    private static final int BUNDLE_VALUE = 1;

    private static final long DEVICE_ID_1 = 111L;
    private static final NetworkProviderInfo NETWORK_PROVIDER_INFO1 =
            new NetworkProviderInfo.Builder("TEST_NAME", "TEST_MODEL")
                    .setDeviceType(DEVICE_TYPE_PHONE).setConnectionStrength(2)
                    .setBatteryPercentage(50).build();
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
        HotspotNetwork network = buildHotspotNetworkBuilder(true).build();

        Parcel parcelW = Parcel.obtain();
        network.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        HotspotNetwork fromParcel = HotspotNetwork.CREATOR.createFromParcel(parcelR);

        assertThat(fromParcel).isEqualTo(network);
        assertThat(fromParcel.hashCode()).isEqualTo(network.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        HotspotNetwork network1 = buildHotspotNetworkBuilder(true).build();
        HotspotNetwork network2 = buildHotspotNetworkBuilder(true).build();
        assertThat(network1).isEqualTo(network2);

        HotspotNetwork.Builder builder = buildHotspotNetworkBuilder(true).setDeviceId(DEVICE_ID_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildHotspotNetworkBuilder(true).setNetworkProviderInfo(NETWORK_PROVIDER_INFO1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildHotspotNetworkBuilder(true).setHostNetworkType(NETWORK_TYPE_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildHotspotNetworkBuilder(true).setNetworkName(NETWORK_NAME_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildHotspotNetworkBuilder(true).setHotspotSsid(HOTSPOT_SSID_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildHotspotNetworkBuilder(true).setHotspotBssid(HOTSPOT_BSSID_1);
        assertThat(builder.build()).isNotEqualTo(network1);

        builder = buildHotspotNetworkBuilder(true);
        HotspotNetwork.Builder builder1 = buildHotspotNetworkBuilder(true);
        Arrays.stream(HOTSPOT_SECURITY_TYPES_1).forEach(builder1::addHotspotSecurityType);

        assertThat(builder1.build()).isNotEqualTo(builder.build());
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        HotspotNetwork network = buildHotspotNetworkBuilder(true).build();
        ArraySet<Integer> securityTypes = new ArraySet<>();
        Arrays.stream(HOTSPOT_SECURITY_TYPES).forEach(securityTypes::add);

        assertThat(network.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(network.getNetworkProviderInfo()).isEqualTo(NETWORK_PROVIDER_INFO);
        assertThat(network.getHostNetworkType()).isEqualTo(NETWORK_TYPE);
        assertThat(network.getNetworkName()).isEqualTo(NETWORK_NAME);
        assertThat(network.getHotspotSsid()).isEqualTo(HOTSPOT_SSID);
        assertThat(network.getHotspotBssid()).isEqualTo(HOTSPOT_BSSID);
        assertThat(network.getHotspotSecurityTypes()).containsExactlyElementsIn(securityTypes);
        assertThat(network.getExtras().getInt(BUNDLE_KEY)).isEqualTo(BUNDLE_VALUE);
    }

    @Test
    public void testHashCode() {
        HotspotNetwork network1 = buildHotspotNetworkBuilder(true).build();
        HotspotNetwork network2 = buildHotspotNetworkBuilder(true).build();

        assertThat(network1.hashCode()).isEqualTo(network2.hashCode());
    }

    @Test
    public void networkProviderInfoNotSet_shouldThrowException() {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> buildHotspotNetworkBuilder(false).build());
        assertThat(e.getMessage()).contains("NetworkProviderInfo");
    }

    private HotspotNetwork.Builder buildHotspotNetworkBuilder(boolean withNetworkProviderInfo) {
        HotspotNetwork.Builder builder = new HotspotNetwork.Builder()
                .setDeviceId(DEVICE_ID)
                .setHostNetworkType(NETWORK_TYPE)
                .setNetworkName(NETWORK_NAME)
                .setHotspotSsid(HOTSPOT_SSID)
                .setHotspotBssid(HOTSPOT_BSSID)
                .setExtras(buildBundle());
        Arrays.stream(HOTSPOT_SECURITY_TYPES).forEach(builder::addHotspotSecurityType);
        if (withNetworkProviderInfo) {
            builder.setNetworkProviderInfo(NETWORK_PROVIDER_INFO);
        }
        return builder;
    }

    private Bundle buildBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt(BUNDLE_KEY, BUNDLE_VALUE);
        return bundle;
    }
}
