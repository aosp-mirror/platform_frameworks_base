/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.connectivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.metrics.IpConnectivityLog;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.TelephonyManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkMonitorTest {

    static final int TEST_ID = 60; // should be less than min netid 100

    @Mock Context mContext;
    @Mock Handler mHandler;
    @Mock IpConnectivityLog mLogger;
    @Mock NetworkAgentInfo mAgent;
    @Mock NetworkMonitor.NetworkMonitorSettings mSettings;
    @Mock NetworkRequest mRequest;
    @Mock TelephonyManager mTelephony;
    @Mock WifiManager mWifi;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mAgent.network()).thenReturn(new Network(TEST_ID));
        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephony);
        when(mContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mWifi);
    }

    NetworkMonitor makeMonitor() {
        return new NetworkMonitor(mContext, mHandler, mAgent, mRequest, mLogger, mSettings);
    }

    @Test
    public void testCreatingNetworkMonitor() {
        NetworkMonitor monitor = makeMonitor();
    }
}

