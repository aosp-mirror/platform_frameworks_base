/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.validateMockitoUsage;

import android.net.wifi.ScanResult.InformationElement;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link android.net.wifi.WifiScanner}.
 */
@SmallTest
public class ScanResultTest {
    public static final String TEST_SSID = "\"test_ssid\"";
    public static final String TEST_BSSID = "04:ac:fe:45:34:10";
    public static final String TEST_CAPS = "CCMP";
    public static final int TEST_LEVEL = -56;
    public static final int TEST_FREQUENCY = 2412;
    public static final long TEST_TSF = 04660l;
    public static final @WifiAnnotations.WifiStandard int TEST_WIFI_STANDARD =
            ScanResult.WIFI_STANDARD_11AC;

    /**
     * Frequency to channel map. This include some frequencies used outside the US.
     * Representing it using a vector (instead of map) for simplification.
     */
    private static final int[] FREQUENCY_TO_CHANNEL_MAP = {
            2412, WifiScanner.WIFI_BAND_24_GHZ, 1,
            2417, WifiScanner.WIFI_BAND_24_GHZ, 2,
            2422, WifiScanner.WIFI_BAND_24_GHZ, 3,
            2427, WifiScanner.WIFI_BAND_24_GHZ, 4,
            2432, WifiScanner.WIFI_BAND_24_GHZ, 5,
            2437, WifiScanner.WIFI_BAND_24_GHZ, 6,
            2442, WifiScanner.WIFI_BAND_24_GHZ, 7,
            2447, WifiScanner.WIFI_BAND_24_GHZ, 8,
            2452, WifiScanner.WIFI_BAND_24_GHZ, 9,
            2457, WifiScanner.WIFI_BAND_24_GHZ, 10,
            2462, WifiScanner.WIFI_BAND_24_GHZ, 11,
            /* 12, 13 are only legitimate outside the US. */
            2467, WifiScanner.WIFI_BAND_24_GHZ, 12,
            2472, WifiScanner.WIFI_BAND_24_GHZ, 13,
            /* 14 is for Japan, DSSS and CCK only. */
            2484, WifiScanner.WIFI_BAND_24_GHZ, 14,
            /* 34 valid in Japan. */
            5170, WifiScanner.WIFI_BAND_5_GHZ, 34,
            5180, WifiScanner.WIFI_BAND_5_GHZ, 36,
            5190, WifiScanner.WIFI_BAND_5_GHZ, 38,
            5200, WifiScanner.WIFI_BAND_5_GHZ, 40,
            5210, WifiScanner.WIFI_BAND_5_GHZ, 42,
            5220, WifiScanner.WIFI_BAND_5_GHZ, 44,
            5230, WifiScanner.WIFI_BAND_5_GHZ, 46,
            5240, WifiScanner.WIFI_BAND_5_GHZ, 48,
            5260, WifiScanner.WIFI_BAND_5_GHZ, 52,
            5280, WifiScanner.WIFI_BAND_5_GHZ, 56,
            5300, WifiScanner.WIFI_BAND_5_GHZ, 60,
            5320, WifiScanner.WIFI_BAND_5_GHZ, 64,
            5500, WifiScanner.WIFI_BAND_5_GHZ, 100,
            5520, WifiScanner.WIFI_BAND_5_GHZ, 104,
            5540, WifiScanner.WIFI_BAND_5_GHZ, 108,
            5560, WifiScanner.WIFI_BAND_5_GHZ, 112,
            5580, WifiScanner.WIFI_BAND_5_GHZ, 116,
            /* 120, 124, 128 valid in Europe/Japan. */
            5600, WifiScanner.WIFI_BAND_5_GHZ, 120,
            5620, WifiScanner.WIFI_BAND_5_GHZ, 124,
            5640, WifiScanner.WIFI_BAND_5_GHZ, 128,
            /* 132+ valid in US. */
            5660, WifiScanner.WIFI_BAND_5_GHZ, 132,
            5680, WifiScanner.WIFI_BAND_5_GHZ, 136,
            5700, WifiScanner.WIFI_BAND_5_GHZ, 140,
            /* 144 is supported by a subset of WiFi chips. */
            5720, WifiScanner.WIFI_BAND_5_GHZ, 144,
            5745, WifiScanner.WIFI_BAND_5_GHZ, 149,
            5765, WifiScanner.WIFI_BAND_5_GHZ, 153,
            5785, WifiScanner.WIFI_BAND_5_GHZ, 157,
            5805, WifiScanner.WIFI_BAND_5_GHZ, 161,
            5825, WifiScanner.WIFI_BAND_5_GHZ, 165,
            5845, WifiScanner.WIFI_BAND_5_GHZ, 169,
            5865, WifiScanner.WIFI_BAND_5_GHZ, 173,
            /* Now some 6GHz channels */
            5955, WifiScanner.WIFI_BAND_6_GHZ, 1,
            5935, WifiScanner.WIFI_BAND_6_GHZ, 2,
            5970, WifiScanner.WIFI_BAND_6_GHZ, 4,
            6110, WifiScanner.WIFI_BAND_6_GHZ, 32
    };

    /**
     * Setup before tests.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Clean up after tests.
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Verify parcel read/write for ScanResult.
     */
    @Test
    public void verifyScanResultParcelWithoutRadioChainInfo() throws Exception {
        ScanResult writeScanResult = createScanResult();
        ScanResult readScanResult = parcelReadWrite(writeScanResult);
        assertScanResultEquals(writeScanResult, readScanResult);
    }

    /**
     * Verify parcel read/write for ScanResult.
     */
    @Test
    public void verifyScanResultParcelWithZeroRadioChainInfo() throws Exception {
        ScanResult writeScanResult = createScanResult();
        writeScanResult.radioChainInfos = new ScanResult.RadioChainInfo[0];
        ScanResult readScanResult = parcelReadWrite(writeScanResult);
        assertNull(readScanResult.radioChainInfos);
    }

    /**
     * Verify parcel read/write for ScanResult.
     */
    @Test
    public void verifyScanResultParcelWithRadioChainInfo() throws Exception {
        ScanResult writeScanResult = createScanResult();
        writeScanResult.radioChainInfos = new ScanResult.RadioChainInfo[2];
        writeScanResult.radioChainInfos[0] = new ScanResult.RadioChainInfo();
        writeScanResult.radioChainInfos[0].id = 0;
        writeScanResult.radioChainInfos[0].level = -45;
        writeScanResult.radioChainInfos[1] = new ScanResult.RadioChainInfo();
        writeScanResult.radioChainInfos[1].id = 1;
        writeScanResult.radioChainInfos[1].level = -54;
        ScanResult readScanResult = parcelReadWrite(writeScanResult);
        assertScanResultEquals(writeScanResult, readScanResult);
    }

    /**
     * Verify copy constructor for ScanResult.
     */
    @Test
    public void verifyScanResultCopyWithoutRadioChainInfo() throws Exception {
        ScanResult scanResult = createScanResult();
        ScanResult copyScanResult = new ScanResult(scanResult);
        assertScanResultEquals(scanResult, copyScanResult);
    }

    /**
     * Verify copy constructor for ScanResult.
     */
    @Test
    public void verifyScanResultCopyWithRadioChainInfo() throws Exception {
        ScanResult scanResult = createScanResult();
        scanResult.radioChainInfos = new ScanResult.RadioChainInfo[2];
        scanResult.radioChainInfos[0] = new ScanResult.RadioChainInfo();
        scanResult.radioChainInfos[0].id = 0;
        scanResult.radioChainInfos[0].level = -45;
        scanResult.radioChainInfos[1] = new ScanResult.RadioChainInfo();
        scanResult.radioChainInfos[1].id = 1;
        scanResult.radioChainInfos[1].level = -54;
        ScanResult copyScanResult = new ScanResult(scanResult);
        assertScanResultEquals(scanResult, copyScanResult);
    }

    /**
     * Verify parcel read/write for ScanResult with Information Element
     */
    @Test
    public void verifyScanResultParcelWithInformationElement() throws Exception {
        ScanResult writeScanResult = createScanResult();
        writeScanResult.informationElements = new ScanResult.InformationElement[2];
        writeScanResult.informationElements[0] = new ScanResult.InformationElement();
        writeScanResult.informationElements[0].id = InformationElement.EID_HT_OPERATION;
        writeScanResult.informationElements[0].idExt = 0;
        writeScanResult.informationElements[0].bytes = new byte[]{0x11, 0x22, 0x33};
        writeScanResult.informationElements[1] = new ScanResult.InformationElement();
        writeScanResult.informationElements[1].id = InformationElement.EID_EXTENSION_PRESENT;
        writeScanResult.informationElements[1].idExt = InformationElement.EID_EXT_HE_OPERATION;
        writeScanResult.informationElements[1].bytes = new byte[]{0x44, 0x55, 0x66};
        ScanResult readScanResult = new ScanResult(writeScanResult);
        assertScanResultEquals(writeScanResult, readScanResult);
    }

    /**
     * Verify toString for ScanResult.
     */
    @Test
    public void verifyScanResultToStringWithoutRadioChainInfo() throws Exception {
        ScanResult scanResult = createScanResult();
        assertEquals("SSID: \"test_ssid\", BSSID: 04:ac:fe:45:34:10, capabilities: CCMP, "
                + "level: -56, frequency: 2412, timestamp: 2480, "
                + "distance: 0(cm), distanceSd: 0(cm), "
                + "passpoint: no, ChannelBandwidth: 0, centerFreq0: 0, centerFreq1: 0, "
                + "standard: 11ac, "
                + "80211mcResponder: is not supported, "
                + "Radio Chain Infos: null", scanResult.toString());
    }

    /**
     * Verify toString for ScanResult.
     */
    @Test
    public void verifyScanResultToStringWithRadioChainInfo() throws Exception {
        ScanResult scanResult = createScanResult();
        scanResult.radioChainInfos = new ScanResult.RadioChainInfo[2];
        scanResult.radioChainInfos[0] = new ScanResult.RadioChainInfo();
        scanResult.radioChainInfos[0].id = 0;
        scanResult.radioChainInfos[0].level = -45;
        scanResult.radioChainInfos[1] = new ScanResult.RadioChainInfo();
        scanResult.radioChainInfos[1].id = 1;
        scanResult.radioChainInfos[1].level = -54;
        assertEquals("SSID: \"test_ssid\", BSSID: 04:ac:fe:45:34:10, capabilities: CCMP, "
                + "level: -56, frequency: 2412, timestamp: 2480, distance: 0(cm), "
                + "distanceSd: 0(cm), "
                + "passpoint: no, ChannelBandwidth: 0, centerFreq0: 0, centerFreq1: 0, "
                + "standard: 11ac, "
                + "80211mcResponder: is not supported, "
                + "Radio Chain Infos: [RadioChainInfo: id=0, level=-45, "
                + "RadioChainInfo: id=1, level=-54]", scanResult.toString());
    }

    /**
     * verify frequency to channel conversion for all possible frequencies.
     */
    @Test
    public void convertFrequencyToChannel() throws Exception {
        for (int i = 0; i < FREQUENCY_TO_CHANNEL_MAP.length; i += 3) {
            assertEquals(FREQUENCY_TO_CHANNEL_MAP[i + 2],
                    ScanResult.convertFrequencyMhzToChannel(FREQUENCY_TO_CHANNEL_MAP[i]));
        }
    }

    /**
     * Verify frequency to channel conversion failed for an invalid frequency.
     */
    @Test
    public void convertFrequencyToChannelWithInvalidFreq() throws Exception {
        assertEquals(-1, ScanResult.convertFrequencyMhzToChannel(8000));
    }

    /**
     * Write the provided {@link ScanResult} to a parcel and deserialize it.
     */
    private static ScanResult parcelReadWrite(ScanResult writeResult) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeResult.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        return ScanResult.CREATOR.createFromParcel(parcel);
    }

    private static ScanResult createScanResult() {
        ScanResult result = new ScanResult();
        result.wifiSsid = WifiSsid.createFromAsciiEncoded(TEST_SSID);
        result.BSSID = TEST_BSSID;
        result.capabilities = TEST_CAPS;
        result.level = TEST_LEVEL;
        result.frequency = TEST_FREQUENCY;
        result.timestamp = TEST_TSF;
        result.setWifiStandard(TEST_WIFI_STANDARD);
        return result;
    }

    private static void assertScanResultEquals(ScanResult expected, ScanResult actual) {
        assertEquals(expected.SSID, actual.SSID);
        assertEquals(expected.BSSID, actual.BSSID);
        assertEquals(expected.capabilities, actual.capabilities);
        assertEquals(expected.level, actual.level);
        assertEquals(expected.frequency, actual.frequency);
        assertEquals(expected.timestamp, actual.timestamp);
        assertEquals(expected.getWifiStandard(), actual.getWifiStandard());
        assertArrayEquals(expected.radioChainInfos, actual.radioChainInfos);
        assertArrayEquals(expected.informationElements, actual.informationElements);
    }
}
