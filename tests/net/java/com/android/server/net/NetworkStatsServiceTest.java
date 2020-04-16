/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.net;

import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_VPN;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.INTERFACES_ALL;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.ROAMING_YES;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.STATS_PER_IFACE;
import static android.net.NetworkStats.STATS_PER_UID;
import static android.net.NetworkStats.TAG_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStatsHistory.FIELD_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.net.NetworkTemplate.buildTemplateMobileWithRatType;
import static android.net.NetworkTemplate.buildTemplateWifiWildcard;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.net.TrafficStats.UID_REMOVED;
import static android.net.TrafficStats.UID_TETHERING;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;

import static com.android.server.net.NetworkStatsService.ACTION_NETWORK_STATS_POLL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.net.DataUsageRequest;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkStatsSession;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.netstats.provider.INetworkStatsProviderCallback;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.SimpleClock;
import android.telephony.TelephonyManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.net.VpnInfo;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.server.net.NetworkStatsService.NetworkStatsSettings;
import com.android.server.net.NetworkStatsService.NetworkStatsSettings.Config;
import com.android.testutils.HandlerUtilsKt;
import com.android.testutils.TestableNetworkStatsProviderBinder;

import libcore.io.IoUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Tests for {@link NetworkStatsService}.
 *
 * TODO: This test used to be really brittle because it used Easymock - it uses Mockito now, but
 * still uses the Easymock structure, which could be simplified.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkStatsServiceTest extends NetworkStatsBaseTest {
    private static final String TAG = "NetworkStatsServiceTest";

    private static final long TEST_START = 1194220800000L;

    private static final String IMSI_1 = "310004";
    private static final String IMSI_2 = "310260";
    private static final String TEST_SSID = "AndroidAP";

    private static NetworkTemplate sTemplateWifi = buildTemplateWifiWildcard();
    private static NetworkTemplate sTemplateImsi1 = buildTemplateMobileAll(IMSI_1);
    private static NetworkTemplate sTemplateImsi2 = buildTemplateMobileAll(IMSI_2);

    private static final Network WIFI_NETWORK =  new Network(100);
    private static final Network MOBILE_NETWORK =  new Network(101);
    private static final Network VPN_NETWORK = new Network(102);

    private static final Network[] NETWORKS_WIFI = new Network[]{ WIFI_NETWORK };
    private static final Network[] NETWORKS_MOBILE = new Network[]{ MOBILE_NETWORK };

    private static final long WAIT_TIMEOUT = 2 * 1000;  // 2 secs
    private static final int INVALID_TYPE = -1;

    private long mElapsedRealtime;

    private BroadcastInterceptingContext mServiceContext;
    private File mStatsDir;

    private @Mock INetworkManagementService mNetManager;
    private @Mock NetworkStatsFactory mStatsFactory;
    private @Mock NetworkStatsSettings mSettings;
    private @Mock IBinder mBinder;
    private @Mock AlarmManager mAlarmManager;
    @Mock
    private NetworkStatsSubscriptionsMonitor mNetworkStatsSubscriptionsMonitor;
    private HandlerThread mHandlerThread;

    private NetworkStatsService mService;
    private INetworkStatsSession mSession;
    private INetworkManagementEventObserver mNetworkObserver;

    private final Clock mClock = new SimpleClock(ZoneOffset.UTC) {
        @Override
        public long millis() {
            return currentTimeMillis();
        }
    };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        final Context context = InstrumentationRegistry.getContext();

        mServiceContext = new BroadcastInterceptingContext(context);
        mStatsDir = context.getFilesDir();
        if (mStatsDir.exists()) {
            IoUtils.deleteContents(mStatsDir);
        }

        PowerManager powerManager = (PowerManager) mServiceContext.getSystemService(
                Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock =
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mHandlerThread = new HandlerThread("HandlerThread");
        final NetworkStatsService.Dependencies deps = makeDependencies();
        mService = new NetworkStatsService(mServiceContext, mNetManager, mAlarmManager, wakeLock,
                mClock, mSettings, mStatsFactory, new NetworkStatsObservers(), mStatsDir,
                getBaseDir(mStatsDir), deps);

        mElapsedRealtime = 0L;

        expectDefaultSettings();
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectSystemReady();

        mService.systemReady();
        // Verify that system ready fetches realtime stats
        verify(mStatsFactory).readNetworkStatsDetail(UID_ALL, INTERFACES_ALL, TAG_ALL);

        mSession = mService.openSession();
        assertNotNull("openSession() failed", mSession);

        // catch INetworkManagementEventObserver during systemReady()
        ArgumentCaptor<INetworkManagementEventObserver> networkObserver =
                ArgumentCaptor.forClass(INetworkManagementEventObserver.class);
        verify(mNetManager).registerObserver(networkObserver.capture());
        mNetworkObserver = networkObserver.getValue();
    }

    @NonNull
    private NetworkStatsService.Dependencies makeDependencies() {
        return new NetworkStatsService.Dependencies() {
            @Override
            public HandlerThread makeHandlerThread() {
                return mHandlerThread;
            }

            @Override
            public NetworkStatsSubscriptionsMonitor makeSubscriptionsMonitor(
                    @NonNull Context context, @NonNull Executor executor,
                    @NonNull NetworkStatsService service) {

                return mNetworkStatsSubscriptionsMonitor;
            }
        };
    }

    @After
    public void tearDown() throws Exception {
        IoUtils.deleteContents(mStatsDir);

        mServiceContext = null;
        mStatsDir = null;

        mNetManager = null;
        mSettings = null;

        mSession.close();
        mService = null;

        mHandlerThread.quitSafely();
    }

    @Test
    public void testNetworkStatsWifi() throws Exception {
        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildWifiState()};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // verify service has empty history for wifi
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);


        // modify some number on wifi, and trigger poll event
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 1024L, 1L, 2048L, 2L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        forcePollAndWaitForIdle();

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 1024L, 1L, 2048L, 2L, 0);


        // and bump forward again, with counters going higher. this is
        // important, since polling should correctly subtract last snapshot.
        incrementCurrentTime(DAY_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 4096L, 4L, 8192L, 8L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        forcePollAndWaitForIdle();

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 4096L, 4L, 8192L, 8L, 0);

    }

    @Test
    public void testStatsRebootPersist() throws Exception {
        assertStatsFilesExist(false);

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildWifiState()};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // verify service has empty history for wifi
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);


        // modify some number on wifi, and trigger poll event
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 1024L, 8L, 2048L, 16L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 2)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 512L, 4L, 256L, 2L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xFAAD, 256L, 2L, 128L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_FOREGROUND, TAG_NONE, 512L, 4L, 256L, 2L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_FOREGROUND, 0xFAAD, 256L, 2L, 128L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 128L, 1L, 128L, 1L, 0L));
        mService.setUidForeground(UID_RED, false);
        mService.incrementOperationCount(UID_RED, 0xFAAD, 4);
        mService.setUidForeground(UID_RED, true);
        mService.incrementOperationCount(UID_RED, 0xFAAD, 6);

        forcePollAndWaitForIdle();

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 1024L, 8L, 2048L, 16L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 1024L, 8L, 512L, 4L, 10);
        assertUidTotal(sTemplateWifi, UID_RED, SET_DEFAULT, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 512L, 4L, 256L, 2L, 4);
        assertUidTotal(sTemplateWifi, UID_RED, SET_FOREGROUND, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 512L, 4L, 256L, 2L, 6);
        assertUidTotal(sTemplateWifi, UID_BLUE, 128L, 1L, 128L, 1L, 0);


        // graceful shutdown system, which should trigger persist of stats, and
        // clear any values in memory.
        expectDefaultSettings();
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SHUTDOWN));
        assertStatsFilesExist(true);

        // boot through serviceReady() again
        expectDefaultSettings();
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectSystemReady();

        mService.systemReady();

        // after systemReady(), we should have historical stats loaded again
        assertNetworkTotal(sTemplateWifi, 1024L, 8L, 2048L, 16L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 1024L, 8L, 512L, 4L, 10);
        assertUidTotal(sTemplateWifi, UID_RED, SET_DEFAULT, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 512L, 4L, 256L, 2L, 4);
        assertUidTotal(sTemplateWifi, UID_RED, SET_FOREGROUND, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 512L, 4L, 256L, 2L, 6);
        assertUidTotal(sTemplateWifi, UID_BLUE, 128L, 1L, 128L, 1L, 0);

    }

    // TODO: simulate reboot to test bucket resize
    @Test
    @Ignore
    public void testStatsBucketResize() throws Exception {
        NetworkStatsHistory history = null;

        assertStatsFilesExist(false);

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
        NetworkState[] states = new NetworkState[] {buildWifiState()};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // modify some number on wifi, and trigger poll event
        incrementCurrentTime(2 * HOUR_IN_MILLIS);
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 512L, 4L, 512L, 4L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        forcePollAndWaitForIdle();

        // verify service recorded history
        history = mSession.getHistoryForNetwork(sTemplateWifi, FIELD_ALL);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, 512L, 4L, 512L, 4L, 0);
        assertEquals(HOUR_IN_MILLIS, history.getBucketDuration());
        assertEquals(2, history.size());


        // now change bucket duration setting and trigger another poll with
        // exact same values, which should resize existing buckets.
        expectSettings(0L, 30 * MINUTE_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        forcePollAndWaitForIdle();

        // verify identical stats, but spread across 4 buckets now
        history = mSession.getHistoryForNetwork(sTemplateWifi, FIELD_ALL);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, 512L, 4L, 512L, 4L, 0);
        assertEquals(30 * MINUTE_IN_MILLIS, history.getBucketDuration());
        assertEquals(4, history.size());

    }

    @Test
    public void testUidStatsAcrossNetworks() throws Exception {
        // pretend first mobile network comes online
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildMobile3gState(IMSI_1)};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_MOBILE, states, getActiveIface(states), new VpnInfo[0]);

        // create some traffic on first network
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 2048L, 16L, 512L, 4L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 3)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1536L, 12L, 512L, 4L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 512L, 4L, 0L, 0L, 0L));
        mService.incrementOperationCount(UID_RED, 0xF00D, 10);

        forcePollAndWaitForIdle();

        // verify service recorded history
        assertNetworkTotal(sTemplateImsi1, 2048L, 16L, 512L, 4L, 0);
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 512L, 4L, 10);
        assertUidTotal(sTemplateImsi1, UID_BLUE, 512L, 4L, 0L, 0L, 0);


        // now switch networks; this also tests that we're okay with interfaces
        // disappearing, to verify we don't count backwards.
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        states = new NetworkState[] {buildMobile3gState(IMSI_2)};
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 2048L, 16L, 512L, 4L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 3)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1536L, 12L, 512L, 4L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 512L, 4L, 0L, 0L, 0L));

        mService.forceUpdateIfaces(NETWORKS_MOBILE, states, getActiveIface(states), new VpnInfo[0]);
        forcePollAndWaitForIdle();


        // create traffic on second network
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 2176L, 17L, 1536L, 12L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1536L, 12L, 512L, 4L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 640L, 5L, 1024L, 8L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, 0xFAAD, 128L, 1L, 1024L, 8L, 0L));
        mService.incrementOperationCount(UID_BLUE, 0xFAAD, 10);

        forcePollAndWaitForIdle();

        // verify original history still intact
        assertNetworkTotal(sTemplateImsi1, 2048L, 16L, 512L, 4L, 0);
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 512L, 4L, 10);
        assertUidTotal(sTemplateImsi1, UID_BLUE, 512L, 4L, 0L, 0L, 0);

        // and verify new history also recorded under different template, which
        // verifies that we didn't cross the streams.
        assertNetworkTotal(sTemplateImsi2, 128L, 1L, 1024L, 8L, 0);
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateImsi2, UID_BLUE, 128L, 1L, 1024L, 8L, 10);

    }

    @Test
    public void testUidRemovedIsMoved() throws Exception {
        // pretend that network comes online
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildWifiState()};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // create some traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 4128L, 258L, 544L, 34L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 16L, 1L, 16L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xFAAD, 16L, 1L, 16L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE,
                        4096L, 258L, 512L, 32L, 0L)
                .insertEntry(TEST_IFACE, UID_GREEN, SET_DEFAULT, TAG_NONE, 16L, 1L, 16L, 1L, 0L));
        mService.incrementOperationCount(UID_RED, 0xFAAD, 10);

        forcePollAndWaitForIdle();

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 4128L, 258L, 544L, 34L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 16L, 1L, 16L, 1L, 10);
        assertUidTotal(sTemplateWifi, UID_BLUE, 4096L, 258L, 512L, 32L, 0);
        assertUidTotal(sTemplateWifi, UID_GREEN, 16L, 1L, 16L, 1L, 0);


        // now pretend two UIDs are uninstalled, which should migrate stats to
        // special "removed" bucket.
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 4128L, 258L, 544L, 34L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 16L, 1L, 16L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xFAAD, 16L, 1L, 16L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE,
                        4096L, 258L, 512L, 32L, 0L)
                .insertEntry(TEST_IFACE, UID_GREEN, SET_DEFAULT, TAG_NONE, 16L, 1L, 16L, 1L, 0L));
        final Intent intent = new Intent(ACTION_UID_REMOVED);
        intent.putExtra(EXTRA_UID, UID_BLUE);
        mServiceContext.sendBroadcast(intent);
        intent.putExtra(EXTRA_UID, UID_RED);
        mServiceContext.sendBroadcast(intent);

        // existing uid and total should remain unchanged; but removed UID
        // should be gone completely.
        assertNetworkTotal(sTemplateWifi, 4128L, 258L, 544L, 34L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateWifi, UID_BLUE, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateWifi, UID_GREEN, 16L, 1L, 16L, 1L, 0);
        assertUidTotal(sTemplateWifi, UID_REMOVED, 4112L, 259L, 528L, 33L, 10);

    }

    @Test
    public void testUid3gWimaxCombinedByTemplate() throws Exception {
        // pretend that network comes online
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildMobile3gState(IMSI_1)};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_MOBILE, states, getActiveIface(states), new VpnInfo[0]);

        // create some traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L));
        mService.incrementOperationCount(UID_RED, 0xF00D, 5);

        forcePollAndWaitForIdle();

        // verify service recorded history
        assertUidTotal(sTemplateImsi1, UID_RED, 1024L, 8L, 1024L, 8L, 5);


        // now switch over to wimax network
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        states = new NetworkState[] {buildWimaxState(TEST_IFACE2)};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L));

        mService.forceUpdateIfaces(NETWORKS_MOBILE, states, getActiveIface(states), new VpnInfo[0]);
        forcePollAndWaitForIdle();


        // create traffic on second network
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L)
                .insertEntry(TEST_IFACE2, UID_RED, SET_DEFAULT, TAG_NONE, 512L, 4L, 256L, 2L, 0L)
                .insertEntry(TEST_IFACE2, UID_RED, SET_DEFAULT, 0xFAAD, 512L, 4L, 256L, 2L, 0L));
        mService.incrementOperationCount(UID_RED, 0xFAAD, 5);

        forcePollAndWaitForIdle();

        // verify that ALL_MOBILE template combines both
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 1280L, 10L, 10);
    }

    @Test
    public void testMobileStatsByRatType() throws Exception {
        final NetworkTemplate template3g =
                buildTemplateMobileWithRatType(null, TelephonyManager.NETWORK_TYPE_UMTS);
        final NetworkTemplate template4g =
                buildTemplateMobileWithRatType(null, TelephonyManager.NETWORK_TYPE_LTE);
        final NetworkTemplate template5g =
                buildTemplateMobileWithRatType(null, TelephonyManager.NETWORK_TYPE_NR);
        final NetworkState[] states = new NetworkState[]{buildMobile3gState(IMSI_1)};

        // 3G network comes online.
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        setMobileRatTypeAndWaitForIdle(TelephonyManager.NETWORK_TYPE_UMTS);
        mService.forceUpdateIfaces(NETWORKS_MOBILE, states, getActiveIface(states),
                new VpnInfo[0]);

        // Create some traffic.
        incrementCurrentTime(MINUTE_IN_MILLIS);
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addEntry(new NetworkStats.Entry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE,
                        12L, 18L, 14L, 1L, 0L)));
        forcePollAndWaitForIdle();

        // Verify 3g templates gets stats.
        assertUidTotal(sTemplateImsi1, UID_RED, 12L, 18L, 14L, 1L, 0);
        assertUidTotal(template3g, UID_RED, 12L, 18L, 14L, 1L, 0);
        assertUidTotal(template4g, UID_RED, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(template5g, UID_RED, 0L, 0L, 0L, 0L, 0);

        // 4G network comes online.
        incrementCurrentTime(MINUTE_IN_MILLIS);
        setMobileRatTypeAndWaitForIdle(TelephonyManager.NETWORK_TYPE_LTE);
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                // Append more traffic on existing 3g stats entry.
                .addEntry(new NetworkStats.Entry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE,
                        16L, 22L, 17L, 2L, 0L))
                // Add entry that is new on 4g.
                .addEntry(new NetworkStats.Entry(TEST_IFACE, UID_RED, SET_FOREGROUND, TAG_NONE,
                        33L, 27L, 8L, 10L, 1L)));
        forcePollAndWaitForIdle();

        // Verify ALL_MOBILE template gets all. 3g template counters do not increase.
        assertUidTotal(sTemplateImsi1, UID_RED, 49L, 49L, 25L, 12L, 1);
        assertUidTotal(template3g, UID_RED, 12L, 18L, 14L, 1L, 0);
        // Verify 4g template counts appended stats on existing entry and newly created entry.
        assertUidTotal(template4g, UID_RED, 4L + 33L, 4L + 27L, 3L + 8L, 1L + 10L, 1);
        // Verify 5g template doesn't get anything since no traffic is generated on 5g.
        assertUidTotal(template5g, UID_RED, 0L, 0L, 0L, 0L, 0);

        // 5g network comes online.
        incrementCurrentTime(MINUTE_IN_MILLIS);
        setMobileRatTypeAndWaitForIdle(TelephonyManager.NETWORK_TYPE_NR);
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                // Existing stats remains.
                .addEntry(new NetworkStats.Entry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE,
                        16L, 22L, 17L, 2L, 0L))
                .addEntry(new NetworkStats.Entry(TEST_IFACE, UID_RED, SET_FOREGROUND, TAG_NONE,
                        33L, 27L, 8L, 10L, 1L))
                // Add some traffic on 5g.
                .addEntry(new NetworkStats.Entry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE,
                5L, 13L, 31L, 9L, 2L)));
        forcePollAndWaitForIdle();

        // Verify ALL_MOBILE template gets all.
        assertUidTotal(sTemplateImsi1, UID_RED, 54L, 62L, 56L, 21L, 3);
        // 3g/4g template counters do not increase.
        assertUidTotal(template3g, UID_RED, 12L, 18L, 14L, 1L, 0);
        assertUidTotal(template4g, UID_RED, 4L + 33L, 4L + 27L, 3L + 8L, 1L + 10L, 1);
        // Verify 5g template gets the 5g count.
        assertUidTotal(template5g, UID_RED, 5L, 13L, 31L, 9L, 2);
    }

    // TODO: support per IMSI state
    private void setMobileRatTypeAndWaitForIdle(int ratType) {
        when(mNetworkStatsSubscriptionsMonitor.getRatTypeForSubscriberId(anyString()))
                .thenReturn(ratType);
        mService.handleOnCollapsedRatTypeChanged();
        HandlerUtilsKt.waitForIdle(mHandlerThread, WAIT_TIMEOUT);
    }

    @Test
    public void testSummaryForAllUid() throws Exception {
        // pretend that network comes online
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildWifiState()};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // create some traffic for two apps
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 50L, 5L, 50L, 5L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 10L, 1L, 10L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 1024L, 8L, 512L, 4L, 0L));
        mService.incrementOperationCount(UID_RED, 0xF00D, 1);

        forcePollAndWaitForIdle();

        // verify service recorded history
        assertUidTotal(sTemplateWifi, UID_RED, 50L, 5L, 50L, 5L, 1);
        assertUidTotal(sTemplateWifi, UID_BLUE, 1024L, 8L, 512L, 4L, 0);


        // now create more traffic in next hour, but only for one app
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 50L, 5L, 50L, 5L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 10L, 1L, 10L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE,
                        2048L, 16L, 1024L, 8L, 0L));
        forcePollAndWaitForIdle();

        // first verify entire history present
        NetworkStats stats = mSession.getSummaryForAllUid(
                sTemplateWifi, Long.MIN_VALUE, Long.MAX_VALUE, true);
        assertEquals(3, stats.size());
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 50L, 5L, 50L, 5L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, 0xF00D, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 10L, 1L, 10L, 1L, 1);
        assertValues(stats, IFACE_ALL, UID_BLUE, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 2048L, 16L, 1024L, 8L, 0);

        // now verify that recent history only contains one uid
        final long currentTime = currentTimeMillis();
        stats = mSession.getSummaryForAllUid(
                sTemplateWifi, currentTime - HOUR_IN_MILLIS, currentTime, true);
        assertEquals(1, stats.size());
        assertValues(stats, IFACE_ALL, UID_BLUE, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 1024L, 8L, 512L, 4L, 0);
    }

    @Test
    public void testDetailedUidStats() throws Exception {
        // pretend that network comes online
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildWifiState()};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        NetworkStats.Entry entry1 = new NetworkStats.Entry(
                TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 50L, 5L, 50L, 5L, 0L);
        NetworkStats.Entry entry2 = new NetworkStats.Entry(
                TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 50L, 5L, 50L, 5L, 0L);
        NetworkStats.Entry entry3 = new NetworkStats.Entry(
                TEST_IFACE, UID_BLUE, SET_DEFAULT, 0xBEEF, 1024L, 8L, 512L, 4L, 0L);

        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 3)
                .insertEntry(entry1)
                .insertEntry(entry2)
                .insertEntry(entry3));
        mService.incrementOperationCount(UID_RED, 0xF00D, 1);

        NetworkStats stats = mService.getDetailedUidStats(INTERFACES_ALL);

        assertEquals(3, stats.size());
        entry1.operations = 1;
        assertEquals(entry1, stats.getValues(0, null));
        entry2.operations = 1;
        assertEquals(entry2, stats.getValues(1, null));
        assertEquals(entry3, stats.getValues(2, null));
    }

    @Test
    public void testDetailedUidStats_Filtered() throws Exception {
        // pretend that network comes online
        expectDefaultSettings();

        final String stackedIface = "stacked-test0";
        final LinkProperties stackedProp = new LinkProperties();
        stackedProp.setInterfaceName(stackedIface);
        final NetworkState wifiState = buildWifiState();
        wifiState.linkProperties.addStackedLink(stackedProp);
        NetworkState[] states = new NetworkState[] {wifiState};

        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        NetworkStats.Entry uidStats = new NetworkStats.Entry(
                TEST_IFACE, UID_BLUE, SET_DEFAULT, 0xF00D, 1024L, 8L, 512L, 4L, 0L);
        // Stacked on matching interface
        NetworkStats.Entry tetheredStats1 = new NetworkStats.Entry(
                stackedIface, UID_BLUE, SET_DEFAULT, 0xF00D, 1024L, 8L, 512L, 4L, 0L);
        // Different interface
        NetworkStats.Entry tetheredStats2 = new NetworkStats.Entry(
                "otherif", UID_BLUE, SET_DEFAULT, 0xF00D, 1024L, 8L, 512L, 4L, 0L);

        final String[] ifaceFilter = new String[] { TEST_IFACE };
        final String[] augmentedIfaceFilter = new String[] { stackedIface, TEST_IFACE };
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        when(mStatsFactory.augmentWithStackedInterfaces(eq(ifaceFilter)))
                .thenReturn(augmentedIfaceFilter);
        when(mStatsFactory.readNetworkStatsDetail(eq(UID_ALL), any(), eq(TAG_ALL)))
                .thenReturn(new NetworkStats(getElapsedRealtime(), 1)
                        .insertEntry(uidStats));
        when(mNetManager.getNetworkStatsTethering(STATS_PER_UID))
                .thenReturn(new NetworkStats(getElapsedRealtime(), 2)
                        .insertEntry(tetheredStats1)
                        .insertEntry(tetheredStats2));

        NetworkStats stats = mService.getDetailedUidStats(ifaceFilter);

        // mStatsFactory#readNetworkStatsDetail() has the following invocations:
        // 1) NetworkStatsService#systemReady from #setUp.
        // 2) mService#forceUpdateIfaces in the test above.
        //
        // Additionally, we should have one call from the above call to mService#getDetailedUidStats
        // with the augmented ifaceFilter.
        verify(mStatsFactory, times(2)).readNetworkStatsDetail(UID_ALL, INTERFACES_ALL, TAG_ALL);
        verify(mStatsFactory, times(1)).readNetworkStatsDetail(
                eq(UID_ALL),
                eq(augmentedIfaceFilter),
                eq(TAG_ALL));
        assertTrue(ArrayUtils.contains(stats.getUniqueIfaces(), TEST_IFACE));
        assertTrue(ArrayUtils.contains(stats.getUniqueIfaces(), stackedIface));
        assertEquals(2, stats.size());
        assertEquals(uidStats, stats.getValues(0, null));
        assertEquals(tetheredStats1, stats.getValues(1, null));
    }

    @Test
    public void testForegroundBackground() throws Exception {
        // pretend that network comes online
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildWifiState()};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // create some initial traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 128L, 2L, 128L, 2L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 64L, 1L, 64L, 1L, 0L));
        mService.incrementOperationCount(UID_RED, 0xF00D, 1);

        forcePollAndWaitForIdle();

        // verify service recorded history
        assertUidTotal(sTemplateWifi, UID_RED, 128L, 2L, 128L, 2L, 1);


        // now switch to foreground
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 128L, 2L, 128L, 2L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 64L, 1L, 64L, 1L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_FOREGROUND, TAG_NONE, 32L, 2L, 32L, 2L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_FOREGROUND, 0xFAAD, 1L, 1L, 1L, 1L, 0L));
        mService.setUidForeground(UID_RED, true);
        mService.incrementOperationCount(UID_RED, 0xFAAD, 1);

        forcePollAndWaitForIdle();

        // test that we combined correctly
        assertUidTotal(sTemplateWifi, UID_RED, 160L, 4L, 160L, 4L, 2);

        // verify entire history present
        final NetworkStats stats = mSession.getSummaryForAllUid(
                sTemplateWifi, Long.MIN_VALUE, Long.MAX_VALUE, true);
        assertEquals(4, stats.size());
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 128L, 2L, 128L, 2L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, 0xF00D, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 64L, 1L, 64L, 1L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_FOREGROUND, TAG_NONE, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 32L, 2L, 32L, 2L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_FOREGROUND, 0xFAAD, METERED_NO, ROAMING_NO,
                DEFAULT_NETWORK_YES, 1L, 1L, 1L, 1L, 1);
    }

    @Test
    public void testMetered() throws Exception {
        // pretend that network comes online
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildWifiState(true /* isMetered */)};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // create some initial traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        // Note that all traffic from NetworkManagementService is tagged as METERED_NO, ROAMING_NO
        // and DEFAULT_NETWORK_YES, because these three properties aren't tracked at that layer.
        // We layer them on top by inspecting the iface properties.
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 128L, 2L, 128L, 2L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, METERED_NO, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 64L, 1L, 64L, 1L, 0L));
        mService.incrementOperationCount(UID_RED, 0xF00D, 1);

        forcePollAndWaitForIdle();

        // verify service recorded history
        assertUidTotal(sTemplateWifi, UID_RED, 128L, 2L, 128L, 2L, 1);
        // verify entire history present
        final NetworkStats stats = mSession.getSummaryForAllUid(
                sTemplateWifi, Long.MIN_VALUE, Long.MAX_VALUE, true);
        assertEquals(2, stats.size());
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                DEFAULT_NETWORK_YES,  128L, 2L, 128L, 2L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, 0xF00D, METERED_YES, ROAMING_NO,
                DEFAULT_NETWORK_YES, 64L, 1L, 64L, 1L, 1);
    }

    @Test
    public void testRoaming() throws Exception {
        // pretend that network comes online
        expectDefaultSettings();
        NetworkState[] states =
            new NetworkState[] {buildMobile3gState(IMSI_1, true /* isRoaming */)};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_MOBILE, states, getActiveIface(states), new VpnInfo[0]);

        // Create some traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        // Note that all traffic from NetworkManagementService is tagged as METERED_NO and
        // ROAMING_NO, because metered and roaming isn't tracked at that layer. We layer it
        // on top by inspecting the iface properties.
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, METERED_ALL, ROAMING_NO,
                        DEFAULT_NETWORK_YES,  128L, 2L, 128L, 2L, 0L)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, METERED_ALL, ROAMING_NO,
                        DEFAULT_NETWORK_YES, 64L, 1L, 64L, 1L, 0L));
        forcePollAndWaitForIdle();

        // verify service recorded history
        assertUidTotal(sTemplateImsi1, UID_RED, 128L, 2L, 128L, 2L, 0);

        // verify entire history present
        final NetworkStats stats = mSession.getSummaryForAllUid(
                sTemplateImsi1, Long.MIN_VALUE, Long.MAX_VALUE, true);
        assertEquals(2, stats.size());
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, TAG_NONE, METERED_ALL, ROAMING_YES,
                DEFAULT_NETWORK_YES, 128L, 2L, 128L, 2L, 0);
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, 0xF00D, METERED_ALL, ROAMING_YES,
                DEFAULT_NETWORK_YES, 64L, 1L, 64L, 1L, 0);
    }

    @Test
    public void testTethering() throws Exception {
        // pretend first mobile network comes online
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildMobile3gState(IMSI_1)};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_MOBILE, states, getActiveIface(states), new VpnInfo[0]);

        // create some tethering traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();

        // Traffic seen by kernel counters (includes software tethering).
        final NetworkStats ifaceStats = new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 1536L, 12L, 384L, 3L);
        // Hardware tethering traffic, not seen by kernel counters.
        final NetworkStats tetherStatsHardware = new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 512L, 4L, 128L, 1L);

        // Traffic for UID_RED.
        final NetworkStats uidStats = new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 128L, 2L, 128L, 2L, 0L);
        // All tethering traffic, both hardware and software.
        final NetworkStats tetherStats = new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, UID_TETHERING, SET_DEFAULT, TAG_NONE, 1920L, 14L, 384L, 2L,
                        0L);

        expectNetworkStatsSummary(ifaceStats, tetherStatsHardware);
        expectNetworkStatsUidDetail(uidStats, tetherStats);
        forcePollAndWaitForIdle();

        // verify service recorded history
        assertNetworkTotal(sTemplateImsi1, 2048L, 16L, 512L, 4L, 0);
        assertUidTotal(sTemplateImsi1, UID_RED, 128L, 2L, 128L, 2L, 0);
        assertUidTotal(sTemplateImsi1, UID_TETHERING, 1920L, 14L, 384L, 2L, 0);
    }

    @Test
    public void testRegisterUsageCallback() throws Exception {
        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[] {buildWifiState()};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // verify service has empty history for wifi
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        long thresholdInBytes = 1L;  // very small; should be overriden by framework
        DataUsageRequest inputRequest = new DataUsageRequest(
                DataUsageRequest.REQUEST_ID_UNSET, sTemplateWifi, thresholdInBytes);

        // Create a messenger that waits for callback activity
        ConditionVariable cv = new ConditionVariable(false);
        LatchedHandler latchedHandler = new LatchedHandler(Looper.getMainLooper(), cv);
        Messenger messenger = new Messenger(latchedHandler);

        // Force poll
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        // Register and verify request and that binder was called
        DataUsageRequest request =
                mService.registerUsageCallback(mServiceContext.getOpPackageName(), inputRequest,
                        messenger, mBinder);
        assertTrue(request.requestId > 0);
        assertTrue(Objects.equals(sTemplateWifi, request.template));
        long minThresholdInBytes = 2 * 1024 * 1024; // 2 MB
        assertEquals(minThresholdInBytes, request.thresholdInBytes);

        HandlerUtilsKt.waitForIdle(mHandlerThread, WAIT_TIMEOUT);

        // Make sure that the caller binder gets connected
        verify(mBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        // modify some number on wifi, and trigger poll event
        // not enough traffic to call data usage callback
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 1024L, 1L, 2048L, 2L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        forcePollAndWaitForIdle();

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 1024L, 1L, 2048L, 2L, 0);

        // make sure callback has not being called
        assertEquals(INVALID_TYPE, latchedHandler.lastMessageType);

        // and bump forward again, with counters going higher. this is
        // important, since it will trigger the data usage callback
        incrementCurrentTime(DAY_IN_MILLIS);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .insertEntry(TEST_IFACE, 4096000L, 4L, 8192000L, 8L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        forcePollAndWaitForIdle();

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 4096000L, 4L, 8192000L, 8L, 0);


        // Wait for the caller to ack receipt of CALLBACK_LIMIT_REACHED
        assertTrue(cv.block(WAIT_TIMEOUT));
        assertEquals(NetworkStatsManager.CALLBACK_LIMIT_REACHED, latchedHandler.lastMessageType);
        cv.close();

        // Allow binder to disconnect
        when(mBinder.unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt())).thenReturn(true);

        // Unregister request
        mService.unregisterUsageRequest(request);

        // Wait for the caller to ack receipt of CALLBACK_RELEASED
        assertTrue(cv.block(WAIT_TIMEOUT));
        assertEquals(NetworkStatsManager.CALLBACK_RELEASED, latchedHandler.lastMessageType);

        // Make sure that the caller binder gets disconnected
        verify(mBinder).unlinkToDeath(any(IBinder.DeathRecipient.class), anyInt());
    }

    @Test
    public void testUnregisterUsageCallback_unknown_noop() throws Exception {
        String callingPackage = "the.calling.package";
        long thresholdInBytes = 10 * 1024 * 1024;  // 10 MB
        DataUsageRequest unknownRequest = new DataUsageRequest(
                2 /* requestId */, sTemplateImsi1, thresholdInBytes);

        mService.unregisterUsageRequest(unknownRequest);
    }

    @Test
    public void testStatsProviderUpdateStats() throws Exception {
        // Pretend that network comes online.
        expectDefaultSettings();
        final NetworkState[] states = new NetworkState[]{buildWifiState(true /* isMetered */)};
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());

        // Register custom provider and retrieve callback.
        final TestableNetworkStatsProviderBinder provider =
                new TestableNetworkStatsProviderBinder();
        final INetworkStatsProviderCallback cb =
                mService.registerNetworkStatsProvider("TEST", provider);
        assertNotNull(cb);

        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // Verifies that one requestStatsUpdate will be called during iface update.
        provider.expectOnRequestStatsUpdate(0 /* unused */);

        // Create some initial traffic and report to the service.
        incrementCurrentTime(HOUR_IN_MILLIS);
        final NetworkStats expectedStats = new NetworkStats(0L, 1)
                .addEntry(new NetworkStats.Entry(TEST_IFACE, UID_RED, SET_DEFAULT,
                        TAG_NONE, METERED_YES, ROAMING_NO, DEFAULT_NETWORK_YES,
                        128L, 2L, 128L, 2L, 1L))
                .addEntry(new NetworkStats.Entry(TEST_IFACE, UID_RED, SET_DEFAULT,
                        0xF00D, METERED_YES, ROAMING_NO, DEFAULT_NETWORK_YES,
                        64L, 1L, 64L, 1L, 1L));
        cb.notifyStatsUpdated(0 /* unused */, expectedStats, expectedStats);

        // Make another empty mutable stats object. This is necessary since the new NetworkStats
        // object will be used to compare with the old one in NetworkStatsRecoder, two of them
        // cannot be the same object.
        expectNetworkStatsUidDetail(buildEmptyStats());

        forcePollAndWaitForIdle();

        // Verifies that one requestStatsUpdate and setAlert will be called during polling.
        provider.expectOnRequestStatsUpdate(0 /* unused */);
        provider.expectOnSetAlert(MB_IN_BYTES);

        // Verifies that service recorded history, does not verify uid tag part.
        assertUidTotal(sTemplateWifi, UID_RED, 128L, 2L, 128L, 2L, 1);

        // Verifies that onStatsUpdated updates the stats accordingly.
        final NetworkStats stats = mSession.getSummaryForAllUid(
                sTemplateWifi, Long.MIN_VALUE, Long.MAX_VALUE, true);
        assertEquals(2, stats.size());
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, TAG_NONE, METERED_YES, ROAMING_NO,
                DEFAULT_NETWORK_YES, 128L, 2L, 128L, 2L, 1L);
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, 0xF00D, METERED_YES, ROAMING_NO,
                DEFAULT_NETWORK_YES, 64L, 1L, 64L, 1L, 1L);

        // Verifies that unregister the callback will remove the provider from service.
        cb.unregister();
        forcePollAndWaitForIdle();
        provider.assertNoCallback();
    }

    @Test
    public void testStatsProviderSetAlert() throws Exception {
        // Pretend that network comes online.
        expectDefaultSettings();
        NetworkState[] states = new NetworkState[]{buildWifiState(true /* isMetered */)};
        mService.forceUpdateIfaces(NETWORKS_WIFI, states, getActiveIface(states), new VpnInfo[0]);

        // Register custom provider and retrieve callback.
        final TestableNetworkStatsProviderBinder provider =
                new TestableNetworkStatsProviderBinder();
        final INetworkStatsProviderCallback cb =
                mService.registerNetworkStatsProvider("TEST", provider);
        assertNotNull(cb);

        // Simulates alert quota of the provider has been reached.
        cb.notifyAlertReached();
        HandlerUtilsKt.waitForIdle(mHandlerThread, WAIT_TIMEOUT);

        // Verifies that polling is triggered by alert reached.
        provider.expectOnRequestStatsUpdate(0 /* unused */);
        // Verifies that global alert will be re-armed.
        provider.expectOnSetAlert(MB_IN_BYTES);
    }

    private static File getBaseDir(File statsDir) {
        File baseDir = new File(statsDir, "netstats");
        baseDir.mkdirs();
        return baseDir;
    }

    private void assertNetworkTotal(NetworkTemplate template, long rxBytes, long rxPackets,
            long txBytes, long txPackets, int operations) throws Exception {
        assertNetworkTotal(template, Long.MIN_VALUE, Long.MAX_VALUE, rxBytes, rxPackets, txBytes,
                txPackets, operations);
    }

    private void assertNetworkTotal(NetworkTemplate template, long start, long end, long rxBytes,
            long rxPackets, long txBytes, long txPackets, int operations) throws Exception {
        // verify history API
        final NetworkStatsHistory history = mSession.getHistoryForNetwork(template, FIELD_ALL);
        assertValues(history, start, end, rxBytes, rxPackets, txBytes, txPackets, operations);

        // verify summary API
        final NetworkStats stats = mSession.getSummaryForNetwork(template, start, end);
        assertValues(stats, IFACE_ALL, UID_ALL, SET_ALL, TAG_NONE, METERED_ALL, ROAMING_ALL,
                DEFAULT_NETWORK_ALL,  rxBytes, rxPackets, txBytes, txPackets, operations);
    }

    private void assertUidTotal(NetworkTemplate template, int uid, long rxBytes, long rxPackets,
            long txBytes, long txPackets, int operations) throws Exception {
        assertUidTotal(template, uid, SET_ALL, METERED_ALL, ROAMING_ALL, DEFAULT_NETWORK_ALL,
                rxBytes, rxPackets, txBytes, txPackets, operations);
    }

    private void assertUidTotal(NetworkTemplate template, int uid, int set, int metered,
            int roaming, int defaultNetwork, long rxBytes, long rxPackets, long txBytes,
            long txPackets, int operations) throws Exception {
        // verify history API
        final NetworkStatsHistory history = mSession.getHistoryForUid(
                template, uid, set, TAG_NONE, FIELD_ALL);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, rxBytes, rxPackets, txBytes,
                txPackets, operations);

        // verify summary API
        final NetworkStats stats = mSession.getSummaryForAllUid(
                template, Long.MIN_VALUE, Long.MAX_VALUE, false);
        assertValues(stats, IFACE_ALL, uid, set, TAG_NONE, metered, roaming, defaultNetwork,
                rxBytes, rxPackets, txBytes, txPackets, operations);
    }

    private void expectSystemReady() throws Exception {
        expectNetworkStatsSummary(buildEmptyStats());
    }

    private String getActiveIface(NetworkState... states) throws Exception {
        if (states == null || states.length == 0 || states[0].linkProperties == null) {
            return null;
        }
        return states[0].linkProperties.getInterfaceName();
    }

    private void expectNetworkStatsSummary(NetworkStats summary) throws Exception {
        expectNetworkStatsSummary(summary, new NetworkStats(0L, 0));
    }

    private void expectNetworkStatsSummary(NetworkStats summary, NetworkStats tetherStats)
            throws Exception {
        expectNetworkStatsTethering(STATS_PER_IFACE, tetherStats);
        expectNetworkStatsSummaryDev(summary.clone());
        expectNetworkStatsSummaryXt(summary.clone());
    }

    private void expectNetworkStatsSummaryDev(NetworkStats summary) throws Exception {
        when(mStatsFactory.readNetworkStatsSummaryDev()).thenReturn(summary);
    }

    private void expectNetworkStatsSummaryXt(NetworkStats summary) throws Exception {
        when(mStatsFactory.readNetworkStatsSummaryXt()).thenReturn(summary);
    }

    private void expectNetworkStatsTethering(int how, NetworkStats stats)
            throws Exception {
        when(mNetManager.getNetworkStatsTethering(how)).thenReturn(stats);
    }

    private void expectNetworkStatsUidDetail(NetworkStats detail) throws Exception {
        expectNetworkStatsUidDetail(detail, new NetworkStats(0L, 0));
    }

    private void expectNetworkStatsUidDetail(NetworkStats detail, NetworkStats tetherStats)
            throws Exception {
        when(mStatsFactory.readNetworkStatsDetail(UID_ALL, INTERFACES_ALL, TAG_ALL))
                .thenReturn(detail);

        // also include tethering details, since they are folded into UID
        when(mNetManager.getNetworkStatsTethering(STATS_PER_UID)).thenReturn(tetherStats);
    }

    private void expectDefaultSettings() throws Exception {
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
    }

    private void expectSettings(long persistBytes, long bucketDuration, long deleteAge)
            throws Exception {
        when(mSettings.getPollInterval()).thenReturn(HOUR_IN_MILLIS);
        when(mSettings.getPollDelay()).thenReturn(0L);
        when(mSettings.getSampleEnabled()).thenReturn(true);
        when(mSettings.getCombineSubtypeEnabled()).thenReturn(false);

        final Config config = new Config(bucketDuration, deleteAge, deleteAge);
        when(mSettings.getDevConfig()).thenReturn(config);
        when(mSettings.getXtConfig()).thenReturn(config);
        when(mSettings.getUidConfig()).thenReturn(config);
        when(mSettings.getUidTagConfig()).thenReturn(config);

        when(mSettings.getGlobalAlertBytes(anyLong())).thenReturn(MB_IN_BYTES);
        when(mSettings.getDevPersistBytes(anyLong())).thenReturn(MB_IN_BYTES);
        when(mSettings.getXtPersistBytes(anyLong())).thenReturn(MB_IN_BYTES);
        when(mSettings.getUidPersistBytes(anyLong())).thenReturn(MB_IN_BYTES);
        when(mSettings.getUidTagPersistBytes(anyLong())).thenReturn(MB_IN_BYTES);
    }

    private void assertStatsFilesExist(boolean exist) {
        final File basePath = new File(mStatsDir, "netstats");
        if (exist) {
            assertTrue(basePath.list().length > 0);
        } else {
            assertTrue(basePath.list().length == 0);
        }
    }

    private static void assertValues(NetworkStatsHistory stats, long start, long end, long rxBytes,
            long rxPackets, long txBytes, long txPackets, int operations) {
        final NetworkStatsHistory.Entry entry = stats.getValues(start, end, null);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
        assertEquals("unexpected operations", operations, entry.operations);
    }

    private static NetworkState buildWifiState() {
        return buildWifiState(false);
    }

    private static NetworkState buildWifiState(boolean isMetered) {
        final NetworkInfo info = new NetworkInfo(TYPE_WIFI, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        final NetworkCapabilities capabilities = new NetworkCapabilities();
        capabilities.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, !isMetered);
        capabilities.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, true);
        capabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        return new NetworkState(info, prop, capabilities, WIFI_NETWORK, null, TEST_SSID);
    }

    private static NetworkState buildMobile3gState(String subscriberId) {
        return buildMobile3gState(subscriberId, false /* isRoaming */);
    }

    private static NetworkState buildMobile3gState(String subscriberId, boolean isRoaming) {
        final NetworkInfo info = new NetworkInfo(
                TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_UMTS, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        info.setRoaming(isRoaming);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        final NetworkCapabilities capabilities = new NetworkCapabilities();
        capabilities.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, false);
        capabilities.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, !isRoaming);
        capabilities.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        return new NetworkState(info, prop, capabilities, MOBILE_NETWORK, subscriberId, null);
    }

    private static NetworkState buildWimaxState(@NonNull String iface) {
        final NetworkInfo info = new NetworkInfo(TYPE_WIMAX, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(iface);
        final NetworkCapabilities capabilities = new NetworkCapabilities();
        capabilities.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, false);
        capabilities.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, true);
        return new NetworkState(info, prop, capabilities, MOBILE_NETWORK, null, null);
    }

    private NetworkStats buildEmptyStats() {
        return new NetworkStats(getElapsedRealtime(), 0);
    }

    private static NetworkState buildVpnState() {
        final NetworkInfo info = new NetworkInfo(TYPE_VPN, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TUN_IFACE);
        return new NetworkState(info, prop, new NetworkCapabilities(), VPN_NETWORK, null, null);
    }

    private long getElapsedRealtime() {
        return mElapsedRealtime;
    }

    private long startTimeMillis() {
        return TEST_START;
    }

    private long currentTimeMillis() {
        return startTimeMillis() + mElapsedRealtime;
    }

    private void incrementCurrentTime(long duration) {
        mElapsedRealtime += duration;
    }

    private void forcePollAndWaitForIdle() {
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        HandlerUtilsKt.waitForIdle(mHandlerThread, WAIT_TIMEOUT);
    }

    static class LatchedHandler extends Handler {
        private final ConditionVariable mCv;
        int lastMessageType = INVALID_TYPE;

        LatchedHandler(Looper looper, ConditionVariable cv) {
            super(looper);
            mCv = cv;
        }

        @Override
        public void handleMessage(Message msg) {
            lastMessageType = msg.what;
            mCv.open();
            super.handleMessage(msg);
        }
    }
}
