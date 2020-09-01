/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.accessibility.magnification;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for WindowMagnificationManager.
 */
public class WindowMagnificationManagerTest {

    private MockWindowMagnificationConnection mMockConnection;
    private WindowMagnificationManager mWindowMagnificationManager;

    @Before
    public void setUp() {
        mMockConnection = new MockWindowMagnificationConnection();
        mWindowMagnificationManager = new WindowMagnificationManager();
    }

    @Test
    public void setConnection_connectionIsNull_wrapperIsNullAndLinkToDeath() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        assertNotNull(mWindowMagnificationManager.mConnectionWrapper);
        verify(mMockConnection.asBinder()).linkToDeath(any(IBinder.DeathRecipient.class), eq(0));
    }

    @Test
    public void setConnection_connectionIsNull_setMirrorWindowCallbackAndHasWrapper()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        assertNotNull(mWindowMagnificationManager.mConnectionWrapper);
        verify(mMockConnection.asBinder()).linkToDeath(any(IBinder.DeathRecipient.class), eq(0));
        verify(mMockConnection.getConnection()).setConnectionCallback(
                any(IWindowMagnificationConnectionCallback.class));
    }

    @Test
    public void binderDied_hasConnection_wrapperIsNullAndUnlinkToDeath() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        mMockConnection.getDeathRecipient().binderDied();

        assertNull(mWindowMagnificationManager.mConnectionWrapper);
        verify(mMockConnection.asBinder()).unlinkToDeath(mMockConnection.getDeathRecipient(),
                0);
    }

    /**
     * This test simulates {@link WindowMagnificationManager#setConnection} is called by thread A
     * and then the former connection is called by thread B. In this situation we should keep the
     * new connection.
     */
    @Test
    public void
            setSecondConnectionAndFormerConnectionBinderDead_hasWrapperAndNotCallUnlinkToDeath() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        MockWindowMagnificationConnection secondConnection =
                new MockWindowMagnificationConnection();

        mWindowMagnificationManager.setConnection(secondConnection.getConnection());
        mMockConnection.getDeathRecipient().binderDied();

        assertNotNull(mWindowMagnificationManager.mConnectionWrapper);
        verify(mMockConnection.asBinder()).unlinkToDeath(mMockConnection.getDeathRecipient(), 0);
        verify(secondConnection.asBinder(), never()).unlinkToDeath(
                secondConnection.getDeathRecipient(), 0);
    }

    @Test
    public void setNullConnection_hasConnection_wrapperIsNull() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        mWindowMagnificationManager.setConnection(null);

        assertNull(mWindowMagnificationManager.mConnectionWrapper);
        verify(mMockConnection.getConnection()).setConnectionCallback(null);
    }

    private static class MockWindowMagnificationConnection  {

        private final IWindowMagnificationConnection mConnection;
        private final Binder mBinder;
        private IBinder.DeathRecipient mDeathRecipient;

        MockWindowMagnificationConnection() {
            mConnection = mock(IWindowMagnificationConnection.class);
            mBinder = mock(Binder.class);
            when(mConnection.asBinder()).thenReturn(mBinder);
            doAnswer((invocation) -> {
                mDeathRecipient = invocation.getArgument(0);
                return null;
            }).when(mBinder).linkToDeath(
                    any(IBinder.DeathRecipient.class), eq(0));
        }

        IWindowMagnificationConnection getConnection() {
            return mConnection;
        }

        public IBinder.DeathRecipient getDeathRecipient() {
            return mDeathRecipient;
        }

        Binder asBinder() {
            return mBinder;
        }
    }
}
