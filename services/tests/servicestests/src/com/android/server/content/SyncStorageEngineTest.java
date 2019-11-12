/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.content;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import android.content.Context;
import android.content.SyncStatusInfo;
import android.util.Pair;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Random;

/**
 * Tests for {@link SyncStorageEngine}.
 */
@RunWith(AndroidJUnit4.class)
public class SyncStorageEngineTest {

    private Context mContext;
    private SyncStorageEngine mSyncStorageEngine;

    private static final int NUM_SYNC_STATUS = 100;
    private static final int NUM_PERIODIC_SYNC_TIMES = 20;
    private static final int NUM_EVENTS = 10;
    private static final int NUM_SOURCES = 6;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mSyncStorageEngine = SyncStorageEngine.newTestInstance(mContext);
    }

    @Test
    public void testStatisticsReadWrite() {
        populateDayStats(mSyncStorageEngine.mDayStats);
        mSyncStorageEngine.writeStatisticsLocked();

        final SyncStorageEngine other = SyncStorageEngine.newTestInstance(mContext);
        verifyDayStats(mSyncStorageEngine.mDayStats, other.getDayStatistics());
    }

    @Test
    public void testStatusReadWrite() {
        populateStatus(mSyncStorageEngine.mSyncStatus);
        mSyncStorageEngine.writeStatusLocked();

        final SyncStorageEngine other = SyncStorageEngine.newTestInstance(mContext);
        for (int i = 0; i < NUM_SYNC_STATUS; i++) {
            other.mAuthorities.put(i, null);
        }
        other.readStatusLocked();
        verifyStatus(mSyncStorageEngine.mSyncStatus, other.mSyncStatus);
    }

    private void populateDayStats(SyncStorageEngine.DayStats[] dayStats) {
        final Random r = new Random(1);
        for (int i = 0; i < dayStats.length; i++) {
            final SyncStorageEngine.DayStats ds = new SyncStorageEngine.DayStats(i);
            ds.successCount = r.nextInt();
            ds.successTime = r.nextLong();
            ds.failureCount = r.nextInt();
            ds.failureTime = r.nextLong();
            dayStats[i] = ds;
        }
    }

    private void verifyDayStats(SyncStorageEngine.DayStats[] dayStats,
            SyncStorageEngine.DayStats[] dayStatsOther) {
        assertEquals(dayStatsOther.length, dayStats.length);
        for (int i = 0; i < dayStatsOther.length; i++) {
            final SyncStorageEngine.DayStats ds = dayStats[i];
            final SyncStorageEngine.DayStats dsOther = dayStatsOther[i];
            assertEquals(dsOther.day, ds.day);
            assertEquals(dsOther.successCount, ds.successCount);
            assertEquals(dsOther.successTime, ds.successTime);
            assertEquals(dsOther.failureCount, ds.failureCount);
            assertEquals(dsOther.failureTime, ds.failureTime);
        }
    }

    private void populateStatus(SparseArray<SyncStatusInfo> syncStatus) {
        final Random r = new Random(1);
        for (int i = 0; i < NUM_SYNC_STATUS; i++) {
            final SyncStatusInfo ss = new SyncStatusInfo(i);
            ss.lastSuccessTime = r.nextLong();
            ss.lastSuccessSource = r.nextInt();
            ss.lastFailureTime = r.nextLong();
            ss.lastFailureSource = r.nextInt();
            ss.lastFailureMesg = "fail_msg_" + r.nextInt();
            ss.initialFailureTime = r.nextLong();
            ss.initialize = r.nextBoolean();
            for (int j = 0; j < NUM_PERIODIC_SYNC_TIMES; j++) {
                ss.addPeriodicSyncTime(r.nextLong());
            }
            final ArrayList<Pair<Long, String>> lastEventInfos = new ArrayList<>();
            for (int j = 0; j < NUM_EVENTS; j++) {
                lastEventInfos.add(new Pair<>(r.nextLong(), "event_" + r.nextInt()));
            }
            ss.populateLastEventsInformation(lastEventInfos);
            ss.lastTodayResetTime = r.nextLong();
            populateStats(ss.totalStats, r);
            populateStats(ss.todayStats, r);
            populateStats(ss.yesterdayStats, r);
            for (int j = 0; j < NUM_SOURCES; j++) {
                ss.perSourceLastSuccessTimes[j] = r.nextLong();
            }
            for (int j = 0; j < NUM_SOURCES; j++) {
                ss.perSourceLastFailureTimes[j] = r.nextLong();
            }
            syncStatus.put(i, ss);
        }
    }

    private void populateStats(SyncStatusInfo.Stats stats, Random r) {
        stats.totalElapsedTime = r.nextLong();
        stats.numSyncs = r.nextInt();
        stats.numFailures = r.nextInt();
        stats.numCancels = r.nextInt();
        stats.numSourceOther = r.nextInt();
        stats.numSourceLocal = r.nextInt();
        stats.numSourcePoll = r.nextInt();
        stats.numSourceUser = r.nextInt();
        stats.numSourcePeriodic = r.nextInt();
        stats.numSourceFeed = r.nextInt();
    }

    private void verifyStatus(SparseArray<SyncStatusInfo> syncStatus,
            SparseArray<SyncStatusInfo> syncStatusOther) {
        assertEquals(syncStatusOther.size(), syncStatus.size());
        for (int i = 0; i < NUM_SYNC_STATUS; i++) {
            final SyncStatusInfo ss = syncStatus.valueAt(i);
            final SyncStatusInfo ssOther = syncStatusOther.valueAt(i);
            assertEquals(ssOther.authorityId, ss.authorityId);
            assertEquals(ssOther.lastSuccessTime, ss.lastSuccessTime);
            assertEquals(ssOther.lastSuccessSource, ss.lastSuccessSource);
            assertEquals(ssOther.lastFailureTime, ss.lastFailureTime);
            assertEquals(ssOther.lastFailureSource, ss.lastFailureSource);
            assertEquals(ssOther.lastFailureMesg, ss.lastFailureMesg);
            assertFalse(ssOther.pending); // pending is always set to false when read
            assertEquals(ssOther.initialize, ss.initialize);
            assertEquals(ssOther.getPeriodicSyncTimesSize(), NUM_PERIODIC_SYNC_TIMES);
            for (int j = 0; j < NUM_PERIODIC_SYNC_TIMES; j++) {
                assertEquals(ssOther.getPeriodicSyncTime(j), ss.getPeriodicSyncTime(j));
            }
            assertEquals(ssOther.getEventCount(), NUM_EVENTS);
            for (int j = 0; j < NUM_EVENTS; j++) {
                assertEquals(ssOther.getEventTime(j), ss.getEventTime(j));
                assertEquals(ssOther.getEvent(j), ss.getEvent(j));
            }
            assertEquals(ssOther.lastTodayResetTime, ss.lastTodayResetTime);
            verifyStats(ss.totalStats, ssOther.totalStats);
            verifyStats(ss.todayStats, ssOther.todayStats);
            verifyStats(ss.yesterdayStats, ssOther.yesterdayStats);
            assertEquals(ssOther.perSourceLastSuccessTimes.length, NUM_SOURCES);
            for (int j = 0; j < NUM_SOURCES; j++) {
                assertEquals(ssOther.perSourceLastSuccessTimes[j], ss.perSourceLastSuccessTimes[j]);
            }
            assertEquals(ssOther.perSourceLastFailureTimes.length, NUM_SOURCES);
            for (int j = 0; j < NUM_SOURCES; j++) {
                assertEquals(ssOther.perSourceLastFailureTimes[j], ss.perSourceLastFailureTimes[j]);
            }
        }
    }

    private void verifyStats(SyncStatusInfo.Stats stats, SyncStatusInfo.Stats statsOther) {
        assertEquals(statsOther.totalElapsedTime, stats.totalElapsedTime);
        assertEquals(statsOther.numSyncs, stats.numSyncs);
        assertEquals(statsOther.numFailures, stats.numFailures);
        assertEquals(statsOther.numCancels, stats.numCancels);
        assertEquals(statsOther.numSourceOther, stats.numSourceOther);
        assertEquals(statsOther.numSourceLocal, stats.numSourceLocal);
        assertEquals(statsOther.numSourcePoll, stats.numSourcePoll);
        assertEquals(statsOther.numSourceUser, stats.numSourceUser);
        assertEquals(statsOther.numSourcePeriodic, stats.numSourcePeriodic);
        assertEquals(statsOther.numSourceFeed, stats.numSourceFeed);
    }
}
