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


import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.view.Display;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for WindowMagnificationConnectionWrapper. We don't test {@code
 * WindowMagnificationConnectionWrapper#linkToDeath(IBinder.DeathRecipient)} since it's tested in
 * {@link WindowMagnificationManagerTest}.
 */
public class WindowMagnificationConnectionWrapperTest {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;

    @Mock
    private IWindowMagnificationConnection mConnection;
    @Mock
    private IWindowMagnificationConnectionCallback mCallback;
    private WindowMagnificationConnectionWrapper mConnectionWrapper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mConnectionWrapper = new WindowMagnificationConnectionWrapper(mConnection);
    }

    @Test
    public void enableWindowMagnification() throws RemoteException {
        mConnectionWrapper.enableWindowMagnification(TEST_DISPLAY, 2, 100f, 200f);
        verify(mConnection).enableWindowMagnification(TEST_DISPLAY, 2, 100f, 200f);
    }

    @Test
    public void setScale() throws RemoteException {
        mConnectionWrapper.setScale(TEST_DISPLAY, 3.0f);
        verify(mConnection).setScale(TEST_DISPLAY, 3.0f);
    }

    @Test
    public void disableWindowMagnification() throws RemoteException {
        mConnectionWrapper.disableWindowMagnification(TEST_DISPLAY);
        verify(mConnection).disableWindowMagnification(TEST_DISPLAY);
    }

    @Test
    public void moveWindowMagnifier() throws RemoteException {
        mConnectionWrapper.moveWindowMagnifier(0, 100, 150);
        verify(mConnection).moveWindowMagnifier(0, 100, 150);
    }

    @Test
    public void setMirrorWindowCallback() throws RemoteException {
        mConnectionWrapper.setConnectionCallback(mCallback);
        verify(mConnection).setConnectionCallback(mCallback);
    }

}
