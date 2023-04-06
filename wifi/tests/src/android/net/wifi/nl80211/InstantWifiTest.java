/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.test.TestAlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link android.net.wifi.nl80211.InstantWifi}.
 */
@SmallTest
public class InstantWifiTest {
    @Mock private Context mContext;
    @Mock private ConnectivityManager mMockConnectivityManager;
    @Mock private WifiManager mMockWifiManager;
    @Mock private Network mMockWifiNetwork;
    @Mock private WifiInfo mMockWifiInfo;
    @Mock private WifiConfiguration mMockWifiConfiguration;
    @Mock private IPowerManager mPowerManagerService;
    private InstantWifi mInstantWifi;
    private TestLooper mLooper;
    private Handler mHandler;
    private TestAlarmManager mTestAlarmManager;
    private AlarmManager mAlarmManager;
    private PowerManager mMockPowerManager;

    private final ArgumentCaptor<NetworkCallback> mWifiNetworkCallbackCaptor =
            ArgumentCaptor.forClass(NetworkCallback.class);
    private final ArgumentCaptor<BroadcastReceiver> mScreenBroadcastReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);
    private final ArgumentCaptor<BroadcastReceiver> mWifiStateBroadcastReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);

    private static final int TEST_NETWORK_ID = 1;
    private static final int TEST_24G_FREQUENCY = 2412;
    private static final int TEST_5G_FREQUENCY = 5745;
    private long mTimeOffsetMs = 0;

    private class InstantWifiSpy extends InstantWifi {
        InstantWifiSpy(Context context, AlarmManager alarmManager, Handler handler) {
            super(context, alarmManager, handler);
        }

        @Override
        protected long getMockableElapsedRealtime() {
            return mTimeOffsetMs;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());

        mTestAlarmManager = new TestAlarmManager();
        mAlarmManager = mTestAlarmManager.getAlarmManager();
        when(mContext.getSystemServiceName(AlarmManager.class)).thenReturn(Context.ALARM_SERVICE);
        when(mContext.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);
        when(mContext.getSystemServiceName(WifiManager.class)).thenReturn(Context.WIFI_SERVICE);
        when(mContext.getSystemService(WifiManager.class)).thenReturn(mMockWifiManager);
        when(mContext.getSystemServiceName(ConnectivityManager.class))
                .thenReturn(Context.CONNECTIVITY_SERVICE);
        when(mContext.getSystemService(ConnectivityManager.class))
                .thenReturn(mMockConnectivityManager);
        mMockPowerManager = new PowerManager(mContext, mPowerManagerService,
                mock(IThermalService.class), mHandler);
        when(mContext.getSystemServiceName(PowerManager.class)).thenReturn(Context.POWER_SERVICE);
        when(mContext.getSystemService(PowerManager.class)).thenReturn(mMockPowerManager);
        when(mPowerManagerService.isInteractive()).thenReturn(true);

        doReturn(mMockWifiInfo).when(mMockWifiInfo).makeCopy(anyLong());
        mTimeOffsetMs = 0;
        mInstantWifi = new InstantWifiSpy(mContext, mAlarmManager, mHandler);
        verifyInstantWifiInitialization();
    }

    private void verifyInstantWifiInitialization() {
        verify(mMockConnectivityManager).registerNetworkCallback(any(),
                mWifiNetworkCallbackCaptor.capture());
        verify(mContext).registerReceiver(mScreenBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_SCREEN_ON)
                                && filter.hasAction(Intent.ACTION_SCREEN_OFF)), eq(null), any());

        verify(mContext).registerReceiver(mWifiStateBroadcastReceiverCaptor.capture(),
                argThat((IntentFilter filter) ->
                        filter.hasAction(WifiManager.WIFI_STATE_CHANGED_ACTION)), eq(null), any());
    }

    private void mockWifiConnectedEvent(int networkId, int connectedFrequency) {
        // Send wifi connected event
        NetworkCapabilities mockWifiNetworkCapabilities =
                new NetworkCapabilities.Builder().setTransportInfo(mMockWifiInfo).build();
        mMockWifiConfiguration.networkId = networkId;
        when(mMockWifiManager.getPrivilegedConnectedNetwork()).thenReturn(mMockWifiConfiguration);
        when(mMockWifiInfo.getFrequency()).thenReturn(connectedFrequency);
        mWifiNetworkCallbackCaptor.getValue().onCapabilitiesChanged(mMockWifiNetwork,
                mockWifiNetworkCapabilities);
        mLooper.dispatchAll();
    }

    private void mockWifiOnScreenOnBroadcast(boolean isWifiOn, boolean isScreenOn)
            throws Exception {
        // Send Wifi On broadcast
        Intent wifiOnIntent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wifiOnIntent.putExtra(WifiManager.EXTRA_WIFI_STATE,
                isWifiOn ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED);
        mWifiStateBroadcastReceiverCaptor.getValue().onReceive(mContext, wifiOnIntent);
        mLooper.dispatchAll();
        // Send Screen On broadcast
        Intent screenOnIntent =
                new Intent(isScreenOn ? Intent.ACTION_SCREEN_ON : Intent.ACTION_SCREEN_OFF);
        mScreenBroadcastReceiverCaptor.getValue().onReceive(mContext, screenOnIntent);
        mLooper.dispatchAll();
        when(mMockWifiManager.isWifiEnabled()).thenReturn(isWifiOn);
        when(mPowerManagerService.isInteractive()).thenReturn(isScreenOn);
    }

    @Test
    public void testisUsePredictedScanningChannels() throws Exception {
        assertFalse(mInstantWifi.isUsePredictedScanningChannels());
        mockWifiOnScreenOnBroadcast(true /* isWifiOn */, false /* isScreenOn */);
        assertFalse(mInstantWifi.isUsePredictedScanningChannels());
        mockWifiOnScreenOnBroadcast(false /* isWifiOn */, true /* isScreenOn */);
        assertFalse(mInstantWifi.isUsePredictedScanningChannels());
        mockWifiOnScreenOnBroadcast(true /* isWifiOn */, true /* isScreenOn */);
        assertFalse(mInstantWifi.isUsePredictedScanningChannels());
        // Send wifi connected event
        mockWifiConnectedEvent(TEST_NETWORK_ID, TEST_24G_FREQUENCY);
        assertFalse(mInstantWifi.isUsePredictedScanningChannels());
        // Send wifi disconnect
        mWifiNetworkCallbackCaptor.getValue().onLost(mMockWifiNetwork);
        assertTrue(mInstantWifi.isUsePredictedScanningChannels());
        // Shift time to make it expired
        mTimeOffsetMs = 1100;
        assertFalse(mInstantWifi.isUsePredictedScanningChannels());
    }

    @Test
    public void testGetPredictedScanningChannels() throws Exception {
        mockWifiOnScreenOnBroadcast(true /* isWifiOn */, true /* isScreenOn */);
        // Send wifi connected event on T0
        mockWifiConnectedEvent(TEST_NETWORK_ID, TEST_24G_FREQUENCY);
        // Send wifi disconnect
        mWifiNetworkCallbackCaptor.getValue().onLost(mMockWifiNetwork);
        assertTrue(mInstantWifi.isUsePredictedScanningChannels());
        assertTrue(mInstantWifi.getPredictedScanningChannels().contains(TEST_24G_FREQUENCY));
        mTimeOffsetMs += 1000; // T1 = 1000 ms
        // Send wifi connected event
        mockWifiConnectedEvent(TEST_NETWORK_ID + 1, TEST_5G_FREQUENCY);
        // Send wifi disconnect
        mWifiNetworkCallbackCaptor.getValue().onLost(mMockWifiNetwork);
        // isUsePredictedScanningChannels is false since wifi on & screen on is expired
        assertFalse(mInstantWifi.isUsePredictedScanningChannels());
        // Override the Wifi On & Screen on time
        mockWifiOnScreenOnBroadcast(true /* isWifiOn */, true /* isScreenOn */);
        assertTrue(mInstantWifi.getPredictedScanningChannels().contains(TEST_5G_FREQUENCY));
        mTimeOffsetMs += 7 * 24 * 60 * 60 * 1000; // Make T0 expired
        // Override the Wifi On & Screen on time
        mockWifiOnScreenOnBroadcast(true /* isWifiOn */, true /* isScreenOn */);
        assertFalse(mInstantWifi.getPredictedScanningChannels().contains(TEST_24G_FREQUENCY));
        assertTrue(mInstantWifi.getPredictedScanningChannels().contains(TEST_5G_FREQUENCY));
    }

    @Test
    public void testOverrideFreqsForSingleScanSettings() throws Exception {
        mockWifiOnScreenOnBroadcast(true /* isWifiOn */, true /* isScreenOn */);
        // Send wifi connected event
        mockWifiConnectedEvent(TEST_NETWORK_ID, TEST_24G_FREQUENCY);
        assertFalse(mInstantWifi.isUsePredictedScanningChannels());
        // Send wifi disconnect
        mWifiNetworkCallbackCaptor.getValue().onLost(mMockWifiNetwork);
        assertTrue(mInstantWifi.isUsePredictedScanningChannels());

        final ArgumentCaptor<AlarmManager.OnAlarmListener> alarmListenerCaptor =
                ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);
        doNothing().when(mAlarmManager).set(anyInt(), anyLong(), any(),
                alarmListenerCaptor.capture(), any());
        Set<Integer> testFreqs = Set.of(
                TEST_24G_FREQUENCY, TEST_5G_FREQUENCY);
        SingleScanSettings testSingleScanSettings = new SingleScanSettings();
        mInstantWifi.overrideFreqsForSingleScanSettingsIfNecessary(
                testSingleScanSettings, new HashSet<Integer>());
        mInstantWifi.overrideFreqsForSingleScanSettingsIfNecessary(
                testSingleScanSettings, null);
        mInstantWifi.overrideFreqsForSingleScanSettingsIfNecessary(null, null);
        verify(mAlarmManager, never()).set(anyInt(), anyLong(), any(), any(), any());
        mInstantWifi.overrideFreqsForSingleScanSettingsIfNecessary(testSingleScanSettings,
                testFreqs);
        verify(mAlarmManager).set(anyInt(), anyLong(), any(), any(), any());
        Set<Integer> overridedFreqs = new HashSet<Integer>();
        for (ChannelSettings channel : testSingleScanSettings.channelSettings) {
            overridedFreqs.add(channel.frequency);
        }
        assertEquals(testFreqs, overridedFreqs);
        alarmListenerCaptor.getValue().onAlarm();
        verify(mMockWifiManager).startScan();
    }
}
