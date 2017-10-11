package com.android.systemui.statusbar.policy;

import android.content.Intent;
import android.net.NetworkBadging;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkKey;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.Bundle;
import android.provider.Settings;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.settingslib.Utils;
import com.android.systemui.statusbar.policy.NetworkController.IconState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NetworkControllerWifiTest extends NetworkControllerBaseTest {
    // These match the constants in WifiManager and need to be kept up to date.
    private static final int MIN_RSSI = -100;
    private static final int MAX_RSSI = -55;

    private static final int LATCH_TIMEOUT = 2000;
    private static final String TEST_SSID = "\"Test SSID\"";
    private static final String TEST_BSSID = "00:00:00:00:00:00";

    private final List<NetworkKey> mRequestedKeys = new ArrayList<>();
    private CountDownLatch mRequestScoresLatch;

    @Test
    public void testWifiIcon() {
        String testSsid = "Test SSID";
        setWifiEnabled(true);
        verifyLastWifiIcon(false, WifiIcons.WIFI_NO_NETWORK);

        setWifiState(true, testSsid);
        verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[0][0]);

        for (int testLevel = 0; testLevel < WifiIcons.WIFI_LEVEL_COUNT; testLevel++) {
            setWifiLevel(testLevel);

            setConnectivity(NetworkCapabilities.TRANSPORT_WIFI, true, true);
            verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);
            setConnectivity(NetworkCapabilities.TRANSPORT_WIFI, false, true);
            verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[0][testLevel]);
        }
    }

    @Test
    public void testBadgedWifiIcon() throws Exception {
        // TODO(sghuman): Refactor this setup code when creating a test for the badged QsIcon.
        int testLevel = 1;
        RssiCurve mockBadgeCurve = mock(RssiCurve.class);
        Bundle attr = new Bundle();
        attr.putParcelable(ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE, mockBadgeCurve);
        ScoredNetwork score =
                new ScoredNetwork(
                        new NetworkKey(new WifiKey(TEST_SSID, TEST_BSSID)),
                        null,
                        false /* meteredHint */,
                        attr);

        // Must set the Settings value before instantiating the NetworkControllerImpl due to bugs in
        // TestableSettingsProvider.
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.NETWORK_SCORING_UI_ENABLED,
                "1");
        super.setUp(); // re-instantiate NetworkControllImpl now that setting has been updated
        setupNetworkScoreManager();

        // Test Requesting Scores
        mRequestScoresLatch = new CountDownLatch(1);
        setWifiEnabled(true);
        setWifiState(true, TEST_SSID, TEST_BSSID);
        mRequestScoresLatch.await(LATCH_TIMEOUT, TimeUnit.MILLISECONDS);

        when(mockBadgeCurve.lookupScore(anyInt())).thenReturn((byte) NetworkBadging.BADGING_SD);

        ArgumentCaptor<WifiNetworkScoreCache> scoreCacheCaptor =
                ArgumentCaptor.forClass(WifiNetworkScoreCache.class);
        verify(mMockNetworkScoreManager).registerNetworkScoreCache(
                anyInt(),
                scoreCacheCaptor.capture(),
                Matchers.anyInt());
        scoreCacheCaptor.getValue().updateScores(Arrays.asList(score));

        //  Test badge is set
        setWifiLevel(testLevel);

        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setWifiIndicators(
                anyBoolean(), iconArg.capture(), any(), anyBoolean(), anyBoolean(),
                any(), anyBoolean());
        IconState iconState = iconArg.getValue();

        assertEquals("Badged Wifi Resource is set",
                Utils.WIFI_PIE_FOR_BADGING[testLevel],
                iconState.icon);
        assertEquals("SD Badge is set",
                Utils.getWifiBadgeResource(NetworkBadging.BADGING_SD),
                iconState.iconOverlay);
    }

    private void setupNetworkScoreManager() {
        // Capture requested keys and count down latch if present
        doAnswer(
                new Answer<Boolean>() {
                    @Override
                    public Boolean answer(InvocationOnMock input) {
                        if (mRequestScoresLatch != null) {
                            mRequestScoresLatch.countDown();
                        }
                        NetworkKey[] keys = (NetworkKey[]) input.getArguments()[0];
                        for (NetworkKey key : keys) {
                            mRequestedKeys.add(key);
                        }
                        return true;
                    }
                }).when(mMockNetworkScoreManager).requestScores(Matchers.<NetworkKey[]>any());
    }

    @Test
    public void testQsWifiIcon() {
        String testSsid = "Test SSID";

        setWifiEnabled(false);
        verifyLastQsWifiIcon(false, false, WifiIcons.QS_WIFI_NO_NETWORK, null);

        setWifiEnabled(true);
        verifyLastQsWifiIcon(true, false, WifiIcons.QS_WIFI_NO_NETWORK, null);

        setWifiState(true, testSsid);
        for (int testLevel = 0; testLevel < WifiIcons.WIFI_LEVEL_COUNT; testLevel++) {
            setWifiLevel(testLevel);

            setConnectivity(NetworkCapabilities.TRANSPORT_WIFI, true, true);
            verifyLastQsWifiIcon(true, true, WifiIcons.QS_WIFI_SIGNAL_STRENGTH[1][testLevel],
                    testSsid);
            setConnectivity(NetworkCapabilities.TRANSPORT_WIFI, false, true);
            verifyLastQsWifiIcon(true, true, WifiIcons.QS_WIFI_SIGNAL_STRENGTH[0][testLevel],
                    testSsid);
        }
    }

    @Test
    public void testQsDataDirection() {
        // Setup normal connection
        String testSsid = "Test SSID";
        int testLevel = 2;
        setWifiEnabled(true);
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivity(NetworkCapabilities.TRANSPORT_WIFI, true, true);
        verifyLastQsWifiIcon(true, true,
                WifiIcons.QS_WIFI_SIGNAL_STRENGTH[1][testLevel], testSsid);

        // Set to different activity state first to ensure a callback happens.
        setWifiActivity(WifiManager.DATA_ACTIVITY_IN);

        setWifiActivity(WifiManager.DATA_ACTIVITY_NONE);
        verifyLastQsDataDirection(false, false);
        setWifiActivity(WifiManager.DATA_ACTIVITY_IN);
        verifyLastQsDataDirection(true, false);
        setWifiActivity(WifiManager.DATA_ACTIVITY_OUT);
        verifyLastQsDataDirection(false, true);
        setWifiActivity(WifiManager.DATA_ACTIVITY_INOUT);
        verifyLastQsDataDirection(true, true);
    }

    @Test
    public void testRoamingIconDuringWifi() {
        // Setup normal connection
        String testSsid = "\"Test SSID\"";
        int testLevel = 2;
        setWifiEnabled(true);
        setWifiState(true, testSsid);
        setWifiLevel(testLevel);
        setConnectivity(NetworkCapabilities.TRANSPORT_WIFI, true, true);
        verifyLastWifiIcon(true, WifiIcons.WIFI_SIGNAL_STRENGTH[1][testLevel]);

        setupDefaultSignal();
        setGsmRoaming(true);
        // Still be on wifi though.
        setConnectivity(NetworkCapabilities.TRANSPORT_WIFI, true, true);
        setConnectivity(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);
        verifyLastMobileDataIndicators(true,
                DEFAULT_LEVEL,
                0, true);
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
        setWifiState(connected, ssid, null);
    }

    protected void setWifiState(boolean connected, String ssid, String bssid) {
        Intent i = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        NetworkInfo networkInfo = Mockito.mock(NetworkInfo.class);
        Mockito.when(networkInfo.isConnected()).thenReturn(connected);

        WifiInfo wifiInfo = Mockito.mock(WifiInfo.class);
        Mockito.when(wifiInfo.getSSID()).thenReturn(ssid);
        if (bssid != null) {
            Mockito.when(wifiInfo.getBSSID()).thenReturn(bssid);
        }

        i.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        i.putExtra(WifiManager.EXTRA_WIFI_INFO, wifiInfo);
        mNetworkController.onReceive(mContext, i);
    }

    protected void verifyLastQsDataDirection(boolean in, boolean out) {
        ArgumentCaptor<Boolean> inArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> outArg = ArgumentCaptor.forClass(Boolean.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setWifiIndicators(
                anyBoolean(), any(), any(), inArg.capture(), outArg.capture(), any(), anyBoolean());
        assertEquals("WiFi data in, in quick settings", in, (boolean) inArg.getValue());
        assertEquals("WiFi data out, in quick settings", out, (boolean) outArg.getValue());
    }

    protected void verifyLastQsWifiIcon(boolean enabled, boolean connected, int icon,
            String description) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Boolean> enabledArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> descArg = ArgumentCaptor.forClass(String.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setWifiIndicators(
                enabledArg.capture(), any(), iconArg.capture(), anyBoolean(),
                anyBoolean(),  descArg.capture(), anyBoolean());
        IconState iconState = iconArg.getValue();
        assertEquals("WiFi enabled, in quick settings", enabled, (boolean) enabledArg.getValue());
        assertEquals("WiFi connected, in quick settings", connected, iconState.visible);
        assertEquals("WiFi signal, in quick settings", icon, iconState.icon);
        assertEquals("WiFI desc (ssid), in quick settings", description, descArg.getValue());
    }

    protected void verifyLastWifiIcon(boolean visible, int icon) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setWifiIndicators(
                anyBoolean(), iconArg.capture(), any(), anyBoolean(), anyBoolean(),
                any(), anyBoolean());
        IconState iconState = iconArg.getValue();
        assertEquals("WiFi visible, in status bar", visible, iconState.visible);
        assertEquals("WiFi signal, in status bar", icon, iconState.icon);
    }
}
