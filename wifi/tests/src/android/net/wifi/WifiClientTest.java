/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi;

import static com.android.testutils.MiscAssertsKt.assertFieldCountEquals;
import static com.android.testutils.ParcelUtilsKt.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.net.MacAddress;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiClient}.
 */
@SmallTest
public class WifiClientTest {
    private static final String INTERFACE_NAME = "wlan0";
    private static final String MAC_ADDRESS_STRING = "00:0a:95:9d:68:16";
    private static final MacAddress MAC_ADDRESS = MacAddress.fromString(MAC_ADDRESS_STRING);

    /**
     *  Verify parcel write/read with WifiClient.
     */
    @Test
    public void testWifiClientParcelWriteRead() throws Exception {
        WifiClient writeWifiClient = new WifiClient(MAC_ADDRESS);

        assertParcelSane(writeWifiClient, 1);
    }

    /**
     *  Verify equals with WifiClient.
     */
    @Test
    public void testWifiClientEquals() throws Exception {
        WifiClient writeWifiClient = new WifiClient(MAC_ADDRESS);
        WifiClient writeWifiClientEquals = new WifiClient(MAC_ADDRESS);

        assertEquals(writeWifiClient, writeWifiClientEquals);
        assertEquals(writeWifiClient.hashCode(), writeWifiClientEquals.hashCode());
        assertFieldCountEquals(1, WifiClient.class);
    }

    /**
     *  Verify not-equals with WifiClient.
     */
    @Test
    public void testWifiClientNotEquals() throws Exception {
        final MacAddress macAddressNotEquals = MacAddress.fromString("00:00:00:00:00:00");
        WifiClient writeWifiClient = new WifiClient(MAC_ADDRESS);
        WifiClient writeWifiClientNotEquals = new WifiClient(macAddressNotEquals);

        assertNotEquals(writeWifiClient, writeWifiClientNotEquals);
        assertNotEquals(writeWifiClient.hashCode(), writeWifiClientNotEquals.hashCode());
    }
}
