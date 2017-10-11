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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.android.internal.util.TestUtils.waitForIdleHandler;

import android.os.HandlerThread;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.os.Message;
import android.os.Messenger;
import com.android.internal.util.AsyncChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NsdManagerTest {

    static final int PROTOCOL = NsdManager.PROTOCOL_DNS_SD;

    @Mock Context mContext;
    @Mock INsdManager mService;
    MockServiceHandler mServiceHandler;

    NsdManager mManager;

    long mTimeoutMs = 200; // non-final so that tests can adjust the value.

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mServiceHandler = spy(MockServiceHandler.create(mContext));
        when(mService.getMessenger()).thenReturn(new Messenger(mServiceHandler));

        mManager = makeManager();
    }

    @After
    public void tearDown() throws Exception {
        mServiceHandler.waitForIdle(mTimeoutMs);
        mServiceHandler.chan.disconnect();
        mServiceHandler.stop();
        if (mManager != null) {
            mManager.disconnect();
        }
    }

    @Test
    public void testResolveService() {
        NsdManager manager = mManager;

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
        NsdManager manager = mManager;

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

    @Test
    public void testRegisterService() {
        NsdManager manager = mManager;

        NsdServiceInfo request1 = new NsdServiceInfo("a_name", "a_type");
        NsdServiceInfo request2 = new NsdServiceInfo("another_name", "another_type");
        request1.setPort(2201);
        request2.setPort(2202);
        NsdManager.RegistrationListener listener1 = mock(NsdManager.RegistrationListener.class);
        NsdManager.RegistrationListener listener2 = mock(NsdManager.RegistrationListener.class);

        // Register two services
        manager.registerService(request1, PROTOCOL, listener1);
        int key1 = verifyRequest(NsdManager.REGISTER_SERVICE);

        manager.registerService(request2, PROTOCOL, listener2);
        int key2 = verifyRequest(NsdManager.REGISTER_SERVICE);

        // First reques fails, second request succeeds
        sendResponse(NsdManager.REGISTER_SERVICE_SUCCEEDED, 0, key2, request2);
        verify(listener2, timeout(mTimeoutMs).times(1)).onServiceRegistered(request2);

        int err = 1;
        sendResponse(NsdManager.REGISTER_SERVICE_FAILED, err, key1, request1);
        verify(listener1, timeout(mTimeoutMs).times(1)).onRegistrationFailed(request1, err);

        // Client retries first request, it succeeds
        manager.registerService(request1, PROTOCOL, listener1);
        int key3 = verifyRequest(NsdManager.REGISTER_SERVICE);

        sendResponse(NsdManager.REGISTER_SERVICE_SUCCEEDED, 0, key3, request1);
        verify(listener1, timeout(mTimeoutMs).times(1)).onServiceRegistered(request1);

        // First request is unregistered, it succeeds
        manager.unregisterService(listener1);
        int key3again = verifyRequest(NsdManager.UNREGISTER_SERVICE);
        assertEquals(key3, key3again);

        sendResponse(NsdManager.UNREGISTER_SERVICE_SUCCEEDED, 0, key3again, null);
        verify(listener1, timeout(mTimeoutMs).times(1)).onServiceUnregistered(request1);

        // Second request is unregistered, it fails
        manager.unregisterService(listener2);
        int key2again = verifyRequest(NsdManager.UNREGISTER_SERVICE);
        assertEquals(key2, key2again);

        sendResponse(NsdManager.UNREGISTER_SERVICE_FAILED, err, key2again, null);
        verify(listener2, timeout(mTimeoutMs).times(1)).onUnregistrationFailed(request2, err);

        // TODO: do not unregister listener until service is unregistered
        // Client retries unregistration of second request, it succeeds
        //manager.unregisterService(listener2);
        //int key2yetAgain = verifyRequest(NsdManager.UNREGISTER_SERVICE);
        //assertEquals(key2, key2yetAgain);

        //sendResponse(NsdManager.UNREGISTER_SERVICE_SUCCEEDED, 0, key2yetAgain, null);
        //verify(listener2, timeout(mTimeoutMs).times(1)).onServiceUnregistered(request2);
    }

    @Test
    public void testDiscoverService() {
        NsdManager manager = mManager;

        NsdServiceInfo reply1 = new NsdServiceInfo("a_name", "a_type");
        NsdServiceInfo reply2 = new NsdServiceInfo("another_name", "a_type");
        NsdServiceInfo reply3 = new NsdServiceInfo("a_third_name", "a_type");

        NsdManager.DiscoveryListener listener = mock(NsdManager.DiscoveryListener.class);

        // Client registers for discovery, request fails
        manager.discoverServices("a_type", PROTOCOL, listener);
        int key1 = verifyRequest(NsdManager.DISCOVER_SERVICES);

        int err = 1;
        sendResponse(NsdManager.DISCOVER_SERVICES_FAILED, err, key1, null);
        verify(listener, timeout(mTimeoutMs).times(1)).onStartDiscoveryFailed("a_type", err);

        // Client retries, request succeeds
        manager.discoverServices("a_type", PROTOCOL, listener);
        int key2 = verifyRequest(NsdManager.DISCOVER_SERVICES);

        sendResponse(NsdManager.DISCOVER_SERVICES_STARTED, 0, key2, reply1);
        verify(listener, timeout(mTimeoutMs).times(1)).onDiscoveryStarted("a_type");


        // mdns notifies about services
        sendResponse(NsdManager.SERVICE_FOUND, 0, key2, reply1);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceFound(reply1);

        sendResponse(NsdManager.SERVICE_FOUND, 0, key2, reply2);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceFound(reply2);

        sendResponse(NsdManager.SERVICE_LOST, 0, key2, reply2);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceLost(reply2);


        // Client unregisters its listener
        manager.stopServiceDiscovery(listener);
        int key2again = verifyRequest(NsdManager.STOP_DISCOVERY);
        assertEquals(key2, key2again);

        // TODO: unregister listener immediately and stop notifying it about services
        // Notifications are still passed to the client's listener
        sendResponse(NsdManager.SERVICE_LOST, 0, key2, reply1);
        verify(listener, timeout(mTimeoutMs).times(1)).onServiceLost(reply1);

        // Client is notified of complete unregistration
        sendResponse(NsdManager.STOP_DISCOVERY_SUCCEEDED, 0, key2again, "a_type");
        verify(listener, timeout(mTimeoutMs).times(1)).onDiscoveryStopped("a_type");

        // Notifications are not passed to the client anymore
        sendResponse(NsdManager.SERVICE_FOUND, 0, key2, reply3);
        verify(listener, timeout(mTimeoutMs).times(0)).onServiceLost(reply3);


        // Client registers for service discovery
        reset(listener);
        manager.discoverServices("a_type", PROTOCOL, listener);
        int key3 = verifyRequest(NsdManager.DISCOVER_SERVICES);

        sendResponse(NsdManager.DISCOVER_SERVICES_STARTED, 0, key3, reply1);
        verify(listener, timeout(mTimeoutMs).times(1)).onDiscoveryStarted("a_type");

        // Client unregisters immediately, it fails
        manager.stopServiceDiscovery(listener);
        int key3again = verifyRequest(NsdManager.STOP_DISCOVERY);
        assertEquals(key3, key3again);

        err = 2;
        sendResponse(NsdManager.STOP_DISCOVERY_FAILED, err, key3again, "a_type");
        verify(listener, timeout(mTimeoutMs).times(1)).onStopDiscoveryFailed("a_type", err);

        // New notifications are not passed to the client anymore
        sendResponse(NsdManager.SERVICE_FOUND, 0, key3, reply1);
        verify(listener, timeout(mTimeoutMs).times(0)).onServiceFound(reply1);
    }

    @Test
    public void testInvalidCalls() {
        NsdManager manager = mManager;

        NsdManager.RegistrationListener listener1 = mock(NsdManager.RegistrationListener.class);
        NsdManager.DiscoveryListener listener2 = mock(NsdManager.DiscoveryListener.class);
        NsdManager.ResolveListener listener3 = mock(NsdManager.ResolveListener.class);

        NsdServiceInfo invalidService = new NsdServiceInfo(null, null);
        NsdServiceInfo validService = new NsdServiceInfo("a_name", "a_type");
        validService.setPort(2222);

        // Service registration
        //  - invalid arguments
        mustFail(() -> { manager.unregisterService(null); });
        mustFail(() -> { manager.registerService(null, -1, null); });
        mustFail(() -> { manager.registerService(null, PROTOCOL, listener1); });
        mustFail(() -> { manager.registerService(invalidService, PROTOCOL, listener1); });
        mustFail(() -> { manager.registerService(validService, -1, listener1); });
        mustFail(() -> { manager.registerService(validService, PROTOCOL, null); });
        manager.registerService(validService, PROTOCOL, listener1);
        //  - listener already registered
        mustFail(() -> { manager.registerService(validService, PROTOCOL, listener1); });
        manager.unregisterService(listener1);
        // TODO: make listener immediately reusable
        //mustFail(() -> { manager.unregisterService(listener1); });
        //manager.registerService(validService, PROTOCOL, listener1);

        // Discover service
        //  - invalid arguments
        mustFail(() -> { manager.stopServiceDiscovery(null); });
        mustFail(() -> { manager.discoverServices(null, -1, null); });
        mustFail(() -> { manager.discoverServices(null, PROTOCOL, listener2); });
        mustFail(() -> { manager.discoverServices("a_service", -1, listener2); });
        mustFail(() -> { manager.discoverServices("a_service", PROTOCOL, null); });
        manager.discoverServices("a_service", PROTOCOL, listener2);
        //  - listener already registered
        mustFail(() -> { manager.discoverServices("another_service", PROTOCOL, listener2); });
        manager.stopServiceDiscovery(listener2);
        // TODO: make listener immediately reusable
        //mustFail(() -> { manager.stopServiceDiscovery(listener2); });
        //manager.discoverServices("another_service", PROTOCOL, listener2);

        // Resolver service
        //  - invalid arguments
        mustFail(() -> { manager.resolveService(null, null); });
        mustFail(() -> { manager.resolveService(null, listener3); });
        mustFail(() -> { manager.resolveService(invalidService, listener3); });
        mustFail(() -> { manager.resolveService(validService, null); });
        manager.resolveService(validService, listener3);
        //  - listener already registered:w
        mustFail(() -> { manager.resolveService(validService, listener3); });
    }

    public void mustFail(Runnable fn) {
        try {
            fn.run();
            fail();
        } catch (Exception expected) {
        }
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
        mServiceHandler.waitForIdle(mTimeoutMs);
        verify(mServiceHandler, timeout(mTimeoutMs)).handleMessage(any());
        reset(mServiceHandler);
        Message received = mServiceHandler.getLastMessage();
        assertEquals(NsdManager.nameOf(expectedMessageType), NsdManager.nameOf(received.what));
        return received.arg2;
    }

    void sendResponse(int replyType, int arg, int key, Object obj) {
        mServiceHandler.chan.sendMessage(replyType, arg, key, obj);
    }

    // Implements the server side of AsyncChannel connection protocol
    public static class MockServiceHandler extends Handler {
        public final Context context;
        public AsyncChannel chan;
        public Message lastMessage;

        MockServiceHandler(Looper l, Context c) {
            super(l);
            context = c;
        }

        synchronized Message getLastMessage() {
            return lastMessage;
        }

        synchronized void setLastMessage(Message msg) {
            lastMessage = obtainMessage();
            lastMessage.copyFrom(msg);
        }

        void waitForIdle(long timeoutMs) {
            waitForIdleHandler(this, timeoutMs);
        }

        @Override
        public void handleMessage(Message msg) {
            setLastMessage(msg);
            if (msg.what == AsyncChannel.CMD_CHANNEL_FULL_CONNECTION) {
                chan = new AsyncChannel();
                chan.connect(context, this, msg.replyTo);
                chan.sendMessage(AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED);
            }
        }

        void stop() {
            getLooper().quitSafely();
        }

        static MockServiceHandler create(Context context) {
            HandlerThread t = new HandlerThread("mock-service-handler");
            t.start();
            return new MockServiceHandler(t.getLooper(), context);
        }
    }
}
