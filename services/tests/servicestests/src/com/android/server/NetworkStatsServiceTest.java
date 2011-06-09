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
import static android.net.NetworkStats.UID_ALL;
import static android.net.TrafficStats.TEMPLATE_WIFI;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
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

    private BroadcastInterceptingContext mServiceContext;
    private File mStatsDir;

    private INetworkManagementService mNetManager;
    private IAlarmManager mAlarmManager;
    private TrustedTime mTime;
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
        mConnManager = createMock(IConnectivityManager.class);

        mService = new NetworkStatsService(
                mServiceContext, mNetManager, mAlarmManager, mTime, mStatsDir);
        mService.bindConnectivityManager(mConnManager);

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

    private static NetworkState buildWifi() {
        final NetworkInfo info = new NetworkInfo(TYPE_WIFI, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        return new NetworkState(info, prop, null);
    }

    public void testHistoryForWifi() throws Exception {
        long elapsedRealtime = 0;
        NetworkState[] state = null;
        NetworkStats stats = null;
        NetworkStats detail = null;

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        state = new NetworkState[] { buildWifi() };
        stats = new NetworkStats.Builder(elapsedRealtime, 0).build();
        detail = new NetworkStats.Builder(elapsedRealtime, 0).build();

        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
        expect(mNetManager.getNetworkStatsSummary()).andReturn(stats).atLeastOnce();
        expect(mNetManager.getNetworkStatsDetail()).andReturn(detail).atLeastOnce();
        expectTime(TEST_START + elapsedRealtime);

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // verify service has empty history for wifi
        assertNetworkTotal(TEMPLATE_WIFI, 0L, 0L);

        // modify some number on wifi, and trigger poll event
        elapsedRealtime += HOUR_IN_MILLIS;
        stats = new NetworkStats.Builder(elapsedRealtime, 1).addEntry(
                TEST_IFACE, UID_ALL, 1024L, 2048L).build();

        expect(mNetManager.getNetworkStatsSummary()).andReturn(stats).atLeastOnce();
        expect(mNetManager.getNetworkStatsDetail()).andReturn(detail).atLeastOnce();
        expectTime(TEST_START + elapsedRealtime);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        verifyAndReset();

        // verify service recorded history
        assertNetworkTotal(TEMPLATE_WIFI, 1024L, 2048L);

        // and bump forward again, with counters going higher. this is
        // important, since polling should correctly subtract last snapshot.
        elapsedRealtime += DAY_IN_MILLIS;
        stats = new NetworkStats.Builder(elapsedRealtime, 1).addEntry(
                TEST_IFACE, UID_ALL, 4096L, 8192L).build();

        expect(mNetManager.getNetworkStatsSummary()).andReturn(stats).atLeastOnce();
        expect(mNetManager.getNetworkStatsDetail()).andReturn(detail).atLeastOnce();
        expectTime(TEST_START + elapsedRealtime);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        verifyAndReset();

        // verify service recorded history
        assertNetworkTotal(TEMPLATE_WIFI, 4096L, 8192L);
    }

    public void testHistoryForRebootPersist() throws Exception {
        long elapsedRealtime = 0;
        NetworkState[] state = null;
        NetworkStats stats = null;
        NetworkStats detail = null;

        // assert that no stats file exists
        final File statsFile = new File(mStatsDir, "netstats.bin");
        assertFalse(statsFile.exists());

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        state = new NetworkState[] { buildWifi() };
        stats = new NetworkStats.Builder(elapsedRealtime, 0).build();
        detail = new NetworkStats.Builder(elapsedRealtime, 0).build();

        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
        expect(mNetManager.getNetworkStatsSummary()).andReturn(stats).atLeastOnce();
        expect(mNetManager.getNetworkStatsDetail()).andReturn(detail).atLeastOnce();
        expectTime(TEST_START + elapsedRealtime);

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // verify service has empty history for wifi
        assertNetworkTotal(TEMPLATE_WIFI, 0L, 0L);

        // modify some number on wifi, and trigger poll event
        elapsedRealtime += HOUR_IN_MILLIS;
        stats = new NetworkStats.Builder(elapsedRealtime, 1).addEntry(
                TEST_IFACE, UID_ALL, 1024L, 2048L).build();

        expect(mNetManager.getNetworkStatsSummary()).andReturn(stats).atLeastOnce();
        expect(mNetManager.getNetworkStatsDetail()).andReturn(detail).atLeastOnce();
        expectTime(TEST_START + elapsedRealtime);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        verifyAndReset();

        // verify service recorded history
        assertNetworkTotal(TEMPLATE_WIFI, 1024L, 2048L);

        // graceful shutdown system, which should trigger persist of stats, and
        // clear any values in memory.
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SHUTDOWN));

        // talk with zombie service to assert stats have gone; and assert that
        // we persisted them to file.
        assertNetworkTotal(TEMPLATE_WIFI, 0L, 0L);
        assertTrue(statsFile.exists());

        // boot through serviceReady() again
        expectSystemReady();

        replay();
        mService.systemReady();
        verifyAndReset();

        // after systemReady(), we should have historical stats loaded again
        assertNetworkTotal(TEMPLATE_WIFI, 1024L, 2048L);

    }

    private void assertNetworkTotal(int template, long rx, long tx) {
        final NetworkStatsHistory history = mService.getHistoryForNetwork(template);
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

    public void expectTime(long currentTime) throws Exception {
        expect(mTime.forceRefresh()).andReturn(false).anyTimes();
        expect(mTime.hasCache()).andReturn(true).anyTimes();
        expect(mTime.currentTimeMillis()).andReturn(currentTime).anyTimes();
        expect(mTime.getCacheAge()).andReturn(0L).anyTimes();
        expect(mTime.getCacheCertainty()).andReturn(0L).anyTimes();
    }

    private void replay() {
        EasyMock.replay(mNetManager, mAlarmManager, mTime, mConnManager);
    }

    private void verifyAndReset() {
        EasyMock.verify(mNetManager, mAlarmManager, mTime, mConnManager);
        EasyMock.reset(mNetManager, mAlarmManager, mTime, mConnManager);
    }
}
