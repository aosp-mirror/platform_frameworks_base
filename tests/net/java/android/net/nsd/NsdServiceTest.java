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

package com.android.server;

import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.test.TestLooper;
import android.content.Context;
import android.content.ContentResolver;
import android.net.nsd.NsdManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

// TODOs:
//  - test daemon connection
//  - test client disconnects
//  - test client can send requests and receive replies
//  - test NSD_ON ENABLE/DISABLED listening
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NsdServiceTest {

    @Mock Context mContext;
    @Mock ContentResolver mResolver;
    @Mock NsdService.NsdSettings mSettings;
    TestLooper mLooper;
    TestHandler mHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        mHandler = new TestHandler(mLooper.getLooper());
        when(mContext.getContentResolver()).thenReturn(mResolver);
    }

    @Test
    public void testClientsCanConnect() {
        NsdService service = makeService();
        NsdManager client1 = connectClient(service);
        NsdManager client2 = connectClient(service);

        // TODO: disconnect client1
        // TODO: disconnect client2
    }

    NsdService makeService() {
        return new NsdService(mContext, mSettings, mHandler);
    }

    NsdManager connectClient(NsdService service) {
        mLooper.startAutoDispatch();
        NsdManager client = new NsdManager(mContext, service);
        mLooper.stopAutoDispatch();
        return client;
    }

    public static class TestHandler extends Handler {
        public Message lastMessage;

        TestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            lastMessage = obtainMessage();
            lastMessage.copyFrom(msg);
        }
    }
}
