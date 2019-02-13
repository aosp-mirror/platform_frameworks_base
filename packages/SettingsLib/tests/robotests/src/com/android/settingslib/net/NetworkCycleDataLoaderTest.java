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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.util.Range;

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
    @Mock
    private INetworkStatsService mNetworkStatsService;

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
        ReflectionHelpers.setField(mLoader, "mNetworkType", networkType);
        ReflectionHelpers.setField(mLoader, "mSubId", subId);

        mLoader.loadPolicyData();

        verify(mLoader).recordUsage(nowInMs, nowInMs);
    }

    @Test
    public void loadFourWeeksData_shouldRecordUsageForLast4Weeks() throws RemoteException {
        mLoader = spy(new NetworkCycleDataTestLoader(mContext));
        ReflectionHelpers.setField(mLoader, "mNetworkStatsService", mNetworkStatsService);
        final INetworkStatsSession networkSession = mock(INetworkStatsSession.class);
        when(mNetworkStatsService.openSession()).thenReturn(networkSession);
        final NetworkStatsHistory networkHistory = mock(NetworkStatsHistory.class);
        when(networkSession.getHistoryForNetwork(nullable(NetworkTemplate.class), anyInt()))
            .thenReturn(networkHistory);
        final long now = System.currentTimeMillis();
        final long fourWeeksAgo = now - (DateUtils.WEEK_IN_MILLIS * 4);
        final long twoDaysAgo = now - (DateUtils.DAY_IN_MILLIS * 2);
        when(networkHistory.getStart()).thenReturn(twoDaysAgo);
        when(networkHistory.getEnd()).thenReturn(now);

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

    public class NetworkCycleDataTestLoader extends NetworkCycleDataLoader<List<NetworkCycleData>> {

        private NetworkCycleDataTestLoader(Context context) {
            super(NetworkCycleDataLoader.builder(mContext));
            mContext = context;
        }

        @Override
        void recordUsage(long start, long end) {
        }

        @Override
        List<NetworkCycleData> getCycleUsage() {
            return null;
        }
    }
}
