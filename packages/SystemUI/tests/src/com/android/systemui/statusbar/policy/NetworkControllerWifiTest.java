package com.android.systemui.statusbar.policy;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class NetworkControllerWifiTest extends NetworkControllerBaseTest {
    // These match the constants in WifiManager and need to be kept up to date.
    private static final int MIN_RSSI = -100;
    private static final int MAX_RSSI = -55;

    public void testWifiIcon() {
        String testSsid = "Test SSID";
        setWifiEnabled(true);
        verifyLastWifiIcon(false, WifiIcons.WIFI_NO_NETWORK);

        setWifiState(true, testSsid);
        verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[0][0]);

        for (int testLevel = 0; testLevel < WifiIcons.WIFI_LEVEL_COUNT; testLevel++) {
            setWifiLevel(testLevel);

            setConnectivity(100, ConnectivityManager.TYPE_WIFI, true);
            verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);
            setConnectivity(0, ConnectivityManager.TYPE_WIFI, true);
            verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[0][testLevel]);
        }
    }

    public void testQsWifiIcon() {
        String testSsid = "Test SSID";

        setWifiEnabled(false);
        verifyLastQsWifiIcon(false, false, WifiIcons.QS_WIFI_NO_NETWORK, null);

        setWifiEnabled(true);
        verifyLastQsWifiIcon(true, false, WifiIcons.QS_WIFI_NO_NETWORK, null);

        setWifiState(true, testSsid);
        for (int testLevel = 0; testLevel < WifiIcons.WIFI_LEVEL_COUNT; testLevel++) {
            setWifiLevel(testLevel);

            setConnectivity(100, ConnectivityManager.TYPE_WIFI, true);
            verifyLastQsWifiIcon(true, true, WifiIcons.QS_WIFI_SIGNAL_STRENGTH[1][testLevel],
                    testSsid);
            setConnectivity(0, ConnectivityManager.TYPE_WIFI, true);
            verifyLastQsWifiIcon(true, true, WifiIcons.QS_WIFI_SIGNAL_STRENGTH[0][testLevel],
                    testSsid);
        }
    }

    public void testQsDataDirection() {
        // Setup normal connection
        String testSsid = "Test SSID";
        int testLevel = 2;
        setWifiEnabled(true);
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivity(100, ConnectivityManager.TYPE_WIFI, true);
        verifyLastQsWifiIcon(true, true,
                WifiIcons.QS_WIFI_SIGNAL_STRENGTH[1][testLevel], testSsid);

        setWifiActivity(WifiManager.DATA_ACTIVITY_NONE);
        verifyLastQsDataDirection(false, false);
        setWifiActivity(WifiManager.DATA_ACTIVITY_IN);
        verifyLastQsDataDirection(true, false);
        setWifiActivity(WifiManager.DATA_ACTIVITY_OUT);
        verifyLastQsDataDirection(false, true);
        setWifiActivity(WifiManager.DATA_ACTIVITY_INOUT);
        verifyLastQsDataDirection(true, true);
    }

    public void testRoamingIconDuringWifi() {
        // Setup normal connection
        String testSsid = "Test SSID";
        int testLevel = 2;
        setWifiEnabled(true);
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivity(100, ConnectivityManager.TYPE_WIFI, true);
        verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);

        setupDefaultSignal();
        setGsmRoaming(true);
        // Still be on wifi though.
        setConnectivity(100, ConnectivityManager.TYPE_WIFI, true);
        verifyLastMobileDataIndicators(true,
                TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[1][DEFAULT_LEVEL],
                TelephonyIcons.ROAMING_ICON);
    }

    protected void setWifiActivity(int activity) {
        // TODO: Not this, because this variable probably isn't sticking around.
        mNetworkController.mWifiSignalController.setActivity(activity);
    }

    protected void setWifiLevel(int level) {
        float amountPerLevel = (MAX_RSSI - MIN_RSSI) / (WifiIcons.WIFI_LEVEL_COUNT - 1);
        int rssi = (int)(MIN_RSSI + level * amountPerLevel);
        // Put RSSI in the middle of the range.
        rssi += amountPerLevel / 2;
        Intent i = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        i.putExtra(WifiManager.EXTRA_NEW_RSSI, rssi);
        mNetworkController.onReceive(mContext, i);
    }

    protected void setWifiEnabled(boolean enabled) {
        Intent i = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        i.putExtra(WifiManager.EXTRA_WIFI_STATE,
                enabled ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED);
        mNetworkController.onReceive(mContext, i);
    }

    protected void setWifiState(boolean connected, String ssid) {
        Intent i = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        NetworkInfo networkInfo = Mockito.mock(NetworkInfo.class);
        Mockito.when(networkInfo.isConnected()).thenReturn(connected);

        WifiInfo wifiInfo = Mockito.mock(WifiInfo.class);
        Mockito.when(wifiInfo.getSSID()).thenReturn(ssid);

        i.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        i.putExtra(WifiManager.EXTRA_WIFI_INFO, wifiInfo);
        mNetworkController.onReceive(mContext, i);
    }

    protected void verifyLastQsDataDirection(boolean in, boolean out) {
        ArgumentCaptor<Boolean> inArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> outArg = ArgumentCaptor.forClass(Boolean.class);

        Mockito.verify(mNetworkSignalChangedCallback, Mockito.atLeastOnce()).onWifiSignalChanged(
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                inArg.capture(), outArg.capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(String.class).capture());
        assertEquals("WiFi data in, in quick settings", in, (boolean) inArg.getValue());
        assertEquals("WiFi data out, in quick settings", out, (boolean) outArg.getValue());
    }

    protected void verifyLastQsWifiIcon(boolean enabled, boolean connected, int icon,
            String description) {
        ArgumentCaptor<Boolean> enabledArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> connectedArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> iconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> descArg = ArgumentCaptor.forClass(String.class);

        Mockito.verify(mNetworkSignalChangedCallback, Mockito.atLeastOnce()).onWifiSignalChanged(
                enabledArg.capture(), connectedArg.capture(), iconArg.capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                descArg.capture());
        assertEquals("WiFi enabled, in quick settings", enabled, (boolean) enabledArg.getValue());
        assertEquals("WiFi connected, in quick settings", connected,
                (boolean) connectedArg.getValue());
        assertEquals("WiFi signal, in quick settings", icon, (int) iconArg.getValue());
        assertEquals("WiFI desc (ssid), in quick settings", description,
                (String) descArg.getValue());
    }

    protected void verifyLastWifiIcon(boolean visible, int icon) {
        ArgumentCaptor<Boolean> visibleArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> iconArg = ArgumentCaptor.forClass(Integer.class);

        Mockito.verify(mSignalCluster, Mockito.atLeastOnce()).setWifiIndicators(
                visibleArg.capture(), iconArg.capture(),
                ArgumentCaptor.forClass(String.class).capture());
        assertEquals("WiFi visible, in status bar", visible, (boolean) visibleArg.getValue());
        assertEquals("WiFi signal, in status bar", icon, (int) iconArg.getValue());
    }
}
