/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.wifi.nl80211;

import static org.junit.Assert.assertEquals;

import android.net.wifi.ScanResult;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.nl80211.DeviceWiphyCapabilities}.
 */
@SmallTest
public class DeviceWiphyCapabilitiesTest {
    @Before
    public void setUp() {}

    /**
     *  DeviceWiphyCapabilities object can be serialized and deserialized, while keeping the
     *  values unchanged.
     */
    @Test
    public void canSerializeAndDeserialize() {
        DeviceWiphyCapabilities capa = new DeviceWiphyCapabilities();

        capa.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11N, true);
        capa.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11AC, true);
        capa.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11AX, false);
        capa.setChannelWidthSupported(ScanResult.CHANNEL_WIDTH_160MHZ, true);
        capa.setChannelWidthSupported(ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ, false);
        capa.setMaxNumberTxSpatialStreams(2);
        capa.setMaxNumberRxSpatialStreams(1);

        Parcel parcel = Parcel.obtain();
        capa.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        DeviceWiphyCapabilities capaDeserialized =
                DeviceWiphyCapabilities.CREATOR.createFromParcel(parcel);

        assertEquals(capa, capaDeserialized);
        assertEquals(capa.hashCode(), capaDeserialized.hashCode());
    }

    /**
     * Test mapping wifi standard support into channel width support
     */
    @Test
    public void testMappingWifiStandardIntoChannelWidthSupport() {
        DeviceWiphyCapabilities capa = new DeviceWiphyCapabilities();

        capa.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11N, false);
        capa.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11AC, false);
        capa.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11AX, false);
        assertEquals(true, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_20MHZ));
        assertEquals(false, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_40MHZ));
        assertEquals(false, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_80MHZ));

        capa.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11N, true);
        assertEquals(true, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_20MHZ));
        assertEquals(true, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_40MHZ));
        assertEquals(false, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_80MHZ));

        capa.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11AC, true);
        assertEquals(true, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_20MHZ));
        assertEquals(true, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_40MHZ));
        assertEquals(true, capa.isChannelWidthSupported(ScanResult.CHANNEL_WIDTH_80MHZ));
    }
}
