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

package android.net.wifi;

import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_METERED;
import static android.net.wifi.WifiManager.ActionListener;
import static android.net.wifi.WifiManager.BUSY;
import static android.net.wifi.WifiManager.ERROR;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.REQUEST_REGISTERED;
import static android.net.wifi.WifiManager.NOT_AUTHORIZED;
import static android.net.wifi.WifiManager.OnWifiActivityEnergyInfoListener;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_GENERAL;
import static android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
import static android.net.wifi.WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_AUTHENTICATION;
import static android.net.wifi.WifiManager.TxPacketCountListener;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;
import static android.net.wifi.WifiManager.WIFI_FEATURE_DPP;
import static android.net.wifi.WifiManager.WIFI_FEATURE_OWE;
import static android.net.wifi.WifiManager.WIFI_FEATURE_P2P;
import static android.net.wifi.WifiManager.WIFI_FEATURE_PASSPOINT;
import static android.net.wifi.WifiManager.WIFI_FEATURE_SCANNER;
import static android.net.wifi.WifiManager.WIFI_FEATURE_WPA3_SAE;
import static android.net.wifi.WifiManager.WIFI_FEATURE_WPA3_SUITE_B;
import static android.net.wifi.WifiManager.WpsCallback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.DhcpInfo;
import android.net.MacAddress;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiManager.LocalOnlyHotspotObserver;
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation;
import android.net.wifi.WifiManager.LocalOnlyHotspotSubscription;
import android.net.wifi.WifiManager.NetworkRequestMatchCallback;
import android.net.wifi.WifiManager.NetworkRequestUserSelectionCallback;
import android.net.wifi.WifiManager.OnWifiUsabilityStatsListener;
import android.net.wifi.WifiManager.ScanResultsCallback;
import android.net.wifi.WifiManager.SoftApCallback;
import android.net.wifi.WifiManager.SuggestionConnectionStatusListener;
import android.net.wifi.WifiManager.TrafficStateCallback;
import android.net.wifi.WifiManager.WifiConnectedNetworkScorer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.test.TestLooper;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Unit tests for {@link android.net.wifi.WifiManager}.
 */
@SmallTest
public class WifiManagerTest {

    private static final int ERROR_NOT_SET = -1;
    private static final int ERROR_TEST_REASON = 5;
    private static final int TEST_UID = 14553;
    private static final int TEST_NETWORK_ID = 143;
    private static final String TEST_PACKAGE_NAME = "TestPackage";
    private static final String TEST_FEATURE_ID = "TestFeature";
    private static final String TEST_COUNTRY_CODE = "US";
    private static final String[] TEST_MAC_ADDRESSES = {"da:a1:19:0:0:0"};
    private static final int TEST_AP_FREQUENCY = 2412;
    private static final int TEST_AP_BANDWIDTH = SoftApInfo.CHANNEL_WIDTH_20MHZ;

    @Mock Context mContext;
    @Mock android.net.wifi.IWifiManager mWifiService;
    @Mock ApplicationInfo mApplicationInfo;
    @Mock WifiConfiguration mApConfig;
    @Mock SoftApCallback mSoftApCallback;
    @Mock TrafficStateCallback mTrafficStateCallback;
    @Mock NetworkRequestMatchCallback mNetworkRequestMatchCallback;
    @Mock OnWifiUsabilityStatsListener mOnWifiUsabilityStatsListener;
    @Mock OnWifiActivityEnergyInfoListener mOnWifiActivityEnergyInfoListener;
    @Mock SuggestionConnectionStatusListener mListener;
    @Mock Runnable mRunnable;
    @Mock Executor mExecutor;
    @Mock Executor mAnotherExecutor;
    @Mock ActivityManager mActivityManager;
    @Mock WifiConnectedNetworkScorer mWifiConnectedNetworkScorer;

    private Handler mHandler;
    private TestLooper mLooper;
    private WifiManager mWifiManager;
    private WifiNetworkSuggestion mWifiNetworkSuggestion;
    private ScanResultsCallback mScanResultsCallback;
    private WifiActivityEnergyInfo mWifiActivityEnergyInfo;

    /**
     * Util function to check public field which used for softap  in WifiConfiguration
     * same as the value in SoftApConfiguration.
     *
     */
    private boolean compareWifiAndSoftApConfiguration(
            SoftApConfiguration softApConfig, WifiConfiguration wifiConfig) {
        if (!Objects.equals(wifiConfig.SSID, softApConfig.getSsid())) {
            return false;
        }
        if (!Objects.equals(wifiConfig.BSSID, softApConfig.getBssid())) {
            return false;
        }
        if (!Objects.equals(wifiConfig.preSharedKey, softApConfig.getPassphrase())) {
            return false;
        }

        if (wifiConfig.hiddenSSID != softApConfig.isHiddenSsid()) {
            return false;
        }
        switch (softApConfig.getSecurityType()) {
            case SoftApConfiguration.SECURITY_TYPE_OPEN:
                if (wifiConfig.getAuthType() != WifiConfiguration.KeyMgmt.NONE) {
                    return false;
                }
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                if (wifiConfig.getAuthType() != WifiConfiguration.KeyMgmt.WPA2_PSK) {
                    return false;
                }
                break;
            default:
                return false;
        }
        return true;
    }

    private SoftApConfiguration generatorTestSoftApConfig() {
        return new SoftApConfiguration.Builder()
                .setSsid("TestSSID")
                .setPassphrase("TestPassphrase", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        mHandler = spy(new Handler(mLooper.getLooper()));
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.Q;
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mContext.getOpPackageName()).thenReturn(TEST_PACKAGE_NAME);
        mWifiManager = new WifiManager(mContext, mWifiService, mLooper.getLooper());
        verify(mWifiService).getVerboseLoggingLevel();
        mWifiNetworkSuggestion = new WifiNetworkSuggestion();
        mScanResultsCallback = new ScanResultsCallback() {
            @Override
            public void onScanResultsAvailable() {
                mRunnable.run();
            }
        };
        mWifiActivityEnergyInfo = new WifiActivityEnergyInfo(0, 0, 0, 0, 0, 0);
    }

    /**
     * Check the call to startSoftAp calls WifiService to startSoftAp with the provided
     * WifiConfiguration.  Verify that the return value is propagated to the caller.
     */
    @Test
    public void testStartSoftApCallsServiceWithWifiConfig() throws Exception {
        when(mWifiService.startSoftAp(eq(mApConfig))).thenReturn(true);
        assertTrue(mWifiManager.startSoftAp(mApConfig));

        when(mWifiService.startSoftAp(eq(mApConfig))).thenReturn(false);
        assertFalse(mWifiManager.startSoftAp(mApConfig));
    }

    /**
     * Check the call to startSoftAp calls WifiService to startSoftAp with a null config.  Verify
     * that the return value is propagated to the caller.
     */
    @Test
    public void testStartSoftApCallsServiceWithNullConfig() throws Exception {
        when(mWifiService.startSoftAp(eq(null))).thenReturn(true);
        assertTrue(mWifiManager.startSoftAp(null));

        when(mWifiService.startSoftAp(eq(null))).thenReturn(false);
        assertFalse(mWifiManager.startSoftAp(null));
    }

    /**
     * Check the call to stopSoftAp calls WifiService to stopSoftAp.
     */
    @Test
    public void testStopSoftApCallsService() throws Exception {
        when(mWifiService.stopSoftAp()).thenReturn(true);
        assertTrue(mWifiManager.stopSoftAp());

        when(mWifiService.stopSoftAp()).thenReturn(false);
        assertFalse(mWifiManager.stopSoftAp());
    }

    /**
     * Check the call to startSoftAp calls WifiService to startSoftAp with the provided
     * WifiConfiguration.  Verify that the return value is propagated to the caller.
     */
    @Test
    public void testStartTetheredHotspotCallsServiceWithSoftApConfig() throws Exception {
        SoftApConfiguration softApConfig = generatorTestSoftApConfig();
        when(mWifiService.startTetheredHotspot(eq(softApConfig))).thenReturn(true);
        assertTrue(mWifiManager.startTetheredHotspot(softApConfig));

        when(mWifiService.startTetheredHotspot(eq(softApConfig))).thenReturn(false);
        assertFalse(mWifiManager.startTetheredHotspot(softApConfig));
    }

    /**
     * Check the call to startSoftAp calls WifiService to startSoftAp with a null config.  Verify
     * that the return value is propagated to the caller.
     */
    @Test
    public void testStartTetheredHotspotCallsServiceWithNullConfig() throws Exception {
        when(mWifiService.startTetheredHotspot(eq(null))).thenReturn(true);
        assertTrue(mWifiManager.startTetheredHotspot(null));

        when(mWifiService.startTetheredHotspot(eq(null))).thenReturn(false);
        assertFalse(mWifiManager.startTetheredHotspot(null));
    }

    /**
     * Test creation of a LocalOnlyHotspotReservation and verify that close properly calls
     * WifiService.stopLocalOnlyHotspot.
     */
    @Test
    public void testCreationAndCloseOfLocalOnlyHotspotReservation() throws Exception {
        SoftApConfiguration softApConfig = generatorTestSoftApConfig();
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);

        callback.onStarted(mWifiManager.new LocalOnlyHotspotReservation(softApConfig));

        assertEquals(softApConfig, callback.mRes.getSoftApConfiguration());
        WifiConfiguration wifiConfig = callback.mRes.getWifiConfiguration();
        assertTrue(compareWifiAndSoftApConfiguration(softApConfig, wifiConfig));

        callback.mRes.close();
        verify(mWifiService).stopLocalOnlyHotspot();
    }

    /**
     * Verify stopLOHS is called when try-with-resources is used properly.
     */
    @Test
    public void testLocalOnlyHotspotReservationCallsStopProperlyInTryWithResources()
            throws Exception {
        SoftApConfiguration softApConfig = generatorTestSoftApConfig();
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);

        callback.onStarted(mWifiManager.new LocalOnlyHotspotReservation(softApConfig));

        try (WifiManager.LocalOnlyHotspotReservation res = callback.mRes) {
            assertEquals(softApConfig, res.getSoftApConfiguration());
            WifiConfiguration wifiConfig = callback.mRes.getWifiConfiguration();
            assertTrue(compareWifiAndSoftApConfiguration(softApConfig, wifiConfig));
        }

        verify(mWifiService).stopLocalOnlyHotspot();
    }

    /**
     * Test creation of a LocalOnlyHotspotSubscription.
     * TODO: when registrations are tracked, verify removal on close.
     */
    @Test
    public void testCreationOfLocalOnlyHotspotSubscription() throws Exception {
        try (WifiManager.LocalOnlyHotspotSubscription sub =
                     mWifiManager.new LocalOnlyHotspotSubscription()) {
            sub.close();
        }
    }

    public class TestLocalOnlyHotspotCallback extends LocalOnlyHotspotCallback {
        public boolean mOnStartedCalled = false;
        public boolean mOnStoppedCalled = false;
        public int mFailureReason = -1;
        public LocalOnlyHotspotReservation mRes = null;
        public long mCallingThreadId = -1;

        @Override
        public void onStarted(LocalOnlyHotspotReservation r) {
            mRes = r;
            mOnStartedCalled = true;
            mCallingThreadId = Thread.currentThread().getId();
        }

        @Override
        public void onStopped() {
            mOnStoppedCalled = true;
            mCallingThreadId = Thread.currentThread().getId();
        }

        @Override
        public void onFailed(int reason) {
            mFailureReason = reason;
            mCallingThreadId = Thread.currentThread().getId();
        }
    }

    /**
     * Verify callback is properly plumbed when called.
     */
    @Test
    public void testLocalOnlyHotspotCallback() {
        SoftApConfiguration softApConfig = generatorTestSoftApConfig();
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);
        assertEquals(null, callback.mRes);

        // test onStarted
        WifiManager.LocalOnlyHotspotReservation res =
                mWifiManager.new LocalOnlyHotspotReservation(softApConfig);
        callback.onStarted(res);
        assertEquals(res, callback.mRes);
        assertTrue(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);

        // test onStopped
        callback.onStopped();
        assertEquals(res, callback.mRes);
        assertTrue(callback.mOnStartedCalled);
        assertTrue(callback.mOnStoppedCalled);
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);

        // test onFailed
        callback.onFailed(ERROR_TEST_REASON);
        assertEquals(res, callback.mRes);
        assertTrue(callback.mOnStartedCalled);
        assertTrue(callback.mOnStoppedCalled);
        assertEquals(ERROR_TEST_REASON, callback.mFailureReason);
    }

    public class TestLocalOnlyHotspotObserver extends LocalOnlyHotspotObserver {
        public boolean mOnRegistered = false;
        public boolean mOnStartedCalled = false;
        public boolean mOnStoppedCalled = false;
        public SoftApConfiguration mConfig = null;
        public LocalOnlyHotspotSubscription mSub = null;
        public long mCallingThreadId = -1;

        @Override
        public void onRegistered(LocalOnlyHotspotSubscription sub) {
            mOnRegistered = true;
            mSub = sub;
            mCallingThreadId = Thread.currentThread().getId();
        }

        @Override
        public void onStarted(SoftApConfiguration config) {
            mOnStartedCalled = true;
            mConfig = config;
            mCallingThreadId = Thread.currentThread().getId();
        }

        @Override
        public void onStopped() {
            mOnStoppedCalled = true;
            mCallingThreadId = Thread.currentThread().getId();
        }
    }

    /**
     * Verify observer is properly plumbed when called.
     */
    @Test
    public void testLocalOnlyHotspotObserver() {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        SoftApConfiguration softApConfig = generatorTestSoftApConfig();
        assertFalse(observer.mOnRegistered);
        assertFalse(observer.mOnStartedCalled);
        assertFalse(observer.mOnStoppedCalled);
        assertEquals(null, observer.mConfig);
        assertEquals(null, observer.mSub);

        WifiManager.LocalOnlyHotspotSubscription sub =
                mWifiManager.new LocalOnlyHotspotSubscription();
        observer.onRegistered(sub);
        assertTrue(observer.mOnRegistered);
        assertFalse(observer.mOnStartedCalled);
        assertFalse(observer.mOnStoppedCalled);
        assertEquals(null, observer.mConfig);
        assertEquals(sub, observer.mSub);

        observer.onStarted(softApConfig);
        assertTrue(observer.mOnRegistered);
        assertTrue(observer.mOnStartedCalled);
        assertFalse(observer.mOnStoppedCalled);
        assertEquals(softApConfig, observer.mConfig);
        assertEquals(sub, observer.mSub);

        observer.onStopped();
        assertTrue(observer.mOnRegistered);
        assertTrue(observer.mOnStartedCalled);
        assertTrue(observer.mOnStoppedCalled);
        assertEquals(softApConfig, observer.mConfig);
        assertEquals(sub, observer.mSub);
    }

    /**
     * Verify call to startLocalOnlyHotspot goes to WifiServiceImpl.
     */
    @Test
    public void testStartLocalOnlyHotspot() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);

        verify(mWifiService).startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class),
                anyString(), nullable(String.class), eq(null));
    }

    /**
     * Verify a SecurityException is thrown for callers without proper permissions for
     * startLocalOnlyHotspot.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityException() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        doThrow(new SecurityException()).when(mWifiService).startLocalOnlyHotspot(
                any(ILocalOnlyHotspotCallback.class), anyString(), nullable(String.class),
                eq(null));
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
    }

    /**
     * Verify an IllegalStateException is thrown for callers that already have a pending request for
     * startLocalOnlyHotspot.
     */
    @Test(expected = IllegalStateException.class)
    public void testStartLocalOnlyHotspotThrowsIllegalStateException() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        doThrow(new IllegalStateException()).when(mWifiService).startLocalOnlyHotspot(
                any(ILocalOnlyHotspotCallback.class), anyString(), nullable(String.class),
                eq(null));
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
    }

    /**
     * Verify that the handler provided by the caller is used for the callbacks.
     */
    @Test
    public void testCorrectLooperIsUsedForHandler() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(ERROR_INCOMPATIBLE_MODE);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        assertEquals(ERROR_INCOMPATIBLE_MODE, callback.mFailureReason);
        verify(mContext, never()).getMainLooper();
        verify(mContext, never()).getMainExecutor();
    }

    /**
     * Verify that the main looper's thread is used if a handler is not provided by the reqiestomg
     * application.
     */
    @Test
    public void testMainLooperIsUsedWhenHandlerNotProvided() throws Exception {
        // record thread from looper.getThread and check ids.
        TestLooper altLooper = new TestLooper();
        when(mContext.getMainExecutor()).thenReturn(altLooper.getNewExecutor());
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(ERROR_INCOMPATIBLE_MODE);
        mWifiManager.startLocalOnlyHotspot(callback, null);
        altLooper.dispatchAll();
        assertEquals(ERROR_INCOMPATIBLE_MODE, callback.mFailureReason);
        assertEquals(altLooper.getLooper().getThread().getId(), callback.mCallingThreadId);
        verify(mContext).getMainExecutor();
    }

    /**
     * Verify the LOHS onStarted callback is triggered when WifiManager receives a HOTSPOT_STARTED
     * message from WifiServiceImpl.
     */
    @Test
    public void testOnStartedIsCalledWithReservation() throws Exception {
        SoftApConfiguration softApConfig = generatorTestSoftApConfig();
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        ArgumentCaptor<ILocalOnlyHotspotCallback> internalCallback =
                ArgumentCaptor.forClass(ILocalOnlyHotspotCallback.class);
        when(mWifiService.startLocalOnlyHotspot(internalCallback.capture(), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(callback.mOnStartedCalled);
        assertEquals(null, callback.mRes);
        // now trigger the callback
        internalCallback.getValue().onHotspotStarted(softApConfig);
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertTrue(callback.mOnStartedCalled);
        assertEquals(softApConfig, callback.mRes.getSoftApConfiguration());
        WifiConfiguration wifiConfig = callback.mRes.getWifiConfiguration();
        assertTrue(compareWifiAndSoftApConfiguration(softApConfig, wifiConfig));
    }

    /**
     * Verify the LOHS onStarted callback is triggered when WifiManager receives a HOTSPOT_STARTED
     * message from WifiServiceImpl when softap enabled with SAE security type.
     */
    @Test
    public void testOnStartedIsCalledWithReservationAndSaeSoftApConfig() throws Exception {
        SoftApConfiguration softApConfig = new SoftApConfiguration.Builder()
                .setSsid("TestSSID")
                .setPassphrase("TestPassphrase", SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)
                .build();
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        ArgumentCaptor<ILocalOnlyHotspotCallback> internalCallback =
                ArgumentCaptor.forClass(ILocalOnlyHotspotCallback.class);
        when(mWifiService.startLocalOnlyHotspot(internalCallback.capture(), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(callback.mOnStartedCalled);
        assertEquals(null, callback.mRes);
        // now trigger the callback
        internalCallback.getValue().onHotspotStarted(softApConfig);
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertTrue(callback.mOnStartedCalled);
        assertEquals(softApConfig, callback.mRes.getSoftApConfiguration());
        assertEquals(null, callback.mRes.getWifiConfiguration());
    }

    /**
     * Verify onFailed is called if WifiServiceImpl sends a HOTSPOT_STARTED message with a null
     * config.
     */
    @Test
    public void testOnStartedIsCalledWithNullConfig() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        ArgumentCaptor<ILocalOnlyHotspotCallback> internalCallback =
                ArgumentCaptor.forClass(ILocalOnlyHotspotCallback.class);
        when(mWifiService.startLocalOnlyHotspot(internalCallback.capture(), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(callback.mOnStartedCalled);
        assertEquals(null, callback.mRes);
        // now trigger the callback
        internalCallback.getValue().onHotspotStarted(null);
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertFalse(callback.mOnStartedCalled);
        assertEquals(ERROR_GENERIC, callback.mFailureReason);
    }

    /**
     * Verify onStopped is called if WifiServiceImpl sends a HOTSPOT_STOPPED message.
     */
    @Test
    public void testOnStoppedIsCalled() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        ArgumentCaptor<ILocalOnlyHotspotCallback> internalCallback =
                ArgumentCaptor.forClass(ILocalOnlyHotspotCallback.class);
        when(mWifiService.startLocalOnlyHotspot(internalCallback.capture(), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(callback.mOnStoppedCalled);
        // now trigger the callback
        internalCallback.getValue().onHotspotStopped();
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertTrue(callback.mOnStoppedCalled);
    }

    /**
     * Verify onFailed is called if WifiServiceImpl sends a HOTSPOT_FAILED message.
     */
    @Test
    public void testOnFailedIsCalled() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        ArgumentCaptor<ILocalOnlyHotspotCallback> internalCallback =
                ArgumentCaptor.forClass(ILocalOnlyHotspotCallback.class);
        when(mWifiService.startLocalOnlyHotspot(internalCallback.capture(), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);
        // now trigger the callback
        internalCallback.getValue().onHotspotFailed(ERROR_NO_CHANNEL);
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertEquals(ERROR_NO_CHANNEL, callback.mFailureReason);
    }

    /**
     * Verify callback triggered from startLocalOnlyHotspot with an incompatible mode failure.
     */
    @Test
    public void testLocalOnlyHotspotCallbackFullOnIncompatibleMode() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(ERROR_INCOMPATIBLE_MODE);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        assertEquals(ERROR_INCOMPATIBLE_MODE, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
    }

    /**
     * Verify callback triggered from startLocalOnlyHotspot with a tethering disallowed failure.
     */
    @Test
    public void testLocalOnlyHotspotCallbackFullOnTetheringDisallowed() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(ERROR_TETHERING_DISALLOWED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        assertEquals(ERROR_TETHERING_DISALLOWED, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
    }

    /**
     * Verify a SecurityException resulting from an application without necessary permissions will
     * bubble up through the call to start LocalOnlyHotspot and will not trigger other callbacks.
     */
    @Test(expected = SecurityException.class)
    public void testLocalOnlyHotspotCallbackFullOnSecurityException() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        doThrow(new SecurityException()).when(mWifiService).startLocalOnlyHotspot(
                any(ILocalOnlyHotspotCallback.class), anyString(), nullable(String.class),
                eq(null));
        try {
            mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        } catch (SecurityException e) {
            assertEquals(ERROR_NOT_SET, callback.mFailureReason);
            assertFalse(callback.mOnStartedCalled);
            assertFalse(callback.mOnStoppedCalled);
            assertEquals(null, callback.mRes);
            throw e;
        }

    }

    /**
     * Verify the handler passed to startLocalOnlyHotspot is correctly used for callbacks when
     * SoftApMode fails due to a underlying error.
     */
    @Test
    public void testLocalOnlyHotspotCallbackFullOnNoChannelError() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        //assertEquals(ERROR_NO_CHANNEL, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
    }

    /**
     * Verify that the call to cancel a LOHS request does call stopLOHS.
     */
    @Test
    public void testCancelLocalOnlyHotspotRequestCallsStopOnWifiService() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mWifiManager.cancelLocalOnlyHotspotRequest();
        verify(mWifiService).stopLocalOnlyHotspot();
    }

    /**
     * Verify that we do not crash if cancelLocalOnlyHotspotRequest is called without an existing
     * callback stored.
     */
    @Test
    public void testCancelLocalOnlyHotspotReturnsWithoutExistingRequest() {
        mWifiManager.cancelLocalOnlyHotspotRequest();
    }

    /**
     * Verify that the callback is not triggered if the LOHS request was already cancelled.
     */
    @Test
    public void testCallbackAfterLocalOnlyHotspotWasCancelled() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mWifiManager.cancelLocalOnlyHotspotRequest();
        verify(mWifiService).stopLocalOnlyHotspot();
        mLooper.dispatchAll();
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
    }

    /**
     * Verify that calling cancel LOHS request does not crash if an error callback was already
     * handled.
     */
    @Test
    public void testCancelAfterLocalOnlyHotspotCallbackTriggered() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class), anyString(),
                nullable(String.class), eq(null))).thenReturn(ERROR_INCOMPATIBLE_MODE);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        assertEquals(ERROR_INCOMPATIBLE_MODE, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
        mWifiManager.cancelLocalOnlyHotspotRequest();
        verify(mWifiService, never()).stopLocalOnlyHotspot();
    }

    @Test
    public void testStartLocalOnlyHotspotForwardsCustomConfig() throws Exception {
        SoftApConfiguration customConfig = new SoftApConfiguration.Builder()
                .setSsid("customSsid")
                .build();
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        mWifiManager.startLocalOnlyHotspot(customConfig, mExecutor, callback);
        verify(mWifiService).startLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class),
                anyString(), nullable(String.class), eq(customConfig));
    }

    /**
     * Verify the watchLocalOnlyHotspot call goes to WifiServiceImpl.
     */
    @Test
    public void testWatchLocalOnlyHotspot() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();

        mWifiManager.watchLocalOnlyHotspot(observer, mHandler);
        verify(mWifiService).startWatchLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class));
    }

    /**
     * Verify a SecurityException is thrown for callers without proper permissions for
     * startWatchLocalOnlyHotspot.
     */
    @Test(expected = SecurityException.class)
    public void testStartWatchLocalOnlyHotspotThrowsSecurityException() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        doThrow(new SecurityException()).when(mWifiService)
                .startWatchLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class));
        mWifiManager.watchLocalOnlyHotspot(observer, mHandler);
    }

    /**
     * Verify an IllegalStateException is thrown for callers that already have a pending request for
     * watchLocalOnlyHotspot.
     */
    @Test(expected = IllegalStateException.class)
    public void testStartWatchLocalOnlyHotspotThrowsIllegalStateException() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        doThrow(new IllegalStateException()).when(mWifiService)
                .startWatchLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class));
        mWifiManager.watchLocalOnlyHotspot(observer, mHandler);
    }

    /**
     * Verify an IllegalArgumentException is thrown if callback is not provided.
     */
    @Test
    public void registerSoftApCallbackThrowsIllegalArgumentExceptionOnNullArgumentForCallback() {
        try {
            mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify an IllegalArgumentException is thrown if executor is null.
     */
    @Test
    public void registerSoftApCallbackThrowsIllegalArgumentExceptionOnNullArgumentForExecutor() {
        try {
            mWifiManager.registerSoftApCallback(null, mSoftApCallback);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify an IllegalArgumentException is thrown if callback is not provided.
     */
    @Test
    public void unregisterSoftApCallbackThrowsIllegalArgumentExceptionOnNullArgumentForCallback() {
        try {
            mWifiManager.unregisterSoftApCallback(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify the call to registerSoftApCallback goes to WifiServiceImpl.
     */
    @Test
    public void registerSoftApCallbackCallGoesToWifiServiceImpl() throws Exception {
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class),
                any(ISoftApCallback.Stub.class), anyInt());
    }

    /**
     * Verify the call to unregisterSoftApCallback goes to WifiServiceImpl.
     */
    @Test
    public void unregisterSoftApCallbackCallGoesToWifiServiceImpl() throws Exception {
        ArgumentCaptor<Integer> callbackIdentifier = ArgumentCaptor.forClass(Integer.class);
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class),
                any(ISoftApCallback.Stub.class), callbackIdentifier.capture());

        mWifiManager.unregisterSoftApCallback(mSoftApCallback);
        verify(mWifiService).unregisterSoftApCallback(eq((int) callbackIdentifier.getValue()));
    }

    /*
     * Verify client-provided callback is being called through callback proxy
     */
    @Test
    public void softApCallbackProxyCallsOnStateChanged() throws Exception {
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        callbackCaptor.getValue().onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mLooper.dispatchAll();
        verify(mSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
    }

    /*
     * Verify client-provided callback is being called through callback proxy
     */
    @Test
    public void softApCallbackProxyCallsOnConnectedClientsChanged() throws Exception {
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        final List<WifiClient> testClients = new ArrayList();
        callbackCaptor.getValue().onConnectedClientsChanged(testClients);
        mLooper.dispatchAll();
        verify(mSoftApCallback).onConnectedClientsChanged(testClients);
    }


    /*
     * Verify client-provided callback is being called through callback proxy
     */
    @Test
    public void softApCallbackProxyCallsOnSoftApInfoChanged() throws Exception {
        SoftApInfo testSoftApInfo = new SoftApInfo();
        testSoftApInfo.setFrequency(TEST_AP_FREQUENCY);
        testSoftApInfo.setBandwidth(TEST_AP_BANDWIDTH);
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        callbackCaptor.getValue().onInfoChanged(testSoftApInfo);
        mLooper.dispatchAll();
        verify(mSoftApCallback).onInfoChanged(testSoftApInfo);
    }


    /*
     * Verify client-provided callback is being called through callback proxy
     */
    @Test
    public void softApCallbackProxyCallsOnCapabilityChanged() throws Exception {
        SoftApCapability testSoftApCapability = new SoftApCapability(0);
        testSoftApCapability.setMaxSupportedClients(10);
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        callbackCaptor.getValue().onCapabilityChanged(testSoftApCapability);
        mLooper.dispatchAll();
        verify(mSoftApCallback).onCapabilityChanged(testSoftApCapability);
    }

    /*
     * Verify client-provided callback is being called through callback proxy
     */
    @Test
    public void softApCallbackProxyCallsOnBlockedClientConnecting() throws Exception {
        WifiClient testWifiClient = new WifiClient(MacAddress.fromString("22:33:44:55:66:77"));
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        callbackCaptor.getValue().onBlockedClientConnecting(testWifiClient,
                WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
        mLooper.dispatchAll();
        verify(mSoftApCallback).onBlockedClientConnecting(testWifiClient,
                WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
    }

    /*
     * Verify client-provided callback is being called through callback proxy on multiple events
     */
    @Test
    public void softApCallbackProxyCallsOnMultipleUpdates() throws Exception {
        SoftApInfo testSoftApInfo = new SoftApInfo();
        testSoftApInfo.setFrequency(TEST_AP_FREQUENCY);
        testSoftApInfo.setBandwidth(TEST_AP_BANDWIDTH);
        SoftApCapability testSoftApCapability = new SoftApCapability(0);
        testSoftApCapability.setMaxSupportedClients(10);
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        final List<WifiClient> testClients = new ArrayList();
        callbackCaptor.getValue().onStateChanged(WIFI_AP_STATE_ENABLING, 0);
        callbackCaptor.getValue().onConnectedClientsChanged(testClients);
        callbackCaptor.getValue().onInfoChanged(testSoftApInfo);
        callbackCaptor.getValue().onStateChanged(WIFI_AP_STATE_FAILED, SAP_START_FAILURE_GENERAL);
        callbackCaptor.getValue().onCapabilityChanged(testSoftApCapability);


        mLooper.dispatchAll();
        verify(mSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLING, 0);
        verify(mSoftApCallback).onConnectedClientsChanged(testClients);
        verify(mSoftApCallback).onInfoChanged(testSoftApInfo);
        verify(mSoftApCallback).onStateChanged(WIFI_AP_STATE_FAILED, SAP_START_FAILURE_GENERAL);
        verify(mSoftApCallback).onCapabilityChanged(testSoftApCapability);
    }

    /*
     * Verify client-provided callback is being called on the correct thread
     */
    @Test
    public void softApCallbackIsCalledOnCorrectThread() throws Exception {
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        TestLooper altLooper = new TestLooper();
        Handler altHandler = new Handler(altLooper.getLooper());
        mWifiManager.registerSoftApCallback(new HandlerExecutor(altHandler), mSoftApCallback);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        callbackCaptor.getValue().onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        altLooper.dispatchAll();
        verify(mSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
    }

    /**
     * Verify that the handler provided by the caller is used for registering soft AP callback.
     */
    @Test
    public void testCorrectLooperIsUsedForSoftApCallbackHandler() throws Exception {
        mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        mLooper.dispatchAll();
        verify(mWifiService).registerSoftApCallback(any(IBinder.class),
                any(ISoftApCallback.Stub.class), anyInt());
        verify(mContext, never()).getMainLooper();
        verify(mContext, never()).getMainExecutor();
    }

    /**
     * Verify that the handler provided by the caller is used for the observer.
     */
    @Test
    public void testCorrectLooperIsUsedForObserverHandler() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        mWifiManager.watchLocalOnlyHotspot(observer, mHandler);
        mLooper.dispatchAll();
        assertTrue(observer.mOnRegistered);
        verify(mContext, never()).getMainLooper();
        verify(mContext, never()).getMainExecutor();
    }

    /**
     * Verify that the main looper's thread is used if a handler is not provided by the requesting
     * application.
     */
    @Test
    public void testMainLooperIsUsedWhenHandlerNotProvidedForObserver() throws Exception {
        // record thread from looper.getThread and check ids.
        TestLooper altLooper = new TestLooper();
        when(mContext.getMainExecutor()).thenReturn(altLooper.getNewExecutor());
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        mWifiManager.watchLocalOnlyHotspot(observer, null);
        altLooper.dispatchAll();
        assertTrue(observer.mOnRegistered);
        assertEquals(altLooper.getLooper().getThread().getId(), observer.mCallingThreadId);
        verify(mContext).getMainExecutor();
    }

    /**
     * Verify the LOHS onRegistered observer callback is triggered when WifiManager receives a
     * HOTSPOT_OBSERVER_REGISTERED message from WifiServiceImpl.
     */
    @Test
    public void testOnRegisteredIsCalledWithSubscription() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        assertFalse(observer.mOnRegistered);
        assertEquals(null, observer.mSub);
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        verify(mWifiService).startWatchLocalOnlyHotspot(any(ILocalOnlyHotspotCallback.class));
        // now trigger the callback
        observerLooper.dispatchAll();
        mLooper.dispatchAll();
        assertTrue(observer.mOnRegistered);
        assertNotNull(observer.mSub);
    }

    /**
     * Verify the LOHS onStarted observer callback is triggered when WifiManager receives a
     * HOTSPOT_STARTED message from WifiServiceImpl.
     */
    @Test
    public void testObserverOnStartedIsCalledWithWifiConfig() throws Exception {
        SoftApConfiguration softApConfig = generatorTestSoftApConfig();
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        ArgumentCaptor<ILocalOnlyHotspotCallback> internalCallback =
                ArgumentCaptor.forClass(ILocalOnlyHotspotCallback.class);
        verify(mWifiService).startWatchLocalOnlyHotspot(internalCallback.capture());
        observerLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(observer.mOnStartedCalled);
        // now trigger the callback
        internalCallback.getValue().onHotspotStarted(softApConfig);
        mLooper.dispatchAll();
        observerLooper.dispatchAll();
        assertTrue(observer.mOnStartedCalled);
        assertEquals(softApConfig, observer.mConfig);
    }

    /**
     * Verify the LOHS onStarted observer callback is triggered not when WifiManager receives a
     * HOTSPOT_STARTED message from WifiServiceImpl with a null config.
     */
    @Test
    public void testObserverOnStartedNotCalledWithNullConfig() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        ArgumentCaptor<ILocalOnlyHotspotCallback> internalCallback =
                ArgumentCaptor.forClass(ILocalOnlyHotspotCallback.class);
        verify(mWifiService).startWatchLocalOnlyHotspot(internalCallback.capture());
        observerLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(observer.mOnStartedCalled);
        // now trigger the callback
        internalCallback.getValue().onHotspotStarted(null);
        mLooper.dispatchAll();
        observerLooper.dispatchAll();
        assertFalse(observer.mOnStartedCalled);
        assertEquals(null, observer.mConfig);
    }


    /**
     * Verify the LOHS onStopped observer callback is triggered when WifiManager receives a
     * HOTSPOT_STOPPED message from WifiServiceImpl.
     */
    @Test
    public void testObserverOnStoppedIsCalled() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        ArgumentCaptor<ILocalOnlyHotspotCallback> internalCallback =
                ArgumentCaptor.forClass(ILocalOnlyHotspotCallback.class);
        verify(mWifiService).startWatchLocalOnlyHotspot(internalCallback.capture());
        observerLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(observer.mOnStoppedCalled);
        // now trigger the callback
        internalCallback.getValue().onHotspotStopped();
        mLooper.dispatchAll();
        observerLooper.dispatchAll();
        assertTrue(observer.mOnStoppedCalled);
    }

    /**
     * Verify WifiServiceImpl is not called if there is not a registered LOHS observer callback.
     */
    @Test
    public void testUnregisterWifiServiceImplNotCalledWithoutRegisteredObserver() throws Exception {
        mWifiManager.unregisterLocalOnlyHotspotObserver();
        verifyZeroInteractions(mWifiService);
    }

    /**
     * Verify WifiServiceImpl is called when there is a registered LOHS observer callback.
     */
    @Test
    public void testUnregisterWifiServiceImplCalledWithRegisteredObserver() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        mWifiManager.unregisterLocalOnlyHotspotObserver();
        verify(mWifiService).stopWatchLocalOnlyHotspot();
    }

    /**
     * Test that calls to get the current WPS config token return null and do not have any
     * interactions with WifiServiceImpl.
     */
    @Test
    public void testGetCurrentNetworkWpsNfcConfigurationTokenReturnsNull() {
        assertNull(mWifiManager.getCurrentNetworkWpsNfcConfigurationToken());
        verifyNoMoreInteractions(mWifiService);
    }


    class WpsCallbackTester extends WpsCallback {
        public boolean mStarted = false;
        public boolean mSucceeded = false;
        public boolean mFailed = false;
        public int mFailureCode = -1;

        @Override
        public void onStarted(String pin) {
            mStarted = true;
        }

        @Override
        public void onSucceeded() {
            mSucceeded = true;
        }

        @Override
        public void onFailed(int reason) {
            mFailed = true;
            mFailureCode = reason;
        }

    }

    /**
     * Verify that a call to start WPS immediately returns a failure.
     */
    @Test
    public void testStartWpsImmediatelyFailsWithCallback() {
        WpsCallbackTester wpsCallback = new WpsCallbackTester();
        mWifiManager.startWps(null, wpsCallback);
        assertTrue(wpsCallback.mFailed);
        assertEquals(ERROR, wpsCallback.mFailureCode);
        assertFalse(wpsCallback.mStarted);
        assertFalse(wpsCallback.mSucceeded);
        verifyNoMoreInteractions(mWifiService);
    }

    /**
     * Verify that a call to start WPS does not go to WifiServiceImpl if we do not have a callback.
     */
    @Test
    public void testStartWpsDoesNotCallWifiServiceImpl() {
        mWifiManager.startWps(null, null);
        verifyNoMoreInteractions(mWifiService);
    }

    /**
     * Verify that a call to cancel WPS immediately returns a failure.
     */
    @Test
    public void testCancelWpsImmediatelyFailsWithCallback() {
        WpsCallbackTester wpsCallback = new WpsCallbackTester();
        mWifiManager.cancelWps(wpsCallback);
        assertTrue(wpsCallback.mFailed);
        assertEquals(ERROR, wpsCallback.mFailureCode);
        assertFalse(wpsCallback.mStarted);
        assertFalse(wpsCallback.mSucceeded);
        verifyNoMoreInteractions(mWifiService);
    }

    /**
     * Verify that a call to cancel WPS does not go to WifiServiceImpl if we do not have a callback.
     */
    @Test
    public void testCancelWpsDoesNotCallWifiServiceImpl() {
        mWifiManager.cancelWps(null);
        verifyNoMoreInteractions(mWifiService);
    }

    /**
     * Verify that a successful call properly returns true.
     */
    @Test
    public void testSetWifiApConfigurationSuccessReturnsTrue() throws Exception {
        WifiConfiguration apConfig = new WifiConfiguration();

        when(mWifiService.setWifiApConfiguration(eq(apConfig), eq(TEST_PACKAGE_NAME)))
                .thenReturn(true);
        assertTrue(mWifiManager.setWifiApConfiguration(apConfig));
    }

    /**
     * Verify that a failed call properly returns false.
     */
    @Test
    public void testSetWifiApConfigurationFailureReturnsFalse() throws Exception {
        WifiConfiguration apConfig = new WifiConfiguration();

        when(mWifiService.setWifiApConfiguration(eq(apConfig), eq(TEST_PACKAGE_NAME)))
                .thenReturn(false);
        assertFalse(mWifiManager.setWifiApConfiguration(apConfig));
    }

    /**
     * Verify Exceptions are rethrown when underlying calls to WifiService throw exceptions.
     */
    @Test
    public void testSetWifiApConfigurationRethrowsException() throws Exception {
        doThrow(new SecurityException()).when(mWifiService).setWifiApConfiguration(any(), any());

        try {
            mWifiManager.setWifiApConfiguration(new WifiConfiguration());
            fail("setWifiApConfiguration should rethrow Exceptions from WifiService");
        } catch (SecurityException e) { }
    }

    /**
     * Verify that a successful call properly returns true.
     */
    @Test
    public void testSetSoftApConfigurationSuccessReturnsTrue() throws Exception {
        SoftApConfiguration apConfig = generatorTestSoftApConfig();

        when(mWifiService.setSoftApConfiguration(eq(apConfig), eq(TEST_PACKAGE_NAME)))
                .thenReturn(true);
        assertTrue(mWifiManager.setSoftApConfiguration(apConfig));
    }

    /**
     * Verify that a failed call properly returns false.
     */
    @Test
    public void testSetSoftApConfigurationFailureReturnsFalse() throws Exception {
        SoftApConfiguration apConfig = generatorTestSoftApConfig();

        when(mWifiService.setSoftApConfiguration(eq(apConfig), eq(TEST_PACKAGE_NAME)))
                .thenReturn(false);
        assertFalse(mWifiManager.setSoftApConfiguration(apConfig));
    }

    /**
     * Verify Exceptions are rethrown when underlying calls to WifiService throw exceptions.
     */
    @Test
    public void testSetSoftApConfigurationRethrowsException() throws Exception {
        doThrow(new SecurityException()).when(mWifiService).setSoftApConfiguration(any(), any());

        try {
            mWifiManager.setSoftApConfiguration(generatorTestSoftApConfig());
            fail("setWifiApConfiguration should rethrow Exceptions from WifiService");
        } catch (SecurityException e) { }
    }

    /**
     * Check the call to startScan calls WifiService.
     */
    @Test
    public void testStartScan() throws Exception {
        when(mWifiService.startScan(eq(TEST_PACKAGE_NAME), nullable(String.class))).thenReturn(
                true);
        assertTrue(mWifiManager.startScan());

        when(mWifiService.startScan(eq(TEST_PACKAGE_NAME), nullable(String.class))).thenReturn(
                false);
        assertFalse(mWifiManager.startScan());
    }

    /**
     * Verify main looper is used when handler is not provided.
     */
    @Test
    public void registerTrafficStateCallbackUsesMainLooperOnNullArgumentForHandler()
            throws Exception {
        ArgumentCaptor<ITrafficStateCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ITrafficStateCallback.Stub.class);
        mWifiManager.registerTrafficStateCallback(
                new HandlerExecutor(new Handler(mLooper.getLooper())), mTrafficStateCallback);
        verify(mWifiService).registerTrafficStateCallback(
                any(IBinder.class), callbackCaptor.capture(), anyInt());

        assertEquals(0, mLooper.dispatchAll());
        callbackCaptor.getValue().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT);
        assertEquals(1, mLooper.dispatchAll());
        verify(mTrafficStateCallback).onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT);
    }

    /**
     * Verify the call to unregisterTrafficStateCallback goes to WifiServiceImpl.
     */
    @Test
    public void unregisterTrafficStateCallbackCallGoesToWifiServiceImpl() throws Exception {
        ArgumentCaptor<Integer> callbackIdentifier = ArgumentCaptor.forClass(Integer.class);
        mWifiManager.registerTrafficStateCallback(new HandlerExecutor(mHandler),
                mTrafficStateCallback);
        verify(mWifiService).registerTrafficStateCallback(any(IBinder.class),
                any(ITrafficStateCallback.Stub.class), callbackIdentifier.capture());

        mWifiManager.unregisterTrafficStateCallback(mTrafficStateCallback);
        verify(mWifiService).unregisterTrafficStateCallback(
                eq((int) callbackIdentifier.getValue()));
    }

    /*
     * Verify client-provided callback is being called through callback proxy on multiple events
     */
    @Test
    public void trafficStateCallbackProxyCallsOnMultipleUpdates() throws Exception {
        ArgumentCaptor<ITrafficStateCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ITrafficStateCallback.Stub.class);
        mWifiManager.registerTrafficStateCallback(new HandlerExecutor(mHandler),
                mTrafficStateCallback);
        verify(mWifiService).registerTrafficStateCallback(
                any(IBinder.class), callbackCaptor.capture(), anyInt());

        InOrder inOrder = inOrder(mTrafficStateCallback);

        callbackCaptor.getValue().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_IN);
        callbackCaptor.getValue().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT);
        callbackCaptor.getValue().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_OUT);

        mLooper.dispatchAll();
        inOrder.verify(mTrafficStateCallback).onStateChanged(
                TrafficStateCallback.DATA_ACTIVITY_IN);
        inOrder.verify(mTrafficStateCallback).onStateChanged(
                TrafficStateCallback.DATA_ACTIVITY_INOUT);
        inOrder.verify(mTrafficStateCallback).onStateChanged(
                TrafficStateCallback.DATA_ACTIVITY_OUT);
    }

    /**
     * Verify client-provided callback is being called on the correct thread
     */
    @Test
    public void trafficStateCallbackIsCalledOnCorrectThread() throws Exception {
        ArgumentCaptor<ITrafficStateCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ITrafficStateCallback.Stub.class);
        TestLooper altLooper = new TestLooper();
        Handler altHandler = new Handler(altLooper.getLooper());
        mWifiManager.registerTrafficStateCallback(new HandlerExecutor(altHandler),
                mTrafficStateCallback);
        verify(mContext, never()).getMainLooper();
        verify(mContext, never()).getMainExecutor();
        verify(mWifiService).registerTrafficStateCallback(
                any(IBinder.class), callbackCaptor.capture(), anyInt());

        assertEquals(0, altLooper.dispatchAll());
        callbackCaptor.getValue().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT);
        assertEquals(1, altLooper.dispatchAll());
        verify(mTrafficStateCallback).onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT);
    }

    /**
     * Verify the call to registerNetworkRequestMatchCallback goes to WifiServiceImpl.
     */
    @Test
    public void registerNetworkRequestMatchCallbackCallGoesToWifiServiceImpl()
            throws Exception {
        ArgumentCaptor<INetworkRequestMatchCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(INetworkRequestMatchCallback.Stub.class);
        mWifiManager.registerNetworkRequestMatchCallback(
                new HandlerExecutor(new Handler(mLooper.getLooper())),
                mNetworkRequestMatchCallback);
        verify(mWifiService).registerNetworkRequestMatchCallback(
                any(IBinder.class), callbackCaptor.capture(), anyInt());

        INetworkRequestUserSelectionCallback iUserSelectionCallback =
                mock(INetworkRequestUserSelectionCallback.class);

        assertEquals(0, mLooper.dispatchAll());

        callbackCaptor.getValue().onAbort();
        assertEquals(1, mLooper.dispatchAll());
        verify(mNetworkRequestMatchCallback).onAbort();

        callbackCaptor.getValue().onMatch(new ArrayList<ScanResult>());
        assertEquals(1, mLooper.dispatchAll());
        verify(mNetworkRequestMatchCallback).onMatch(anyList());

        callbackCaptor.getValue().onUserSelectionConnectSuccess(new WifiConfiguration());
        assertEquals(1, mLooper.dispatchAll());
        verify(mNetworkRequestMatchCallback).onUserSelectionConnectSuccess(
                any(WifiConfiguration.class));

        callbackCaptor.getValue().onUserSelectionConnectFailure(new WifiConfiguration());
        assertEquals(1, mLooper.dispatchAll());
        verify(mNetworkRequestMatchCallback).onUserSelectionConnectFailure(
                any(WifiConfiguration.class));
    }

    /**
     * Verify the call to unregisterNetworkRequestMatchCallback goes to WifiServiceImpl.
     */
    @Test
    public void unregisterNetworkRequestMatchCallbackCallGoesToWifiServiceImpl() throws Exception {
        ArgumentCaptor<Integer> callbackIdentifier = ArgumentCaptor.forClass(Integer.class);
        mWifiManager.registerNetworkRequestMatchCallback(new HandlerExecutor(mHandler),
                mNetworkRequestMatchCallback);
        verify(mWifiService).registerNetworkRequestMatchCallback(
                any(IBinder.class), any(INetworkRequestMatchCallback.class),
                callbackIdentifier.capture());

        mWifiManager.unregisterNetworkRequestMatchCallback(mNetworkRequestMatchCallback);
        verify(mWifiService).unregisterNetworkRequestMatchCallback(
                eq((int) callbackIdentifier.getValue()));
    }

    /**
     * Verify the call to NetworkRequestUserSelectionCallback goes to
     * WifiServiceImpl.
     */
    @Test
    public void networkRequestUserSelectionCallbackCallGoesToWifiServiceImpl()
            throws Exception {
        ArgumentCaptor<INetworkRequestMatchCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(INetworkRequestMatchCallback.Stub.class);
        mWifiManager.registerNetworkRequestMatchCallback(
                new HandlerExecutor(new Handler(mLooper.getLooper())),
                mNetworkRequestMatchCallback);
        verify(mWifiService).registerNetworkRequestMatchCallback(
                any(IBinder.class), callbackCaptor.capture(), anyInt());

        INetworkRequestUserSelectionCallback iUserSelectionCallback =
                mock(INetworkRequestUserSelectionCallback.class);
        ArgumentCaptor<NetworkRequestUserSelectionCallback> userSelectionCallbackCaptor =
                ArgumentCaptor.forClass(NetworkRequestUserSelectionCallback.class);
        callbackCaptor.getValue().onUserSelectionCallbackRegistration(
                iUserSelectionCallback);
        assertEquals(1, mLooper.dispatchAll());
        verify(mNetworkRequestMatchCallback).onUserSelectionCallbackRegistration(
                userSelectionCallbackCaptor.capture());

        WifiConfiguration selected = new WifiConfiguration();
        userSelectionCallbackCaptor.getValue().select(selected);
        verify(iUserSelectionCallback).select(selected);

        userSelectionCallbackCaptor.getValue().reject();
        verify(iUserSelectionCallback).reject();
    }

    /**
     * Check the call to getAllMatchingWifiConfigs calls getAllMatchingFqdnsForScanResults and
     * getWifiConfigsForPasspointProfiles of WifiService in order.
     */
    @Test
    public void testGetAllMatchingWifiConfigs() throws Exception {
        Map<String, List<ScanResult>> passpointProfiles = new HashMap<>();
        passpointProfiles.put("www.test.com_987a69bca26", new ArrayList<>());
        when(mWifiService.getAllMatchingPasspointProfilesForScanResults(
                any(List.class))).thenReturn(passpointProfiles);
        InOrder inOrder = inOrder(mWifiService);

        mWifiManager.getAllMatchingWifiConfigs(new ArrayList<>());

        inOrder.verify(mWifiService).getAllMatchingPasspointProfilesForScanResults(any(List.class));
        inOrder.verify(mWifiService).getWifiConfigsForPasspointProfiles(any(List.class));
    }

    /**
     * Check the call to getMatchingOsuProviders calls getMatchingOsuProviders of WifiService
     * with the provided a list of ScanResult.
     */
    @Test
    public void testGetMatchingOsuProviders() throws Exception {
        mWifiManager.getMatchingOsuProviders(new ArrayList<>());

        verify(mWifiService).getMatchingOsuProviders(any(List.class));
    }

    /**
     * Verify calls to {@link WifiManager#addNetworkSuggestions(List)},
     * {@link WifiManager#getNetworkSuggestions()} and
     * {@link WifiManager#removeNetworkSuggestions(List)}.
     */
    @Test
    public void addGetRemoveNetworkSuggestions() throws Exception {
        List<WifiNetworkSuggestion> testList = new ArrayList<>();
        when(mWifiService.addNetworkSuggestions(any(List.class), anyString(),
                nullable(String.class))).thenReturn(STATUS_NETWORK_SUGGESTIONS_SUCCESS);
        when(mWifiService.removeNetworkSuggestions(any(List.class), anyString())).thenReturn(
                STATUS_NETWORK_SUGGESTIONS_SUCCESS);
        when(mWifiService.getNetworkSuggestions(anyString()))
                .thenReturn(testList);

        assertEquals(STATUS_NETWORK_SUGGESTIONS_SUCCESS,
                mWifiManager.addNetworkSuggestions(testList));
        verify(mWifiService).addNetworkSuggestions(anyList(), eq(TEST_PACKAGE_NAME),
                nullable(String.class));

        assertEquals(testList, mWifiManager.getNetworkSuggestions());
        verify(mWifiService).getNetworkSuggestions(eq(TEST_PACKAGE_NAME));

        assertEquals(STATUS_NETWORK_SUGGESTIONS_SUCCESS,
                mWifiManager.removeNetworkSuggestions(new ArrayList<>()));
        verify(mWifiService).removeNetworkSuggestions(anyList(), eq(TEST_PACKAGE_NAME));
    }

    /**
     * Verify call to {@link WifiManager#getMaxNumberOfNetworkSuggestionsPerApp()}.
     */
    @Test
    public void getMaxNumberOfNetworkSuggestionsPerApp() {
        when(mContext.getSystemServiceName(ActivityManager.class))
                .thenReturn(Context.ACTIVITY_SERVICE);
        when(mContext.getSystemService(Context.ACTIVITY_SERVICE))
                .thenReturn(mActivityManager);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        assertEquals(256, mWifiManager.getMaxNumberOfNetworkSuggestionsPerApp());

        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        assertEquals(1024, mWifiManager.getMaxNumberOfNetworkSuggestionsPerApp());
    }

    /**
     * Verify getting the factory MAC address.
     */
    @Test
    public void testGetFactoryMacAddress() throws Exception {
        when(mWifiService.getFactoryMacAddresses()).thenReturn(TEST_MAC_ADDRESSES);
        assertArrayEquals(TEST_MAC_ADDRESSES, mWifiManager.getFactoryMacAddresses());
        verify(mWifiService).getFactoryMacAddresses();
    }

    /**
     * Verify the call to addOnWifiUsabilityStatsListener goes to WifiServiceImpl.
     */
    @Test
    public void addOnWifiUsabilityStatsListeneroesToWifiServiceImpl() throws Exception {
        mExecutor = new SynchronousExecutor();
        mWifiManager.addOnWifiUsabilityStatsListener(mExecutor, mOnWifiUsabilityStatsListener);
        verify(mWifiService).addOnWifiUsabilityStatsListener(any(IBinder.class),
                any(IOnWifiUsabilityStatsListener.Stub.class), anyInt());
    }

    /**
     * Verify the call to removeOnWifiUsabilityStatsListener goes to WifiServiceImpl.
     */
    @Test
    public void removeOnWifiUsabilityListenerGoesToWifiServiceImpl() throws Exception {
        ArgumentCaptor<Integer> listenerIdentifier = ArgumentCaptor.forClass(Integer.class);
        mExecutor = new SynchronousExecutor();
        mWifiManager.addOnWifiUsabilityStatsListener(mExecutor, mOnWifiUsabilityStatsListener);
        verify(mWifiService).addOnWifiUsabilityStatsListener(any(IBinder.class),
                any(IOnWifiUsabilityStatsListener.Stub.class), listenerIdentifier.capture());

        mWifiManager.removeOnWifiUsabilityStatsListener(mOnWifiUsabilityStatsListener);
        verify(mWifiService).removeOnWifiUsabilityStatsListener(
                eq((int) listenerIdentifier.getValue()));
    }

    /**
     * Test behavior of isEnhancedOpenSupported
     */
    @Test
    public void testIsEnhancedOpenSupported() throws Exception {
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(WIFI_FEATURE_OWE));
        assertTrue(mWifiManager.isEnhancedOpenSupported());
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(~WIFI_FEATURE_OWE));
        assertFalse(mWifiManager.isEnhancedOpenSupported());
    }

    /**
     * Test behavior of isWpa3SaeSupported
     */
    @Test
    public void testIsWpa3SaeSupported() throws Exception {
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(WIFI_FEATURE_WPA3_SAE));
        assertTrue(mWifiManager.isWpa3SaeSupported());
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(~WIFI_FEATURE_WPA3_SAE));
        assertFalse(mWifiManager.isWpa3SaeSupported());
    }

    /**
     * Test behavior of isWpa3SuiteBSupported
     */
    @Test
    public void testIsWpa3SuiteBSupported() throws Exception {
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(WIFI_FEATURE_WPA3_SUITE_B));
        assertTrue(mWifiManager.isWpa3SuiteBSupported());
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(~WIFI_FEATURE_WPA3_SUITE_B));
        assertFalse(mWifiManager.isWpa3SuiteBSupported());
    }

    /**
     * Test behavior of isEasyConnectSupported
     */
    @Test
    public void testIsEasyConnectSupported() throws Exception {
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(WIFI_FEATURE_DPP));
        assertTrue(mWifiManager.isEasyConnectSupported());
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(~WIFI_FEATURE_DPP));
        assertFalse(mWifiManager.isEasyConnectSupported());
    }

    /**
     * Test behavior of {@link WifiManager#addNetwork(WifiConfiguration)}
     */
    @Test
    public void testAddNetwork() throws Exception {
        WifiConfiguration configuration = new WifiConfiguration();
        when(mWifiService.addOrUpdateNetwork(any(), anyString()))
                .thenReturn(TEST_NETWORK_ID);

        assertEquals(mWifiManager.addNetwork(configuration), TEST_NETWORK_ID);
        verify(mWifiService).addOrUpdateNetwork(configuration, mContext.getOpPackageName());

        // send a null config
        assertEquals(mWifiManager.addNetwork(null), -1);
    }

    /**
     * Test behavior of {@link WifiManager#addNetwork(WifiConfiguration)}
     */
    @Test
    public void testUpdateNetwork() throws Exception {
        WifiConfiguration configuration = new WifiConfiguration();
        when(mWifiService.addOrUpdateNetwork(any(), anyString()))
                .thenReturn(TEST_NETWORK_ID);

        configuration.networkId = TEST_NETWORK_ID;
        assertEquals(mWifiManager.updateNetwork(configuration), TEST_NETWORK_ID);
        verify(mWifiService).addOrUpdateNetwork(configuration, mContext.getOpPackageName());

        // config with invalid network ID
        configuration.networkId = -1;
        assertEquals(mWifiManager.updateNetwork(configuration), -1);

        // send a null config
        assertEquals(mWifiManager.updateNetwork(null), -1);
    }

    /**
     * Test behavior of {@link WifiManager#enableNetwork(int, boolean)}
     */
    @Test
    public void testEnableNetwork() throws Exception {
        when(mWifiService.enableNetwork(anyInt(), anyBoolean(), anyString()))
                .thenReturn(true);
        assertTrue(mWifiManager.enableNetwork(TEST_NETWORK_ID, true));
        verify(mWifiService).enableNetwork(TEST_NETWORK_ID, true, mContext.getOpPackageName());
    }

    /**
     * Test behavior of {@link WifiManager#disableNetwork(int)}
     */
    @Test
    public void testDisableNetwork() throws Exception {
        when(mWifiService.disableNetwork(anyInt(), anyString()))
                .thenReturn(true);
        assertTrue(mWifiManager.disableNetwork(TEST_NETWORK_ID));
        verify(mWifiService).disableNetwork(TEST_NETWORK_ID, mContext.getOpPackageName());
    }

    /**
     * Test behavior of {@link WifiManager#allowAutojoin(int, boolean)}
     * @throws Exception
     */
    @Test
    public void testAllowAutojoin() throws Exception {
        mWifiManager.allowAutojoin(1, true);
        verify(mWifiService).allowAutojoin(1, true);
    }

    /**
     * Test behavior of {@link WifiManager#allowAutojoinPasspoint(String, boolean)}
     * @throws Exception
     */
    @Test
    public void testAllowAutojoinPasspoint() throws Exception {
        final String fqdn = "FullyQualifiedDomainName";
        mWifiManager.allowAutojoinPasspoint(fqdn, true);
        verify(mWifiService).allowAutojoinPasspoint(fqdn, true);
    }

    /**
     * Test behavior of
     * {@link WifiManager#setMacRandomizationSettingPasspointEnabled(String, boolean)}
     */
    @Test
    public void testSetMacRandomizationSettingPasspointEnabled() throws Exception {
        final String fqdn = "FullyQualifiedDomainName";
        mWifiManager.setMacRandomizationSettingPasspointEnabled(fqdn, true);
        verify(mWifiService).setMacRandomizationSettingPasspointEnabled(fqdn, true);
    }

    /**
     * Test behavior of
     * {@link WifiManager#setMacRandomizationSettingPasspointEnabled(String, boolean)}
     */
    @Test
    public void testSetPasspointMeteredOverride() throws Exception {
        final String fqdn = "FullyQualifiedDomainName";
        mWifiManager.setPasspointMeteredOverride(fqdn, METERED_OVERRIDE_METERED);
        verify(mWifiService).setPasspointMeteredOverride(fqdn, METERED_OVERRIDE_METERED);
    }

    /**
     * Test behavior of {@link WifiManager#disconnect()}
     */
    @Test
    public void testDisconnect() throws Exception {
        when(mWifiService.disconnect(anyString())).thenReturn(true);
        assertTrue(mWifiManager.disconnect());
        verify(mWifiService).disconnect(mContext.getOpPackageName());
    }

    /**
     * Test behavior of {@link WifiManager#reconnect()}
     */
    @Test
    public void testReconnect() throws Exception {
        when(mWifiService.reconnect(anyString())).thenReturn(true);
        assertTrue(mWifiManager.reconnect());
        verify(mWifiService).reconnect(mContext.getOpPackageName());
    }

    /**
     * Test behavior of {@link WifiManager#reassociate()}
     */
    @Test
    public void testReassociate() throws Exception {
        when(mWifiService.reassociate(anyString())).thenReturn(true);
        assertTrue(mWifiManager.reassociate());
        verify(mWifiService).reassociate(mContext.getOpPackageName());
    }

    /**
     * Test behavior of {@link WifiManager#getSupportedFeatures()}
     */
    @Test
    public void testGetSupportedFeatures() throws Exception {
        long supportedFeatures =
                WIFI_FEATURE_SCANNER
                        | WIFI_FEATURE_PASSPOINT
                        | WIFI_FEATURE_P2P;
        when(mWifiService.getSupportedFeatures())
                .thenReturn(Long.valueOf(supportedFeatures));

        assertTrue(mWifiManager.isWifiScannerSupported());
        assertTrue(mWifiManager.isPasspointSupported());
        assertTrue(mWifiManager.isP2pSupported());
        assertFalse(mWifiManager.isPortableHotspotSupported());
        assertFalse(mWifiManager.isDeviceToDeviceRttSupported());
        assertFalse(mWifiManager.isDeviceToApRttSupported());
        assertFalse(mWifiManager.isPreferredNetworkOffloadSupported());
        assertFalse(mWifiManager.isAdditionalStaSupported());
        assertFalse(mWifiManager.isTdlsSupported());
        assertFalse(mWifiManager.isOffChannelTdlsSupported());
        assertFalse(mWifiManager.isEnhancedPowerReportingSupported());
    }

    /**
     * Tests that passing a null Executor to {@link WifiManager#getWifiActivityEnergyInfoAsync}
     * throws an exception.
     */
    @Test(expected = NullPointerException.class)
    public void testGetWifiActivityInfoNullExecutor() throws Exception {
        mWifiManager.getWifiActivityEnergyInfoAsync(null, mOnWifiActivityEnergyInfoListener);
    }

    /**
     * Tests that passing a null listener to {@link WifiManager#getWifiActivityEnergyInfoAsync}
     * throws an exception.
     */
    @Test(expected = NullPointerException.class)
    public void testGetWifiActivityInfoNullListener() throws Exception {
        mWifiManager.getWifiActivityEnergyInfoAsync(mExecutor, null);
    }

    /** Tests that the listener runs on the correct Executor. */
    @Test
    public void testGetWifiActivityInfoRunsOnCorrectExecutor() throws Exception {
        mWifiManager.getWifiActivityEnergyInfoAsync(mExecutor, mOnWifiActivityEnergyInfoListener);
        ArgumentCaptor<IOnWifiActivityEnergyInfoListener> listenerCaptor =
                ArgumentCaptor.forClass(IOnWifiActivityEnergyInfoListener.class);
        verify(mWifiService).getWifiActivityEnergyInfoAsync(listenerCaptor.capture());
        IOnWifiActivityEnergyInfoListener listener = listenerCaptor.getValue();
        listener.onWifiActivityEnergyInfo(mWifiActivityEnergyInfo);
        verify(mExecutor).execute(any());

        // ensure that the executor is only triggered once
        listener.onWifiActivityEnergyInfo(mWifiActivityEnergyInfo);
        verify(mExecutor).execute(any());
    }

    /** Tests that the correct listener runs. */
    @Test
    public void testGetWifiActivityInfoRunsCorrectListener() throws Exception {
        int[] flag = {0};
        mWifiManager.getWifiActivityEnergyInfoAsync(
                new SynchronousExecutor(), info -> flag[0]++);
        ArgumentCaptor<IOnWifiActivityEnergyInfoListener> listenerCaptor =
                ArgumentCaptor.forClass(IOnWifiActivityEnergyInfoListener.class);
        verify(mWifiService).getWifiActivityEnergyInfoAsync(listenerCaptor.capture());
        IOnWifiActivityEnergyInfoListener listener = listenerCaptor.getValue();
        listener.onWifiActivityEnergyInfo(mWifiActivityEnergyInfo);
        assertEquals(1, flag[0]);

        // ensure that the listener is only triggered once
        listener.onWifiActivityEnergyInfo(mWifiActivityEnergyInfo);
        assertEquals(1, flag[0]);
    }

    /**
     * Test behavior of {@link WifiManager#getConnectionInfo()}
     */
    @Test
    public void testGetConnectionInfo() throws Exception {
        WifiInfo wifiInfo = new WifiInfo();
        when(mWifiService.getConnectionInfo(anyString(), nullable(String.class))).thenReturn(
                wifiInfo);

        assertEquals(wifiInfo, mWifiManager.getConnectionInfo());
    }

    /**
     * Test behavior of {@link WifiManager#is5GHzBandSupported()}
     */
    @Test
    public void testIs5GHzBandSupported() throws Exception {
        when(mWifiService.is5GHzBandSupported()).thenReturn(true);
        assertTrue(mWifiManager.is5GHzBandSupported());
        verify(mWifiService).is5GHzBandSupported();
    }

    /**
     * Test behavior of {@link WifiManager#is6GHzBandSupported()}
     */
    @Test
    public void testIs6GHzBandSupported() throws Exception {
        when(mWifiService.is6GHzBandSupported()).thenReturn(true);
        assertTrue(mWifiManager.is6GHzBandSupported());
        verify(mWifiService).is6GHzBandSupported();
    }

    /**
     * Test behavior of {@link WifiManager#isWifiStandardSupported()}
     */
    @Test
    public void testIsWifiStandardSupported() throws Exception {
        int standard = ScanResult.WIFI_STANDARD_11AX;
        when(mWifiService.isWifiStandardSupported(standard)).thenReturn(true);
        assertTrue(mWifiManager.isWifiStandardSupported(standard));
        verify(mWifiService).isWifiStandardSupported(standard);
    }

    /**
     * Test behavior of {@link WifiManager#getDhcpInfo()}
     */
    @Test
    public void testGetDhcpInfo() throws Exception {
        DhcpInfo dhcpInfo = new DhcpInfo();

        when(mWifiService.getDhcpInfo()).thenReturn(dhcpInfo);
        assertEquals(dhcpInfo, mWifiManager.getDhcpInfo());
        verify(mWifiService).getDhcpInfo();
    }

    /**
     * Test behavior of {@link WifiManager#setWifiEnabled(boolean)}
     */
    @Test
    public void testSetWifiEnabled() throws Exception {
        when(mWifiService.setWifiEnabled(anyString(), anyBoolean())).thenReturn(true);
        assertTrue(mWifiManager.setWifiEnabled(true));
        verify(mWifiService).setWifiEnabled(mContext.getOpPackageName(), true);
        assertTrue(mWifiManager.setWifiEnabled(false));
        verify(mWifiService).setWifiEnabled(mContext.getOpPackageName(), false);
    }

    /**
     * Test behavior of {@link WifiManager#connect(int, ActionListener)}
     */
    @Test
    public void testConnectWithListener() throws Exception {
        ActionListener externalListener = mock(ActionListener.class);
        mWifiManager.connect(TEST_NETWORK_ID, externalListener);

        ArgumentCaptor<IActionListener> binderListenerCaptor =
                ArgumentCaptor.forClass(IActionListener.class);
        verify(mWifiService).connect(eq(null), eq(TEST_NETWORK_ID), any(Binder.class),
                binderListenerCaptor.capture(), anyInt());
        assertNotNull(binderListenerCaptor.getValue());

        // Trigger on success.
        binderListenerCaptor.getValue().onSuccess();
        mLooper.dispatchAll();
        verify(externalListener).onSuccess();

        // Trigger on failure.
        binderListenerCaptor.getValue().onFailure(BUSY);
        mLooper.dispatchAll();
        verify(externalListener).onFailure(BUSY);
    }

    /**
     * Test behavior of {@link WifiManager#connect(int, ActionListener)}
     */
    @Test
    public void testConnectWithListenerHandleSecurityException() throws Exception {
        doThrow(new SecurityException()).when(mWifiService)
                .connect(eq(null), anyInt(), any(IBinder.class),
                        any(IActionListener.class), anyInt());
        ActionListener externalListener = mock(ActionListener.class);
        mWifiManager.connect(TEST_NETWORK_ID, externalListener);

        mLooper.dispatchAll();
        verify(externalListener).onFailure(NOT_AUTHORIZED);
    }

    /**
     * Test behavior of {@link WifiManager#connect(int, ActionListener)}
     */
    @Test
    public void testConnectWithListenerHandleRemoteException() throws Exception {
        doThrow(new RemoteException()).when(mWifiService)
                .connect(eq(null), anyInt(), any(IBinder.class),
                        any(IActionListener.class), anyInt());
        ActionListener externalListener = mock(ActionListener.class);
        mWifiManager.connect(TEST_NETWORK_ID, externalListener);

        mLooper.dispatchAll();
        verify(externalListener).onFailure(ERROR);
    }

    /**
     * Test behavior of {@link WifiManager#connect(int, ActionListener)}
     */
    @Test
    public void testConnectWithoutListener() throws Exception {
        WifiConfiguration configuration = new WifiConfiguration();
        mWifiManager.connect(configuration, null);

        verify(mWifiService).connect(configuration, WifiConfiguration.INVALID_NETWORK_ID, null,
                null, 0);
    }

    /**
     * Test behavior of {@link WifiManager#getTxPacketCount(TxPacketCountListener)}
     */
    @Test
    public void testGetTxPacketCount() throws Exception {
        TxPacketCountListener externalListener =
                mock(TxPacketCountListener.class);
        mWifiManager.getTxPacketCount(externalListener);

        ArgumentCaptor<ITxPacketCountListener> binderListenerCaptor =
                ArgumentCaptor.forClass(ITxPacketCountListener.class);
        verify(mWifiService).getTxPacketCount(anyString(), any(Binder.class),
                binderListenerCaptor.capture(), anyInt());
        assertNotNull(binderListenerCaptor.getValue());

        // Trigger on success.
        binderListenerCaptor.getValue().onSuccess(6);
        mLooper.dispatchAll();
        verify(externalListener).onSuccess(6);

        // Trigger on failure.
        binderListenerCaptor.getValue().onFailure(BUSY);
        mLooper.dispatchAll();
        verify(externalListener).onFailure(BUSY);
    }

    /**
     * Verify an IllegalArgumentException is thrown if callback is not provided.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRegisterScanResultsCallbackWithNullCallback() throws Exception {
        mWifiManager.registerScanResultsCallback(mExecutor, null);
    }

    /**
     * Verify an IllegalArgumentException is thrown if executor is not provided.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRegisterCallbackWithNullExecutor() throws Exception {
        mWifiManager.registerScanResultsCallback(null, mScanResultsCallback);
    }

    /**
     * Verify client provided callback is being called to the right callback.
     */
    @Test
    public void testAddScanResultsCallbackAndReceiveEvent() throws Exception {
        ArgumentCaptor<IScanResultsCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(IScanResultsCallback.Stub.class);
        mWifiManager.registerScanResultsCallback(new SynchronousExecutor(), mScanResultsCallback);
        verify(mWifiService).registerScanResultsCallback(callbackCaptor.capture());
        callbackCaptor.getValue().onScanResultsAvailable();
        verify(mRunnable).run();
    }

    /**
     * Verify client provided callback is being called to the right executor.
     */
    @Test
    public void testRegisterScanResultsCallbackWithTheTargetExecutor() throws Exception {
        ArgumentCaptor<IScanResultsCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(IScanResultsCallback.Stub.class);
        mWifiManager.registerScanResultsCallback(mExecutor, mScanResultsCallback);
        verify(mWifiService).registerScanResultsCallback(callbackCaptor.capture());
        mWifiManager.registerScanResultsCallback(mAnotherExecutor, mScanResultsCallback);
        callbackCaptor.getValue().onScanResultsAvailable();
        verify(mExecutor, never()).execute(any(Runnable.class));
        verify(mAnotherExecutor).execute(any(Runnable.class));
    }

    /**
     * Verify client register unregister then register again, to ensure callback still works.
     */
    @Test
    public void testRegisterUnregisterThenRegisterAgainWithScanResultCallback() throws Exception {
        ArgumentCaptor<IScanResultsCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(IScanResultsCallback.Stub.class);
        mWifiManager.registerScanResultsCallback(new SynchronousExecutor(), mScanResultsCallback);
        verify(mWifiService).registerScanResultsCallback(callbackCaptor.capture());
        mWifiManager.unregisterScanResultsCallback(mScanResultsCallback);
        callbackCaptor.getValue().onScanResultsAvailable();
        verify(mRunnable, never()).run();
        mWifiManager.registerScanResultsCallback(new SynchronousExecutor(), mScanResultsCallback);
        callbackCaptor.getValue().onScanResultsAvailable();
        verify(mRunnable).run();
    }

    /**
     * Verify client unregisterScanResultsCallback.
     */
    @Test
    public void testUnregisterScanResultsCallback() throws Exception {
        mWifiManager.unregisterScanResultsCallback(mScanResultsCallback);
        verify(mWifiService).unregisterScanResultsCallback(any());
    }

    /**
     * Verify client unregisterScanResultsCallback with null callback will cause an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUnregisterScanResultsCallbackWithNullCallback() throws Exception {
        mWifiManager.unregisterScanResultsCallback(null);
    }

    /**
     * Verify an IllegalArgumentException is thrown if executor not provided.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testAddSuggestionConnectionStatusListenerWithNullExecutor() {
        mWifiManager.addSuggestionConnectionStatusListener(null, mListener);
    }

    /**
     * Verify an IllegalArgumentException is thrown if listener is not provided.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testAddSuggestionConnectionStatusListenerWithNullListener() {
        mWifiManager.addSuggestionConnectionStatusListener(mExecutor, null);
    }

    /**
     * Verify client provided listener is being called to the right listener.
     */
    @Test
    public void testAddSuggestionConnectionStatusListenerAndReceiveEvent() throws Exception {
        int errorCode = STATUS_SUGGESTION_CONNECTION_FAILURE_AUTHENTICATION;
        ArgumentCaptor<ISuggestionConnectionStatusListener.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISuggestionConnectionStatusListener.Stub.class);
        Executor executor = new SynchronousExecutor();
        mWifiManager.addSuggestionConnectionStatusListener(executor, mListener);
        verify(mWifiService).registerSuggestionConnectionStatusListener(any(IBinder.class),
                callbackCaptor.capture(), anyInt(), anyString(), nullable(String.class));
        callbackCaptor.getValue().onConnectionStatus(mWifiNetworkSuggestion, errorCode);
        verify(mListener).onConnectionStatus(any(WifiNetworkSuggestion.class), eq(errorCode));
    }

    /**
     * Verify client provided listener is being called to the right executor.
     */
    @Test
    public void testAddSuggestionConnectionStatusListenerWithTheTargetExecutor() throws Exception {
        int errorCode = STATUS_SUGGESTION_CONNECTION_FAILURE_AUTHENTICATION;
        ArgumentCaptor<ISuggestionConnectionStatusListener.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISuggestionConnectionStatusListener.Stub.class);
        mWifiManager.addSuggestionConnectionStatusListener(mExecutor, mListener);
        verify(mWifiService).registerSuggestionConnectionStatusListener(any(IBinder.class),
                callbackCaptor.capture(), anyInt(), anyString(), nullable(String.class));
        callbackCaptor.getValue().onConnectionStatus(any(WifiNetworkSuggestion.class), errorCode);
        verify(mExecutor).execute(any(Runnable.class));
    }

    /**
     * Verify an IllegalArgumentException is thrown if listener is not provided.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRemoveSuggestionConnectionListenerWithNullListener() {
        mWifiManager.removeSuggestionConnectionStatusListener(null);
    }

    /**
     * Verify removeSuggestionConnectionListener.
     */
    @Test
    public void testRemoveSuggestionConnectionListener() throws Exception {
        mWifiManager.removeSuggestionConnectionStatusListener(mListener);
        verify(mWifiService).unregisterSuggestionConnectionStatusListener(anyInt(), anyString());
    }

    /** Test {@link WifiManager#calculateSignalLevel(int)} */
    @Test
    public void testCalculateSignalLevel() throws Exception {
        when(mWifiService.calculateSignalLevel(anyInt())).thenReturn(3);
        int actual = mWifiManager.calculateSignalLevel(-60);
        verify(mWifiService).calculateSignalLevel(-60);
        assertEquals(3, actual);
    }

    /** Test {@link WifiManager#getMaxSignalLevel()} */
    @Test
    public void testGetMaxSignalLevel() throws Exception {
        when(mWifiService.calculateSignalLevel(anyInt())).thenReturn(4);
        int actual = mWifiManager.getMaxSignalLevel();
        verify(mWifiService).calculateSignalLevel(Integer.MAX_VALUE);
        assertEquals(4, actual);
    }

    /*
     * Test behavior of isWapiSupported
     * @throws Exception
     */
    @Test
    public void testIsWapiSupported() throws Exception {
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(WifiManager.WIFI_FEATURE_WAPI));
        assertTrue(mWifiManager.isWapiSupported());
        when(mWifiService.getSupportedFeatures())
                .thenReturn(new Long(~WifiManager.WIFI_FEATURE_WAPI));
        assertFalse(mWifiManager.isWapiSupported());
    }

    /*
     * Test that DPP channel list is parsed correctly
     */
    @Test
    public void testparseDppChannelList() throws Exception {
        String channelList = "81/1,2,3,4,5,6,7,8,9,10,11,115/36,40,44,48";
        SparseArray<int[]> expectedResult = new SparseArray<>();
        expectedResult.append(81, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
        expectedResult.append(115, new int[]{36, 40, 44, 48});

        SparseArray<int[]> result = WifiManager.parseDppChannelList(channelList);
        assertEquals(expectedResult.size(), result.size());

        int index = 0;
        int key;

        // Compare the two primitive int arrays
        do {
            try {
                key = result.keyAt(index);
            } catch (java.lang.ArrayIndexOutOfBoundsException e) {
                break;
            }
            int[] expected = expectedResult.get(key);
            int[] output = result.get(key);
            assertEquals(expected.length, output.length);
            for (int i = 0; i < output.length; i++) {
                assertEquals(expected[i], output[i]);
            }
            index++;
        } while (true);
    }

    /*
     * Test that DPP channel list parser gracefully fails for invalid input
     */
    @Test
    public void testparseDppChannelListWithInvalidFormats() throws Exception {
        String channelList = "1,2,3,4,5,6,7,8,9,10,11,36,40,44,48";
        SparseArray<int[]> result = WifiManager.parseDppChannelList(channelList);
        assertEquals(result.size(), 0);

        channelList = "ajgalskgjalskjg3-09683dh";
        result = WifiManager.parseDppChannelList(channelList);
        assertEquals(result.size(), 0);

        channelList = "13/abc,46////";
        result = WifiManager.parseDppChannelList(channelList);
        assertEquals(result.size(), 0);

        channelList = "11/4,5,13/";
        result = WifiManager.parseDppChannelList(channelList);
        assertEquals(result.size(), 0);

        channelList = "/24,6";
        result = WifiManager.parseDppChannelList(channelList);
        assertEquals(result.size(), 0);
    }

    /**
     * Test getWifiConfigsForMatchedNetworkSuggestions for given scanResults.
     */
    @Test
    public void testGetWifiConfigsForMatchedNetworkSuggestions() throws Exception {
        List<WifiConfiguration> testResults = new ArrayList<>();
        testResults.add(new WifiConfiguration());

        when(mWifiService.getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(any(List.class)))
                .thenReturn(testResults);
        assertEquals(testResults, mWifiManager
                .getWifiConfigForMatchedNetworkSuggestionsSharedWithUser(new ArrayList<>()));
    }

    /**
     * Verify the call to setWifiConnectedNetworkScorer goes to WifiServiceImpl.
     */
    @Test
    public void setWifiConnectedNetworkScorerGoesToWifiServiceImpl() throws Exception {
        mExecutor = new SynchronousExecutor();
        mWifiManager.setWifiConnectedNetworkScorer(mExecutor, mWifiConnectedNetworkScorer);
        verify(mWifiService).setWifiConnectedNetworkScorer(any(IBinder.class),
                any(IWifiConnectedNetworkScorer.Stub.class));
    }

    /**
     * Verify the call to clearWifiConnectedNetworkScorer goes to WifiServiceImpl.
     */
    @Test
    public void clearWifiConnectedNetworkScorerGoesToWifiServiceImpl() throws Exception {
        mExecutor = new SynchronousExecutor();
        mWifiManager.setWifiConnectedNetworkScorer(mExecutor, mWifiConnectedNetworkScorer);
        verify(mWifiService).setWifiConnectedNetworkScorer(any(IBinder.class),
                any(IWifiConnectedNetworkScorer.Stub.class));

        mWifiManager.clearWifiConnectedNetworkScorer();
        verify(mWifiService).clearWifiConnectedNetworkScorer();
    }

    /**
     * Verify that Wi-Fi connected scorer receives score update observer after registeration.
     */
    @Test
    public void verifyScorerReceiveScoreUpdateObserverAfterRegistration() throws Exception {
        mExecutor = new SynchronousExecutor();
        mWifiManager.setWifiConnectedNetworkScorer(mExecutor, mWifiConnectedNetworkScorer);
        ArgumentCaptor<IWifiConnectedNetworkScorer.Stub> scorerCaptor =
                ArgumentCaptor.forClass(IWifiConnectedNetworkScorer.Stub.class);
        verify(mWifiService).setWifiConnectedNetworkScorer(any(IBinder.class),
                scorerCaptor.capture());
        scorerCaptor.getValue().onSetScoreUpdateObserver(any());
        mLooper.dispatchAll();
        verify(mWifiConnectedNetworkScorer).onSetScoreUpdateObserver(any());
    }

    /**
     * Verify that Wi-Fi connected scorer receives session ID when onStart/onStop methods
     * are called.
     */
    @Test
    public void verifyScorerReceiveSessionIdWhenStartStopIsCalled() throws Exception {
        mExecutor = new SynchronousExecutor();
        mWifiManager.setWifiConnectedNetworkScorer(mExecutor, mWifiConnectedNetworkScorer);
        ArgumentCaptor<IWifiConnectedNetworkScorer.Stub> callbackCaptor =
                ArgumentCaptor.forClass(IWifiConnectedNetworkScorer.Stub.class);
        verify(mWifiService).setWifiConnectedNetworkScorer(any(IBinder.class),
                callbackCaptor.capture());
        callbackCaptor.getValue().onStart(0);
        callbackCaptor.getValue().onStop(10);
        mLooper.dispatchAll();
        verify(mWifiConnectedNetworkScorer).onStart(0);
        verify(mWifiConnectedNetworkScorer).onStop(10);
    }

    @Test
    public void testScanThrottle() throws Exception {
        mWifiManager.setScanThrottleEnabled(true);
        verify(mWifiService).setScanThrottleEnabled(true);

        when(mWifiService.isScanThrottleEnabled()).thenReturn(false);
        assertFalse(mWifiManager.isScanThrottleEnabled());
        verify(mWifiService).isScanThrottleEnabled();
    }

    @Test
    public void testAutoWakeup() throws Exception {
        mWifiManager.setAutoWakeupEnabled(true);
        verify(mWifiService).setAutoWakeupEnabled(true);

        when(mWifiService.isAutoWakeupEnabled()).thenReturn(false);
        assertFalse(mWifiManager.isAutoWakeupEnabled());
        verify(mWifiService).isAutoWakeupEnabled();
    }


    @Test
    public void testScanAvailable() throws Exception {
        mWifiManager.setScanAlwaysAvailable(true);
        verify(mWifiService).setScanAlwaysAvailable(true);

        when(mWifiService.isScanAlwaysAvailable()).thenReturn(false);
        assertFalse(mWifiManager.isScanAlwaysAvailable());
        verify(mWifiService).isScanAlwaysAvailable();
    }
}
