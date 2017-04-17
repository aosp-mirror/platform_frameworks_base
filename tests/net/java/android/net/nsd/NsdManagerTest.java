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

package android.net.nsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;

import android.os.HandlerThread;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.os.Message;
import android.os.Messenger;
import com.android.internal.util.AsyncChannel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NsdManagerTest {

    @Mock Context mContext;
    @Mock INsdManager mService;
    MockServiceHandler mServiceHandler;

    long mTimeoutMs = 100; // non-final so that tests can adjust the value.

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mServiceHandler = spy(MockServiceHandler.create(mContext));
        when(mService.getMessenger()).thenReturn(new Messenger(mServiceHandler));
    }

    @Test
    public void testResolveService() {
        NsdManager manager = makeManager();

        NsdServiceInfo request = new NsdServiceInfo("a_name", "a_type");
        NsdServiceInfo reply = new NsdServiceInfo("resolved_name", "resolved_type");
        NsdManager.ResolveListener listener = mock(NsdManager.ResolveListener.class);

        manager.resolveService(request, listener);
        int key1 = verifyRequest(NsdManager.RESOLVE_SERVICE);
        int err = 33;
        sendResponse(NsdManager.RESOLVE_SERVICE_FAILED, err, key1, null);
        verify(listener, timeout(mTimeoutMs).times(1)).onResolveFailed(request, err);

        manager.resolveService(request, listener);
        int key2 = verifyRequest(NsdManager.RESOLVE_SERVICE);
        sendResponse(NsdManager.RESOLVE_SERVICE_SUCCEEDED, 0, key2, reply);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceResolved(reply);
    }

    @Test
    public void testParallelResolveService() {
        NsdManager manager = makeManager();

        NsdServiceInfo request = new NsdServiceInfo("a_name", "a_type");
        NsdServiceInfo reply = new NsdServiceInfo("resolved_name", "resolved_type");

        NsdManager.ResolveListener listener1 = mock(NsdManager.ResolveListener.class);
        NsdManager.ResolveListener listener2 = mock(NsdManager.ResolveListener.class);

        manager.resolveService(request, listener1);
        int key1 = verifyRequest(NsdManager.RESOLVE_SERVICE);

        manager.resolveService(request, listener2);
        int key2 = verifyRequest(NsdManager.RESOLVE_SERVICE);

        sendResponse(NsdManager.RESOLVE_SERVICE_SUCCEEDED, 0, key2, reply);
        sendResponse(NsdManager.RESOLVE_SERVICE_SUCCEEDED, 0, key1, reply);

        verify(listener1, timeout(mTimeoutMs).times(1)).onServiceResolved(reply);
        verify(listener2, timeout(mTimeoutMs).times(1)).onServiceResolved(reply);
   }

    NsdManager makeManager() {
        NsdManager manager = new NsdManager(mContext, mService);
        // Acknowledge first two messages connecting the AsyncChannel.
        verify(mServiceHandler, timeout(mTimeoutMs).times(2)).handleMessage(any());
        reset(mServiceHandler);
        assertNotNull(mServiceHandler.chan);
        return manager;
    }

    int verifyRequest(int expectedMessageType) {
        verify(mServiceHandler, timeout(mTimeoutMs)).handleMessage(any());
        reset(mServiceHandler);
        Message received = mServiceHandler.lastMessage;
        assertEquals(NsdManager.nameOf(expectedMessageType), NsdManager.nameOf(received.what));
        return received.arg2;
    }

    void sendResponse(int replyType, int arg, int key, Object obj) {
        mServiceHandler.chan.sendMessage(replyType, arg, key, obj);
    }

    // Implements the server side of AsyncChannel connection protocol
    public static class MockServiceHandler extends Handler {
        public Context mContext;
        public AsyncChannel chan;
        public Message lastMessage;

        MockServiceHandler(Looper looper, Context context) {
            super(looper);
            mContext = context;
        }

        @Override
        public void handleMessage(Message msg) {
            lastMessage = obtainMessage();
            lastMessage.copyFrom(msg);
            if (msg.what == AsyncChannel.CMD_CHANNEL_FULL_CONNECTION) {
                chan = new AsyncChannel();
                chan.connect(mContext, this, msg.replyTo);
                chan.sendMessage(AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED);
            }

        }

        public static MockServiceHandler create(Context context) {
            HandlerThread t = new HandlerThread("mock-service-handler");
            t.start();
            return new MockServiceHandler(t.getLooper(), context);
        }
    }
}
