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
                + "80211mcResponder: is not supported, Carrier AP: no, "
                + "Carrier AP EAP Type: 0, Carrier name: null, "
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
                + "80211mcResponder: is not supported, Carrier AP: no, "
                + "Carrier AP EAP Type: 0, Carrier name: null, "
                + "Radio Chain Infos: [RadioChainInfo: id=0, level=-45, "
                + "RadioChainInfo: id=1, level=-54]", scanResult.toString());
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
