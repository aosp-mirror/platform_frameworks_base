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

import static android.net.wifi.WifiInfo.SECURITY_TYPE_PSK;
import static android.net.wifi.WifiInfo.SECURITY_TYPE_WEP;
import static android.net.wifi.sharedconnectivity.app.DeviceInfo.DEVICE_TYPE_PHONE;
import static android.net.wifi.sharedconnectivity.app.DeviceInfo.DEVICE_TYPE_TABLET;
import static android.net.wifi.sharedconnectivity.app.KnownNetwork.NETWORK_SOURCE_CLOUD_SELF;
import static android.net.wifi.sharedconnectivity.app.KnownNetwork.NETWORK_SOURCE_NEARBY_SELF;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.app.sharedconnectivity.KnownNetwork}.
 */
@SmallTest
public class KnownNetworkTest {

    private static final int NETWORK_SOURCE = NETWORK_SOURCE_NEARBY_SELF;
    private static final String SSID = "TEST_SSID";
    private static final int[] SECURITY_TYPES = {SECURITY_TYPE_WEP};
    private static final DeviceInfo DEVICE_INFO = new DeviceInfo.Builder()
            .setDeviceType(DEVICE_TYPE_TABLET).setDeviceName("TEST_NAME").setModelName("TEST_MODEL")
            .setConnectionStrength(2).setBatteryPercentage(50).build();
    private static final int NETWORK_SOURCE_1 = NETWORK_SOURCE_CLOUD_SELF;
    private static final String SSID_1 = "TEST_SSID1";
    private static final int[] SECURITY_TYPES_1 = {SECURITY_TYPE_PSK};
    private static final DeviceInfo DEVICE_INFO_1 = new DeviceInfo.Builder()
            .setDeviceType(DEVICE_TYPE_PHONE).setDeviceName("TEST_NAME_1")
            .setModelName("TEST_MODEL_1").setConnectionStrength(3).setBatteryPercentage(33).build();

    /**
     * Verifies parcel serialization/deserialization.
     */
    @Test
    public void testParcelOperation() {
        KnownNetwork network = buildKnownNetworkBuilder().build();

        Parcel parcelW = Parcel.obtain();
        network.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        KnownNetwork fromParcel = KnownNetwork.CREATOR.createFromParcel(parcelR);

        assertEquals(network, fromParcel);
        assertEquals(network.hashCode(), fromParcel.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        KnownNetwork network1 = buildKnownNetworkBuilder().build();
        KnownNetwork network2 = buildKnownNetworkBuilder().build();
        assertEquals(network1, network2);

        KnownNetwork.Builder builder = buildKnownNetworkBuilder()
                .setNetworkSource(NETWORK_SOURCE_1);
        assertNotEquals(network1, builder.build());

        builder = buildKnownNetworkBuilder().setSsid(SSID_1);
        assertNotEquals(network1, builder.build());

        builder = buildKnownNetworkBuilder().setSecurityTypes(SECURITY_TYPES_1);
        assertNotEquals(network1, builder.build());

        builder = buildKnownNetworkBuilder().setDeviceInfo(DEVICE_INFO_1);
        assertNotEquals(network1, builder.build());
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        KnownNetwork network = buildKnownNetworkBuilder().build();
        assertEquals(network.getNetworkSource(), NETWORK_SOURCE);
        assertEquals(network.getSsid(), SSID);
        assertArrayEquals(network.getSecurityTypes(), SECURITY_TYPES);
        assertEquals(network.getDeviceInfo(), DEVICE_INFO);
    }

    private KnownNetwork.Builder buildKnownNetworkBuilder() {
        return new KnownNetwork.Builder().setNetworkSource(NETWORK_SOURCE).setSsid(SSID)
                .setSecurityTypes(SECURITY_TYPES).setDeviceInfo(DEVICE_INFO);
    }
}
