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

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.TEMPLATE_WIFI;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;
import static com.android.server.net.NetworkStatsService.ACTION_NETWORK_STATS_POLL;
import static org.easymock.EasyMock.anyLong;
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
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.os.INetworkManagementService;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.TrustedTime;

import com.android.server.net.NetworkStatsService;
import com.android.server.net.NetworkStatsService.NetworkStatsSettings;

import org.easymock.EasyMock;

import java.io.File;

/**
 * Tests for {@link NetworkStatsService}.
 */
@LargeTest
public class NetworkStatsServiceTest extends AndroidTestCase {
    private static final String TAG = "NetworkStatsServiceTest";

    private static final String TEST_IFACE = "test0";
    private static final long TEST_START = 1194220800000L;

    private static final int TEST_UID_1 = 1001;
    private static final int TEST_UID_2 = 1002;

    private BroadcastInterceptingContext mServiceContext;
    private File mStatsDir;

    private INetworkManagementService mNetManager;
    private IAlarmManager mAlarmManager;
    private TrustedTime mTime;
    private NetworkStatsSettings mSettings;
    private IConnectivityManager mConnManager;

    private NetworkStatsService mService;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mServiceContext = new BroadcastInterceptingContext(getContext());
        mStatsDir = getContext().getFilesDir();

        mNetManager = createMock(INetworkManagementService.class);
        mAlarmManager = createMock(IAlarmManager.class);
        mTime = createMock(TrustedTime.class);
        mSettings = createMock(NetworkStatsSettings.class);
        mConnManager = createMock(IConnectivityManager.class);

        mService = new NetworkStatsService(
                mServiceContext, mNetManager, mAlarmManager, mTime, mStatsDir, mSettings);
        mService.bindConnectivityManager(mConnManager);

        expectDefaultSettings();
        expectSystemReady();

        replay();
        mService.systemReady();
        verifyAndReset();

    }

    @Override
    public void tearDown() throws Exception {
        for (File file : mStatsDir.listFiles()) {
            file.delete();
        }

        mServiceContext = null;
        mStatsDir = null;

        mNetManager = null;
        mAlarmManager = null;
        mTime = null;

        mService = null;

        super.tearDown();
    }

    public void testSummaryStatsWifi() throws Exception {
        long elapsedRealtime = 0;

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));

        // verify service has empty history for wifi
        assertNetworkTotal(TEMPLATE_WIFI, 0L, 0L);
        verifyAndReset();

        // modify some number on wifi, and trigger poll event
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats.Builder(elapsedRealtime, 1)
                .addEntry(TEST_IFACE, UID_ALL, 1024L, 2048L).build());
        expectNetworkStatsDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(TEMPLATE_WIFI, 1024L, 2048L);
        verifyAndReset();

        // and bump forward again, with counters going higher. this is
        // important, since polling should correctly subtract last snapshot.
        elapsedRealtime += DAY_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats.Builder(elapsedRealtime, 1)
                .addEntry(TEST_IFACE, UID_ALL, 4096L, 8192L).build());
        expectNetworkStatsDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(TEMPLATE_WIFI, 4096L, 8192L);
        verifyAndReset();

    }

    public void testStatsRebootPersist() throws Exception {
        long elapsedRealtime = 0;
        assertStatsFilesExist(false);

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));

        // verify service has empty history for wifi
        assertNetworkTotal(TEMPLATE_WIFI, 0L, 0L);
        verifyAndReset();

        // modify some number on wifi, and trigger poll event
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats.Builder(elapsedRealtime, 1)
                .addEntry(TEST_IFACE, UID_ALL, 1024L, 2048L).build());
        // TODO: switch these stats to specific iface
        expectNetworkStatsDetail(new NetworkStats.Builder(elapsedRealtime, 2)
                .addEntry(IFACE_ALL, TEST_UID_1, 512L, 256L)
                .addEntry(IFACE_ALL, TEST_UID_2, 128L, 128L).build());

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(TEMPLATE_WIFI, 1024L, 2048L);
        assertUidTotal(TEST_UID_1, TEMPLATE_WIFI, 512L, 256L);
        assertUidTotal(TEST_UID_2, TEMPLATE_WIFI, 128L, 128L);
        verifyAndReset();

        // graceful shutdown system, which should trigger persist of stats, and
        // clear any values in memory.
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SHUTDOWN));

        // talk with zombie service to assert stats have gone; and assert that
        // we persisted them to file.
        expectDefaultSettings();
        replay();
        assertNetworkTotal(TEMPLATE_WIFI, 0L, 0L);
        verifyAndReset();

        assertStatsFilesExist(true);

        // boot through serviceReady() again
        expectDefaultSettings();
        expectSystemReady();

        replay();
        mService.systemReady();

        // after systemReady(), we should have historical stats loaded again
        assertNetworkTotal(TEMPLATE_WIFI, 1024L, 2048L);
        assertUidTotal(TEST_UID_1, TEMPLATE_WIFI, 512L, 256L);
        assertUidTotal(TEST_UID_2, TEMPLATE_WIFI, 128L, 128L);
        verifyAndReset();

    }

    public void testStatsBucketResize() throws Exception {
        long elapsedRealtime = 0;
        NetworkStatsHistory history = null;
        long[] total = null;

        assertStatsFilesExist(false);

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectTime(TEST_START + elapsedRealtime);
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // modify some number on wifi, and trigger poll event
        elapsedRealtime += 2 * HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkStatsSummary(new NetworkStats.Builder(elapsedRealtime, 1)
                .addEntry(TEST_IFACE, UID_ALL, 512L, 512L).build());
        expectNetworkStatsDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        history = mService.getHistoryForNetwork(TEMPLATE_WIFI);
        total = history.getTotalData(Long.MIN_VALUE, Long.MAX_VALUE, null);
        assertEquals(512L, total[0]);
        assertEquals(512L, total[1]);
        assertEquals(HOUR_IN_MILLIS, history.bucketDuration);
        assertEquals(2, history.bucketCount);
        verifyAndReset();

        // now change bucket duration setting and trigger another poll with
        // exact same values, which should resize existing buckets.
        expectTime(TEST_START + elapsedRealtime);
        expectSettings(0L, 30 * MINUTE_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify identical stats, but spread across 4 buckets now
        history = mService.getHistoryForNetwork(TEMPLATE_WIFI);
        total = history.getTotalData(Long.MIN_VALUE, Long.MAX_VALUE, null);
        assertEquals(512L, total[0]);
        assertEquals(512L, total[1]);
        assertEquals(30 * MINUTE_IN_MILLIS, history.bucketDuration);
        assertEquals(4, history.bucketCount);
        verifyAndReset();

    }

    private void assertNetworkTotal(int template, long rx, long tx) {
        final NetworkStatsHistory history = mService.getHistoryForNetwork(template);
        final long[] total = history.getTotalData(Long.MIN_VALUE, Long.MAX_VALUE, null);
        assertEquals(rx, total[0]);
        assertEquals(tx, total[1]);
    }

    private void assertUidTotal(int uid, int template, long rx, long tx) {
        final NetworkStatsHistory history = mService.getHistoryForUid(uid, template);
        final long[] total = history.getTotalData(Long.MIN_VALUE, Long.MAX_VALUE, null);
        assertEquals(rx, total[0]);
        assertEquals(tx, total[1]);
    }

    private void expectSystemReady() throws Exception {
        mAlarmManager.remove(isA(PendingIntent.class));
        expectLastCall().anyTimes();

        mAlarmManager.setInexactRepeating(
                eq(AlarmManager.ELAPSED_REALTIME), anyLong(), anyLong(), isA(PendingIntent.class));
        expectLastCall().atLeastOnce();
    }

    private void expectNetworkState(NetworkState... state) throws Exception {
        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
    }

    private void expectNetworkStatsSummary(NetworkStats summary) throws Exception {
        expect(mNetManager.getNetworkStatsSummary()).andReturn(summary).atLeastOnce();
    }

    private void expectNetworkStatsDetail(NetworkStats detail) throws Exception {
        expect(mNetManager.getNetworkStatsDetail()).andReturn(detail).atLeastOnce();
    }

    private void expectDefaultSettings() throws Exception {
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
    }

    private void expectSettings(long persistThreshold, long bucketDuration, long maxHistory)
            throws Exception {
        expect(mSettings.getPollInterval()).andReturn(HOUR_IN_MILLIS).anyTimes();
        expect(mSettings.getPersistThreshold()).andReturn(persistThreshold).anyTimes();
        expect(mSettings.getNetworkBucketDuration()).andReturn(bucketDuration).anyTimes();
        expect(mSettings.getNetworkMaxHistory()).andReturn(maxHistory).anyTimes();
        expect(mSettings.getUidBucketDuration()).andReturn(bucketDuration).anyTimes();
        expect(mSettings.getUidMaxHistory()).andReturn(maxHistory).anyTimes();
        expect(mSettings.getTimeCacheMaxAge()).andReturn(DAY_IN_MILLIS).anyTimes();
    }

    private void expectTime(long currentTime) throws Exception {
        expect(mTime.forceRefresh()).andReturn(false).anyTimes();
        expect(mTime.hasCache()).andReturn(true).anyTimes();
        expect(mTime.currentTimeMillis()).andReturn(currentTime).anyTimes();
        expect(mTime.getCacheAge()).andReturn(0L).anyTimes();
        expect(mTime.getCacheCertainty()).andReturn(0L).anyTimes();
    }

    private void assertStatsFilesExist(boolean exist) {
        final File summaryFile = new File(mStatsDir, "netstats.bin");
        final File detailFile = new File(mStatsDir, "netstats_uid.bin");
        if (exist) {
            assertTrue(summaryFile.exists());
            assertTrue(detailFile.exists());
        } else {
            assertFalse(summaryFile.exists());
            assertFalse(detailFile.exists());
        }
    }

    private static NetworkState buildWifiState() {
        final NetworkInfo info = new NetworkInfo(TYPE_WIFI, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        return new NetworkState(info, prop, null);
    }

    private static NetworkStats buildEmptyStats(long elapsedRealtime) {
        return new NetworkStats.Builder(elapsedRealtime, 0).build();
    }

    private void replay() {
        EasyMock.replay(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
    }

    private void verifyAndReset() {
        EasyMock.verify(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
        EasyMock.reset(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
    }
}
