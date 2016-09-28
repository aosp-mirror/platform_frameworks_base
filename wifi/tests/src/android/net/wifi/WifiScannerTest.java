/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.WifiScanner.BssidInfo;
import android.net.wifi.WifiScanner.BssidListener;
import android.os.Handler;
import android.os.Message;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.util.test.BidirectionalAsyncChannelServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link android.net.wifi.WifiScanner}.
 */
@SmallTest
public class WifiScannerTest {
    @Mock
    private Context mContext;
    @Mock
    private IWifiScanner mService;
    @Mock
    private BssidListener mBssidListener;

    private WifiScanner mWifiScanner;
    private TestLooper mLooper;
    private Handler mHandler;

    /**
     * Setup before tests.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        mHandler = mock(Handler.class);
        BidirectionalAsyncChannelServer server = new BidirectionalAsyncChannelServer(
                mContext, mLooper.getLooper(), mHandler);
        when(mService.getMessenger()).thenReturn(server.getMessenger());
        mWifiScanner = new WifiScanner(mContext, mService, mLooper.getLooper());
        mLooper.dispatchAll();
    }

    /**
     * Clean up after tests.
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private void verifySetHotlistMessage(Handler handler) {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(handler, atLeastOnce()).handleMessage(messageCaptor.capture());
        assertEquals("message.what is not CMD_SET_HOTLIST",
                WifiScanner.CMD_SET_HOTLIST,
                messageCaptor.getValue().what);
    }

    /**
     * Test duplicate listeners for bssid tracking.
     */
    @Test
    public void testStartTrackingBssidsDuplicateListeners() throws Exception {
        BssidInfo[] bssids = new BssidInfo[] {
                new BssidInfo()
        };

        // First start tracking succeeds.
        mWifiScanner.startTrackingBssids(bssids, -100, mBssidListener);
        mLooper.dispatchAll();
        verifySetHotlistMessage(mHandler);

        // Second start tracking should fail.
        mWifiScanner.startTrackingBssids(bssids, -100, mBssidListener);
        mLooper.dispatchAll();
        verify(mBssidListener).onFailure(eq(WifiScanner.REASON_DUPLICATE_REQEUST), anyString());
    }
}
