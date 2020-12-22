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
 * Unit tests for {@link android.net.wifi.nl80211.PnoSettings}.
 */
@SmallTest
public class PnoSettingsTest {

    private static final byte[] TEST_SSID_1 =
            new byte[] {'G', 'o', 'o', 'g', 'l', 'e', 'G', 'u', 'e', 's', 't'};
    private static final byte[] TEST_SSID_2 =
            new byte[] {'A', 'n', 'd', 'r', 'o', 'i', 'd', 'T', 'e', 's', 't'};
    private static final int[] TEST_FREQUENCIES_1 = {};
    private static final int[] TEST_FREQUENCIES_2 = {2500, 5124};
    private static final int TEST_INTERVAL_MS = 30000;
    private static final int TEST_MIN_2G_RSSI = -60;
    private static final int TEST_MIN_5G_RSSI = -65;
    private static final int TEST_VALUE = 42;

    private PnoNetwork mPnoNetwork1;
    private PnoNetwork mPnoNetwork2;

    @Before
    public void setUp() {
        mPnoNetwork1 = new PnoNetwork();
        mPnoNetwork1.setSsid(TEST_SSID_1);
        mPnoNetwork1.setHidden(true);
        mPnoNetwork1.setFrequenciesMhz(TEST_FREQUENCIES_1);

        mPnoNetwork2 = new PnoNetwork();
        mPnoNetwork2.setSsid(TEST_SSID_2);
        mPnoNetwork2.setHidden(false);
        mPnoNetwork2.setFrequenciesMhz(TEST_FREQUENCIES_2);
    }

    /**
     *  PnoSettings object can be serialized and deserialized, while keeping the
     *  values unchanged.
     */
    @Test
    public void canSerializeAndDeserialize() {
        PnoSettings pnoSettings = new PnoSettings();
        pnoSettings.setIntervalMillis(TEST_INTERVAL_MS);
        pnoSettings.setMin2gRssiDbm(TEST_MIN_2G_RSSI);
        pnoSettings.setMin5gRssiDbm(TEST_MIN_5G_RSSI);
        pnoSettings.setPnoNetworks(new ArrayList<>(Arrays.asList(mPnoNetwork1, mPnoNetwork2)));

        Parcel parcel = Parcel.obtain();
        pnoSettings.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        PnoSettings pnoSettingsDeserialized = PnoSettings.CREATOR.createFromParcel(parcel);

        assertEquals(pnoSettings, pnoSettingsDeserialized);
        assertEquals(pnoSettings.hashCode(), pnoSettingsDeserialized.hashCode());
    }

    /**
     * Tests usage of {@link PnoSettings} as a HashMap key type.
     */
    @Test
    public void testAsHashMapKey() {
        PnoSettings pnoSettings1 = new PnoSettings();
        pnoSettings1.setIntervalMillis(TEST_INTERVAL_MS);
        pnoSettings1.setMin2gRssiDbm(TEST_MIN_2G_RSSI);
        pnoSettings1.setMin5gRssiDbm(TEST_MIN_5G_RSSI);
        pnoSettings1.setPnoNetworks(new ArrayList<>(Arrays.asList(mPnoNetwork1, mPnoNetwork2)));

        PnoSettings pnoSettings2 = new PnoSettings();
        pnoSettings2.setIntervalMillis(TEST_INTERVAL_MS);
        pnoSettings2.setMin2gRssiDbm(TEST_MIN_2G_RSSI);
        pnoSettings2.setMin5gRssiDbm(TEST_MIN_5G_RSSI);
        pnoSettings2.setPnoNetworks(new ArrayList<>(Arrays.asList(mPnoNetwork1, mPnoNetwork2)));

        assertEquals(pnoSettings1, pnoSettings2);
        assertEquals(pnoSettings1.hashCode(), pnoSettings2.hashCode());

        HashMap<PnoSettings, Integer> map = new HashMap<>();
        map.put(pnoSettings1, TEST_VALUE);

        assertEquals(TEST_VALUE, map.get(pnoSettings2).intValue());
    }
}
