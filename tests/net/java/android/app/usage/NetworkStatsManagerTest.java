/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.usage;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkStats.Entry;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.RemoteException;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkStatsManagerTest {

    private @Mock INetworkStatsService mService;
    private @Mock INetworkStatsSession mStatsSession;

    private NetworkStatsManager mManager;

    // TODO: change to NetworkTemplate.MATCH_MOBILE once internal constant rename is merged to aosp.
    private static final int MATCH_MOBILE_ALL = 1;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mManager = new NetworkStatsManager(InstrumentationRegistry.getContext(), mService);
    }

    @Test
    public void testQueryDetails() throws RemoteException {
        final String subscriberId = "subid";
        final long startTime = 1;
        final long endTime = 100;
        final int uid1 = 10001;
        final int uid2 = 10002;
        final int uid3 = 10003;

        Entry uid1Entry1 = new Entry("if1", uid1,
                android.net.NetworkStats.SET_DEFAULT, android.net.NetworkStats.TAG_NONE,
                100, 10, 200, 20, 0);

        Entry uid1Entry2 = new Entry(
                "if2", uid1,
                android.net.NetworkStats.SET_DEFAULT, android.net.NetworkStats.TAG_NONE,
                100, 10, 200, 20, 0);

        Entry uid2Entry1 = new Entry("if1", uid2,
                android.net.NetworkStats.SET_DEFAULT, android.net.NetworkStats.TAG_NONE,
                150, 10, 250, 20, 0);

        Entry uid2Entry2 = new Entry(
                "if2", uid2,
                android.net.NetworkStats.SET_DEFAULT, android.net.NetworkStats.TAG_NONE,
                150, 10, 250, 20, 0);

        NetworkStatsHistory history1 = new NetworkStatsHistory(10, 2);
        history1.recordData(10, 20, uid1Entry1);
        history1.recordData(20, 30, uid1Entry2);

        NetworkStatsHistory history2 = new NetworkStatsHistory(10, 2);
        history1.recordData(30, 40, uid2Entry1);
        history1.recordData(35, 45, uid2Entry2);


        when(mService.openSessionForUsageStats(anyInt(), anyString())).thenReturn(mStatsSession);
        when(mStatsSession.getRelevantUids()).thenReturn(new int[] { uid1, uid2, uid3 });

        when(mStatsSession.getHistoryIntervalForUid(any(NetworkTemplate.class),
                eq(uid1), eq(android.net.NetworkStats.SET_ALL),
                eq(android.net.NetworkStats.TAG_NONE),
                eq(NetworkStatsHistory.FIELD_ALL), eq(startTime), eq(endTime)))
                .then((InvocationOnMock inv) -> {
                    NetworkTemplate template = inv.getArgument(0);
                    assertEquals(MATCH_MOBILE_ALL, template.getMatchRule());
                    assertEquals(subscriberId, template.getSubscriberId());
                    return history1;
                });

        when(mStatsSession.getHistoryIntervalForUid(any(NetworkTemplate.class),
                eq(uid2), eq(android.net.NetworkStats.SET_ALL),
                eq(android.net.NetworkStats.TAG_NONE),
                eq(NetworkStatsHistory.FIELD_ALL), eq(startTime), eq(endTime)))
                .then((InvocationOnMock inv) -> {
                    NetworkTemplate template = inv.getArgument(0);
                    assertEquals(MATCH_MOBILE_ALL, template.getMatchRule());
                    assertEquals(subscriberId, template.getSubscriberId());
                    return history2;
                });


        NetworkStats stats = mManager.queryDetails(
                ConnectivityManager.TYPE_MOBILE, subscriberId, startTime, endTime);

        NetworkStats.Bucket bucket = new NetworkStats.Bucket();

        // First 2 buckets exactly match entry timings
        assertTrue(stats.getNextBucket(bucket));
        assertEquals(10, bucket.getStartTimeStamp());
        assertEquals(20, bucket.getEndTimeStamp());
        assertBucketMatches(uid1Entry1, bucket);

        assertTrue(stats.getNextBucket(bucket));
        assertEquals(20, bucket.getStartTimeStamp());
        assertEquals(30, bucket.getEndTimeStamp());
        assertBucketMatches(uid1Entry2, bucket);

        // 30 -> 40: contains uid2Entry1 and half of uid2Entry2
        assertTrue(stats.getNextBucket(bucket));
        assertEquals(30, bucket.getStartTimeStamp());
        assertEquals(40, bucket.getEndTimeStamp());
        assertEquals(225, bucket.getRxBytes());
        assertEquals(15, bucket.getRxPackets());
        assertEquals(375, bucket.getTxBytes());
        assertEquals(30, bucket.getTxPackets());

        // 40 -> 50: contains half of uid2Entry2
        assertTrue(stats.getNextBucket(bucket));
        assertEquals(40, bucket.getStartTimeStamp());
        assertEquals(50, bucket.getEndTimeStamp());
        assertEquals(75, bucket.getRxBytes());
        assertEquals(5, bucket.getRxPackets());
        assertEquals(125, bucket.getTxBytes());
        assertEquals(10, bucket.getTxPackets());

        assertFalse(stats.hasNextBucket());
    }

    @Test
    public void testQueryDetails_NoSubscriberId() throws RemoteException {
        final long startTime = 1;
        final long endTime = 100;
        final int uid1 = 10001;
        final int uid2 = 10002;

        when(mService.openSessionForUsageStats(anyInt(), anyString())).thenReturn(mStatsSession);
        when(mStatsSession.getRelevantUids()).thenReturn(new int[] { uid1, uid2 });

        NetworkStats stats = mManager.queryDetails(
                ConnectivityManager.TYPE_MOBILE, null, startTime, endTime);

        when(mStatsSession.getHistoryIntervalForUid(any(NetworkTemplate.class),
                anyInt(), anyInt(), anyInt(), anyInt(), anyLong(), anyLong()))
                .thenReturn(new NetworkStatsHistory(10, 0));

        verify(mStatsSession, times(1)).getHistoryIntervalForUid(
                argThat((NetworkTemplate t) ->
                        // No subscriberId: MATCH_MOBILE_WILDCARD template
                        t.getMatchRule() == NetworkTemplate.MATCH_MOBILE_WILDCARD),
                eq(uid1), eq(android.net.NetworkStats.SET_ALL),
                eq(android.net.NetworkStats.TAG_NONE),
                eq(NetworkStatsHistory.FIELD_ALL), eq(startTime), eq(endTime));

        verify(mStatsSession, times(1)).getHistoryIntervalForUid(
                argThat((NetworkTemplate t) ->
                        // No subscriberId: MATCH_MOBILE_WILDCARD template
                        t.getMatchRule() == NetworkTemplate.MATCH_MOBILE_WILDCARD),
                eq(uid2), eq(android.net.NetworkStats.SET_ALL),
                eq(android.net.NetworkStats.TAG_NONE),
                eq(NetworkStatsHistory.FIELD_ALL), eq(startTime), eq(endTime));

        assertFalse(stats.hasNextBucket());
    }

    private void assertBucketMatches(Entry expected,
            NetworkStats.Bucket actual) {
        assertEquals(expected.uid, actual.getUid());
        assertEquals(expected.rxBytes, actual.getRxBytes());
        assertEquals(expected.rxPackets, actual.getRxPackets());
        assertEquals(expected.txBytes, actual.getTxBytes());
        assertEquals(expected.txPackets, actual.getTxPackets());
    }
}
