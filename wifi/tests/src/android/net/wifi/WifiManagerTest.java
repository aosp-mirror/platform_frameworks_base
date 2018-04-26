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

package android.net.wifi;

import static android.net.wifi.WifiManager.HOTSPOT_FAILED;
import static android.net.wifi.WifiManager.HOTSPOT_STARTED;
import static android.net.wifi.WifiManager.HOTSPOT_STOPPED;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.REQUEST_REGISTERED;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_GENERAL;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_NO_CHANNEL;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiManager.LocalOnlyHotspotObserver;
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation;
import android.net.wifi.WifiManager.LocalOnlyHotspotSubscription;
import android.net.wifi.WifiManager.SoftApCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link android.net.wifi.WifiManager}.
 */
@SmallTest
public class WifiManagerTest {

    private static final int ERROR_NOT_SET = -1;
    private static final int ERROR_TEST_REASON = 5;
    private static final String TEST_PACKAGE_NAME = "TestPackage";
    private static final String TEST_COUNTRY_CODE = "US";

    @Mock Context mContext;
    @Mock IWifiManager mWifiService;
    @Mock ApplicationInfo mApplicationInfo;
    @Mock WifiConfiguration mApConfig;
    @Mock IBinder mAppBinder;
    @Mock SoftApCallback mSoftApCallback;

    private Handler mHandler;
    private TestLooper mLooper;
    private WifiManager mWifiManager;
    private Messenger mWifiServiceMessenger;
    final ArgumentCaptor<Messenger> mMessengerCaptor = ArgumentCaptor.forClass(Messenger.class);

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        mHandler = spy(new Handler(mLooper.getLooper()));
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mContext.getOpPackageName()).thenReturn(TEST_PACKAGE_NAME);

        mWifiServiceMessenger = new Messenger(mHandler);
        mWifiManager = new WifiManager(mContext, mWifiService, mLooper.getLooper());
    }

    /**
     * Check the call to startSoftAp calls WifiService to startSoftAp with the provided
     * WifiConfiguration.  Verify that the return value is propagated to the caller.
     */
    @Test
    public void testStartSoftApCallsServiceWithWifiConfig() throws Exception {
        when(mWifiService.startSoftAp(eq(mApConfig))).thenReturn(true);
        assertTrue(mWifiManager.startSoftAp(mApConfig));

        when(mWifiService.startSoftAp(eq(mApConfig))).thenReturn(false);
        assertFalse(mWifiManager.startSoftAp(mApConfig));
    }

    /**
     * Check the call to startSoftAp calls WifiService to startSoftAp with a null config.  Verify
     * that the return value is propagated to the caller.
     */
    @Test
    public void testStartSoftApCallsServiceWithNullConfig() throws Exception {
        when(mWifiService.startSoftAp(eq(null))).thenReturn(true);
        assertTrue(mWifiManager.startSoftAp(null));

        when(mWifiService.startSoftAp(eq(null))).thenReturn(false);
        assertFalse(mWifiManager.startSoftAp(null));
    }

    /**
     * Check the call to stopSoftAp calls WifiService to stopSoftAp.
     */
    @Test
    public void testStopSoftApCallsService() throws Exception {
        when(mWifiService.stopSoftAp()).thenReturn(true);
        assertTrue(mWifiManager.stopSoftAp());

        when(mWifiService.stopSoftAp()).thenReturn(false);
        assertFalse(mWifiManager.stopSoftAp());
    }

    /**
     * Test creation of a LocalOnlyHotspotReservation and verify that close properly calls
     * WifiService.stopLocalOnlyHotspot.
     */
    @Test
    public void testCreationAndCloseOfLocalOnlyHotspotReservation() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);

        callback.onStarted(mWifiManager.new LocalOnlyHotspotReservation(mApConfig));

        assertEquals(mApConfig, callback.mRes.getWifiConfiguration());
        callback.mRes.close();
        verify(mWifiService).stopLocalOnlyHotspot();
    }

    /**
     * Verify stopLOHS is called when try-with-resources is used properly.
     */
    @Test
    public void testLocalOnlyHotspotReservationCallsStopProperlyInTryWithResources()
            throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);

        callback.onStarted(mWifiManager.new LocalOnlyHotspotReservation(mApConfig));

        try (WifiManager.LocalOnlyHotspotReservation res = callback.mRes) {
            assertEquals(mApConfig, res.getWifiConfiguration());
        }

        verify(mWifiService).stopLocalOnlyHotspot();
    }

    /**
     * Test creation of a LocalOnlyHotspotSubscription.
     * TODO: when registrations are tracked, verify removal on close.
     */
    @Test
    public void testCreationOfLocalOnlyHotspotSubscription() throws Exception {
        try (WifiManager.LocalOnlyHotspotSubscription sub =
                mWifiManager.new LocalOnlyHotspotSubscription()) {
            sub.close();
        }
    }

    public class TestLocalOnlyHotspotCallback extends LocalOnlyHotspotCallback {
        public boolean mOnStartedCalled = false;
        public boolean mOnStoppedCalled = false;
        public int mFailureReason = -1;
        public LocalOnlyHotspotReservation mRes = null;
        public long mCallingThreadId = -1;

        @Override
        public void onStarted(LocalOnlyHotspotReservation r) {
            mRes = r;
            mOnStartedCalled = true;
            mCallingThreadId = Thread.currentThread().getId();
        }

        @Override
        public void onStopped() {
            mOnStoppedCalled = true;
            mCallingThreadId = Thread.currentThread().getId();
        }

        @Override
        public void onFailed(int reason) {
            mFailureReason = reason;
            mCallingThreadId = Thread.currentThread().getId();
        }
    }

    /**
     * Verify callback is properly plumbed when called.
     */
    @Test
    public void testLocalOnlyHotspotCallback() {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);
        assertEquals(null, callback.mRes);

        // test onStarted
        WifiManager.LocalOnlyHotspotReservation res =
                mWifiManager.new LocalOnlyHotspotReservation(mApConfig);
        callback.onStarted(res);
        assertEquals(res, callback.mRes);
        assertTrue(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);

        // test onStopped
        callback.onStopped();
        assertEquals(res, callback.mRes);
        assertTrue(callback.mOnStartedCalled);
        assertTrue(callback.mOnStoppedCalled);
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);

        // test onFailed
        callback.onFailed(ERROR_TEST_REASON);
        assertEquals(res, callback.mRes);
        assertTrue(callback.mOnStartedCalled);
        assertTrue(callback.mOnStoppedCalled);
        assertEquals(ERROR_TEST_REASON, callback.mFailureReason);
    }

    public class TestLocalOnlyHotspotObserver extends LocalOnlyHotspotObserver {
        public boolean mOnRegistered = false;
        public boolean mOnStartedCalled = false;
        public boolean mOnStoppedCalled = false;
        public WifiConfiguration mConfig = null;
        public LocalOnlyHotspotSubscription mSub = null;
        public long mCallingThreadId = -1;

        @Override
        public void onRegistered(LocalOnlyHotspotSubscription sub) {
            mOnRegistered = true;
            mSub = sub;
            mCallingThreadId = Thread.currentThread().getId();
        }

        @Override
        public void onStarted(WifiConfiguration config) {
            mOnStartedCalled = true;
            mConfig = config;
            mCallingThreadId = Thread.currentThread().getId();
        }

        @Override
        public void onStopped() {
            mOnStoppedCalled = true;
            mCallingThreadId = Thread.currentThread().getId();
        }
    }

    /**
     * Verify observer is properly plumbed when called.
     */
    @Test
    public void testLocalOnlyHotspotObserver() {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        assertFalse(observer.mOnRegistered);
        assertFalse(observer.mOnStartedCalled);
        assertFalse(observer.mOnStoppedCalled);
        assertEquals(null, observer.mConfig);
        assertEquals(null, observer.mSub);

        WifiManager.LocalOnlyHotspotSubscription sub =
                mWifiManager.new LocalOnlyHotspotSubscription();
        observer.onRegistered(sub);
        assertTrue(observer.mOnRegistered);
        assertFalse(observer.mOnStartedCalled);
        assertFalse(observer.mOnStoppedCalled);
        assertEquals(null, observer.mConfig);
        assertEquals(sub, observer.mSub);

        observer.onStarted(mApConfig);
        assertTrue(observer.mOnRegistered);
        assertTrue(observer.mOnStartedCalled);
        assertFalse(observer.mOnStoppedCalled);
        assertEquals(mApConfig, observer.mConfig);
        assertEquals(sub, observer.mSub);

        observer.onStopped();
        assertTrue(observer.mOnRegistered);
        assertTrue(observer.mOnStartedCalled);
        assertTrue(observer.mOnStoppedCalled);
        assertEquals(mApConfig, observer.mConfig);
        assertEquals(sub, observer.mSub);
    }

    /**
     * Verify call to startLocalOnlyHotspot goes to WifiServiceImpl.
     */
    @Test
    public void testStartLocalOnlyHotspot() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);

        verify(mWifiService)
                .startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class), anyString());
    }

    /**
     * Verify a SecurityException is thrown for callers without proper permissions for
     * startLocalOnlyHotspot.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityException() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        doThrow(new SecurityException()).when(mWifiService)
                .startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class), anyString());
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
    }

    /**
     * Verify an IllegalStateException is thrown for callers that already have a pending request for
     * startLocalOnlyHotspot.
     */
    @Test(expected = IllegalStateException.class)
    public void testStartLocalOnlyHotspotThrowsIllegalStateException() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        doThrow(new IllegalStateException()).when(mWifiService)
                .startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class), anyString());
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
    }

    /**
     * Verify that the handler provided by the caller is used for the callbacks.
     */
    @Test
    public void testCorrectLooperIsUsedForHandler() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(ERROR_INCOMPATIBLE_MODE);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        assertEquals(ERROR_INCOMPATIBLE_MODE, callback.mFailureReason);
        verify(mContext, never()).getMainLooper();
    }

    /**
     * Verify that the main looper's thread is used if a handler is not provided by the reqiestomg
     * application.
     */
    @Test
    public void testMainLooperIsUsedWhenHandlerNotProvided() throws Exception {
        // record thread from looper.getThread and check ids.
        TestLooper altLooper = new TestLooper();
        when(mContext.getMainLooper()).thenReturn(altLooper.getLooper());
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(ERROR_INCOMPATIBLE_MODE);
        mWifiManager.startLocalOnlyHotspot(callback, null);
        altLooper.dispatchAll();
        assertEquals(ERROR_INCOMPATIBLE_MODE, callback.mFailureReason);
        assertEquals(altLooper.getLooper().getThread().getId(), callback.mCallingThreadId);
        verify(mContext).getMainLooper();
    }

    /**
     * Verify the LOHS onStarted callback is triggered when WifiManager receives a HOTSPOT_STARTED
     * message from WifiServiceImpl.
     */
    @Test
    public void testOnStartedIsCalledWithReservation() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        when(mWifiService.startLocalOnlyHotspot(mMessengerCaptor.capture(),
                  any(IBinder.class), anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(callback.mOnStartedCalled);
        assertEquals(null, callback.mRes);
        // now trigger the callback
        Message msg = new Message();
        msg.what = HOTSPOT_STARTED;
        msg.obj = mApConfig;
        mMessengerCaptor.getValue().send(msg);
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertTrue(callback.mOnStartedCalled);
        assertEquals(mApConfig, callback.mRes.getWifiConfiguration());
    }

    /**
     * Verify onFailed is called if WifiServiceImpl sends a HOTSPOT_STARTED message with a null
     * config.
     */
    @Test
    public void testOnStartedIsCalledWithNullConfig() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        when(mWifiService.startLocalOnlyHotspot(mMessengerCaptor.capture(),
                  any(IBinder.class), anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(callback.mOnStartedCalled);
        assertEquals(null, callback.mRes);
        // now trigger the callback
        Message msg = new Message();
        msg.what = HOTSPOT_STARTED;
        mMessengerCaptor.getValue().send(msg);
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertFalse(callback.mOnStartedCalled);
        assertEquals(ERROR_GENERIC, callback.mFailureReason);
    }

    /**
     * Verify onStopped is called if WifiServiceImpl sends a HOTSPOT_STOPPED message.
     */
    @Test
    public void testOnStoppedIsCalled() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        when(mWifiService.startLocalOnlyHotspot(mMessengerCaptor.capture(),
                  any(IBinder.class), anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(callback.mOnStoppedCalled);
        // now trigger the callback
        Message msg = new Message();
        msg.what = HOTSPOT_STOPPED;
        mMessengerCaptor.getValue().send(msg);
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertTrue(callback.mOnStoppedCalled);
    }

    /**
     * Verify onFailed is called if WifiServiceImpl sends a HOTSPOT_FAILED message.
     */
    @Test
    public void testOnFailedIsCalled() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        TestLooper callbackLooper = new TestLooper();
        Handler callbackHandler = new Handler(callbackLooper.getLooper());
        when(mWifiService.startLocalOnlyHotspot(mMessengerCaptor.capture(),
                  any(IBinder.class), anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, callbackHandler);
        callbackLooper.dispatchAll();
        mLooper.dispatchAll();
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);
        // now trigger the callback
        Message msg = new Message();
        msg.what = HOTSPOT_FAILED;
        msg.arg1 = ERROR_NO_CHANNEL;
        mMessengerCaptor.getValue().send(msg);
        mLooper.dispatchAll();
        callbackLooper.dispatchAll();
        assertEquals(ERROR_NO_CHANNEL, callback.mFailureReason);
    }

    /**
     * Verify callback triggered from startLocalOnlyHotspot with an incompatible mode failure.
     */
    @Test
    public void testLocalOnlyHotspotCallbackFullOnIncompatibleMode() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(ERROR_INCOMPATIBLE_MODE);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        assertEquals(ERROR_INCOMPATIBLE_MODE, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
    }

    /**
     * Verify callback triggered from startLocalOnlyHotspot with a tethering disallowed failure.
     */
    @Test
    public void testLocalOnlyHotspotCallbackFullOnTetheringDisallowed() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(ERROR_TETHERING_DISALLOWED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        assertEquals(ERROR_TETHERING_DISALLOWED, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
    }

    /**
     * Verify a SecurityException resulting from an application without necessary permissions will
     * bubble up through the call to start LocalOnlyHotspot and will not trigger other callbacks.
     */
    @Test(expected = SecurityException.class)
    public void testLocalOnlyHotspotCallbackFullOnSecurityException() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        doThrow(new SecurityException()).when(mWifiService)
                .startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class), anyString());
        try {
            mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        } catch (SecurityException e) {
            assertEquals(ERROR_NOT_SET, callback.mFailureReason);
            assertFalse(callback.mOnStartedCalled);
            assertFalse(callback.mOnStoppedCalled);
            assertEquals(null, callback.mRes);
            throw e;
        }

    }

    /**
     * Verify the handler passed to startLocalOnlyHotspot is correctly used for callbacks when
     * SoftApMode fails due to a underlying error.
     */
    @Test
    public void testLocalOnlyHotspotCallbackFullOnNoChannelError() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        //assertEquals(ERROR_NO_CHANNEL, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
    }

    /**
     * Verify that the call to cancel a LOHS request does call stopLOHS.
     */
    @Test
    public void testCancelLocalOnlyHotspotRequestCallsStopOnWifiService() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mWifiManager.cancelLocalOnlyHotspotRequest();
        verify(mWifiService).stopLocalOnlyHotspot();
    }

    /**
     * Verify that we do not crash if cancelLocalOnlyHotspotRequest is called without an existing
     * callback stored.
     */
    @Test
    public void testCancelLocalOnlyHotspotReturnsWithoutExistingRequest() {
        mWifiManager.cancelLocalOnlyHotspotRequest();
    }

    /**
     * Verify that the callback is not triggered if the LOHS request was already cancelled.
     */
    @Test
    public void testCallbackAfterLocalOnlyHotspotWasCancelled() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(REQUEST_REGISTERED);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mWifiManager.cancelLocalOnlyHotspotRequest();
        verify(mWifiService).stopLocalOnlyHotspot();
        mLooper.dispatchAll();
        assertEquals(ERROR_NOT_SET, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
    }

    /**
     * Verify that calling cancel LOHS request does not crash if an error callback was already
     * handled.
     */
    @Test
    public void testCancelAfterLocalOnlyHotspotCallbackTriggered() throws Exception {
        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback();
        when(mWifiService.startLocalOnlyHotspot(any(Messenger.class), any(IBinder.class),
                anyString())).thenReturn(ERROR_INCOMPATIBLE_MODE);
        mWifiManager.startLocalOnlyHotspot(callback, mHandler);
        mLooper.dispatchAll();
        assertEquals(ERROR_INCOMPATIBLE_MODE, callback.mFailureReason);
        assertFalse(callback.mOnStartedCalled);
        assertFalse(callback.mOnStoppedCalled);
        assertEquals(null, callback.mRes);
        mWifiManager.cancelLocalOnlyHotspotRequest();
        verify(mWifiService, never()).stopLocalOnlyHotspot();
    }

    /**
     * Verify the watchLocalOnlyHotspot call goes to WifiServiceImpl.
     */
    @Test
    public void testWatchLocalOnlyHotspot() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();

        mWifiManager.watchLocalOnlyHotspot(observer, mHandler);
        verify(mWifiService).startWatchLocalOnlyHotspot(any(Messenger.class), any(IBinder.class));
    }

    /**
     * Verify a SecurityException is thrown for callers without proper permissions for
     * startWatchLocalOnlyHotspot.
     */
    @Test(expected = SecurityException.class)
    public void testStartWatchLocalOnlyHotspotThrowsSecurityException() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        doThrow(new SecurityException()).when(mWifiService)
                .startWatchLocalOnlyHotspot(any(Messenger.class), any(IBinder.class));
        mWifiManager.watchLocalOnlyHotspot(observer, mHandler);
    }

    /**
     * Verify an IllegalStateException is thrown for callers that already have a pending request for
     * watchLocalOnlyHotspot.
     */
    @Test(expected = IllegalStateException.class)
    public void testStartWatchLocalOnlyHotspotThrowsIllegalStateException() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        doThrow(new IllegalStateException()).when(mWifiService)
                .startWatchLocalOnlyHotspot(any(Messenger.class), any(IBinder.class));
        mWifiManager.watchLocalOnlyHotspot(observer, mHandler);
    }

    /**
     * Verify an IllegalArgumentException is thrown if callback is not provided.
     */
    @Test
    public void registerSoftApCallbackThrowsIllegalArgumentExceptionOnNullArgumentForCallback() {
        try {
            mWifiManager.registerSoftApCallback(null, mHandler);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify an IllegalArgumentException is thrown if callback is not provided.
     */
    @Test
    public void unregisterSoftApCallbackThrowsIllegalArgumentExceptionOnNullArgumentForCallback() {
        try {
            mWifiManager.unregisterSoftApCallback(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify main looper is used when handler is not provided.
     */
    @Test
    public void registerSoftApCallbackUsesMainLooperOnNullArgumentForHandler() {
        when(mContext.getMainLooper()).thenReturn(mLooper.getLooper());
        mWifiManager.registerSoftApCallback(mSoftApCallback, null);
        verify(mContext).getMainLooper();
    }

    /**
     * Verify the call to registerSoftApCallback goes to WifiServiceImpl.
     */
    @Test
    public void registerSoftApCallbackCallGoesToWifiServiceImpl() throws Exception {
        mWifiManager.registerSoftApCallback(mSoftApCallback, mHandler);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class),
                any(ISoftApCallback.Stub.class), anyInt());
    }

    /**
     * Verify the call to unregisterSoftApCallback goes to WifiServiceImpl.
     */
    @Test
    public void unregisterSoftApCallbackCallGoesToWifiServiceImpl() throws Exception {
        ArgumentCaptor<Integer> callbackIdentifier = ArgumentCaptor.forClass(Integer.class);
        mWifiManager.registerSoftApCallback(mSoftApCallback, mHandler);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class),
                any(ISoftApCallback.Stub.class), callbackIdentifier.capture());

        mWifiManager.unregisterSoftApCallback(mSoftApCallback);
        verify(mWifiService).unregisterSoftApCallback(eq((int) callbackIdentifier.getValue()));
    }

    /*
     * Verify client provided callback is being called through callback proxy
     */
    @Test
    public void softApCallbackProxyCallsOnStateChanged() throws Exception {
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(mSoftApCallback, mHandler);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        callbackCaptor.getValue().onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mLooper.dispatchAll();
        verify(mSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
    }

    /*
     * Verify client provided callback is being called through callback proxy
     */
    @Test
    public void softApCallbackProxyCallsOnNumClientsChanged() throws Exception {
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(mSoftApCallback, mHandler);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        final int testNumClients = 3;
        callbackCaptor.getValue().onNumClientsChanged(testNumClients);
        mLooper.dispatchAll();
        verify(mSoftApCallback).onNumClientsChanged(testNumClients);
    }

    /*
     * Verify client provided callback is being called through callback proxy on multiple events
     */
    @Test
    public void softApCallbackProxyCallsOnMultipleUpdates() throws Exception {
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        mWifiManager.registerSoftApCallback(mSoftApCallback, mHandler);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        final int testNumClients = 5;
        callbackCaptor.getValue().onStateChanged(WIFI_AP_STATE_ENABLING, 0);
        callbackCaptor.getValue().onNumClientsChanged(testNumClients);
        callbackCaptor.getValue().onStateChanged(WIFI_AP_STATE_FAILED, SAP_START_FAILURE_GENERAL);

        mLooper.dispatchAll();
        verify(mSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLING, 0);
        verify(mSoftApCallback).onNumClientsChanged(testNumClients);
        verify(mSoftApCallback).onStateChanged(WIFI_AP_STATE_FAILED, SAP_START_FAILURE_GENERAL);
    }

    /*
     * Verify client provided callback is being called on the correct thread
     */
    @Test
    public void softApCallbackIsCalledOnCorrectThread() throws Exception {
        ArgumentCaptor<ISoftApCallback.Stub> callbackCaptor =
                ArgumentCaptor.forClass(ISoftApCallback.Stub.class);
        TestLooper altLooper = new TestLooper();
        Handler altHandler = new Handler(altLooper.getLooper());
        mWifiManager.registerSoftApCallback(mSoftApCallback, altHandler);
        verify(mWifiService).registerSoftApCallback(any(IBinder.class), callbackCaptor.capture(),
                anyInt());

        callbackCaptor.getValue().onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        altLooper.dispatchAll();
        verify(mSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
    }

    /**
     * Verify that the handler provided by the caller is used for registering soft AP callback.
     */
    @Test
    public void testCorrectLooperIsUsedForSoftApCallbackHandler() throws Exception {
        mWifiManager.registerSoftApCallback(mSoftApCallback, mHandler);
        mLooper.dispatchAll();
        verify(mWifiService).registerSoftApCallback(any(IBinder.class),
                any(ISoftApCallback.Stub.class), anyInt());
        verify(mContext, never()).getMainLooper();
    }

    /**
     * Verify that the handler provided by the caller is used for the observer.
     */
    @Test
    public void testCorrectLooperIsUsedForObserverHandler() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        mWifiManager.watchLocalOnlyHotspot(observer, mHandler);
        mLooper.dispatchAll();
        assertTrue(observer.mOnRegistered);
        verify(mContext, never()).getMainLooper();
    }

    /**
     * Verify that the main looper's thread is used if a handler is not provided by the requesting
     * application.
     */
    @Test
    public void testMainLooperIsUsedWhenHandlerNotProvidedForObserver() throws Exception {
        // record thread from looper.getThread and check ids.
        TestLooper altLooper = new TestLooper();
        when(mContext.getMainLooper()).thenReturn(altLooper.getLooper());
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        mWifiManager.watchLocalOnlyHotspot(observer, null);
        altLooper.dispatchAll();
        assertTrue(observer.mOnRegistered);
        assertEquals(altLooper.getLooper().getThread().getId(), observer.mCallingThreadId);
        verify(mContext).getMainLooper();
    }

    /**
     * Verify the LOHS onRegistered observer callback is triggered when WifiManager receives a
     * HOTSPOT_OBSERVER_REGISTERED message from WifiServiceImpl.
     */
    @Test
    public void testOnRegisteredIsCalledWithSubscription() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        assertFalse(observer.mOnRegistered);
        assertEquals(null, observer.mSub);
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        verify(mWifiService).startWatchLocalOnlyHotspot(mMessengerCaptor.capture(),
                  any(IBinder.class));
        // now trigger the callback
        observerLooper.dispatchAll();
        mLooper.dispatchAll();
        assertTrue(observer.mOnRegistered);
        assertNotNull(observer.mSub);
    }

    /**
     * Verify the LOHS onStarted observer callback is triggered when WifiManager receives a
     * HOTSPOT_STARTED message from WifiServiceImpl.
     */
    @Test
    public void testObserverOnStartedIsCalledWithWifiConfig() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        verify(mWifiService).startWatchLocalOnlyHotspot(mMessengerCaptor.capture(),
                  any(IBinder.class));
        observerLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(observer.mOnStartedCalled);
        // now trigger the callback
        Message msg = new Message();
        msg.what = HOTSPOT_STARTED;
        msg.obj = mApConfig;
        mMessengerCaptor.getValue().send(msg);
        mLooper.dispatchAll();
        observerLooper.dispatchAll();
        assertTrue(observer.mOnStartedCalled);
        assertEquals(mApConfig, observer.mConfig);
    }

    /**
     * Verify the LOHS onStarted observer callback is triggered not when WifiManager receives a
     * HOTSPOT_STARTED message from WifiServiceImpl with a null config.
     */
    @Test
    public void testObserverOnStartedNotCalledWithNullConfig() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        verify(mWifiService).startWatchLocalOnlyHotspot(mMessengerCaptor.capture(),
                  any(IBinder.class));
        observerLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(observer.mOnStartedCalled);
        // now trigger the callback
        Message msg = new Message();
        msg.what = HOTSPOT_STARTED;
        mMessengerCaptor.getValue().send(msg);
        mLooper.dispatchAll();
        observerLooper.dispatchAll();
        assertFalse(observer.mOnStartedCalled);
        assertEquals(null, observer.mConfig);
    }


    /**
     * Verify the LOHS onStopped observer callback is triggered when WifiManager receives a
     * HOTSPOT_STOPPED message from WifiServiceImpl.
     */
    @Test
    public void testObserverOnStoppedIsCalled() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        verify(mWifiService).startWatchLocalOnlyHotspot(mMessengerCaptor.capture(),
                  any(IBinder.class));
        observerLooper.dispatchAll();
        mLooper.dispatchAll();
        assertFalse(observer.mOnStoppedCalled);
        // now trigger the callback
        Message msg = new Message();
        msg.what = HOTSPOT_STOPPED;
        mMessengerCaptor.getValue().send(msg);
        mLooper.dispatchAll();
        observerLooper.dispatchAll();
        assertTrue(observer.mOnStoppedCalled);
    }

    /**
     * Verify WifiServiceImpl is not called if there is not a registered LOHS observer callback.
     */
    @Test
    public void testUnregisterWifiServiceImplNotCalledWithoutRegisteredObserver() throws Exception {
        mWifiManager.unregisterLocalOnlyHotspotObserver();
        verifyZeroInteractions(mWifiService);
    }

    /**
     * Verify WifiServiceImpl is called when there is a registered LOHS observer callback.
     */
    @Test
    public void testUnregisterWifiServiceImplCalledWithRegisteredObserver() throws Exception {
        TestLocalOnlyHotspotObserver observer = new TestLocalOnlyHotspotObserver();
        TestLooper observerLooper = new TestLooper();
        Handler observerHandler = new Handler(observerLooper.getLooper());
        mWifiManager.watchLocalOnlyHotspot(observer, observerHandler);
        mWifiManager.unregisterLocalOnlyHotspotObserver();
        verify(mWifiService).stopWatchLocalOnlyHotspot();
    }

    /**
     * Verify that calls WifiServiceImpl to set country code when no exception happens.
     */
    @Test
    public void testSetWifiCountryCode() throws Exception {
        mWifiManager.setCountryCode(TEST_COUNTRY_CODE);
        verify(mWifiService).setCountryCode(TEST_COUNTRY_CODE);
    }

    /**
     * Verify that WifiManager.setCountryCode() rethrows exceptions if caller does not
     * have necessary permissions.
     */
    @Test(expected = SecurityException.class)
    public void testSetWifiCountryCodeFailedOnSecurityException() throws Exception {
        doThrow(new SecurityException()).when(mWifiService).setCountryCode(anyString());
        mWifiManager.setCountryCode(TEST_COUNTRY_CODE);
    }

    /**
     * Test that calls to get the current WPS config token return null and do not have any
     * interactions with WifiServiceImpl.
     */
    @Test
    public void testGetCurrentNetworkWpsNfcConfigurationTokenReturnsNull() {
        assertNull(mWifiManager.getCurrentNetworkWpsNfcConfigurationToken());
        verifyNoMoreInteractions(mWifiService);
    }


    class WpsCallbackTester extends WifiManager.WpsCallback {
        public boolean mStarted = false;
        public boolean mSucceeded = false;
        public boolean mFailed = false;
        public int mFailureCode = -1;

        @Override
        public void onStarted(String pin) {
            mStarted = true;
        }

        @Override
        public void onSucceeded() {
            mSucceeded = true;
        }

        @Override
        public void onFailed(int reason) {
            mFailed = true;
            mFailureCode = reason;
        }

    }

    /**
     * Verify that a call to start WPS immediately returns a failure.
     */
    @Test
    public void testStartWpsImmediatelyFailsWithCallback() {
        WpsCallbackTester wpsCallback = new WpsCallbackTester();
        mWifiManager.startWps(null, wpsCallback);
        assertTrue(wpsCallback.mFailed);
        assertEquals(WifiManager.ERROR, wpsCallback.mFailureCode);
        assertFalse(wpsCallback.mStarted);
        assertFalse(wpsCallback.mSucceeded);
        verifyNoMoreInteractions(mWifiService);
    }

    /**
     * Verify that a call to start WPS does not go to WifiServiceImpl if we do not have a callback.
     */
    @Test
    public void testStartWpsDoesNotCallWifiServiceImpl() {
        mWifiManager.startWps(null, null);
        verifyNoMoreInteractions(mWifiService);
    }

   /**
i     * Verify that a call to cancel WPS immediately returns a failure.
     */
    @Test
    public void testCancelWpsImmediatelyFailsWithCallback() {
        WpsCallbackTester wpsCallback = new WpsCallbackTester();
        mWifiManager.cancelWps(wpsCallback);
        assertTrue(wpsCallback.mFailed);
        assertEquals(WifiManager.ERROR, wpsCallback.mFailureCode);
        assertFalse(wpsCallback.mStarted);
        assertFalse(wpsCallback.mSucceeded);
        verifyNoMoreInteractions(mWifiService);
    }

    /**
     * Verify that a call to cancel WPS does not go to WifiServiceImpl if we do not have a callback.
     */
    @Test
    public void testCancelWpsDoesNotCallWifiServiceImpl() {
        mWifiManager.cancelWps(null);
        verifyNoMoreInteractions(mWifiService);
    }

    /**
     * Verify that a successful call properly returns true.
     */
    @Test
    public void testSetWifiApConfigurationSuccessReturnsTrue() throws Exception {
        WifiConfiguration apConfig = new WifiConfiguration();

        when(mWifiService.setWifiApConfiguration(eq(apConfig), eq(TEST_PACKAGE_NAME)))
                .thenReturn(true);
        assertTrue(mWifiManager.setWifiApConfiguration(apConfig));
    }

    /**
     * Verify that a failed call properly returns false.
     */
    @Test
    public void testSetWifiApConfigurationFailureReturnsFalse() throws Exception {
        WifiConfiguration apConfig = new WifiConfiguration();

        when(mWifiService.setWifiApConfiguration(eq(apConfig), eq(TEST_PACKAGE_NAME)))
                .thenReturn(false);
        assertFalse(mWifiManager.setWifiApConfiguration(apConfig));
    }

    /**
     * Verify Exceptions are rethrown when underlying calls to WifiService throw exceptions.
     */
    @Test
    public void testSetWifiApConfigurationRethrowsException() throws Exception {
        doThrow(new SecurityException()).when(mWifiService).setWifiApConfiguration(any(), any());

        try {
            mWifiManager.setWifiApConfiguration(new WifiConfiguration());
            fail("setWifiApConfiguration should rethrow Exceptions from WifiService");
        } catch (SecurityException e) { }
    }
}
