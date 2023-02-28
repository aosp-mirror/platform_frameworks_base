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

import static android.net.wifi.WifiInfo.SECURITY_TYPE_WEP;
import static android.net.wifi.sharedconnectivity.app.DeviceInfo.DEVICE_TYPE_TABLET;
import static android.net.wifi.sharedconnectivity.app.KnownNetwork.NETWORK_SOURCE_NEARBY_SELF;
import static android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus.CONNECTION_STATUS_SAVED;
import static android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus.CONNECTION_STATUS_SAVE_FAILED;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.Arrays;

/**
 * Unit tests for {@link KnownNetworkConnectionStatus}.
 */
@SmallTest
public class KnownNetworkConnectionStatusTest {
    private static final int NETWORK_SOURCE = NETWORK_SOURCE_NEARBY_SELF;
    private static final String SSID = "TEST_SSID";
    private static final int[] SECURITY_TYPES = {SECURITY_TYPE_WEP};
    private static final DeviceInfo DEVICE_INFO = new DeviceInfo.Builder()
            .setDeviceType(DEVICE_TYPE_TABLET).setDeviceName("TEST_NAME").setModelName("TEST_MODEL")
            .setConnectionStrength(2).setBatteryPercentage(50).build();
    private static final String SSID_1 = "TEST_SSID1";
    private static final String BUNDLE_KEY = "INT-KEY";
    private static final int BUNDLE_VALUE = 1;

    /**
     * Verifies parcel serialization/deserialization.
     */
    @Test
    public void testParcelOperation() {
        KnownNetworkConnectionStatus status = buildConnectionStatusBuilder().build();

        Parcel parcelW = Parcel.obtain();
        status.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        KnownNetworkConnectionStatus fromParcel =
                KnownNetworkConnectionStatus.CREATOR.createFromParcel(parcelR);

        assertThat(fromParcel).isEqualTo(status);
        assertThat(fromParcel.hashCode()).isEqualTo(status.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        KnownNetworkConnectionStatus status1 = buildConnectionStatusBuilder().build();
        KnownNetworkConnectionStatus status2 = buildConnectionStatusBuilder().build();
        assertThat(status1).isEqualTo(status2);

        KnownNetworkConnectionStatus.Builder builder = buildConnectionStatusBuilder()
                .setStatus(CONNECTION_STATUS_SAVE_FAILED);
        assertThat(builder.build()).isNotEqualTo(status1);

        builder = buildConnectionStatusBuilder()
                .setKnownNetwork(buildKnownNetworkBuilder().setSsid(SSID_1).build());
        assertThat(builder.build()).isNotEqualTo(status1);
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        KnownNetworkConnectionStatus status = buildConnectionStatusBuilder().build();
        assertThat(status.getStatus()).isEqualTo(CONNECTION_STATUS_SAVED);
        assertThat(status.getKnownNetwork()).isEqualTo(buildKnownNetworkBuilder().build());
        assertThat(status.getExtras().getInt(BUNDLE_KEY)).isEqualTo(BUNDLE_VALUE);
    }

    @Test
    public void testHashCode() {
        KnownNetworkConnectionStatus status1 = buildConnectionStatusBuilder().build();
        KnownNetworkConnectionStatus status2 = buildConnectionStatusBuilder().build();

        assertThat(status1.hashCode()).isEqualTo(status2.hashCode());
    }

    private KnownNetworkConnectionStatus.Builder buildConnectionStatusBuilder() {
        return new KnownNetworkConnectionStatus.Builder()
                .setStatus(CONNECTION_STATUS_SAVED)
                .setKnownNetwork(buildKnownNetworkBuilder().build())
                .setExtras(buildBundle());
    }

    private Bundle buildBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt(BUNDLE_KEY, BUNDLE_VALUE);
        return bundle;
    }

    private KnownNetwork.Builder buildKnownNetworkBuilder() {
        KnownNetwork.Builder builder =  new KnownNetwork.Builder().setNetworkSource(NETWORK_SOURCE)
                .setSsid(SSID).setDeviceInfo(DEVICE_INFO);
        Arrays.stream(SECURITY_TYPES).forEach(builder::addSecurityType);
        return builder;
    }

}
