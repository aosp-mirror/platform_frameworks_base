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

import static android.app.usage.NetworkStats.Bucket.STATE_FOREGROUND;
import static android.net.NetworkStats.TAG_NONE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkTemplate;
import android.text.format.DateUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class NetworkCycleDataForUidLoaderTest {
    private static final String SUB_ID = "Test Subscriber";

    @Mock
    private NetworkStatsManager mNetworkStatsManager;
    @Mock
    private NetworkPolicyManager mNetworkPolicyManager;
    @Mock
    private Context mContext;
    private NetworkTemplate mNetworkTemplate;

    private NetworkCycleDataForUidLoader mLoader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.NETWORK_STATS_SERVICE))
                .thenReturn(mNetworkStatsManager);
        when(mContext.getSystemService(Context.NETWORK_POLICY_SERVICE))
                .thenReturn(mNetworkPolicyManager);
        when(mNetworkPolicyManager.getNetworkPolicies()).thenReturn(new NetworkPolicy[0]);
        mNetworkTemplate = NetworkTemplate.buildTemplateMobileAll(SUB_ID);
    }

    @Test
    public void recordUsage_shouldQueryNetworkDetailsForUidAndForegroundState() {
        final long end = System.currentTimeMillis();
        final long start = end - (DateUtils.WEEK_IN_MILLIS * 4);
        final int uid = 1;
        mLoader = spy(NetworkCycleDataForUidLoader.builder(mContext)
                .addUid(uid)
                .setNetworkTemplate(mNetworkTemplate)
                .build());
        doReturn(1024L).when(mLoader).getTotalUsage(any());

        mLoader.recordUsage(start, end);

        verify(mNetworkStatsManager).queryDetailsForUid(mNetworkTemplate, start, end, uid);
        verify(mNetworkStatsManager).queryDetailsForUidTagState(
                mNetworkTemplate, start, end, uid, TAG_NONE, STATE_FOREGROUND);
    }

    @Test
    public void recordUsage_retrieveDetailIsFalse_shouldNotQueryNetworkForegroundState() {
        final long end = System.currentTimeMillis();
        final long start = end - (DateUtils.WEEK_IN_MILLIS * 4);
        final int uid = 1;
        mLoader = spy(NetworkCycleDataForUidLoader.builder(mContext)
                .setRetrieveDetail(false).addUid(uid).build());
        doReturn(1024L).when(mLoader).getTotalUsage(any());

        mLoader.recordUsage(start, end);
        verify(mNetworkStatsManager, never()).queryDetailsForUidTagState(
                mNetworkTemplate, start, end, uid, TAG_NONE, STATE_FOREGROUND);
    }

    @Test
    public void recordUsage_multipleUids_shouldQueryNetworkDetailsForEachUid() {
        final long end = System.currentTimeMillis();
        final long start = end - (DateUtils.WEEK_IN_MILLIS * 4);
        mLoader = spy(NetworkCycleDataForUidLoader.builder(mContext)
                .addUid(1)
                .addUid(2)
                .addUid(3)
                .setNetworkTemplate(mNetworkTemplate)
                .build());
        doReturn(1024L).when(mLoader).getTotalUsage(any());

        mLoader.recordUsage(start, end);

        verify(mNetworkStatsManager).queryDetailsForUid(mNetworkTemplate, start, end, 1);
        verify(mNetworkStatsManager).queryDetailsForUid(mNetworkTemplate, start, end, 2);
        verify(mNetworkStatsManager).queryDetailsForUid(mNetworkTemplate, start, end, 3);
    }

}
