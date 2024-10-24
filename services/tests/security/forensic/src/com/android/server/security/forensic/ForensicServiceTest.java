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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Looper;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.security.forensic.IForensicServiceCommandCallback;
import android.security.forensic.IForensicServiceStateCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ForensicServiceTest {
    private static final int STATE_UNKNOWN = IForensicServiceStateCallback.State.UNKNOWN;
    private static final int STATE_INVISIBLE = IForensicServiceStateCallback.State.INVISIBLE;
    private static final int STATE_VISIBLE = IForensicServiceStateCallback.State.VISIBLE;
    private static final int STATE_ENABLED = IForensicServiceStateCallback.State.ENABLED;

    private static final int ERROR_UNKNOWN = IForensicServiceCommandCallback.ErrorCode.UNKNOWN;
    private static final int ERROR_PERMISSION_DENIED =
            IForensicServiceCommandCallback.ErrorCode.PERMISSION_DENIED;
    private static final int ERROR_INVALID_STATE_TRANSITION =
            IForensicServiceCommandCallback.ErrorCode.INVALID_STATE_TRANSITION;
    private static final int ERROR_BACKUP_TRANSPORT_UNAVAILABLE =
            IForensicServiceCommandCallback.ErrorCode.BACKUP_TRANSPORT_UNAVAILABLE;
    private static final int ERROR_DATA_SOURCE_UNAVAILABLE =
            IForensicServiceCommandCallback.ErrorCode.DATA_SOURCE_UNAVAILABLE;

    @Mock
    private Context mContext;
    private BackupTransportConnection mBackupTransportConnection;

    private ForensicService mForensicService;
    private TestLooper mTestLooper;
    private Looper mLooper;

    @SuppressLint("VisibleForTests")
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();
        mLooper = mTestLooper.getLooper();
        mForensicService = new ForensicService(new MockInjector(mContext));
        mForensicService.onStart();
    }

    @Test
    public void testMonitorState_Invisible() throws RemoteException {
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        mForensicService.getBinderService().monitorState(scb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb.mState);
    }

    @Test
    public void testMonitorState_Invisible_TwoMonitors() throws RemoteException {
        StateCallback scb1 = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb1.mState);
        mForensicService.getBinderService().monitorState(scb1);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);

        StateCallback scb2 = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb2.mState);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb2.mState);
    }

    @Test
    public void testMakeVisible_FromInvisible() throws RemoteException {
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        mForensicService.getBinderService().monitorState(scb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().makeVisible(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testMakeVisible_FromInvisible_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_INVISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().makeVisible(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testMakeVisible_FromVisible_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_VISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().makeVisible(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testMakeVisible_FromEnabled_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().makeVisible(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);
        assertNotNull(ccb.mErrorCode);
        assertEquals(ERROR_INVALID_STATE_TRANSITION, ccb.mErrorCode.intValue());
    }

    @Test
    public void testMakeInvisible_FromInvisible_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_INVISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().makeInvisible(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testMakeInvisible_FromVisible_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_VISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().makeInvisible(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testMakeInvisible_FromEnabled_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().makeInvisible(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);
        assertNull(ccb.mErrorCode);
    }


    @Test
    public void testEnable_FromInvisible_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_INVISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);
        assertNotNull(ccb.mErrorCode);
        assertEquals(ERROR_INVALID_STATE_TRANSITION, ccb.mErrorCode.intValue());
    }

    @Test
    public void testEnable_FromVisible_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_VISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);

        doReturn(true).when(mBackupTransportConnection).initialize();

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testEnable_FromVisible_TwoMonitors_BackupTransportUnavailable()
            throws RemoteException {
        mForensicService.setState(STATE_VISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);

        doReturn(false).when(mBackupTransportConnection).initialize();

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);
        assertNotNull(ccb.mErrorCode);
        assertEquals(ERROR_BACKUP_TRANSPORT_UNAVAILABLE, ccb.mErrorCode.intValue());
    }

    @Test
    public void testEnable_FromEnabled_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
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
    public void testDisable_FromInvisible_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_INVISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_INVISIBLE, scb1.mState);
        assertEquals(STATE_INVISIBLE, scb2.mState);
        assertNotNull(ccb.mErrorCode);
        assertEquals(ERROR_INVALID_STATE_TRANSITION, ccb.mErrorCode.intValue());
    }

    @Test
    public void testDisable_FromVisible_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_VISIBLE);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testDisable_FromEnabled_TwoMonitors() throws RemoteException {
        mForensicService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mForensicService.getBinderService().monitorState(scb1);
        mForensicService.getBinderService().monitorState(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        doNothing().when(mBackupTransportConnection).release();

        CommandCallback ccb = new CommandCallback();
        mForensicService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_VISIBLE, scb1.mState);
        assertEquals(STATE_VISIBLE, scb2.mState);
        assertNull(ccb.mErrorCode);
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
        public Looper getLooper() {
            return mLooper;
        }

        @Override
        public BackupTransportConnection getBackupTransportConnection() {
            mBackupTransportConnection = spy(new BackupTransportConnection(mContext));
            return mBackupTransportConnection;
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
