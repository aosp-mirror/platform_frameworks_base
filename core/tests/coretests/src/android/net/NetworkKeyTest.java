package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.support.test.runner.AndroidJUnit4;

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
        when(mWifiInfo.getSSID()).thenReturn(WifiSsid.NONE);
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
    public void createFromScanResult_nullWifiSsid() {
        ScanResult scanResult = new ScanResult();
        scanResult.BSSID = VALID_BSSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_emptyWifiSsid() {
        ScanResult scanResult = new ScanResult();
        scanResult.wifiSsid = WifiSsid.createFromAsciiEncoded("");
        scanResult.BSSID = VALID_BSSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_noneWifiSsid() {
        ScanResult scanResult = new ScanResult();
        scanResult.wifiSsid = WifiSsid.createFromAsciiEncoded(WifiSsid.NONE);
        scanResult.BSSID = VALID_BSSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_nullBssid() {
        ScanResult scanResult = new ScanResult();
        scanResult.wifiSsid = WifiSsid.createFromAsciiEncoded(VALID_UNQUOTED_SSID);

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_emptyBssid() {
        ScanResult scanResult = new ScanResult();
        scanResult.wifiSsid = WifiSsid.createFromAsciiEncoded(VALID_UNQUOTED_SSID);
        scanResult.BSSID = "";

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_invalidBssid() {
        ScanResult scanResult = new ScanResult();
        scanResult.wifiSsid = WifiSsid.createFromAsciiEncoded(VALID_UNQUOTED_SSID);
        scanResult.BSSID = INVALID_BSSID;

        assertNull(NetworkKey.createFromScanResult(scanResult));
    }

    @Test
    public void createFromScanResult_validWifiSsid() {
        ScanResult scanResult = new ScanResult();
        scanResult.wifiSsid = WifiSsid.createFromAsciiEncoded(VALID_UNQUOTED_SSID);
        scanResult.BSSID = VALID_BSSID;

        NetworkKey expected = new NetworkKey(new WifiKey(VALID_SSID, VALID_BSSID));
        NetworkKey actual = NetworkKey.createFromScanResult(scanResult);
        assertEquals(expected, actual);
    }
}
