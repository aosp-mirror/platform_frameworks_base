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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.os.RemoteException;
import android.text.format.DateUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class NetworkCycleChartDataLoaderTest {

    @Mock
    private NetworkStatsManager mNetworkStatsManager;
    @Mock
    private NetworkPolicyManager mNetworkPolicyManager;
    @Mock
    private Context mContext;

    private NetworkCycleChartDataLoader mLoader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.NETWORK_STATS_SERVICE))
            .thenReturn(mNetworkStatsManager);
        when(mContext.getSystemService(Context.NETWORK_POLICY_SERVICE))
            .thenReturn(mNetworkPolicyManager);
        when(mNetworkPolicyManager.getNetworkPolicies()).thenReturn(new NetworkPolicy[0]);
    }

    @Test
    public void recordUsage_shouldQueryNetworkSummaryForDevice() throws RemoteException {
        final long end = System.currentTimeMillis();
        final long start = end - (DateUtils.WEEK_IN_MILLIS * 4);
        final int networkType = ConnectivityManager.TYPE_MOBILE;
        final String subId = "TestSubscriber";
        mLoader = NetworkCycleChartDataLoader.builder(mContext)
            .setSubscriberId(subId).build();

        mLoader.recordUsage(start, end);

        verify(mNetworkStatsManager).querySummaryForDevice(networkType, subId, start, end);
    }
}
