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
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.provider.Settings;
import android.view.Display;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;
import android.view.accessibility.MagnificationAnimationCallback;

import com.android.server.accessibility.AccessibilityTraceManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for MagnificationConnectionWrapper. We don't test {@code
 * MagnificationConnectionWrapper#linkToDeath(IBinder.DeathRecipient)} since it's tested in
 * {@link WindowMagnificationManagerTest}.
 */
public class MagnificationConnectionWrapperTest {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;

    private IWindowMagnificationConnection mConnection;
    @Mock
    private AccessibilityTraceManager mTrace;
    @Mock
    private IWindowMagnificationConnectionCallback mCallback;
    @Mock
    private MagnificationAnimationCallback mAnimationCallback;

    private MockWindowMagnificationConnection mMockWindowMagnificationConnection;
    private MagnificationConnectionWrapper mConnectionWrapper;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mMockWindowMagnificationConnection = new MockWindowMagnificationConnection();
        mConnection = mMockWindowMagnificationConnection.getConnection();
        mConnectionWrapper = new MagnificationConnectionWrapper(mConnection, mTrace);
    }

    @Test
    public void enableWindowMagnification() throws RemoteException {
        mConnectionWrapper.enableWindowMagnification(TEST_DISPLAY, 2, 100f, 200f,
                0f, 0f, mAnimationCallback);

        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void setScale() throws RemoteException {
        mConnectionWrapper.setScale(TEST_DISPLAY, 3.0f);
        verify(mConnection).setScale(TEST_DISPLAY, 3.0f);
    }

    @Test
    public void disableWindowMagnification() throws RemoteException {
        mConnectionWrapper.disableWindowMagnification(TEST_DISPLAY, mAnimationCallback);

        verify(mConnection).disableWindowMagnification(eq(TEST_DISPLAY),
                any(IRemoteMagnificationAnimationCallback.class));
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void moveWindowMagnifier() throws RemoteException {
        mConnectionWrapper.moveWindowMagnifier(TEST_DISPLAY, 100, 150);
        verify(mConnection).moveWindowMagnifier(TEST_DISPLAY, 100, 150);
    }

    @Test
    public void moveWindowMagnifierToPosition() throws RemoteException {
        mConnectionWrapper.moveWindowMagnifierToPosition(TEST_DISPLAY, 100, 150,
                mAnimationCallback);
        verify(mConnection).moveWindowMagnifierToPosition(eq(TEST_DISPLAY),
                eq(100f), eq(150f), any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void showMagnificationButton() throws RemoteException {
        mConnectionWrapper.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        verify(mConnection).showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void removeMagnificationButton() throws RemoteException {
        mConnectionWrapper.removeMagnificationButton(TEST_DISPLAY);
        verify(mConnection).removeMagnificationButton(TEST_DISPLAY);
    }

    @Test
    public void removeMagnificationSettingsPanel() throws RemoteException {
        mConnectionWrapper.removeMagnificationSettingsPanel(TEST_DISPLAY);
        verify(mConnection).removeMagnificationSettingsPanel(eq(TEST_DISPLAY));
    }

    @Test
    public void onUserMagnificationScaleChanged() throws RemoteException {
        final int testUserId = 1;
        final float testScale = 3f;
        mConnectionWrapper.onUserMagnificationScaleChanged(testUserId, TEST_DISPLAY, testScale);
        verify(mConnection).onUserMagnificationScaleChanged(
                eq(testUserId), eq(TEST_DISPLAY), eq(testScale));
    }

    @Test
    public void setMirrorWindowCallback() throws RemoteException {
        mConnectionWrapper.setConnectionCallback(mCallback);
        verify(mConnection).setConnectionCallback(mCallback);
    }
}
