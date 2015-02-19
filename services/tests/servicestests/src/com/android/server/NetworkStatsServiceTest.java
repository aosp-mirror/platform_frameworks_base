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

package com.android.server;

import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.SET_ALL;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.SET_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkStatsHistory.FIELD_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.net.NetworkTemplate.buildTemplateWifiWildcard;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.net.TrafficStats.UID_REMOVED;
import static android.net.TrafficStats.UID_TETHERING;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;
import static com.android.server.net.NetworkStatsService.ACTION_NETWORK_STATS_POLL;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;

import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkStatsSession;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.INetworkManagementService;
import android.os.WorkSource;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.TrustedTime;

import com.android.server.net.NetworkStatsService;
import com.android.server.net.NetworkStatsService.NetworkStatsSettings;
import com.android.server.net.NetworkStatsService.NetworkStatsSettings.Config;

import libcore.io.IoUtils;

import org.easymock.Capture;
import org.easymock.EasyMock;

import java.io.File;

/**
 * Tests for {@link NetworkStatsService}.
 */
@LargeTest
public class NetworkStatsServiceTest extends AndroidTestCase {
    private static final String TAG = "NetworkStatsServiceTest";

    private static final String TEST_IFACE = "test0";
    private static final String TEST_IFACE2 = "test1";
    private static final long TEST_START = 1194220800000L;

    private static final String IMSI_1 = "310004";
    private static final String IMSI_2 = "310260";
    private static final String TEST_SSID = "AndroidAP";

    private static NetworkTemplate sTemplateWifi = buildTemplateWifiWildcard();
    private static NetworkTemplate sTemplateImsi1 = buildTemplateMobileAll(IMSI_1);
    private static NetworkTemplate sTemplateImsi2 = buildTemplateMobileAll(IMSI_2);

    private static final int UID_RED = 1001;
    private static final int UID_BLUE = 1002;
    private static final int UID_GREEN = 1003;

    private long mElapsedRealtime;

    private BroadcastInterceptingContext mServiceContext;
    private File mStatsDir;

    private INetworkManagementService mNetManager;
    private IAlarmManager mAlarmManager;
    private TrustedTime mTime;
    private NetworkStatsSettings mSettings;
    private IConnectivityManager mConnManager;

    private NetworkStatsService mService;
    private INetworkStatsSession mSession;
    private INetworkManagementEventObserver mNetworkObserver;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mServiceContext = new BroadcastInterceptingContext(getContext());
        mStatsDir = getContext().getFilesDir();
        if (mStatsDir.exists()) {
            IoUtils.deleteContents(mStatsDir);
        }

        mNetManager = createMock(INetworkManagementService.class);
        mAlarmManager = createMock(IAlarmManager.class);
        mTime = createMock(TrustedTime.class);
        mSettings = createMock(NetworkStatsSettings.class);
        mConnManager = createMock(IConnectivityManager.class);

        mService = new NetworkStatsService(
                mServiceContext, mNetManager, mAlarmManager, mTime, mStatsDir, mSettings);
        mService.bindConnectivityManager(mConnManager);

        mElapsedRealtime = 0L;

        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectSystemReady();

        // catch INetworkManagementEventObserver during systemReady()
        final Capture<INetworkManagementEventObserver> networkObserver = new Capture<
                INetworkManagementEventObserver>();
        mNetManager.registerObserver(capture(networkObserver));
        expectLastCall().atLeastOnce();

        replay();
        mService.systemReady();
        mSession = mService.openSession();
        verifyAndReset();

        mNetworkObserver = networkObserver.getValue();

    }

    @Override
    public void tearDown() throws Exception {
        IoUtils.deleteContents(mStatsDir);

        mServiceContext = null;
        mStatsDir = null;

        mNetManager = null;
        mAlarmManager = null;
        mTime = null;
        mSettings = null;
        mConnManager = null;

        mSession.close();
        mService = null;

        super.tearDown();
    }

    public void testNetworkStatsWifi() throws Exception {
        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));

        // verify service has empty history for wifi
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        verifyAndReset();

        // modify some number on wifi, and trigger poll event
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 1024L, 1L, 2048L, 2L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 1024L, 1L, 2048L, 2L, 0);
        verifyAndReset();

        // and bump forward again, with counters going higher. this is
        // important, since polling should correctly subtract last snapshot.
        incrementCurrentTime(DAY_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 4096L, 4L, 8192L, 8L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 4096L, 4L, 8192L, 8L, 0);
        verifyAndReset();

    }

    public void testStatsRebootPersist() throws Exception {
        assertStatsFilesExist(false);

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));

        // verify service has empty history for wifi
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        verifyAndReset();

        // modify some number on wifi, and trigger poll event
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 1024L, 8L, 2048L, 16L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 2)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 512L, 4L, 256L, 2L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xFAAD, 256L, 2L, 128L, 1L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_FOREGROUND, TAG_NONE, 512L, 4L, 256L, 2L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_FOREGROUND, 0xFAAD, 256L, 2L, 128L, 1L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 128L, 1L, 128L, 1L, 0L));
        expectNetworkStatsPoll();

        mService.setUidForeground(UID_RED, false);
        mService.incrementOperationCount(UID_RED, 0xFAAD, 4);
        mService.setUidForeground(UID_RED, true);
        mService.incrementOperationCount(UID_RED, 0xFAAD, 6);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 1024L, 8L, 2048L, 16L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 1024L, 8L, 512L, 4L, 10);
        assertUidTotal(sTemplateWifi, UID_RED, SET_DEFAULT, 512L, 4L, 256L, 2L, 4);
        assertUidTotal(sTemplateWifi, UID_RED, SET_FOREGROUND, 512L, 4L, 256L, 2L, 6);
        assertUidTotal(sTemplateWifi, UID_BLUE, 128L, 1L, 128L, 1L, 0);
        verifyAndReset();

        // graceful shutdown system, which should trigger persist of stats, and
        // clear any values in memory.
        expectCurrentTime();
        expectDefaultSettings();
        replay();
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SHUTDOWN));
        verifyAndReset();

        assertStatsFilesExist(true);

        // boot through serviceReady() again
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectSystemReady();

        // catch INetworkManagementEventObserver during systemReady()
        final Capture<INetworkManagementEventObserver> networkObserver = new Capture<
                INetworkManagementEventObserver>();
        mNetManager.registerObserver(capture(networkObserver));
        expectLastCall().atLeastOnce();

        replay();
        mService.systemReady();

        mNetworkObserver = networkObserver.getValue();

        // after systemReady(), we should have historical stats loaded again
        assertNetworkTotal(sTemplateWifi, 1024L, 8L, 2048L, 16L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 1024L, 8L, 512L, 4L, 10);
        assertUidTotal(sTemplateWifi, UID_RED, SET_DEFAULT, 512L, 4L, 256L, 2L, 4);
        assertUidTotal(sTemplateWifi, UID_RED, SET_FOREGROUND, 512L, 4L, 256L, 2L, 6);
        assertUidTotal(sTemplateWifi, UID_BLUE, 128L, 1L, 128L, 1L, 0);
        verifyAndReset();

    }

    // TODO: simulate reboot to test bucket resize
    @Suppress
    public void testStatsBucketResize() throws Exception {
        NetworkStatsHistory history = null;

        assertStatsFilesExist(false);

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectCurrentTime();
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        verifyAndReset();

        // modify some number on wifi, and trigger poll event
        incrementCurrentTime(2 * HOUR_IN_MILLIS);
        expectCurrentTime();
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 512L, 4L, 512L, 4L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        history = mSession.getHistoryForNetwork(sTemplateWifi, FIELD_ALL);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, 512L, 4L, 512L, 4L, 0);
        assertEquals(HOUR_IN_MILLIS, history.getBucketDuration());
        assertEquals(2, history.size());
        verifyAndReset();

        // now change bucket duration setting and trigger another poll with
        // exact same values, which should resize existing buckets.
        expectCurrentTime();
        expectSettings(0L, 30 * MINUTE_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify identical stats, but spread across 4 buckets now
        history = mSession.getHistoryForNetwork(sTemplateWifi, FIELD_ALL);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, 512L, 4L, 512L, 4L, 0);
        assertEquals(30 * MINUTE_IN_MILLIS, history.getBucketDuration());
        assertEquals(4, history.size());
        verifyAndReset();

    }

    public void testUidStatsAcrossNetworks() throws Exception {
        // pretend first mobile network comes online
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildMobile3gState(IMSI_1));
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        verifyAndReset();

        // create some traffic on first network
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 2048L, 16L, 512L, 4L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 3)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1536L, 12L, 512L, 4L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 512L, 4L, 0L, 0L, 0L));
        expectNetworkStatsPoll();

        mService.incrementOperationCount(UID_RED, 0xF00D, 10);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateImsi1, 2048L, 16L, 512L, 4L, 0);
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 512L, 4L, 10);
        assertUidTotal(sTemplateImsi1, UID_BLUE, 512L, 4L, 0L, 0L, 0);
        verifyAndReset();

        // now switch networks; this also tests that we're okay with interfaces
        // disappearing, to verify we don't count backwards.
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildMobile3gState(IMSI_2));
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 2048L, 16L, 512L, 4L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 3)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1536L, 12L, 512L, 4L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 512L, 4L, 0L, 0L, 0L));
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        verifyAndReset();

        // create traffic on second network
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 2176L, 17L, 1536L, 12L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1536L, 12L, 512L, 4L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 640L, 5L, 1024L, 8L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, 0xFAAD, 128L, 1L, 1024L, 8L, 0L));
        expectNetworkStatsPoll();

        mService.incrementOperationCount(UID_BLUE, 0xFAAD, 10);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify original history still intact
        assertNetworkTotal(sTemplateImsi1, 2048L, 16L, 512L, 4L, 0);
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 512L, 4L, 10);
        assertUidTotal(sTemplateImsi1, UID_BLUE, 512L, 4L, 0L, 0L, 0);

        // and verify new history also recorded under different template, which
        // verifies that we didn't cross the streams.
        assertNetworkTotal(sTemplateImsi2, 128L, 1L, 1024L, 8L, 0);
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateImsi2, UID_BLUE, 128L, 1L, 1024L, 8L, 10);
        verifyAndReset();

    }

    public void testUidRemovedIsMoved() throws Exception {
        // pretend that network comes online
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        verifyAndReset();

        // create some traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 4128L, 258L, 544L, 34L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 16L, 1L, 16L, 1L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xFAAD, 16L, 1L, 16L, 1L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 4096L, 258L, 512L, 32L, 0L)
                .addValues(TEST_IFACE, UID_GREEN, SET_DEFAULT, TAG_NONE, 16L, 1L, 16L, 1L, 0L));
        expectNetworkStatsPoll();

        mService.incrementOperationCount(UID_RED, 0xFAAD, 10);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 4128L, 258L, 544L, 34L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 16L, 1L, 16L, 1L, 10);
        assertUidTotal(sTemplateWifi, UID_BLUE, 4096L, 258L, 512L, 32L, 0);
        assertUidTotal(sTemplateWifi, UID_GREEN, 16L, 1L, 16L, 1L, 0);
        verifyAndReset();

        // now pretend two UIDs are uninstalled, which should migrate stats to
        // special "removed" bucket.
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 4128L, 258L, 544L, 34L));
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 16L, 1L, 16L, 1L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xFAAD, 16L, 1L, 16L, 1L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 4096L, 258L, 512L, 32L, 0L)
                .addValues(TEST_IFACE, UID_GREEN, SET_DEFAULT, TAG_NONE, 16L, 1L, 16L, 1L, 0L));
        expectNetworkStatsPoll();

        replay();
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
        verifyAndReset();

    }

    public void testUid3g4gCombinedByTemplate() throws Exception {
        // pretend that network comes online
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildMobile3gState(IMSI_1));
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        verifyAndReset();

        // create some traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L));
        expectNetworkStatsPoll();

        mService.incrementOperationCount(UID_RED, 0xF00D, 5);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertUidTotal(sTemplateImsi1, UID_RED, 1024L, 8L, 1024L, 8L, 5);
        verifyAndReset();

        // now switch over to 4g network
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildMobile4gState(TEST_IFACE2));
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L));
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        verifyAndReset();

        // create traffic on second network
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 1024L, 8L, 1024L, 8L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 512L, 4L, 512L, 4L, 0L)
                .addValues(TEST_IFACE2, UID_RED, SET_DEFAULT, TAG_NONE, 512L, 4L, 256L, 2L, 0L)
                .addValues(TEST_IFACE2, UID_RED, SET_DEFAULT, 0xFAAD, 512L, 4L, 256L, 2L, 0L));
        expectNetworkStatsPoll();

        mService.incrementOperationCount(UID_RED, 0xFAAD, 5);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify that ALL_MOBILE template combines both
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 1280L, 10L, 10);

        verifyAndReset();
    }

    public void testSummaryForAllUid() throws Exception {
        // pretend that network comes online
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        verifyAndReset();

        // create some traffic for two apps
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 50L, 5L, 50L, 5L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 10L, 1L, 10L, 1L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 1024L, 8L, 512L, 4L, 0L));
        expectNetworkStatsPoll();

        mService.incrementOperationCount(UID_RED, 0xF00D, 1);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertUidTotal(sTemplateWifi, UID_RED, 50L, 5L, 50L, 5L, 1);
        assertUidTotal(sTemplateWifi, UID_BLUE, 1024L, 8L, 512L, 4L, 0);
        verifyAndReset();

        // now create more traffic in next hour, but only for one app
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 50L, 5L, 50L, 5L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 10L, 1L, 10L, 1L, 0L)
                .addValues(TEST_IFACE, UID_BLUE, SET_DEFAULT, TAG_NONE, 2048L, 16L, 1024L, 8L, 0L));
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // first verify entire history present
        NetworkStats stats = mSession.getSummaryForAllUid(
                sTemplateWifi, Long.MIN_VALUE, Long.MAX_VALUE, true);
        assertEquals(3, stats.size());
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, TAG_NONE, 50L, 5L, 50L, 5L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, 0xF00D, 10L, 1L, 10L, 1L, 1);
        assertValues(stats, IFACE_ALL, UID_BLUE, SET_DEFAULT, TAG_NONE, 2048L, 16L, 1024L, 8L, 0);

        // now verify that recent history only contains one uid
        final long currentTime = currentTimeMillis();
        stats = mSession.getSummaryForAllUid(
                sTemplateWifi, currentTime - HOUR_IN_MILLIS, currentTime, true);
        assertEquals(1, stats.size());
        assertValues(stats, IFACE_ALL, UID_BLUE, SET_DEFAULT, TAG_NONE, 1024L, 8L, 512L, 4L, 0);

        verifyAndReset();
    }

    public void testForegroundBackground() throws Exception {
        // pretend that network comes online
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        verifyAndReset();

        // create some initial traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 128L, 2L, 128L, 2L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 64L, 1L, 64L, 1L, 0L));
        expectNetworkStatsPoll();

        mService.incrementOperationCount(UID_RED, 0xF00D, 1);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertUidTotal(sTemplateWifi, UID_RED, 128L, 2L, 128L, 2L, 1);
        verifyAndReset();

        // now switch to foreground
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 128L, 2L, 128L, 2L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, 0xF00D, 64L, 1L, 64L, 1L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_FOREGROUND, TAG_NONE, 32L, 2L, 32L, 2L, 0L)
                .addValues(TEST_IFACE, UID_RED, SET_FOREGROUND, 0xFAAD, 1L, 1L, 1L, 1L, 0L));
        expectNetworkStatsPoll();

        mService.setUidForeground(UID_RED, true);
        mService.incrementOperationCount(UID_RED, 0xFAAD, 1);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // test that we combined correctly
        assertUidTotal(sTemplateWifi, UID_RED, 160L, 4L, 160L, 4L, 2);

        // verify entire history present
        final NetworkStats stats = mSession.getSummaryForAllUid(
                sTemplateWifi, Long.MIN_VALUE, Long.MAX_VALUE, true);
        assertEquals(4, stats.size());
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, TAG_NONE, 128L, 2L, 128L, 2L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_DEFAULT, 0xF00D, 64L, 1L, 64L, 1L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_FOREGROUND, TAG_NONE, 32L, 2L, 32L, 2L, 1);
        assertValues(stats, IFACE_ALL, UID_RED, SET_FOREGROUND, 0xFAAD, 1L, 1L, 1L, 1L, 1);

        verifyAndReset();
    }

    public void testTethering() throws Exception {
        // pretend first mobile network comes online
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildMobile3gState(IMSI_1));
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        verifyAndReset();

        // create some tethering traffic
        incrementCurrentTime(HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 2048L, 16L, 512L, 4L));

        final NetworkStats uidStats = new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_RED, SET_DEFAULT, TAG_NONE, 128L, 2L, 128L, 2L, 0L);
        final String[] tetherIfacePairs = new String[] { TEST_IFACE, "wlan0" };
        final NetworkStats tetherStats = new NetworkStats(getElapsedRealtime(), 1)
                .addValues(TEST_IFACE, UID_TETHERING, SET_DEFAULT, TAG_NONE, 1920L, 14L, 384L, 2L, 0L);

        expectNetworkStatsUidDetail(uidStats, tetherIfacePairs, tetherStats);
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateImsi1, 2048L, 16L, 512L, 4L, 0);
        assertUidTotal(sTemplateImsi1, UID_RED, 128L, 2L, 128L, 2L, 0);
        assertUidTotal(sTemplateImsi1, UID_TETHERING, 1920L, 14L, 384L, 2L, 0);
        verifyAndReset();

    }

    public void testReportXtOverDev() throws Exception {
        // bring mobile network online
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkState(buildMobile3gState(IMSI_1));
        expectNetworkStatsSummary(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        verifyAndReset();

        // create some traffic, but only for DEV, and across 1.5 buckets
        incrementCurrentTime(90 * MINUTE_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummaryDev(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 6000L, 60L, 3000L, 30L));
        expectNetworkStatsSummaryXt(buildEmptyStats());
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history:
        // 4000(dev) + 2000(dev)
        assertNetworkTotal(sTemplateImsi1, 6000L, 60L, 3000L, 30L, 0);
        verifyAndReset();

        // create traffic on both DEV and XT, across two buckets
        incrementCurrentTime(2 * HOUR_IN_MILLIS);
        expectCurrentTime();
        expectDefaultSettings();
        expectNetworkStatsSummaryDev(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 6004L, 64L, 3004L, 34L));
        expectNetworkStatsSummaryXt(new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 10240L, 0L, 0L, 0L));
        expectNetworkStatsUidDetail(buildEmptyStats());
        expectNetworkStatsPoll();

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify that we switching reporting at the first atomic XT bucket,
        // which should give us:
        // 4000(dev) + 2000(dev) + 1(dev) + 5120(xt) + 2560(xt)
        assertNetworkTotal(sTemplateImsi1, 13681L, 61L, 3001L, 31L, 0);

        // also test pure-DEV and pure-XT ranges
        assertNetworkTotal(sTemplateImsi1, startTimeMillis(),
                startTimeMillis() + 2 * HOUR_IN_MILLIS, 6001L, 61L, 3001L, 31L, 0);
        assertNetworkTotal(sTemplateImsi1, startTimeMillis() + 2 * HOUR_IN_MILLIS,
                startTimeMillis() + 4 * HOUR_IN_MILLIS, 7680L, 0L, 0L, 0L, 0);

        verifyAndReset();
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
        assertValues(stats, IFACE_ALL, UID_ALL, SET_DEFAULT, TAG_NONE, rxBytes, rxPackets, txBytes,
                txPackets, operations);
    }

    private void assertUidTotal(NetworkTemplate template, int uid, long rxBytes, long rxPackets,
            long txBytes, long txPackets, int operations) throws Exception {
        assertUidTotal(template, uid, SET_ALL, rxBytes, rxPackets, txBytes, txPackets, operations);
    }

    private void assertUidTotal(NetworkTemplate template, int uid, int set, long rxBytes,
            long rxPackets, long txBytes, long txPackets, int operations) throws Exception {
        // verify history API
        final NetworkStatsHistory history = mSession.getHistoryForUid(
                template, uid, set, TAG_NONE, FIELD_ALL);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, rxBytes, rxPackets, txBytes,
                txPackets, operations);

        // verify summary API
        final NetworkStats stats = mSession.getSummaryForAllUid(
                template, Long.MIN_VALUE, Long.MAX_VALUE, false);
        assertValues(stats, IFACE_ALL, uid, set, TAG_NONE, rxBytes, rxPackets, txBytes, txPackets,
                operations);
    }

    private void expectSystemReady() throws Exception {
        mAlarmManager.remove(isA(PendingIntent.class));
        expectLastCall().anyTimes();

        mAlarmManager.set(eq(AlarmManager.ELAPSED_REALTIME), anyLong(), anyLong(), anyLong(),
                isA(PendingIntent.class), isA(WorkSource.class),
                isA(AlarmManager.AlarmClockInfo.class));
        expectLastCall().atLeastOnce();

        mNetManager.setGlobalAlert(anyLong());
        expectLastCall().atLeastOnce();

        expect(mNetManager.isBandwidthControlEnabled()).andReturn(true).atLeastOnce();
    }

    private void expectNetworkState(NetworkState... state) throws Exception {
        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();

        final LinkProperties linkProp = state.length > 0 ? state[0].linkProperties : null;
        expect(mConnManager.getActiveLinkProperties()).andReturn(linkProp).atLeastOnce();
    }

    private void expectNetworkStatsSummary(NetworkStats summary) throws Exception {
        expectNetworkStatsSummaryDev(summary);
        expectNetworkStatsSummaryXt(summary);
    }

    private void expectNetworkStatsSummaryDev(NetworkStats summary) throws Exception {
        expect(mNetManager.getNetworkStatsSummaryDev()).andReturn(summary).atLeastOnce();
    }

    private void expectNetworkStatsSummaryXt(NetworkStats summary) throws Exception {
        expect(mNetManager.getNetworkStatsSummaryXt()).andReturn(summary).atLeastOnce();
    }

    private void expectNetworkStatsUidDetail(NetworkStats detail) throws Exception {
        expectNetworkStatsUidDetail(detail, new String[0], new NetworkStats(0L, 0));
    }

    private void expectNetworkStatsUidDetail(
            NetworkStats detail, String[] tetherIfacePairs, NetworkStats tetherStats)
            throws Exception {
        expect(mNetManager.getNetworkStatsUidDetail(eq(UID_ALL))).andReturn(detail).atLeastOnce();

        // also include tethering details, since they are folded into UID
        expect(mNetManager.getNetworkStatsTethering())
                .andReturn(tetherStats).atLeastOnce();
    }

    private void expectDefaultSettings() throws Exception {
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
    }

    private void expectSettings(long persistBytes, long bucketDuration, long deleteAge)
            throws Exception {
        expect(mSettings.getPollInterval()).andReturn(HOUR_IN_MILLIS).anyTimes();
        expect(mSettings.getTimeCacheMaxAge()).andReturn(DAY_IN_MILLIS).anyTimes();
        expect(mSettings.getSampleEnabled()).andReturn(true).anyTimes();

        final Config config = new Config(bucketDuration, deleteAge, deleteAge);
        expect(mSettings.getDevConfig()).andReturn(config).anyTimes();
        expect(mSettings.getXtConfig()).andReturn(config).anyTimes();
        expect(mSettings.getUidConfig()).andReturn(config).anyTimes();
        expect(mSettings.getUidTagConfig()).andReturn(config).anyTimes();

        expect(mSettings.getGlobalAlertBytes(anyLong())).andReturn(MB_IN_BYTES).anyTimes();
        expect(mSettings.getDevPersistBytes(anyLong())).andReturn(MB_IN_BYTES).anyTimes();
        expect(mSettings.getXtPersistBytes(anyLong())).andReturn(MB_IN_BYTES).anyTimes();
        expect(mSettings.getUidPersistBytes(anyLong())).andReturn(MB_IN_BYTES).anyTimes();
        expect(mSettings.getUidTagPersistBytes(anyLong())).andReturn(MB_IN_BYTES).anyTimes();
    }

    private void expectCurrentTime() throws Exception {
        expect(mTime.forceRefresh()).andReturn(false).anyTimes();
        expect(mTime.hasCache()).andReturn(true).anyTimes();
        expect(mTime.currentTimeMillis()).andReturn(currentTimeMillis()).anyTimes();
        expect(mTime.getCacheAge()).andReturn(0L).anyTimes();
        expect(mTime.getCacheCertainty()).andReturn(0L).anyTimes();
    }

    private void expectNetworkStatsPoll() throws Exception {
        mNetManager.setGlobalAlert(anyLong());
        expectLastCall().anyTimes();
    }

    private void assertStatsFilesExist(boolean exist) {
        final File basePath = new File(mStatsDir, "netstats");
        if (exist) {
            assertTrue(basePath.list().length > 0);
        } else {
            assertTrue(basePath.list().length == 0);
        }
    }

    private static void assertValues(NetworkStats stats, String iface, int uid, int set,
            int tag, long rxBytes, long rxPackets, long txBytes, long txPackets, int operations) {
        final NetworkStats.Entry entry = new NetworkStats.Entry();
        if (set == SET_DEFAULT || set == SET_ALL) {
            final int i = stats.findIndex(iface, uid, SET_DEFAULT, tag);
            if (i != -1) {
                entry.add(stats.getValues(i, null));
            }
        }
        if (set == SET_FOREGROUND || set == SET_ALL) {
            final int i = stats.findIndex(iface, uid, SET_FOREGROUND, tag);
            if (i != -1) {
                entry.add(stats.getValues(i, null));
            }
        }

        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
        assertEquals("unexpected operations", operations, entry.operations);
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
        final NetworkInfo info = new NetworkInfo(TYPE_WIFI, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        return new NetworkState(info, prop, null, null, null, TEST_SSID);
    }

    private static NetworkState buildMobile3gState(String subscriberId) {
        final NetworkInfo info = new NetworkInfo(
                TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_UMTS, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        return new NetworkState(info, prop, null, null, subscriberId, null);
    }

    private static NetworkState buildMobile4gState(String iface) {
        final NetworkInfo info = new NetworkInfo(TYPE_WIMAX, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(iface);
        return new NetworkState(info, prop, null, null, null, null);
    }

    private NetworkStats buildEmptyStats() {
        return new NetworkStats(getElapsedRealtime(), 0);
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

    private void replay() {
        EasyMock.replay(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
    }

    private void verifyAndReset() {
        EasyMock.verify(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
        EasyMock.reset(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
    }
}
