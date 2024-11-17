/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.intrusiondetection;

import static android.Manifest.permission.MANAGE_INTRUSION_DETECTION_STATE;
import static android.Manifest.permission.READ_INTRUSION_DETECTION_STATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.SuppressLint;
import android.app.admin.ConnectEvent;
import android.app.admin.DnsEvent;
import android.app.admin.SecurityLog.SecurityEvent;
import android.content.Context;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.test.FakePermissionEnforcer;
import android.os.test.TestLooper;
import android.security.intrusiondetection.IIntrusionDetectionServiceCommandCallback;
import android.security.intrusiondetection.IIntrusionDetectionServiceStateCallback;
import android.security.intrusiondetection.IntrusionDetectionEvent;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.ServiceThread;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

public class IntrusionDetectionServiceTest {
    private static final int STATE_UNKNOWN =
            IIntrusionDetectionServiceStateCallback.State.UNKNOWN;
    private static final int STATE_DISABLED =
            IIntrusionDetectionServiceStateCallback.State.DISABLED;
    private static final int STATE_ENABLED =
            IIntrusionDetectionServiceStateCallback.State.ENABLED;

    private static final int ERROR_UNKNOWN =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.UNKNOWN;
    private static final int ERROR_PERMISSION_DENIED =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.PERMISSION_DENIED;
    private static final int ERROR_TRANSPORT_UNAVAILABLE =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.TRANSPORT_UNAVAILABLE;
    private static final int ERROR_DATA_SOURCE_UNAVAILABLE =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.DATA_SOURCE_UNAVAILABLE;

    private Context mContext;
    private IntrusionDetectionEventTransportConnection mIntrusionDetectionEventTransportConnection;
    private DataAggregator mDataAggregator;
    private IntrusionDetectionService mIntrusionDetectionService;
    private TestLooper mTestLooper;
    private Looper mLooper;
    private TestLooper mTestLooperOfDataAggregator;
    private Looper mLooperOfDataAggregator;
    private FakePermissionEnforcer mPermissionEnforcer;

    @SuppressLint("VisibleForTests")
    @Before
    public void setUp() {
        mContext = spy(ApplicationProvider.getApplicationContext());

        mPermissionEnforcer = new FakePermissionEnforcer();
        mPermissionEnforcer.grant(READ_INTRUSION_DETECTION_STATE);
        mPermissionEnforcer.grant(MANAGE_INTRUSION_DETECTION_STATE);

        mTestLooper = new TestLooper();
        mLooper = mTestLooper.getLooper();
        mTestLooperOfDataAggregator = new TestLooper();
        mLooperOfDataAggregator = mTestLooperOfDataAggregator.getLooper();
        mIntrusionDetectionService = new IntrusionDetectionService(new MockInjector(mContext));
        mIntrusionDetectionService.onStart();
    }

    @Test
    public void testAddStateCallback_NoPermission() {
        mPermissionEnforcer.revoke(READ_INTRUSION_DETECTION_STATE);
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        assertThrows(SecurityException.class,
                () -> mIntrusionDetectionService.getBinderService().addStateCallback(scb));
    }

    @Test
    public void testRemoveStateCallback_NoPermission() {
        mPermissionEnforcer.revoke(READ_INTRUSION_DETECTION_STATE);
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        assertThrows(SecurityException.class,
                () -> mIntrusionDetectionService.getBinderService().removeStateCallback(scb));
    }

    @Test
    public void testEnable_NoPermission() {
        mPermissionEnforcer.revoke(MANAGE_INTRUSION_DETECTION_STATE);

        CommandCallback ccb = new CommandCallback();
        assertThrows(SecurityException.class,
                () -> mIntrusionDetectionService.getBinderService().enable(ccb));
    }

    @Test
    public void testDisable_NoPermission() {
        mPermissionEnforcer.revoke(MANAGE_INTRUSION_DETECTION_STATE);

        CommandCallback ccb = new CommandCallback();
        assertThrows(SecurityException.class,
                () -> mIntrusionDetectionService.getBinderService().disable(ccb));
    }

    @Test
    public void testAddStateCallback_Disabled() throws RemoteException {
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb.mState);
    }

    @Test
    public void testAddStateCallback_Disabled_TwoStateCallbacks() throws RemoteException {
        StateCallback scb1 = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb1.mState);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);

        StateCallback scb2 = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb2.mState);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb2.mState);
    }

    @Test
    public void testRemoveStateCallback() throws RemoteException {
        mIntrusionDetectionService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(true).when(mDataAggregator).initialize();
        doReturn(true).when(mIntrusionDetectionEventTransportConnection).initialize();

        mIntrusionDetectionService.getBinderService().removeStateCallback(scb2);

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testEnable_FromDisabled_TwoStateCallbacks() throws RemoteException {
        mIntrusionDetectionService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(true).when(mIntrusionDetectionEventTransportConnection).initialize();

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();

        verify(mDataAggregator, times(1)).enable();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testEnable_FromEnabled_TwoStateCallbacks()
            throws RemoteException {
        mIntrusionDetectionService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();

        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testDisable_FromDisabled_TwoStateCallbacks() throws RemoteException {
        mIntrusionDetectionService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();

        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testDisable_FromEnabled_TwoStateCallbacks() throws RemoteException {
        mIntrusionDetectionService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        doNothing().when(mIntrusionDetectionEventTransportConnection).release();

        ServiceThread mockThread = spy(ServiceThread.class);
        mDataAggregator.setHandler(mLooperOfDataAggregator, mockThread);

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();
        mTestLooperOfDataAggregator.dispatchAll();
        // TODO: We can verify the data sources once we implement them.
        verify(mockThread, times(1)).quitSafely();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Ignore("Enable once the IntrusionDetectionEventTransportConnection is ready")
    @Test
    public void testEnable_FromDisable_TwoStateCallbacks_TransportUnavailable()
            throws RemoteException {
        mIntrusionDetectionService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(false).when(mIntrusionDetectionEventTransportConnection).initialize();

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNotNull(ccb.mErrorCode);
        assertEquals(ERROR_TRANSPORT_UNAVAILABLE, ccb.mErrorCode.intValue());
    }

    @Test
    public void testDataAggregator_AddBatchData() {
        mIntrusionDetectionService.setState(STATE_ENABLED);
        ServiceThread mockThread = spy(ServiceThread.class);
        mDataAggregator.setHandler(mLooperOfDataAggregator, mockThread);

        SecurityEvent securityEvent = new SecurityEvent(0, new byte[0]);
        IntrusionDetectionEvent eventOne = new IntrusionDetectionEvent(securityEvent);

        ConnectEvent connectEvent = new ConnectEvent(
                "127.0.0.1", 80, null, 0);
        IntrusionDetectionEvent eventTwo = new IntrusionDetectionEvent(connectEvent);

        DnsEvent dnsEvent = new DnsEvent(
                null, new String[] {"127.0.0.1"}, 1, null, 0);
        IntrusionDetectionEvent eventThree = new IntrusionDetectionEvent(dnsEvent);

        List<IntrusionDetectionEvent> events = new ArrayList<>();
        events.add(eventOne);
        events.add(eventTwo);
        events.add(eventThree);

        doReturn(true).when(mIntrusionDetectionEventTransportConnection).addData(any());

        mDataAggregator.addBatchData(events);
        mTestLooperOfDataAggregator.dispatchAll();
        mTestLooper.dispatchAll();

        ArgumentCaptor<List<IntrusionDetectionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(mIntrusionDetectionEventTransportConnection).addData(captor.capture());
        List<IntrusionDetectionEvent> receivedEvents = captor.getValue();
        assertEquals(receivedEvents.size(), 3);

        assertEquals(receivedEvents.get(0).getType(), IntrusionDetectionEvent.SECURITY_EVENT);
        assertNotNull(receivedEvents.get(0).getSecurityEvent());

        assertEquals(receivedEvents.get(1).getType(),
                IntrusionDetectionEvent.NETWORK_EVENT_CONNECT);
        assertNotNull(receivedEvents.get(1).getConnectEvent());

        assertEquals(receivedEvents.get(2).getType(), IntrusionDetectionEvent.NETWORK_EVENT_DNS);
        assertNotNull(receivedEvents.get(2).getDnsEvent());
    }

    private class MockInjector implements IntrusionDetectionService.Injector {
        private final Context mContext;

        MockInjector(Context context) {
            mContext = context;
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public PermissionEnforcer getPermissionEnforcer() {
            return mPermissionEnforcer;
        }

        @Override
        public Looper getLooper() {
            return mLooper;
        }

        @Override
        public IntrusionDetectionEventTransportConnection
                getIntrusionDetectionEventransportConnection() {
            mIntrusionDetectionEventTransportConnection =
                    spy(new IntrusionDetectionEventTransportConnection(mContext));
            return mIntrusionDetectionEventTransportConnection;
        }

        @Override
        public DataAggregator getDataAggregator(
                IntrusionDetectionService intrusionDetectionService) {
            mDataAggregator = spy(new DataAggregator(mContext, intrusionDetectionService));
            return mDataAggregator;
        }
    }

    private static class StateCallback extends IIntrusionDetectionServiceStateCallback.Stub {
        int mState = STATE_UNKNOWN;

        @Override
        public void onStateChange(int state) throws RemoteException {
            mState = state;
        }
    }

    private static class CommandCallback extends IIntrusionDetectionServiceCommandCallback.Stub {
        Integer mErrorCode = null;

        public void reset() {
            mErrorCode = null;
        }

        @Override
        public void onSuccess() throws RemoteException {

        }

        @Override
        public void onFailure(int errorCode) throws RemoteException {
            mErrorCode = errorCode;
        }
    }
}
