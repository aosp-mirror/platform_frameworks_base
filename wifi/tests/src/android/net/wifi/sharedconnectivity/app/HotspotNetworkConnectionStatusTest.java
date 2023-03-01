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
import static android.net.wifi.WifiInfo.SECURITY_TYPE_WEP;
import static android.net.wifi.sharedconnectivity.app.HotspotNetwork.NETWORK_TYPE_CELLULAR;
import static android.net.wifi.sharedconnectivity.app.HotspotNetworkConnectionStatus.CONNECTION_STATUS_ENABLING_HOTSPOT;
import static android.net.wifi.sharedconnectivity.app.HotspotNetworkConnectionStatus.CONNECTION_STATUS_TETHERING_TIMEOUT;
import static android.net.wifi.sharedconnectivity.app.NetworkProviderInfo.DEVICE_TYPE_TABLET;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.Arrays;

/**
 * Unit tests for {@link HotspotNetworkConnectionStatus}.
 */
@SmallTest
public class HotspotNetworkConnectionStatusTest {
    private static final long DEVICE_ID = 11L;
    private static final NetworkProviderInfo NETWORK_PROVIDER_INFO =
            new NetworkProviderInfo.Builder().setDeviceType(DEVICE_TYPE_TABLET)
                    .setDeviceName("TEST_NAME").setModelName("TEST_MODEL")
                    .setConnectionStrength(2).setBatteryPercentage(50).build();
    private static final int NETWORK_TYPE = NETWORK_TYPE_CELLULAR;
    private static final String NETWORK_NAME = "TEST_NETWORK";
    private static final String HOTSPOT_SSID = "TEST_SSID";
    private static final String HOTSPOT_BSSID = "TEST _BSSID";
    private static final int[] HOTSPOT_SECURITY_TYPES = {SECURITY_TYPE_WEP, SECURITY_TYPE_EAP};
    private static final long DEVICE_ID_1 = 111L;
    private static final String BUNDLE_KEY = "INT-KEY";
    private static final int BUNDLE_VALUE = 1;

    /**
     * Verifies parcel serialization/deserialization.
     */
    @Test
    public void testParcelOperation() {
        HotspotNetworkConnectionStatus status = buildConnectionStatusBuilder().build();

        Parcel parcelW = Parcel.obtain();
        status.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        HotspotNetworkConnectionStatus fromParcel =
                HotspotNetworkConnectionStatus.CREATOR.createFromParcel(parcelR);

        assertThat(fromParcel).isEqualTo(status);
        assertThat(fromParcel.hashCode()).isEqualTo(status.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        HotspotNetworkConnectionStatus status1 = buildConnectionStatusBuilder().build();
        HotspotNetworkConnectionStatus status2 = buildConnectionStatusBuilder().build();
        assertThat(status1).isEqualTo(status2);

        HotspotNetworkConnectionStatus.Builder builder = buildConnectionStatusBuilder()
                .setStatus(CONNECTION_STATUS_TETHERING_TIMEOUT);
        assertThat(builder.build()).isNotEqualTo(status1);

        builder = buildConnectionStatusBuilder()
                .setHotspotNetwork(buildHotspotNetworkBuilder().setDeviceId(DEVICE_ID_1).build());
        assertThat(builder.build()).isNotEqualTo(status1);
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        HotspotNetworkConnectionStatus status = buildConnectionStatusBuilder().build();
        assertThat(status.getStatus()).isEqualTo(CONNECTION_STATUS_ENABLING_HOTSPOT);
        assertThat(status.getHotspotNetwork()).isEqualTo(buildHotspotNetworkBuilder().build());
        assertThat(status.getExtras().getInt(BUNDLE_KEY)).isEqualTo(BUNDLE_VALUE);
    }

    @Test
    public void testHashCode() {
        HotspotNetworkConnectionStatus status1 = buildConnectionStatusBuilder().build();
        HotspotNetworkConnectionStatus status2 = buildConnectionStatusBuilder().build();

        assertThat(status1.hashCode()).isEqualTo(status2.hashCode());
    }

    private HotspotNetworkConnectionStatus.Builder buildConnectionStatusBuilder() {
        return new HotspotNetworkConnectionStatus.Builder()
                .setStatus(CONNECTION_STATUS_ENABLING_HOTSPOT)
                .setHotspotNetwork(buildHotspotNetworkBuilder().build())
                .setExtras(buildBundle());
    }

    private Bundle buildBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt(BUNDLE_KEY, BUNDLE_VALUE);
        return bundle;
    }

    private HotspotNetwork.Builder buildHotspotNetworkBuilder() {
        HotspotNetwork.Builder builder = new HotspotNetwork.Builder()
                .setDeviceId(DEVICE_ID)
                .setNetworkProviderInfo(NETWORK_PROVIDER_INFO)
                .setHostNetworkType(NETWORK_TYPE)
                .setNetworkName(NETWORK_NAME)
                .setHotspotSsid(HOTSPOT_SSID)
                .setHotspotBssid(HOTSPOT_BSSID);
        Arrays.stream(HOTSPOT_SECURITY_TYPES).forEach(builder::addHotspotSecurityType);
        return builder;
    }
}
