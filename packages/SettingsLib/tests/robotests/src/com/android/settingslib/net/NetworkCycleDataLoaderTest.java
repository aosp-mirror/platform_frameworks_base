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

package com.android.settingslib.net;

import static android.app.usage.NetworkStats.Bucket.UID_ALL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkTemplate;
import android.text.format.DateUtils;
import android.util.Range;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

@RunWith(RobolectricTestRunner.class)
public class NetworkCycleDataLoaderTest {

    @Mock
    private NetworkStatsManager mNetworkStatsManager;
    @Mock
    private NetworkPolicyManager mNetworkPolicyManager;
    @Mock
    private Context mContext;
    @Mock
    private NetworkPolicy mPolicy;
    @Mock
    private Iterator<Range<ZonedDateTime>> mIterator;

    private NetworkCycleDataTestLoader mLoader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.NETWORK_STATS_SERVICE))
            .thenReturn(mNetworkStatsManager);
        when(mContext.getSystemService(Context.NETWORK_POLICY_SERVICE))
            .thenReturn(mNetworkPolicyManager);
        when(mPolicy.cycleIterator()).thenReturn(mIterator);
        when(mNetworkPolicyManager.getNetworkPolicies()).thenReturn(new NetworkPolicy[0]);
    }

    @Test
    public void loadInBackground_noNetworkPolicy_shouldLoad4WeeksData() {
        mLoader = spy(new NetworkCycleDataTestLoader(mContext));
        doNothing().when(mLoader).loadFourWeeksData();

        mLoader.loadInBackground();

        verify(mLoader).loadFourWeeksData();
    }

    @Test
    public void loadInBackground_hasNetworkPolicy_shouldLoadPolicyData() {
        mLoader = spy(new NetworkCycleDataTestLoader(mContext));
        ReflectionHelpers.setField(mLoader, "mPolicy", mPolicy);

        mLoader.loadInBackground();

        verify(mLoader).loadPolicyData();
    }

    @Test
    public void loadInBackground_hasCyclePeriod_shouldLoadDataForSpecificCycles() {
        mLoader = spy(new NetworkCycleDataTestLoader(mContext));
        doNothing().when(mLoader).loadDataForSpecificCycles();
        final ArrayList<Long> cycles = new ArrayList<>();
        cycles.add(67890L);
        cycles.add(12345L);
        ReflectionHelpers.setField(mLoader, "mCycles", cycles);

        mLoader.loadInBackground();

        verify(mLoader).loadDataForSpecificCycles();
    }

    @Test
    public void loadPolicyData_shouldRecordUsageFromPolicyCycle() {
        final int networkType = ConnectivityManager.TYPE_MOBILE;
        final String subId = "TestSubscriber";
        final ZonedDateTime now = ZonedDateTime.now();
        final Range<ZonedDateTime> cycle = new Range<>(now, now);
        final long nowInMs = now.toInstant().toEpochMilli();
        // mock 1 cycle data.
        // hasNext() will be called internally in next(), hence setting it to return true twice.
        when(mIterator.hasNext()).thenReturn(true).thenReturn(true).thenReturn(false);
        when(mIterator.next()).thenReturn(cycle);
        mLoader = spy(new NetworkCycleDataTestLoader(mContext));
        ReflectionHelpers.setField(mLoader, "mPolicy", mPolicy);

        mLoader.loadPolicyData();

        verify(mLoader).recordUsage(nowInMs, nowInMs);
    }

    private NetworkStats.Bucket makeMockBucket(int uid, long rxBytes, long txBytes,
            long start, long end) {
        NetworkStats.Bucket ret = mock(NetworkStats.Bucket.class);
        when(ret.getUid()).thenReturn(uid);
        when(ret.getRxBytes()).thenReturn(rxBytes);
        when(ret.getTxBytes()).thenReturn(txBytes);
        when(ret.getStartTimeStamp()).thenReturn(start);
        when(ret.getEndTimeStamp()).thenReturn(end);
        return ret;
    }

    @Test
    public void loadFourWeeksData_shouldRecordUsageForLast4Weeks() {
        mLoader = spy(new NetworkCycleDataTestLoader(mContext));
        final long now = System.currentTimeMillis();
        final long fourWeeksAgo = now - (DateUtils.WEEK_IN_MILLIS * 4);
        final long twoDaysAgo = now - (DateUtils.DAY_IN_MILLIS * 2);
        mLoader.addBucket(makeMockBucket(UID_ALL, 123, 456, twoDaysAgo, now));

        mLoader.loadFourWeeksData();

        verify(mLoader).recordUsage(fourWeeksAgo, now);
    }

    @Test
    public void loadDataForSpecificCycles_shouldRecordUsageForSpecifiedTime() {
        mLoader = spy(new NetworkCycleDataTestLoader(mContext));
        final long now = System.currentTimeMillis();
        final long tenDaysAgo = now - (DateUtils.DAY_IN_MILLIS * 10);
        final long twentyDaysAgo = now - (DateUtils.DAY_IN_MILLIS * 20);
        final long thirtyDaysAgo = now - (DateUtils.DAY_IN_MILLIS * 30);
        final ArrayList<Long> cycles = new ArrayList<>();
        cycles.add(now);
        cycles.add(tenDaysAgo);
        cycles.add(twentyDaysAgo);
        cycles.add(thirtyDaysAgo);
        ReflectionHelpers.setField(mLoader, "mCycles", cycles);

        mLoader.loadDataForSpecificCycles();

        verify(mLoader).recordUsage(tenDaysAgo, now);
        verify(mLoader).recordUsage(twentyDaysAgo, tenDaysAgo);
        verify(mLoader).recordUsage(thirtyDaysAgo, twentyDaysAgo);
    }

    @Test
    public void getTimeRangeOf() {
        mLoader = spy(new NetworkCycleDataTestLoader(mContext));
        // If empty, new Range(MAX_VALUE, MIN_VALUE) will be constructed. Hence, the function
        // should throw.
        assertThrows(IllegalArgumentException.class,
                () -> mLoader.getTimeRangeOf(mock(NetworkStats.class)));

        mLoader.addBucket(makeMockBucket(UID_ALL, 123, 456, 0, 10));
        // Feed the function with unused NetworkStats. The actual data injection is
        // done by addBucket.
        assertEquals(new Range(0L, 10L), mLoader.getTimeRangeOf(mock(NetworkStats.class)));

        mLoader.addBucket(makeMockBucket(UID_ALL, 123, 456, 0, 10));
        mLoader.addBucket(makeMockBucket(UID_ALL, 123, 456, 30, 40));
        mLoader.addBucket(makeMockBucket(UID_ALL, 123, 456, 10, 25));
        assertEquals(new Range(0L, 40L), mLoader.getTimeRangeOf(mock(NetworkStats.class)));
    }

    public class NetworkCycleDataTestLoader extends NetworkCycleDataLoader<List<NetworkCycleData>> {
        private final Queue<NetworkStats.Bucket> mMockedBuckets = new LinkedBlockingQueue<>();

        private NetworkCycleDataTestLoader(Context context) {
            super(NetworkCycleDataLoader.builder(mContext)
                    .setNetworkTemplate(mock(NetworkTemplate.class)));
            mContext = context;
        }

        @Override
        void recordUsage(long start, long end) {
        }

        @Override
        List<NetworkCycleData> getCycleUsage() {
            return null;
        }

        public void addBucket(NetworkStats.Bucket bucket) {
            mMockedBuckets.add(bucket);
        }

        @Override
        public boolean hasNextBucket(@NonNull NetworkStats unused) {
            return !mMockedBuckets.isEmpty();
        }

        @Override
        public NetworkStats.Bucket getNextBucket(@NonNull NetworkStats unused) {
            return mMockedBuckets.remove();
        }
    }
}
