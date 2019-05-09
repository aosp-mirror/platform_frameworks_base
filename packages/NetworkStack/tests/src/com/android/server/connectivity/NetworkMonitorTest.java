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
import static android.net.INetworkMonitor.NETWORK_TEST_RESULT_PARTIAL_CONNECTIVITY;
import static android.net.INetworkMonitor.NETWORK_TEST_RESULT_VALID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.util.DataStallUtils.CONFIG_DATA_STALL_CONSECUTIVE_DNS_TIMEOUT_THRESHOLD;
import static android.net.util.DataStallUtils.CONFIG_DATA_STALL_EVALUATION_TYPE;
import static android.net.util.DataStallUtils.CONFIG_DATA_STALL_MIN_EVALUATE_INTERVAL;
import static android.net.util.DataStallUtils.CONFIG_DATA_STALL_VALID_DNS_TIME_THRESHOLD;
import static android.net.util.DataStallUtils.DATA_STALL_EVALUATION_TYPE_DNS;
import static android.net.util.NetworkStackUtils.CAPTIVE_PORTAL_FALLBACK_PROBE_SPECS;
import static android.net.util.NetworkStackUtils.CAPTIVE_PORTAL_OTHER_FALLBACK_URLS;
import static android.net.util.NetworkStackUtils.CAPTIVE_PORTAL_USE_HTTPS;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.INetworkMonitorCallbacks;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.captiveportal.CaptivePortalProbeResult;
import android.net.metrics.IpConnectivityLog;
import android.net.shared.PrivateDnsConfig;
import android.net.util.SharedLog;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.CellSignalStrength;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.networkstack.R;
import com.android.networkstack.metrics.DataStallDetectionStats;
import com.android.networkstack.metrics.DataStallStatsUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLHandshakeException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkMonitorTest {
    private static final String LOCATION_HEADER = "location";

    private @Mock Context mContext;
    private @Mock Resources mResources;
    private @Mock IpConnectivityLog mLogger;
    private @Mock SharedLog mValidationLogger;
    private @Mock NetworkInfo mNetworkInfo;
    private @Mock DnsResolver mDnsResolver;
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
    private @Mock Network mNonPrivateDnsBypassNetwork;
    private @Mock DataStallStatsUtils mDataStallStatsUtils;
    private @Mock WifiInfo mWifiInfo;
    private @Captor ArgumentCaptor<String> mNetworkTestedRedirectUrlCaptor;

    private HashSet<WrappedNetworkMonitor> mCreatedNetworkMonitors;
    private HashSet<BroadcastReceiver> mRegisteredReceivers;

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

    private static final LinkProperties TEST_LINK_PROPERTIES = new LinkProperties();

    private static final NetworkCapabilities METERED_CAPABILITIES = new NetworkCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NET_CAPABILITY_INTERNET);

    private static final NetworkCapabilities NOT_METERED_CAPABILITIES = new NetworkCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);

    private static final NetworkCapabilities NO_INTERNET_CAPABILITIES = new NetworkCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

    /**
     * Fakes DNS responses.
     *
     * Allows test methods to configure the IP addresses that will be resolved by
     * Network#getAllByName and by DnsResolver#query.
     */
    class FakeDns {
        private final ArrayMap<String, List<InetAddress>> mAnswers = new ArrayMap<>();
        private boolean mNonBypassPrivateDnsWorking = true;

        /** Whether DNS queries on mNonBypassPrivateDnsWorking should succeed. */
        private void setNonBypassPrivateDnsWorking(boolean working) {
            mNonBypassPrivateDnsWorking = working;
        }

        /** Clears all DNS entries. */
        private synchronized void clearAll() {
            mAnswers.clear();
        }

        /** Returns the answer for a given name on the given mock network. */
        private synchronized List<InetAddress> getAnswer(Object mock, String hostname) {
            if (mock == mNonPrivateDnsBypassNetwork && !mNonBypassPrivateDnsWorking) {
                return null;
            }
            if (mAnswers.containsKey(hostname)) {
                return mAnswers.get(hostname);
            }
            return mAnswers.get("*");
        }

        /** Sets the answer for a given name. */
        private synchronized void setAnswer(String hostname, String[] answer)
                throws UnknownHostException {
            if (answer == null) {
                mAnswers.remove(hostname);
            } else {
                List<InetAddress> answerList = new ArrayList<>();
                for (String addr : answer) {
                    answerList.add(InetAddresses.parseNumericAddress(addr));
                }
                mAnswers.put(hostname, answerList);
            }
        }

        /** Simulates a getAllByName call for the specified name on the specified mock network. */
        private InetAddress[] getAllByName(Object mock, String hostname)
                throws UnknownHostException {
            List<InetAddress> answer = getAnswer(mock, hostname);
            if (answer == null || answer.size() == 0) {
                throw new UnknownHostException(hostname);
            }
            return answer.toArray(new InetAddress[0]);
        }

        /** Starts mocking DNS queries. */
        private void startMocking() throws UnknownHostException {
            // Queries on mNetwork (i.e., bypassing private DNS) using getAllByName.
            doAnswer(invocation -> {
                return getAllByName(invocation.getMock(), invocation.getArgument(0));
            }).when(mNetwork).getAllByName(any());

            // Queries on mNonBypassPrivateDnsNetwork using getAllByName.
            doAnswer(invocation -> {
                return getAllByName(invocation.getMock(), invocation.getArgument(0));
            }).when(mNonPrivateDnsBypassNetwork).getAllByName(any());

            // Queries on mNetwork (i.e., bypassing private DNS) using DnsResolver#query.
            doAnswer(invocation -> {
                String hostname = (String) invocation.getArgument(1);
                Executor executor = (Executor) invocation.getArgument(3);
                DnsResolver.Callback<List<InetAddress>> callback = invocation.getArgument(5);

                List<InetAddress> answer = getAnswer(invocation.getMock(), hostname);
                if (answer != null && answer.size() > 0) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        executor.execute(() -> callback.onAnswer(answer, 0));
                    });
                }
                // If no answers, do nothing. sendDnsProbeWithTimeout will time out and throw UHE.
                return null;
            }).when(mDnsResolver).query(any(), any(), anyInt(), any(), any(), any());
        }
    }

    private FakeDns mFakeDns;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        when(mDependencies.getPrivateDnsBypassNetwork(any())).thenReturn(mNetwork);
        when(mDependencies.getDnsResolver()).thenReturn(mDnsResolver);
        when(mDependencies.getRandom()).thenReturn(mRandom);
        when(mDependencies.getSetting(any(), eq(Settings.Global.CAPTIVE_PORTAL_MODE), anyInt()))
                .thenReturn(Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT);
        when(mDependencies.getDeviceConfigPropertyInt(any(), eq(CAPTIVE_PORTAL_USE_HTTPS),
                anyInt())).thenReturn(1);
        when(mDependencies.getSetting(any(), eq(Settings.Global.CAPTIVE_PORTAL_HTTP_URL), any()))
                .thenReturn(TEST_HTTP_URL);
        when(mDependencies.getSetting(any(), eq(Settings.Global.CAPTIVE_PORTAL_HTTPS_URL), any()))
                .thenReturn(TEST_HTTPS_URL);

        doReturn(mNetwork).when(mNonPrivateDnsBypassNetwork).getPrivateDnsBypassingCopy();

        when(mContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mCm);
        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephony);
        when(mContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mWifi);
        when(mContext.getResources()).thenReturn(mResources);

        when(mResources.getString(anyInt())).thenReturn("");
        when(mResources.getStringArray(anyInt())).thenReturn(new String[0]);

        when(mNetworkInfo.getType()).thenReturn(ConnectivityManager.TYPE_WIFI);
        setFallbackUrl(TEST_FALLBACK_URL);
        setOtherFallbackUrls(TEST_OTHER_FALLBACK_URL);
        setFallbackSpecs(null); // Test with no fallback spec by default
        when(mRandom.nextInt()).thenReturn(0);

        when(mResources.getInteger(eq(R.integer.config_captive_portal_dns_probe_timeout)))
                .thenReturn(500);

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

        mFakeDns = new FakeDns();
        mFakeDns.startMocking();
        mFakeDns.setAnswer("*", new String[]{"2001:db8::1", "192.0.2.2"});

        when(mContext.registerReceiver(any(BroadcastReceiver.class), any())).then((invocation) -> {
            mRegisteredReceivers.add(invocation.getArgument(0));
            return new Intent();
        });

        doAnswer((invocation) -> {
            mRegisteredReceivers.remove(invocation.getArgument(0));
            return null;
        }).when(mContext).unregisterReceiver(any());

        setMinDataStallEvaluateInterval(500);
        setDataStallEvaluationType(DATA_STALL_EVALUATION_TYPE_DNS);
        setValidDataStallDnsTimeThreshold(500);
        setConsecutiveDnsTimeoutThreshold(5);

        mCreatedNetworkMonitors = new HashSet<>();
        mRegisteredReceivers = new HashSet<>();
    }

    @After
    public void tearDown() {
        mFakeDns.clearAll();
        assertTrue(mCreatedNetworkMonitors.size() > 0);
        // Make a local copy of mCreatedNetworkMonitors because during the iteration below,
        // WrappedNetworkMonitor#onQuitting will delete elements from it on the handler threads.
        WrappedNetworkMonitor[] networkMonitors = mCreatedNetworkMonitors.toArray(
                new WrappedNetworkMonitor[0]);
        for (WrappedNetworkMonitor nm : networkMonitors) {
            nm.notifyNetworkDisconnected();
            nm.awaitQuit();
        }
        assertEquals("NetworkMonitor still running after disconnect",
                0, mCreatedNetworkMonitors.size());
        assertEquals("BroadcastReceiver still registered after disconnect",
                0, mRegisteredReceivers.size());
    }

    private class WrappedNetworkMonitor extends NetworkMonitor {
        private long mProbeTime = 0;
        private final ConditionVariable mQuitCv = new ConditionVariable(false);

        WrappedNetworkMonitor() {
            super(mContext, mCallbacks, mNonPrivateDnsBypassNetwork, mLogger, mValidationLogger,
                    mDependencies, mDataStallStatsUtils);
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

        @Override
        protected void onQuitting() {
            assertTrue(mCreatedNetworkMonitors.remove(this));
            mQuitCv.open();
        }

        protected void awaitQuit() {
            assertTrue("NetworkMonitor did not quit after " + HANDLER_TIMEOUT_MS + "ms",
                    mQuitCv.block(HANDLER_TIMEOUT_MS));
        }
    }

    private WrappedNetworkMonitor makeMonitor(NetworkCapabilities nc) {
        final WrappedNetworkMonitor nm = new WrappedNetworkMonitor();
        nm.start();
        setNetworkCapabilities(nm, nc);
        waitForIdle(nm.getHandler());
        mCreatedNetworkMonitors.add(nm);
        return nm;
    }

    private WrappedNetworkMonitor makeMeteredNetworkMonitor() {
        final WrappedNetworkMonitor nm = makeMonitor(METERED_CAPABILITIES);
        return nm;
    }

    private WrappedNetworkMonitor makeNotMeteredNetworkMonitor() {
        final WrappedNetworkMonitor nm = makeMonitor(NOT_METERED_CAPABILITIES);
        return nm;
    }

    private void setNetworkCapabilities(NetworkMonitor nm, NetworkCapabilities nc) {
        nm.notifyNetworkCapabilitiesChanged(nc);
        waitForIdle(nm.getHandler());
    }

    private void waitForIdle(Handler handler) {
        final ConditionVariable cv = new ConditionVariable(false);
        handler.post(cv::open);
        if (!cv.block(HANDLER_TIMEOUT_MS)) {
            fail("Timed out waiting for handler");
        }
    }

    @Test
    public void testGetIntSetting() throws Exception {
        WrappedNetworkMonitor wnm = makeNotMeteredNetworkMonitor();

        // No config resource, no device config. Expect to get default resource.
        doThrow(new Resources.NotFoundException())
                .when(mResources).getInteger(eq(R.integer.config_captive_portal_dns_probe_timeout));
        doAnswer(invocation -> {
            int defaultValue = invocation.getArgument(2);
            return defaultValue;
        }).when(mDependencies).getDeviceConfigPropertyInt(any(),
                eq(NetworkMonitor.CONFIG_CAPTIVE_PORTAL_DNS_PROBE_TIMEOUT),
                anyInt());
        when(mResources.getInteger(eq(R.integer.default_captive_portal_dns_probe_timeout)))
                .thenReturn(42);
        assertEquals(42, wnm.getIntSetting(mContext,
                R.integer.config_captive_portal_dns_probe_timeout,
                NetworkMonitor.CONFIG_CAPTIVE_PORTAL_DNS_PROBE_TIMEOUT,
                R.integer.default_captive_portal_dns_probe_timeout));

        // Set device config. Expect to get device config.
        when(mDependencies.getDeviceConfigPropertyInt(any(),
                eq(NetworkMonitor.CONFIG_CAPTIVE_PORTAL_DNS_PROBE_TIMEOUT), anyInt()))
                        .thenReturn(1234);
        assertEquals(1234, wnm.getIntSetting(mContext,
                R.integer.config_captive_portal_dns_probe_timeout,
                NetworkMonitor.CONFIG_CAPTIVE_PORTAL_DNS_PROBE_TIMEOUT,
                R.integer.default_captive_portal_dns_probe_timeout));

        // Set config resource. Expect to get config resource.
        when(mResources.getInteger(eq(R.integer.config_captive_portal_dns_probe_timeout)))
                .thenReturn(5678);
        assertEquals(5678, wnm.getIntSetting(mContext,
                R.integer.config_captive_portal_dns_probe_timeout,
                NetworkMonitor.CONFIG_CAPTIVE_PORTAL_DNS_PROBE_TIMEOUT,
                R.integer.default_captive_portal_dns_probe_timeout));
    }

    @Test
    public void testIsCaptivePortal_HttpProbeIsPortal() throws IOException {
        setSslException(mHttpsConnection);
        setPortal302(mHttpConnection);

        runPortalNetworkTest();
    }

    @Test
    public void testIsCaptivePortal_HttpsProbeIsNotPortal() throws IOException {
        setStatus(mHttpsConnection, 204);
        setStatus(mHttpConnection, 500);

        runNotPortalNetworkTest();
    }

    @Test
    public void testIsCaptivePortal_FallbackProbeIsPortal() throws IOException {
        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setPortal302(mFallbackConnection);

        runPortalNetworkTest();
    }

    @Test
    public void testIsCaptivePortal_FallbackProbeIsNotPortal() throws IOException {
        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setStatus(mFallbackConnection, 500);

        // Fallback probe did not see portal, HTTPS failed -> inconclusive
        runFailedNetworkTest();
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

        // First check always uses the first fallback URL: inconclusive
        final NetworkMonitor monitor = runNetworkTest(NETWORK_TEST_RESULT_INVALID);
        assertNull(mNetworkTestedRedirectUrlCaptor.getValue());
        verify(mFallbackConnection, times(1)).getResponseCode();
        verify(mOtherFallbackConnection, never()).getResponseCode();

        // Second check uses the URL chosen by Random
        final CaptivePortalProbeResult result = monitor.isCaptivePortal();
        assertTrue(result.isPortal());
        verify(mOtherFallbackConnection, times(1)).getResponseCode();
    }

    @Test
    public void testIsCaptivePortal_AllProbesFailed() throws IOException {
        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 500);
        setStatus(mFallbackConnection, 404);

        runFailedNetworkTest();
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

        runPortalNetworkTest();
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
    public void testIsCaptivePortal_FallbackSpecIsPartial() throws IOException {
        setupFallbackSpec();
        set302(mOtherFallbackConnection, "https://www.google.com/test?q=3");

        // HTTPS failed, fallback spec went through -> partial connectivity
        runPartialConnectivityNetworkTest();
        verify(mOtherFallbackConnection, times(1)).getResponseCode();
        verify(mFallbackConnection, never()).getResponseCode();
    }

    @Test
    public void testIsCaptivePortal_FallbackSpecIsPortal() throws IOException {
        setupFallbackSpec();
        set302(mOtherFallbackConnection, "http://login.portal.example.com");

        runPortalNetworkTest();
    }

    @Test
    public void testIsCaptivePortal_IgnorePortals() throws IOException {
        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_IGNORE);
        setSslException(mHttpsConnection);
        setPortal302(mHttpConnection);

        runNotPortalNetworkTest();
    }

    @Test
    public void testIsDataStall_EvaluationDisabled() {
        setDataStallEvaluationType(0);
        WrappedNetworkMonitor wrappedMonitor = makeMeteredNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        assertFalse(wrappedMonitor.isDataStall());
    }

    @Test
    public void testIsDataStall_EvaluationDnsOnNotMeteredNetwork() {
        WrappedNetworkMonitor wrappedMonitor = makeNotMeteredNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        makeDnsTimeoutEvent(wrappedMonitor, DEFAULT_DNS_TIMEOUT_THRESHOLD);
        assertTrue(wrappedMonitor.isDataStall());
    }

    @Test
    public void testIsDataStall_EvaluationDnsOnMeteredNetwork() {
        WrappedNetworkMonitor wrappedMonitor = makeMeteredNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        assertFalse(wrappedMonitor.isDataStall());

        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, DEFAULT_DNS_TIMEOUT_THRESHOLD);
        assertTrue(wrappedMonitor.isDataStall());
    }

    @Test
    public void testIsDataStall_EvaluationDnsWithDnsTimeoutCount() {
        WrappedNetworkMonitor wrappedMonitor = makeMeteredNetworkMonitor();
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
        wrappedMonitor = makeMeteredNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, 50);
        assertFalse(wrappedMonitor.isDataStall());

        makeDnsTimeoutEvent(wrappedMonitor, 1);
        assertTrue(wrappedMonitor.isDataStall());
    }

    @Test
    public void testIsDataStall_EvaluationDnsWithDnsTimeThreshold() {
        // Test dns events happened in valid dns time threshold.
        WrappedNetworkMonitor wrappedMonitor = makeMeteredNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 100);
        makeDnsTimeoutEvent(wrappedMonitor, DEFAULT_DNS_TIMEOUT_THRESHOLD);
        assertFalse(wrappedMonitor.isDataStall());
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        assertTrue(wrappedMonitor.isDataStall());

        // Test dns events happened before valid dns time threshold.
        setValidDataStallDnsTimeThreshold(0);
        wrappedMonitor = makeMeteredNetworkMonitor();
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

        runFailedNetworkTest();
    }

    @Test
    public void testNoInternetCapabilityValidated() throws Exception {
        runNetworkTest(NO_INTERNET_CAPABILITIES, NETWORK_TEST_RESULT_VALID);
        verify(mNetwork, never()).openConnection(any());
    }

    @Test
    public void testLaunchCaptivePortalApp() throws Exception {
        setSslException(mHttpsConnection);
        setPortal302(mHttpConnection);

        final NetworkMonitor nm = makeMonitor(METERED_CAPABILITIES);
        nm.notifyNetworkConnected(TEST_LINK_PROPERTIES, METERED_CAPABILITIES);

        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .showProvisioningNotification(any(), any());

        assertEquals(1, mRegisteredReceivers.size());

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

        assertEquals(0, mRegisteredReceivers.size());
    }

    @Test
    public void testPrivateDnsSuccess() throws Exception {
        setStatus(mHttpsConnection, 204);
        setStatus(mHttpConnection, 204);
        mFakeDns.setAnswer("dns.google", new String[]{"2001:db8::53"});

        WrappedNetworkMonitor wnm = makeNotMeteredNetworkMonitor();
        wnm.notifyPrivateDnsSettingsChanged(new PrivateDnsConfig("dns.google", new InetAddress[0]));
        wnm.notifyNetworkConnected(TEST_LINK_PROPERTIES, NOT_METERED_CAPABILITIES);
        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(eq(NETWORK_TEST_RESULT_VALID), eq(null));
    }

    @Test
    public void testPrivateDnsResolutionRetryUpdate() throws Exception {
        // Set a private DNS hostname that doesn't resolve and expect validation to fail.
        mFakeDns.setAnswer("dns.google", new String[0]);
        setStatus(mHttpsConnection, 204);
        setStatus(mHttpConnection, 204);

        WrappedNetworkMonitor wnm = makeNotMeteredNetworkMonitor();
        wnm.notifyPrivateDnsSettingsChanged(new PrivateDnsConfig("dns.google", new InetAddress[0]));
        wnm.notifyNetworkConnected(TEST_LINK_PROPERTIES, NOT_METERED_CAPABILITIES);
        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(eq(NETWORK_TEST_RESULT_INVALID), eq(null));

        // Fix DNS and retry, expect validation to succeed.
        reset(mCallbacks);
        mFakeDns.setAnswer("dns.google", new String[]{"2001:db8::1"});

        wnm.forceReevaluation(Process.myUid());
        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(eq(NETWORK_TEST_RESULT_VALID), eq(null));

        // Change configuration to an invalid DNS name, expect validation to fail.
        reset(mCallbacks);
        mFakeDns.setAnswer("dns.bad", new String[0]);
        wnm.notifyPrivateDnsSettingsChanged(new PrivateDnsConfig("dns.bad", new InetAddress[0]));
        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(eq(NETWORK_TEST_RESULT_INVALID), eq(null));

        // Change configuration back to working again, but make private DNS not work.
        // Expect validation to fail.
        reset(mCallbacks);
        mFakeDns.setNonBypassPrivateDnsWorking(false);
        wnm.notifyPrivateDnsSettingsChanged(new PrivateDnsConfig("dns.google", new InetAddress[0]));
        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(eq(NETWORK_TEST_RESULT_INVALID), eq(null));

        // Make private DNS work again. Expect validation to succeed.
        reset(mCallbacks);
        mFakeDns.setNonBypassPrivateDnsWorking(true);
        wnm.forceReevaluation(Process.myUid());
        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(eq(NETWORK_TEST_RESULT_VALID), eq(null));
    }

    @Test
    public void testDataStall_StallSuspectedAndSendMetrics() throws IOException {
        WrappedNetworkMonitor wrappedMonitor = makeNotMeteredNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, 5);
        assertTrue(wrappedMonitor.isDataStall());
        verify(mDataStallStatsUtils, times(1)).write(makeEmptyDataStallDetectionStats(), any());
    }

    @Test
    public void testDataStall_NoStallSuspectedAndSendMetrics() throws IOException {
        WrappedNetworkMonitor wrappedMonitor = makeNotMeteredNetworkMonitor();
        wrappedMonitor.setLastProbeTime(SystemClock.elapsedRealtime() - 1000);
        makeDnsTimeoutEvent(wrappedMonitor, 3);
        assertFalse(wrappedMonitor.isDataStall());
        verify(mDataStallStatsUtils, never()).write(makeEmptyDataStallDetectionStats(), any());
    }

    @Test
    public void testCollectDataStallMetrics() {
        WrappedNetworkMonitor wrappedMonitor = makeNotMeteredNetworkMonitor();

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

    @Test
    public void testIgnoreHttpsProbe() throws Exception {
        setSslException(mHttpsConnection);
        setStatus(mHttpConnection, 204);

        final NetworkMonitor nm = runNetworkTest(NETWORK_TEST_RESULT_PARTIAL_CONNECTIVITY);

        nm.setAcceptPartialConnectivity();
        verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                .notifyNetworkTested(eq(NETWORK_TEST_RESULT_VALID), any());
    }

    @Test
    public void testIsPartialConnectivity() throws IOException {
        setStatus(mHttpsConnection, 500);
        setStatus(mHttpConnection, 204);
        setStatus(mFallbackConnection, 500);
        runPartialConnectivityNetworkTest();

        setStatus(mHttpsConnection, 500);
        setStatus(mHttpConnection, 500);
        setStatus(mFallbackConnection, 204);
        runPartialConnectivityNetworkTest();
    }

    private void assertIpAddressArrayEquals(String[] expected, InetAddress[] actual) {
        String[] actualStrings = new String[actual.length];
        for (int i = 0; i < actual.length; i++) {
            actualStrings[i] = actual[i].getHostAddress();
        }
        assertArrayEquals("Array of IP addresses differs", expected, actualStrings);
    }

    @Test
    public void testSendDnsProbeWithTimeout() throws Exception {
        WrappedNetworkMonitor wnm = makeNotMeteredNetworkMonitor();
        final int shortTimeoutMs = 200;

        // Clear the wildcard DNS response created in setUp.
        mFakeDns.setAnswer("*", null);

        String[] expected = new String[]{"2001:db8::"};
        mFakeDns.setAnswer("www.google.com", expected);
        InetAddress[] actual = wnm.sendDnsProbeWithTimeout("www.google.com", shortTimeoutMs);
        assertIpAddressArrayEquals(expected, actual);

        expected = new String[]{"2001:db8::", "192.0.2.1"};
        mFakeDns.setAnswer("www.googleapis.com", expected);
        actual = wnm.sendDnsProbeWithTimeout("www.googleapis.com", shortTimeoutMs);
        assertIpAddressArrayEquals(expected, actual);

        mFakeDns.setAnswer("www.google.com", new String[0]);
        try {
            wnm.sendDnsProbeWithTimeout("www.google.com", shortTimeoutMs);
            fail("No DNS results, expected UnknownHostException");
        } catch (UnknownHostException e) {
        }

        mFakeDns.setAnswer("www.google.com", null);
        try {
            wnm.sendDnsProbeWithTimeout("www.google.com", shortTimeoutMs);
            fail("DNS query timed out, expected UnknownHostException");
        } catch (UnknownHostException e) {
        }
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
        when(mDependencies.getDeviceConfigPropertyInt(any(),
            eq(CONFIG_DATA_STALL_EVALUATION_TYPE), anyInt())).thenReturn(type);
    }

    private void setMinDataStallEvaluateInterval(int time) {
        when(mDependencies.getDeviceConfigPropertyInt(any(),
            eq(CONFIG_DATA_STALL_MIN_EVALUATE_INTERVAL), anyInt())).thenReturn(time);
    }

    private void setValidDataStallDnsTimeThreshold(int time) {
        when(mDependencies.getDeviceConfigPropertyInt(any(),
            eq(CONFIG_DATA_STALL_VALID_DNS_TIME_THRESHOLD), anyInt())).thenReturn(time);
    }

    private void setConsecutiveDnsTimeoutThreshold(int num) {
        when(mDependencies.getDeviceConfigPropertyInt(any(),
            eq(CONFIG_DATA_STALL_CONSECUTIVE_DNS_TIMEOUT_THRESHOLD), anyInt())).thenReturn(num);
    }

    private void setFallbackUrl(String url) {
        when(mDependencies.getSetting(any(),
                eq(Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL), any())).thenReturn(url);
    }

    private void setOtherFallbackUrls(String urls) {
        when(mDependencies.getDeviceConfigProperty(any(),
                eq(CAPTIVE_PORTAL_OTHER_FALLBACK_URLS), any())).thenReturn(urls);
    }

    private void setFallbackSpecs(String specs) {
        when(mDependencies.getDeviceConfigProperty(any(),
                eq(CAPTIVE_PORTAL_FALLBACK_PROBE_SPECS), any())).thenReturn(specs);
    }

    private void setCaptivePortalMode(int mode) {
        when(mDependencies.getSetting(any(),
                eq(Settings.Global.CAPTIVE_PORTAL_MODE), anyInt())).thenReturn(mode);
    }

    private void runPortalNetworkTest() {
        runNetworkTest(NETWORK_TEST_RESULT_INVALID);
        assertEquals(1, mRegisteredReceivers.size());
        assertNotNull(mNetworkTestedRedirectUrlCaptor.getValue());
    }

    private void runNotPortalNetworkTest() {
        runNetworkTest(NETWORK_TEST_RESULT_VALID);
        assertEquals(0, mRegisteredReceivers.size());
        assertNull(mNetworkTestedRedirectUrlCaptor.getValue());
    }

    private void runFailedNetworkTest() {
        runNetworkTest(NETWORK_TEST_RESULT_INVALID);
        assertEquals(0, mRegisteredReceivers.size());
        assertNull(mNetworkTestedRedirectUrlCaptor.getValue());
    }

    private void runPartialConnectivityNetworkTest() {
        runNetworkTest(NETWORK_TEST_RESULT_PARTIAL_CONNECTIVITY);
        assertEquals(0, mRegisteredReceivers.size());
        assertNull(mNetworkTestedRedirectUrlCaptor.getValue());
    }

    private NetworkMonitor runNetworkTest(int testResult) {
        return runNetworkTest(METERED_CAPABILITIES, testResult);
    }

    private NetworkMonitor runNetworkTest(NetworkCapabilities nc, int testResult) {
        final NetworkMonitor monitor = makeMonitor(nc);
        monitor.notifyNetworkConnected(TEST_LINK_PROPERTIES, nc);
        try {
            verify(mCallbacks, timeout(HANDLER_TIMEOUT_MS).times(1))
                    .notifyNetworkTested(eq(testResult), mNetworkTestedRedirectUrlCaptor.capture());
        } catch (RemoteException e) {
            fail("Unexpected exception: " + e);
        }
        waitForIdle(monitor.getHandler());

        return monitor;
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

