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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Matchers.nullable;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkPolicy;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.util.Range;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.util.ReflectionHelpers;

import java.time.ZonedDateTime;
import java.util.Iterator;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class NetworkCycleDataLoaderTest {

    @Mock
    private NetworkStatsManager mNetworkStatsManager;
    @Mock
    private Context mContext;
    @Mock
    private NetworkPolicy mPolicy;
    @Mock
    private Iterator<Range<ZonedDateTime>> mIterator;
    @Mock
    private INetworkStatsService mNetworkStatsService;

    private NetworkCycleDataLoader mLoader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.NETWORK_STATS_SERVICE))
            .thenReturn(mNetworkStatsManager);
        when(mPolicy.cycleIterator()).thenReturn(mIterator);
    }

    @Test
    public void loadInBackground_noNetworkPolicy_shouldLoad4WeeksData() {
        mLoader = spy(new NetworkCycleDataLoader.Builder(mContext).build());
        doReturn(null).when(mLoader).loadFourWeeksData();

        mLoader.loadInBackground();

        verify(mLoader).loadFourWeeksData();
    }

    @Test
    public void loadInBackground_shouldQueryNetworkSummary() throws RemoteException {
        final int networkType = ConnectivityManager.TYPE_MOBILE;
        final String subId = "TestSubscriber";
        final ZonedDateTime now = ZonedDateTime.now();
        final Range<ZonedDateTime> cycle = new Range<>(now, now);
        // mock 1 cycle data.
        // hasNext() will be called internally in next(), hence setting it to return true twice.
        when(mIterator.hasNext()).thenReturn(true).thenReturn(true).thenReturn(false);
        when(mIterator.next()).thenReturn(cycle);
        mLoader = new NetworkCycleDataLoader.Builder(mContext)
            .setNetworkPolicy(mPolicy).setNetworkType(networkType).setSubscriberId(subId).build();

        mLoader.loadInBackground();

        verify(mNetworkStatsManager).querySummary(eq(networkType), eq(subId), anyLong(), anyLong());
    }

    @Test
    public void loadFourWeeksData_shouldGetUsageForLast4Weeks() throws RemoteException {
        mLoader = spy(new NetworkCycleDataLoader.Builder(mContext).build());
        ReflectionHelpers.setField(mLoader, "mNetworkStatsService", mNetworkStatsService);
        final INetworkStatsSession networkSession = mock(INetworkStatsSession.class);
        when(mNetworkStatsService.openSession()).thenReturn(networkSession);
        final NetworkStatsHistory networkHistory = mock(NetworkStatsHistory.class);
        when(networkSession.getHistoryForNetwork(nullable(NetworkTemplate.class), anyInt())).thenReturn(networkHistory);
        final long now = System.currentTimeMillis();
        final long fourWeeksAgo = now - (DateUtils.WEEK_IN_MILLIS * 4);
        when(networkHistory.getStart()).thenReturn(fourWeeksAgo);
        when(networkHistory.getEnd()).thenReturn(now);

        mLoader.loadFourWeeksData();

        verify(mLoader).getUsage(eq(fourWeeksAgo), eq(now), any());
    }
}
