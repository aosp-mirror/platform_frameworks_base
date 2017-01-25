package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

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
    private static final String VALID_BSSID = "00:00:00:00:00:00";
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
    public void createFromWifi_validWifiInfo() throws Exception {
        when(mWifiInfo.getSSID()).thenReturn(VALID_SSID);
        when(mWifiInfo.getBSSID()).thenReturn(VALID_BSSID);

        NetworkKey expected = new NetworkKey(new WifiKey(VALID_SSID, VALID_BSSID));
        final NetworkKey actual = NetworkKey.createFromWifiInfo(mWifiInfo);
        assertEquals(expected, actual);
    }
}
