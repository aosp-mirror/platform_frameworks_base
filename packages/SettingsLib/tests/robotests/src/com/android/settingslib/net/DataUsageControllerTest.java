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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetworkStatsSession;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSubscriptionManager;

@RunWith(RobolectricTestRunner.class)
public class DataUsageControllerTest {

    private static final String SUB_ID = "Test Subscriber";

    @Mock
    private INetworkStatsSession mSession;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private NetworkStatsManager mNetworkStatsManager;
    @Mock
    private Context mContext;

    private DataUsageController mController;
    private NetworkStatsHistory mNetworkStatsHistory;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(NetworkStatsManager.class)).thenReturn(mNetworkStatsManager);
        mController = new DataUsageController(mContext);
        mNetworkStatsHistory = spy(
                new NetworkStatsHistory(DateUtils.DAY_IN_MILLIS /* bucketDuration */));
        doReturn(mNetworkStatsHistory)
                .when(mSession).getHistoryForNetwork(any(NetworkTemplate.class), anyInt());
        final int defaultSubscriptionId = 1234;
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(defaultSubscriptionId);
        doReturn(SUB_ID).when(mTelephonyManager).getSubscriberId(eq(defaultSubscriptionId));
    }

    @Test
    public void getHistoricalUsageLevel_shouldQuerySummaryForDevice() throws Exception {

        mController.getHistoricalUsageLevel(NetworkTemplate.buildTemplateWifiWildcard());

        verify(mNetworkStatsManager).querySummaryForDevice(eq(ConnectivityManager.TYPE_WIFI),
                eq(SUB_ID), eq(0L) /* startTime */, anyLong() /* endTime */);
    }

    @Test
    public void getHistoricalUsageLevel_noUsageData_shouldReturn0() throws Exception {
        when(mNetworkStatsManager.querySummaryForDevice(eq(ConnectivityManager.TYPE_WIFI),
                eq(SUB_ID), eq(0L) /* startTime */, anyLong() /* endTime */))
                .thenReturn(mock(NetworkStats.Bucket.class));
        assertThat(mController.getHistoricalUsageLevel(NetworkTemplate.buildTemplateWifiWildcard()))
            .isEqualTo(0L);
    }

    @Test
    public void getHistoricalUsageLevel_hasUsageData_shouldReturnTotalUsage() throws Exception {
        final long receivedBytes = 743823454L;
        final long transmittedBytes = 16574289L;
        final NetworkStats.Bucket bucket = mock(NetworkStats.Bucket.class);
        when(bucket.getRxBytes()).thenReturn(receivedBytes);
        when(bucket.getTxBytes()).thenReturn(transmittedBytes);
        when(mNetworkStatsManager.querySummaryForDevice(eq(ConnectivityManager.TYPE_WIFI),
                eq(SUB_ID), eq(0L) /* startTime */, anyLong() /* endTime */)).thenReturn(bucket);

        assertThat(mController.getHistoricalUsageLevel(NetworkTemplate.buildTemplateWifiWildcard()))
                .isEqualTo(receivedBytes + transmittedBytes);
    }

    @Test
    public void getDataUsageInfo_hasUsageData_shouldReturnCorrectUsageForExplicitSubId()
            throws Exception {
        // First setup a stats bucket for the default subscription / subscriber ID.
        final long defaultSubRx = 1234567L;
        final long defaultSubTx = 123456L;
        final NetworkStats.Bucket defaultSubscriberBucket = mock(NetworkStats.Bucket.class);
        when(defaultSubscriberBucket.getRxBytes()).thenReturn(defaultSubRx);
        when(defaultSubscriberBucket.getTxBytes()).thenReturn(defaultSubTx);
        when(mNetworkStatsManager.querySummaryForDevice(eq(ConnectivityManager.TYPE_MOBILE),
                eq(SUB_ID), eq(0L)/* startTime */, anyLong() /* endTime */)).thenReturn(
                defaultSubscriberBucket);

        // Now setup a stats bucket for a different, non-default subscription / subscriber ID.
        final long nonDefaultSubRx = 7654321L;
        final long nonDefaultSubTx = 654321L;
        final NetworkStats.Bucket nonDefaultSubscriberBucket = mock(NetworkStats.Bucket.class);
        when(nonDefaultSubscriberBucket.getRxBytes()).thenReturn(nonDefaultSubRx);
        when(nonDefaultSubscriberBucket.getTxBytes()).thenReturn(nonDefaultSubTx);
        final int explictSubscriptionId = 55;
        final String subscriberId2 = "Test Subscriber 2";
        when(mNetworkStatsManager.querySummaryForDevice(eq(ConnectivityManager.TYPE_MOBILE),
                eq(subscriberId2), eq(0L)/* startTime */, anyLong() /* endTime */)).thenReturn(
                nonDefaultSubscriberBucket);
        doReturn(subscriberId2).when(mTelephonyManager).getSubscriberId(explictSubscriptionId);

        // Now verify that when we're asking for stats on the non-default subscription, we get
        // the data back for that subscription and *not* the default one.
        mController.setSubscriptionId(explictSubscriptionId);

        assertThat(mController.getHistoricalUsageLevel(
                NetworkTemplate.buildTemplateMobileAll(subscriberId2))).isEqualTo(
                nonDefaultSubRx + nonDefaultSubTx);
    }
}
