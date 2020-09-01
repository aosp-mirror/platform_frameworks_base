/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Unit tests for {@link android.net.wifi.nl80211.SingleScanSettingsResult}.
 */
@SmallTest
public class SingleScanSettingsTest {

    private static final byte[] TEST_SSID_1 =
            new byte[] {'G', 'o', 'o', 'g', 'l', 'e', 'G', 'u', 'e', 's', 't'};
    private static final byte[] TEST_SSID_2 =
            new byte[] {'A', 'n', 'd', 'r', 'o', 'i', 'd', 'T', 'e', 's', 't'};
    private static final int TEST_FREQUENCY_1 = 2456;
    private static final int TEST_FREQUENCY_2 = 5215;
    private static final int TEST_VALUE = 42;

    private ChannelSettings mChannelSettings1;
    private ChannelSettings mChannelSettings2;
    private HiddenNetwork mHiddenNetwork1;
    private HiddenNetwork mHiddenNetwork2;

    @Before
    public void setUp() {
        mChannelSettings1 = new ChannelSettings();
        mChannelSettings1.frequency = TEST_FREQUENCY_1;
        mChannelSettings2 = new ChannelSettings();
        mChannelSettings2.frequency = TEST_FREQUENCY_2;

        mHiddenNetwork1 = new HiddenNetwork();
        mHiddenNetwork1.ssid = TEST_SSID_1;
        mHiddenNetwork2 = new HiddenNetwork();
        mHiddenNetwork2.ssid = TEST_SSID_2;
    }

    /**
     *  SingleScanSettings object can be serialized and deserialized, while keeping the
     *  values unchanged.
     */
    @Test
    public void canSerializeAndDeserialize() {
        SingleScanSettings scanSettings = new SingleScanSettings();
        scanSettings.scanType = IWifiScannerImpl.SCAN_TYPE_HIGH_ACCURACY;

        scanSettings.channelSettings =
                new ArrayList<>(Arrays.asList(mChannelSettings1, mChannelSettings2));
        scanSettings.hiddenNetworks =
                new ArrayList<>(Arrays.asList(mHiddenNetwork1, mHiddenNetwork2));

        Parcel parcel = Parcel.obtain();
        scanSettings.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        SingleScanSettings scanSettingsDeserialized =
                SingleScanSettings.CREATOR.createFromParcel(parcel);

        assertEquals(scanSettings, scanSettingsDeserialized);
        assertEquals(scanSettings.hashCode(), scanSettingsDeserialized.hashCode());
    }

    /**
     * Tests usage of {@link SingleScanSettings} as a HashMap key type.
     */
    @Test
    public void testAsHashMapKey() {
        SingleScanSettings scanSettings1 = new SingleScanSettings();
        scanSettings1.scanType = IWifiScannerImpl.SCAN_TYPE_HIGH_ACCURACY;
        scanSettings1.channelSettings =
                new ArrayList<>(Arrays.asList(mChannelSettings1, mChannelSettings2));
        scanSettings1.hiddenNetworks =
                new ArrayList<>(Arrays.asList(mHiddenNetwork1, mHiddenNetwork2));

        SingleScanSettings scanSettings2 = new SingleScanSettings();
        scanSettings2.scanType = IWifiScannerImpl.SCAN_TYPE_HIGH_ACCURACY;
        scanSettings2.channelSettings =
                new ArrayList<>(Arrays.asList(mChannelSettings1, mChannelSettings2));
        scanSettings2.hiddenNetworks =
                new ArrayList<>(Arrays.asList(mHiddenNetwork1, mHiddenNetwork2));

        assertEquals(scanSettings1, scanSettings2);
        assertEquals(scanSettings1.hashCode(), scanSettings2.hashCode());

        HashMap<SingleScanSettings, Integer> map = new HashMap<>();
        map.put(scanSettings1, TEST_VALUE);

        assertEquals(TEST_VALUE, map.get(scanSettings2).intValue());
    }
}
