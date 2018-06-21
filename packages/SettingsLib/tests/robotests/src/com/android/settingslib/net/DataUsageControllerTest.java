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

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.INetworkStatsSession;
import android.net.NetworkStatsHistory;
import android.net.NetworkStatsHistory.Entry;
import android.net.NetworkTemplate;
import android.os.RemoteException;
import android.text.format.DateUtils;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class DataUsageControllerTest {

    @Mock
    private INetworkStatsSession mSession;

    private Context mContext;
    private DataUsageController mController;
    private NetworkStatsHistory mNetworkStatsHistory;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mController = spy(new DataUsageController(mContext));
        mNetworkStatsHistory = spy(
                new NetworkStatsHistory(DateUtils.DAY_IN_MILLIS /* bucketDuration */));
        doReturn(mNetworkStatsHistory)
                .when(mSession).getHistoryForNetwork(any(NetworkTemplate.class), anyInt());
    }

    @Test
    public void getHistoriclUsageLevel_noNetworkSession_shouldReturn0() {
        doReturn(null).when(mController).getSession();

        assertThat(mController.getHistoriclUsageLevel(null /* template */)).isEqualTo(0L);

    }

    @Test
    public void getHistoriclUsageLevel_noUsageData_shouldReturn0() {
        doReturn(mSession).when(mController).getSession();

        assertThat(mController.getHistoriclUsageLevel(NetworkTemplate.buildTemplateWifiWildcard()))
                .isEqualTo(0L);

    }

    @Test
    public void getHistoriclUsageLevel_hasUsageData_shouldReturnTotalUsage() {
        doReturn(mSession).when(mController).getSession();
        final long receivedBytes = 743823454L;
        final long transmittedBytes = 16574289L;
        final Entry entry = new Entry();
        entry.bucketStart = 1521583200000L;
        entry.rxBytes = receivedBytes;
        entry.txBytes = transmittedBytes;
        when(mNetworkStatsHistory.getValues(eq(0L), anyLong(), anyLong(), nullable(Entry.class)))
                .thenReturn(entry);

        assertThat(mController.getHistoriclUsageLevel(NetworkTemplate.buildTemplateWifiWildcard()))
                .isEqualTo(receivedBytes + transmittedBytes);

    }
}
