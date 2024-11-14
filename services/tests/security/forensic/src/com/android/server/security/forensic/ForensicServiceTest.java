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

package com.android.server.security.forensic;

import static android.Manifest.permission.MANAGE_FORENSIC_STATE;
import static android.Manifest.permission.READ_FORENSIC_STATE;

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
import android.content.Context;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.test.FakePermissionEnforcer;
import android.os.test.TestLooper;
import android.security.forensic.ForensicEvent;
import android.security.forensic.IForensicServiceCommandCallback;
import android.security.forensic.IForensicServiceStateCallback;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.ServiceThread;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ForensicServiceTest {
    private static final int STATE_UNKNOWN = IForensicServiceStateCallback.State.UNKNOWN;
    private static final int STATE_DISABLED = IForensicServiceStateCallback.State.DISABLED;
    private static final int STATE_ENABLED = IForensicServiceStateCallback.State.ENABLED;

    private static final int ERROR_UNKNOWN = IForensicServiceCommandCallback.ErrorCode.UNKNOWN;
    private static final int ERROR_PERMISSION_DENIED =
            IForensicServiceCommandCallback.ErrorCode.PERMISSION_DENIED;
    private static final int ERROR_TRANSPORT_UNAVAILABLE =
            IForensicServiceCommandCallback.ErrorCode.TRANSPORT_UNAVAILABLE;
    private static final int ERROR_DATA_SOURCE_UNAVAILABLE =
            IForensicServiceCommandCallback.ErrorCode.DATA_SOURCE_UNAVAILABLE;

    private Context mContext;
    private ForensicEventTransportConnection mForensicEventTransportConnection;
    private DataAggregator mDataAggregator;
    private ForensicService mForensicService;
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
        mPermissionEnforcer.grant(READ_FORENSIC_STATE);
        mPermissionEnforcer.grant(MANAGE_FORENSIC_STATE);

        mTestLooper = new TestLooper();
        mLooper = mTestLooper.getLooper();
        mTestLooperOfDataAggregator = new TestLooper();
        mLooperOfDataAggregator = mTestLooperOfDataAggregator.getLooper();
        mForensicService = new ForensicService(new MockInjector(mContext));
        mForensicService.onStart();
    }

    @Test
    public void testAddStateCallback_NoPermission() {
        mPermissionEnforcer.revoke(READ_FORENSIC_STATE);
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        assertThrows(SecurityException.class,
                () -> mForensicService.getBinderService().addStateCallback(scb));
    }

    @Test
    public void testRemoveStateCallback_NoPermission() {
        mPermissionEnforcer.revoke(READ_FORENSIC_STATE);
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        assertThrows(SecurityException.class,
                () -> mForensicService.getBinderService().removeStateCallback(scb));
    }

    @Test
    public void testEnable_NoPermission() {
        mPermissionEnforcer.revoke(MANAGE_FORENSIC_STATE);

        CommandCallback ccb = new CommandCallback();
        assertThrows(SecurityException.class,
                () -> mForensicService.getBinderService().enable(ccb));
    }

    @Test
    public void testDisable_NoPermission() {
        mPermissionEnforcer.revoke(MANAGE_FORENSIC_STATE);

        CommandCallback ccb = new CommandCallback();
        assertThrows(SecurityException.class,
                () -> mForensicService.getBinderService().disable(ccb));
    }

    @Test
    public void testAddStateCallback_Disabled() throws RemoteException {
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        mForensicService.getBinderService().addStateCallback(scb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb.mState);
    }

    @Test
    public void testAddStateCallback_Disabled_TwoStateCallbacks() throws RemoteException {
        StateCallback scb1 = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb1.mState);
        mForensicService.getBinderService().addStateCallback(scb1);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);

        StateCallback scb2 = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb2.mState);
        mForensicService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb2.mState);
    }

    @Test
    public void testRemoveStateCallback() throws RemoteException {
        mForensicService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().addStateCallback(scb1);
        mForensicService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(true).when(mDataAggregator).initialize();
        doReturn(true).when(mForensicEventTransportConnection).initialize();

        mForensicService.getBinderService().removeStateCallback(scb2);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testEnable_FromDisabled_TwoStateCallbacks() throws RemoteException {
        mForensicService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().addStateCallback(scb1);
        mForensicService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(true).when(mForensicEventTransportConnection).initialize();

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();

        verify(mDataAggregator, times(1)).enable();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testEnable_FromEnabled_TwoStateCallbacks()
            throws RemoteException {
        mForensicService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().addStateCallback(scb1);
        mForensicService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();

        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testDisable_FromDisabled_TwoStateCallbacks() throws RemoteException {
        mForensicService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().addStateCallback(scb1);
        mForensicService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();

        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testDisable_FromEnabled_TwoStateCallbacks() throws RemoteException {
        mForensicService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().addStateCallback(scb1);
        mForensicService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        doNothing().when(mForensicEventTransportConnection).release();

        ServiceThread mockThread = spy(ServiceThread.class);
        mDataAggregator.setHandler(mLooperOfDataAggregator, mockThread);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();
        mTestLooperOfDataAggregator.dispatchAll();
        // TODO: We can verify the data sources once we implement them.
        verify(mockThread, times(1)).quitSafely();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Ignore("Enable once the ForensicEventTransportConnection is ready")
    @Test
    public void testEnable_FromDisable_TwoStateCallbacks_TransportUnavailable()
            throws RemoteException {
        mForensicService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().addStateCallback(scb1);
        mForensicService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(false).when(mForensicEventTransportConnection).initialize();

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNotNull(ccb.mErrorCode);
        assertEquals(ERROR_TRANSPORT_UNAVAILABLE, ccb.mErrorCode.intValue());
    }

    @Test
    public void testDataAggregator_AddBatchData() {
        mForensicService.setState(STATE_ENABLED);
        ServiceThread mockThread = spy(ServiceThread.class);
        mDataAggregator.setHandler(mLooperOfDataAggregator, mockThread);

        String eventOneType = "event_one_type";
        String eventOneMapKey = "event_one_map_key";
        String eventOneMapVal = "event_one_map_val";
        Map<String, String> eventOneMap = new ArrayMap<String, String>();
        eventOneMap.put(eventOneMapKey, eventOneMapVal);
        ForensicEvent eventOne = new ForensicEvent(eventOneType, eventOneMap);

        String eventTwoType = "event_two_type";
        String eventTwoMapKey = "event_two_map_key";
        String eventTwoMapVal = "event_two_map_val";
        Map<String, String> eventTwoMap = new ArrayMap<String, String>();
        eventTwoMap.put(eventTwoMapKey, eventTwoMapVal);
        ForensicEvent eventTwo = new ForensicEvent(eventTwoType, eventTwoMap);

        List<ForensicEvent> events = new ArrayList<>();
        events.add(eventOne);
        events.add(eventTwo);

        doReturn(true).when(mForensicEventTransportConnection).addData(any());

        mDataAggregator.addBatchData(events);
        mTestLooperOfDataAggregator.dispatchAll();
        mTestLooper.dispatchAll();

        ArgumentCaptor<List<ForensicEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(mForensicEventTransportConnection).addData(captor.capture());
        List<ForensicEvent> receivedEvents = captor.getValue();
        assertEquals(receivedEvents.size(), 2);

        assertEquals(receivedEvents.getFirst().getType(), eventOneType);
        assertEquals(receivedEvents.getFirst().getKeyValuePairs().size(), 1);
        assertEquals(receivedEvents.getFirst().getKeyValuePairs().get(eventOneMapKey),
                eventOneMapVal);

        assertEquals(receivedEvents.getLast().getType(), eventTwoType);
        assertEquals(receivedEvents.getLast().getKeyValuePairs().size(), 1);
        assertEquals(receivedEvents.getLast().getKeyValuePairs().get(eventTwoMapKey),
                eventTwoMapVal);

    }

    private class MockInjector implements ForensicService.Injector {
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
        public ForensicEventTransportConnection getForensicEventransportConnection() {
            mForensicEventTransportConnection = spy(new ForensicEventTransportConnection(mContext));
            return mForensicEventTransportConnection;
        }

        @Override
        public DataAggregator getDataAggregator(ForensicService forensicService) {
            mDataAggregator = spy(new DataAggregator(mContext, forensicService));
            return mDataAggregator;
        }
    }

    private static class StateCallback extends IForensicServiceStateCallback.Stub {
        int mState = STATE_UNKNOWN;

        @Override
        public void onStateChange(int state) throws RemoteException {
            mState = state;
        }
    }

    private static class CommandCallback extends IForensicServiceCommandCallback.Stub {
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
