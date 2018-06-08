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

package android.net.wifi.p2p;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.test.TestLooper;

import libcore.junit.util.ResourceLeakageDetector;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test harness for WifiP2pManager.
 */
public class WifiP2pManagerTest {
    private WifiP2pManager mDut;
    private TestLooper mTestLooper;

    @Mock
    public Context mContextMock;
    @Mock
    IWifiP2pManager mP2pServiceMock;

    @Rule
    public ResourceLeakageDetector.LeakageDetectorRule leakageDetectorRule =
            ResourceLeakageDetector.getRule();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDut = new WifiP2pManager(mP2pServiceMock);
        mTestLooper = new TestLooper();
    }

    /**
     * Validate that on finalize we close the channel and flag a resource leakage.
     */
    @Test
    public void testChannelFinalize() throws Exception {
        try (WifiP2pManager.Channel channel = new WifiP2pManager.Channel(mContextMock,
                mTestLooper.getLooper(), null, null, mDut)) {
            leakageDetectorRule.assertUnreleasedResourceCount(channel, 1);
        }
    }

    /**
     * Validate that when close is called on a channel it frees up resources (i.e. don't
     * get flagged again on finalize).
     */
    @Test
    public void testChannelClose() throws Exception {
        WifiP2pManager.Channel channel = new WifiP2pManager.Channel(mContextMock,
                mTestLooper.getLooper(), null, null, mDut);

        channel.close();
        verify(mP2pServiceMock).close(any());

        leakageDetectorRule.assertUnreleasedResourceCount(channel, 0);
    }
}
