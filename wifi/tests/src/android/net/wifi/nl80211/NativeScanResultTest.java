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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Unit tests for {@link android.net.wifi.nl80211.NativeScanResult}.
 */
@SmallTest
public class NativeScanResultTest {

    private static final byte[] TEST_SSID =
            new byte[] {'G', 'o', 'o', 'g', 'l', 'e', 'G', 'u', 'e', 's', 't'};
    private static final byte[] TEST_BSSID =
            new byte[] {(byte) 0x12, (byte) 0xef, (byte) 0xa1,
                        (byte) 0x2c, (byte) 0x97, (byte) 0x8b};
    private static final byte[] TEST_INFO_ELEMENT =
            new byte[] {(byte) 0x01, (byte) 0x03, (byte) 0x12, (byte) 0xbe, (byte) 0xff};
    private static final int TEST_FREQUENCY = 2456;
    private static final int TEST_SIGNAL_MBM = -45;
    private static final long TEST_TSF = 34455441;
    private static final int TEST_CAPABILITY = (0x1 << 2) | (0x1 << 5);
    private static final boolean TEST_ASSOCIATED = true;
    private static final int[] RADIO_CHAIN_IDS = { 0, 1 };
    private static final int[] RADIO_CHAIN_LEVELS = { -56, -65 };

    /**
     *  NativeScanResult object can be serialized and deserialized, while keeping the
     *  values unchanged.
     */
    @Test
    public void canSerializeAndDeserialize() {
        NativeScanResult scanResult = new NativeScanResult();
        scanResult.ssid = TEST_SSID;
        scanResult.bssid = TEST_BSSID;
        scanResult.infoElement = TEST_INFO_ELEMENT;
        scanResult.frequency = TEST_FREQUENCY;
        scanResult.signalMbm = TEST_SIGNAL_MBM;
        scanResult.tsf = TEST_TSF;
        scanResult.capability = TEST_CAPABILITY;
        scanResult.associated = TEST_ASSOCIATED;
        scanResult.radioChainInfos = new ArrayList<>(Arrays.asList(
                new RadioChainInfo(RADIO_CHAIN_IDS[0], RADIO_CHAIN_LEVELS[0]),
                new RadioChainInfo(RADIO_CHAIN_IDS[1], RADIO_CHAIN_LEVELS[1])));
        Parcel parcel = Parcel.obtain();
        scanResult.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        NativeScanResult scanResultDeserialized = NativeScanResult.CREATOR.createFromParcel(parcel);

        assertArrayEquals(scanResult.ssid, scanResultDeserialized.ssid);
        assertArrayEquals(scanResult.bssid, scanResultDeserialized.bssid);
        assertArrayEquals(scanResult.infoElement, scanResultDeserialized.infoElement);
        assertEquals(scanResult.frequency, scanResultDeserialized.frequency);
        assertEquals(scanResult.signalMbm, scanResultDeserialized.signalMbm);
        assertEquals(scanResult.tsf, scanResultDeserialized.tsf);
        assertEquals(scanResult.capability, scanResultDeserialized.capability);
        assertEquals(scanResult.associated, scanResultDeserialized.associated);
        assertTrue(scanResult.radioChainInfos.containsAll(scanResultDeserialized.radioChainInfos));
    }
}
