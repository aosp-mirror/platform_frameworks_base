/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class NetworkKeyTest {
    private static final String VALID_SSID = "\"ssid1\"";
    private static final String VALID_UNQUOTED_SSID = "ssid1";
    private static final String VALID_BSSID = "00:00:00:00:00:00";
    private static final String INVALID_BSSID = "invalid_bssid";
    @Mock private WifiInfo mWifiInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void createFromWifi_nullInput() throws Exception {
        assertNull(NetworkKey.createFromWifiInfo(null));
    }

    @Test
    public void createFromWifi_nullSsid() throws Exception {
        when(mWifiInfo.getBSSID()).thenReturn(VALID_BSSID);
        assertNull(NetworkKey.createFromWifiInfo(mWifiInfo));
    }

    @Test
    public void createFromWifi_emptySsid() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn("");
        when(mWifiInfo.getBSSID()).thenReturn(VALID_BSSID);
        assertNull(NetworkKey.createFromWifiInfo(mWifiInfo));
    }

    @Test
    public void createFromWifi_noneSsid() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn(WifiManager.UNKNOWN_SSID);
        when(mWifiInfo.getBSSID()).thenReturn(VALID_BSSID);
        assertNull(NetworkKey.createFromWifiInfo(mWifiInfo));
    }

    @Test
    public void createFromWifi_nullBssid() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn(VALID_SSID);
        assertNull(NetworkKey.createFromWifiInfo(mWifiInfo));
    }

    @Test
    public void createFromWifi_emptyBssid() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn(VALID_SSID);
        when(mWifiInfo.getBSSID()).thenReturn("");
        assertNull(NetworkKey.createFromWifiInfo(mWifiInfo));
    }

    @Test
    public void createFromWifi_invalidBssid() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn(VALID_SSID);
        when(mWifiInfo.getBSSID()).thenReturn(INVALID_BSSID);
        assertNull(NetworkKey.createFromWifiInfo(mWifiInfo));
    }

    @Test
    public void createFromWifi_validWifiInfo() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn(VALID_SSID);
        when(mWifiInfo.getBSSID()).thenReturn(VALID_BSSID);

        NetworkKey expected = new NetworkKey(new WifiKey(VALID_SSID, VALID_BSSID));
        final NetworkKey actual = NetworkKey.createFromWifiInfo(mWifiInfo);
        assertEquals(expected, actual);
    }

    @Test
    public void createFromScanResult_nullInput() {
        assertNull(NetworkKey.createFromScanResult(null));
    }

    @Test
    public void createFromScanResult_nullSsid() {
        ScanResult scanResult = mock(ScanResult.class);
        scanResult.BSSID = VALID_BSSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_emptySsid() {
        ScanResult scanResult = mock(ScanResult.class);
        scanResult.SSID = "";
        scanResult.BSSID = VALID_BSSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_noneSsid() {
        ScanResult scanResult = mock(ScanResult.class);
        scanResult.SSID = WifiManager.UNKNOWN_SSID;
        scanResult.BSSID = VALID_BSSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_nullBssid() {
        ScanResult scanResult = mock(ScanResult.class);
        scanResult.SSID = VALID_UNQUOTED_SSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_emptyBssid() {
        ScanResult scanResult = mock(ScanResult.class);
        scanResult.SSID = VALID_UNQUOTED_SSID;
        scanResult.BSSID = "";

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_invalidBssid() {
        ScanResult scanResult = mock(ScanResult.class);
        scanResult.SSID = VALID_UNQUOTED_SSID;
        scanResult.BSSID = INVALID_BSSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_validSsid() {
        ScanResult scanResult = mock(ScanResult.class);
        scanResult.SSID = VALID_UNQUOTED_SSID;
        scanResult.BSSID = VALID_BSSID;

        NetworkKey expected = new NetworkKey(new WifiKey(VALID_SSID, VALID_BSSID));
        NetworkKey actual = NetworkKey.createFromScanResult(scanResult);
        assertEquals(expected, actual);
    }
}
