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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Handler;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.util.test.BidirectionalAsyncChannelServer;

import org.junit.After;
import org.junit.Before;
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

}
