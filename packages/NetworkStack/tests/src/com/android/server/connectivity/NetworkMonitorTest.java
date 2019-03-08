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

package com.android.server.connectivity;

import static android.net.CaptivePortal.APP_RETURN_DISMISSED;
import static android.net.INetworkMonitor.NETWORK_TEST_RESULT_INVALID;
import static android.net.INetworkMonitor.NETWORK_TEST_RESULT_VALID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.provider.Settings.Global.DATA_STALL_EVALUATION_TYPE_DNS;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetworkMonitorCallbacks;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.captiveportal.CaptivePortalProbeResult;
import android.net.metrics.DataStallDetectionStats;
import android.net.metrics.DataStallStatsUtils;
import android.net.metrics.IpConnectivityLog;
import android.net.util.SharedLog;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.CellSignalStrength;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Random;

import javax.net.ssl.SSLHandshakeException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkMonitorTest {
    private static final String LOCATION_HEADER = "location";

    private @Mock Context mContext;
    private @Mock IpConnectivityLog mLogger;
    private @Mock SharedLog mValidationLogger;
    private @Mock NetworkInfo mNetworkInfo;
    private @Mock ConnectivityManager mCm;
    private @Mock TelephonyManager mTelephony;
    private @Mock WifiManager mWifi;
    private @Mock HttpURLConnection mHttpConnection;
    private @Mock HttpURLConnection mHttpsConnection;
    private @Mock HttpURLConnection mFallbackConnection;
    private @Mock HttpURLConnection mOtherFallbackConnection;
    private @Mock Random mRandom;
    private @Mock NetworkMonitor.Dependencies mDependencies;
    private @Mock INetworkMonitorCallbacks mCallbacks;
    private @Spy Network mNetwork = new Network(TEST_NETID);
    private @Mock DataStallStatsUtils mDataStallStatsUtils;
    private @Mock WifiInfo mWifiInfo;

    private static final int TEST_NETID = 4242;

    private static final String TEST_HTTP_URL = "http://www.google.com/gen_204";
    private static final String TEST_HTTPS_URL = "https://www.google.com/gen_204";
    private static final String TEST_FALLBACK_URL = "http://fallback.google.com/gen_204";
    private static final String TEST_OTHER_FALLBACK_URL = "http://otherfallback.google.com/gen_204";
    private static final String TEST_MCCMNC = "123456";

    private static final int RETURN_CODE_DNS_SUCCESS = 0;
    private static final int RETURN_CODE_DNS_TIMEOUT = 255;
    private static final int DEFAULT_DNS_TIMEOUT_THRESHOLD = 5;

    private static final int HANDLER_TIMEOUT_MS = 1000;

    private static final LinkProperties TEST_LINKPROPERTIES = new LinkProperties();

    private static final NetworkCapabilities METERED_CAPABILITIES = new NetworkCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NET_CAPABILITY_INTERNET);

    private static final NetworkCapabilities NOT_METERED_CAPABILITIES = new NetworkCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);

    private static final NetworkCapabilities NO_INTERNET_CAPABILITIES = new NetworkCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        when(mDependencies.getPrivateDnsBypassNetwork(any())).thenReturn(mNetwork);
        when(mDependencies.getRandom()).thenReturn(mRandom);
        when(mDependencies.getSetting(any(), eq(Settings.Global.CAPTIVE_PORTAL_MODE), anyInt()))
                .thenReturn(Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT);
        when(mDependencies.getSetting(any(), eq(Settings.Global.CAPTIVE_PORTAL_USE_HTTPS),
                anyInt())).thenReturn(1);
        when(mDependencies.getCaptivePortalServerHttpUrl(any())).thenReturn(TEST_HTTP_URL);
        when(mDependencies.getSetting(any(), eq(Settings.Global.CAPTIVE_PORTAL_HTTPS_URL),
                anyString())).thenReturn(TEST_HTTPS_URL);
        doReturn(mNetwork).when(mNetwork).getPrivateDnsBypassingCopy();

        when(mContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mCm);
        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephony);
        when(mContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mWifi);

        when(mNetworkInfo.getType()).thenReturn(ConnectivityManager.TYPE_WIFI);
        setFallbackUrl(TEST_FALLBACK_URL);
        setOtherFallbackUrls(TEST_OTHER_FALLBACK_URL);
        setFallbackSpecs(null); // Test with no fallback spec by default
        when(mRandom.nextInt()).thenReturn(0);

        doAnswer((invocation) -> {
            URL url = invocation.getArgument(0);
            switch(url.toString()) {
                case TEST_HTTP_URL:
                    return mHttpConnection;
                case TEST_HTTPS_URL:
                    return mHttpsConnection;
                case TEST_FALLBACK_URL:
                    return mFallbackConnection;
                case TEST_OTHER_FALLBACK_URL:
                    return mOtherFallbackConnection;
                default:
                    fail("URL not mocked: " + url.toString());
                    return null;
            }
        }).when(mNetwork).openConnection(any());
        when(mHttpConnection.getRequestProperties()).thenReturn(new ArrayMap<>());
        when(mHttpsConnection.getRequestProperties()).thenReturn(new ArrayMap<>());
        doReturn(new InetAddress[] {
                InetAddresses.parseNumericAddress("192.168.0.0")
        }).when(mNetwork).getAllByName(any());

        // Default values. Individual tests can override these.
        when(mCm.getLinkProperties(any())).thenReturn(TEST_LINKPROPERTIES);
        when(mCm.getNetworkCapabilities(any())).thenReturn(METERED_CAPABILITIES);

        setMinDataStallEvaluateInterval(500);
        setDataStallEvaluationType(DATA_STALL_EVALUATION_TYPE_DNS);
        setValidDataStallDnsTimeThreshold(500);
        setConsecutiveDnsTimeoutThreshold(5);
    }

    private class WrappedNetworkMonitor extends NetworkMonitor {
        private long mProbeTime = 0;

        WrappedNetworkMonitor(Context context, Network network, IpConnectivityLog logger,
                Dependencies deps, DataStallStatsUtils statsUtils) {
                super(context, mCallbacks, network, logger,
                        new SharedLog("test_nm"), deps, statsUtils);
        }

        @Override
        protected long getLastProbeTime() {
            return mProbeTime;
        }

        protected void setLastProbeTime(long time) {
            mProbeTime = time;
        }

        @Override
        protected void addDnsEvents(@NonNull final DataStallDetectionStats.Builder stats) {
            generateTimeoutDnsEvent(stats, DEFAULT_DNS_TIMEOUT_THRESHOLD);
        }
    }

    private WrappedNetworkMonitor makeMeteredWrappedNetworkMonitor() {
        final WrappedNetworkMonitor nm = new WrappedNetworkMonitor(
                mContext, mNetwork, mLogger, mDependencies, mDataStallStatsUtils);
        when(mCm.getNetworkCapabilities(any())).thenReturn(METERED_CAPABILITIES);
        nm.start();
        waitForIdle(nm.getHandler());
        return nm;
    }

    private WrappedNetworkMonitor makeNotMeteredWrappedNetworkMonitor() {
        final WrappedNetworkMonitor nm = new WrappedNetworkMonitor(
                mContext, mNetwork, mLogger, mDependencies, mDataStallStatsUtils);
        when(mCm.getNetworkCapabilities(any())).thenReturn(NOT_METERED_CAPABILITIES);
        nm.start();
        waitForIdle(nm.getHandler());
        return nm;
    }

    private NetworkMonitor makeMonitor() {
        final NetworkMonitor nm = new NetworkMonitor(
                mContext, mCallbacks, mNetwork, mLogger, mValidationLogger,
                mDependencies, mDataStallStatsUtils);
        nm.start();
        waitForIdle(nm.getHandler());
        return nm;
    }

    private void waitForIdle(Handler handler) {
        final ConditionVariable cv = new ConditionVariable(false);
        handler.post(cv::open);
        if (!cv.block(HANDLER_TIMEOUT_MS)) {
            fail("Timed out waiting for handler");
        }
    }

    @Test
    public void testIsCaptivePortal_HttpProbeIsPortal() throws IOException {
        setSslException(mHttpsConnection);
        setPortal302(mHttpConnection);

        assertPortal(makeMonitor().isCaptivePortal());
    }

    @Test
    public void testIsCaptivePortal_HttpsProbeIsNotPortal() throws IOException {
        setStatus(mHttpsConnection, 204);
        setStatus(mHttpConnection, 500);

        assertNotPortal(makeMonitor().isCaptivePortal());
    }

    @Test
    public void testIsCaptivePortal_HttpsProbeFailedHttpSuccessNotUsed() throws IOException {
        setSslException(mHttpsConnection);
        // Even if HTTP returns a 204, do not use the result unless HTTPS succeeded
        setStatus(mHttpConnection, 204);
        setStatus(mFallbackConnection, 500);

        assertFailed(makeMonitor().isCaptivePortal());
    }

    @Test
    public void testIsCaptivePortal_FallbackProbeIsPortal() throws IOException {
        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setPortal302(mFallbackConnection);

        assertPortal(makeMonitor().isCaptivePortal());
    }

    @Test
    public void testIsCaptivePortal_FallbackProbeIsNotPortal() throws IOException {
        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setStatus(mFallbackConnection, 204);

        // Fallback probe did not see portal, HTTPS failed -> inconclusive
        assertFailed(makeMonitor().isCaptivePortal());
    }

    @Test
    public void testIsCaptivePortal_OtherFallbackProbeIsPortal() throws IOException {
        // Set all fallback probes but one to invalid URLs to verify they are being skipped
        setFallbackUrl(TEST_FALLBACK_URL);
        setOtherFallbackUrls(TEST_FALLBACK_URL + "," + TEST_OTHER_FALLBACK_URL);

        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setStatus(mFallbackConnection, 500);
        setPortal302(mOtherFallbackConnection);

        // TEST_OTHER_FALLBACK_URL is third
        when(mRandom.nextInt()).thenReturn(2);

        final NetworkMonitor monitor = makeMonitor();

        // First check always uses the first fallback URL: inconclusive
        assertFailed(monitor.isCaptivePortal());
        verify(mFallbackConnection, times(1)).getResponseCode();
        verify(mOtherFallbackConnection, never()).getResponseCode();

        // Second check uses the URL chosen by Random
        assertPortal(monitor.isCaptivePortal());
        verify(mOtherFallbackConnection, times(1)).getResponseCode();
    }

    @Test
    public void testIsCaptivePortal_AllProbesFailed() throws IOException {
        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setStatus(mFallbackConnection, 404);

        assertFailed(makeMonitor().isCaptivePortal());
        verify(mFallbackConnection, times(1)).getResponseCode();
        verify(mOtherFallbackConnection, never()).getResponseCode();
    }

    @Test
    public void testIsCaptivePortal_InvalidUrlSkipped() throws IOException {
        setFallbackUrl("invalid");
        setOtherFallbackUrls("otherinvalid," + TEST_OTHER_FALLBACK_URL + ",yetanotherinvalid");

        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setPortal302(mOtherFallbackConnection);

        assertPortal(makeMonitor().isCaptivePortal());
        verify(mOtherFallbackConnection, times(1)).getResponseCode();
        verify(mFallbackConnection, never()).getResponseCode();
    }

    private void setupFallbackSpec() throws IOException {
        setFallbackSpecs("http://example.com@@/@@204@@/@@"
                + "@@,@@"
                + TEST_OTHER_FALLBACK_URL + "@@/@@30[12]@@/@@https://(www\\.)?google.com/?.*");

        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);

        // Use the 2nd fallback spec
        when(mRandom.nextInt()).thenReturn(1);
    }

    @Test
    public void testIsCaptivePortal_FallbackSpecIsNotPortal() throws IOException {
        setupFallbackSpec();
        set302(mOtherFallbackConnection, "https://www.google.com/test?q=3");

        // HTTPS failed, fallback spec did not see a portal -> inconclusive
        assertFailed(makeMonitor().isCaptivePortal());
        verify(mOtherFallbackConnection, times(1)).getResponseCode();
        verify(mFallbackConnection, never()).getResponseCode();
    }

    @Test
    public void testIsCaptivePortal_FallbackSpecIsPortal() throws IOException {
        setupFallbackSpec();
        set302(mOtherFallbackConnection, "http://login.portal.example.com");

        assertPortal(makeMonitor().isCaptivePortal());
    }

    @Test
    public void testIsCaptivePortal_IgnorePortals() throws IOException {
        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_IGNORE);
        setSslException(mHttpsConnection);
        setPortal302(mHttpConnection);

        assertNotPortal(makeMonitor().isCaptivePortal());
    }

    @Test
    public void testIsDataStall_EvaluationDisabled() {
        setDataStallEvaluationType(0);
        WrappedNetworkMonitor wrappedMonitor = makeMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        assertFalse(wrappedMonitor.isDataStall());
    }

    @Test
    public void testIsDataStall_EvaluationDnsOnNotMeteredNetwork() {
        WrappedNetworkMonitor wrappedMonitor = makeNotMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        makeDnsTimeoutEvent(wrappedMonitor, DEFAULT_DNS_TIMEOUT_THRESHOLD);
        assertTrue(wrappedMonitor.isDataStall());
    }

    @Test
    public void testIsDataStall_EvaluationDnsOnMeteredNetwork() {
        WrappedNetworkMonitor wrappedMonitor = makeMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        assertFalse(wrappedMonitor.isDataStall());

        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, DEFAULT_DNS_TIMEOUT_THRESHOLD);
        assertTrue(wrappedMonitor.isDataStall());
    }

    @Test
    public void testIsDataStall_EvaluationDnsWithDnsTimeoutCount() {
        WrappedNetworkMonitor wrappedMonitor = makeMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, 3);
        assertFalse(wrappedMonitor.isDataStall());
        // Reset consecutive timeout counts.
        makeDnsSuccessEvent(wrappedMonitor, 1);
        makeDnsTimeoutEvent(wrappedMonitor, 2);
        assertFalse(wrappedMonitor.isDataStall());

        makeDnsTimeoutEvent(wrappedMonitor, 3);
        assertTrue(wrappedMonitor.isDataStall());

        // Set the value to larger than the default dns log size.
        setConsecutiveDnsTimeoutThreshold(51);
        wrappedMonitor = makeMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, 50);
        assertFalse(wrappedMonitor.isDataStall());

        makeDnsTimeoutEvent(wrappedMonitor, 1);
        assertTrue(wrappedMonitor.isDataStall());
    }

    @Test
    public void testIsDataStall_EvaluationDnsWithDnsTimeThreshold() {
        // Test dns events happened in valid dns time threshold.
        WrappedNetworkMonitor wrappedMonitor = makeMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        makeDnsTimeoutEvent(wrappedMonitor, DEFAULT_DNS_TIMEOUT_THRESHOLD);
        assertFalse(wrappedMonitor.isDataStall());
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        assertTrue(wrappedMonitor.isDataStall());

        // Test dns events happened before valid dns time threshold.
        setValidDataStallDnsTimeThreshold(0);
        wrappedMonitor = makeMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        makeDnsTimeoutEvent(wrappedMonitor, DEFAULT_DNS_TIMEOUT_THRESHOLD);
        assertFalse(wrappedMonitor.isDataStall());
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        assertFalse(wrappedMonitor.isDataStall());
    }

    @Test
    public void testBrokenNetworkNotValidated() throws Exception {
        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setStatus(mFallbackConnection, 404);
        when(mCm.getNetworkCapabilities(any())).thenReturn(METERED_CAPABILITIES);

        final NetworkMonitor nm = makeMonitor();
        nm.notifyNetworkConnected();

        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(NETWORK_TEST_RESULT_INVALID, null);
    }

    @Test
    public void testNoInternetCapabilityValidated() throws Exception {
        when(mCm.getNetworkCapabilities(any())).thenReturn(NO_INTERNET_CAPABILITIES);

        final NetworkMonitor nm = makeMonitor();
        nm.notifyNetworkConnected();

        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(NETWORK_TEST_RESULT_VALID, null);
        verify(mNetwork, never()).openConnection(any());
    }

    @Test
    public void testLaunchCaptivePortalApp() throws Exception {
        setSslException(mHttpsConnection);
        setPortal302(mHttpConnection);

        final NetworkMonitor nm = makeMonitor();
        nm.notifyNetworkConnected();

        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .showProvisioningNotification(any(), any());

        // Check that startCaptivePortalApp sends the expected intent.
        nm.launchCaptivePortalApp();

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        final ArgumentCaptor<Network> networkCaptor = ArgumentCaptor.forClass(Network.class);
        verify(mCm, timeout(HANDLER_TIMEOUT_MS).times(1))
                .startCaptivePortalApp(networkCaptor.capture(), bundleCaptor.capture());
        final Bundle bundle = bundleCaptor.getValue();
        final Network bundleNetwork = bundle.getParcelable(ConnectivityManager.EXTRA_NETWORK);
        assertEquals(TEST_NETID, bundleNetwork.netId);
        // network is passed both in bundle and as parameter, as the bundle is opaque to the
        // framework and only intended for the captive portal app, but the framework needs
        // the network to identify the right NetworkMonitor.
        assertEquals(TEST_NETID, networkCaptor.getValue().netId);

        // Have the app report that the captive portal is dismissed, and check that we revalidate.
        setStatus(mHttpsConnection, 204);
        setStatus(mHttpConnection, 204);

        nm.notifyCaptivePortalAppFinished(APP_RETURN_DISMISSED);
        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(NETWORK_TEST_RESULT_VALID, null);
    }

    @Test
    public void testDataStall_StallSuspectedAndSendMetrics() throws IOException {
        WrappedNetworkMonitor wrappedMonitor = makeNotMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, 5);
        assertTrue(wrappedMonitor.isDataStall());
        verify(mDataStallStatsUtils, times(1)).write(makeEmptyDataStallDetectionStats(), any());
    }

    @Test
    public void testDataStall_NoStallSuspectedAndSendMetrics() throws IOException {
        WrappedNetworkMonitor wrappedMonitor = makeNotMeteredWrappedNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, 3);
        assertFalse(wrappedMonitor.isDataStall());
        verify(mDataStallStatsUtils, never()).write(makeEmptyDataStallDetectionStats(), any());
    }

    @Test
    public void testCollectDataStallMetrics() {
        WrappedNetworkMonitor wrappedMonitor = makeNotMeteredWrappedNetworkMonitor();

        when(mTelephony.getDataNetworkType()).thenReturn(TelephonyManager.NETWORK_TYPE_LTE);
        when(mTelephony.getNetworkOperator()).thenReturn(TEST_MCCMNC);
        when(mTelephony.getSimOperator()).thenReturn(TEST_MCCMNC);

        DataStallDetectionStats.Builder stats =
                new DataStallDetectionStats.Builder()
                .setEvaluationType(DATA_STALL_EVALUATION_TYPE_DNS)
                .setNetworkType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .setCellData(TelephonyManager.NETWORK_TYPE_LTE /* radioType */,
                        true /* roaming */,
                        TEST_MCCMNC /* networkMccmnc */,
                        TEST_MCCMNC /* simMccmnc */,
                        CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN /* signalStrength */);
        generateTimeoutDnsEvent(stats, DEFAULT_DNS_TIMEOUT_THRESHOLD);

        assertEquals(wrappedMonitor.buildDataStallDetectionStats(
                 NetworkCapabilities.TRANSPORT_CELLULAR), stats.build());

        when(mWifi.getConnectionInfo()).thenReturn(mWifiInfo);

        stats = new DataStallDetectionStats.Builder()
                .setEvaluationType(DATA_STALL_EVALUATION_TYPE_DNS)
                .setNetworkType(NetworkCapabilities.TRANSPORT_WIFI)
                .setWiFiData(mWifiInfo);
        generateTimeoutDnsEvent(stats, DEFAULT_DNS_TIMEOUT_THRESHOLD);

        assertEquals(
                wrappedMonitor.buildDataStallDetectionStats(NetworkCapabilities.TRANSPORT_WIFI),
                stats.build());
    }

    private void makeDnsTimeoutEvent(WrappedNetworkMonitor wrappedMonitor, int count) {
        for (int i = 0; i < count; i++) {
            wrappedMonitor.getDnsStallDetector().accumulateConsecutiveDnsTimeoutCount(
                    RETURN_CODE_DNS_TIMEOUT);
        }
    }

    private void makeDnsSuccessEvent(WrappedNetworkMonitor wrappedMonitor, int count) {
        for (int i = 0; i < count; i++) {
            wrappedMonitor.getDnsStallDetector().accumulateConsecutiveDnsTimeoutCount(
                    RETURN_CODE_DNS_SUCCESS);
        }
    }

    private DataStallDetectionStats makeEmptyDataStallDetectionStats() {
        return new DataStallDetectionStats.Builder().build();
    }

    private void setDataStallEvaluationType(int type) {
        when(mDependencies.getSetting(any(),
            eq(Settings.Global.DATA_STALL_EVALUATION_TYPE), anyInt())).thenReturn(type);
    }

    private void setMinDataStallEvaluateInterval(int time) {
        when(mDependencies.getSetting(any(),
            eq(Settings.Global.DATA_STALL_MIN_EVALUATE_INTERVAL), anyInt())).thenReturn(time);
    }

    private void setValidDataStallDnsTimeThreshold(int time) {
        when(mDependencies.getSetting(any(),
            eq(Settings.Global.DATA_STALL_VALID_DNS_TIME_THRESHOLD), anyInt())).thenReturn(time);
    }

    private void setConsecutiveDnsTimeoutThreshold(int num) {
        when(mDependencies.getSetting(any(),
            eq(Settings.Global.DATA_STALL_CONSECUTIVE_DNS_TIMEOUT_THRESHOLD), anyInt()))
            .thenReturn(num);
    }

    private void setFallbackUrl(String url) {
        when(mDependencies.getSetting(any(),
                eq(Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL), any())).thenReturn(url);
    }

    private void setOtherFallbackUrls(String urls) {
        when(mDependencies.getSetting(any(),
                eq(Settings.Global.CAPTIVE_PORTAL_OTHER_FALLBACK_URLS), any())).thenReturn(urls);
    }

    private void setFallbackSpecs(String specs) {
        when(mDependencies.getSetting(any(),
                eq(Settings.Global.CAPTIVE_PORTAL_FALLBACK_PROBE_SPECS), any())).thenReturn(specs);
    }

    private void setCaptivePortalMode(int mode) {
        when(mDependencies.getSetting(any(),
                eq(Settings.Global.CAPTIVE_PORTAL_MODE), anyInt())).thenReturn(mode);
    }

    private void assertPortal(CaptivePortalProbeResult result) {
        assertTrue(result.isPortal());
        assertFalse(result.isFailed());
        assertFalse(result.isSuccessful());
    }

    private void assertNotPortal(CaptivePortalProbeResult result) {
        assertFalse(result.isPortal());
        assertFalse(result.isFailed());
        assertTrue(result.isSuccessful());
    }

    private void assertFailed(CaptivePortalProbeResult result) {
        assertFalse(result.isPortal());
        assertTrue(result.isFailed());
        assertFalse(result.isSuccessful());
    }

    private void setSslException(HttpURLConnection connection) throws IOException {
        doThrow(new SSLHandshakeException("Invalid cert")).when(connection).getResponseCode();
    }

    private void set302(HttpURLConnection connection, String location) throws IOException {
        setStatus(connection, 302);
        doReturn(location).when(connection).getHeaderField(LOCATION_HEADER);
    }

    private void setPortal302(HttpURLConnection connection) throws IOException {
        set302(connection, "http://login.example.com");
    }

    private void setStatus(HttpURLConnection connection, int status) throws IOException {
        doReturn(status).when(connection).getResponseCode();
    }

    private void generateTimeoutDnsEvent(DataStallDetectionStats.Builder stats, int num) {
        for (int i = 0; i < num; i++) {
            stats.addDnsEvent(RETURN_CODE_DNS_TIMEOUT, 123456789 /* timeMs */);
        }
    }
}

