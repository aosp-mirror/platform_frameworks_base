/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static org.mockito.Mockito.when;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

class MockWindowMagnificationConnection  {

    private final IWindowMagnificationConnection mConnection;
    private final Binder mBinder;
    private IBinder.DeathRecipient mDeathRecipient;
    private IWindowMagnificationConnectionCallback mIMirrorWindowCallback;

    MockWindowMagnificationConnection() throws RemoteException {
        mConnection = mock(IWindowMagnificationConnection.class);
        mBinder = mock(Binder.class);
        when(mConnection.asBinder()).thenReturn(mBinder);
        doAnswer((invocation) -> {
            mIMirrorWindowCallback = invocation.getArgument(0);
            return null;
        }).when(mConnection).setConnectionCallback(
                any(IWindowMagnificationConnectionCallback.class));

        doAnswer((invocation) -> {
            mDeathRecipient = invocation.getArgument(0);
            return null;
        }).when(mBinder).linkToDeath(
                any(IBinder.DeathRecipient.class), eq(0));
    }

    IWindowMagnificationConnection getConnection() {
        return mConnection;
    }

    Binder asBinder() {
        return mBinder;
    }

    public IBinder.DeathRecipient getDeathRecipient() {
        return mDeathRecipient;
    }

    public IWindowMagnificationConnectionCallback getConnectionCallback() {
        return mIMirrorWindowCallback;
    }
}

