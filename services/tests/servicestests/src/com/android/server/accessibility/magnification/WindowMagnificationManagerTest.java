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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import static java.lang.Float.NaN;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for WindowMagnificationManager.
 */
public class WindowMagnificationManagerTest {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;
    private static final int CURRENT_USER_ID = UserHandle.USER_CURRENT;

    private MockWindowMagnificationConnection mMockConnection;
    @Mock
    private Context mContext;
    private MockContentResolver mResolver;
    private WindowMagnificationManager mWindowMagnificationManager;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mResolver = new MockContentResolver();
        mMockConnection = new MockWindowMagnificationConnection();
        mWindowMagnificationManager = new WindowMagnificationManager(mContext, CURRENT_USER_ID);

        when(mContext.getContentResolver()).thenReturn(mResolver);
        mResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        Settings.Secure.putFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 2.5f,
                CURRENT_USER_ID);
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
            setSecondConnectionAndFormerConnectionBinderDead_hasWrapperAndNotCallUnlinkToDeath()
            throws RemoteException {
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

    @Test
    public void enable_TestDisplay_enableWindowMagnification() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 2f, 200f, 300f);

        verify(mMockConnection.getConnection()).enableWindowMagnification(TEST_DISPLAY, 2f,
                200f, 300f);
    }

    @Test
    public void disable_testDisplay_disableWindowMagnification() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 3f, NaN, NaN);

        mWindowMagnificationManager.disableWindowMagnifier(TEST_DISPLAY, false);

        verify(mMockConnection.getConnection()).disableWindowMagnification(TEST_DISPLAY);
    }

    @Test
    public void isWindowMagnifierEnabled_returnExpectedValue() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));

        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 2f, NaN, NaN);

        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void getPersistedScale() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        assertEquals(mWindowMagnificationManager.getPersistedScale(), 2.5f);
    }

    @Test
    public void persistScale_setValue_expectedValueInProvider() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 2.0f, NaN, NaN);
        mWindowMagnificationManager.setScale(TEST_DISPLAY, 2.5f);

        mWindowMagnificationManager.persistScale(TEST_DISPLAY);

        assertEquals(Settings.Secure.getFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 0f,
                CURRENT_USER_ID), 2.5f);
    }

    @Test
    public void scaleSetterGetter_enabledOnTestDisplay_expectedValue() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 2.0f, NaN, NaN);

        mWindowMagnificationManager.setScale(TEST_DISPLAY, 2.5f);

        assertEquals(mWindowMagnificationManager.getScale(TEST_DISPLAY), 2.5f);
    }

    @Test
    public void scaleSetterGetter_scaleIsOutOfRang_getNormalizeValue() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 2.5f, NaN, NaN);

        mWindowMagnificationManager.setScale(TEST_DISPLAY, 10.0f);

        assertEquals(mWindowMagnificationManager.getScale(TEST_DISPLAY),
                WindowMagnificationManager.MAX_SCALE);
    }

    @Test
    public void moveWindowMagnifier() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 2f, NaN, NaN);

        mWindowMagnificationManager.moveWindowMagnifier(TEST_DISPLAY, 200, 300);
        verify(mMockConnection.getConnection()).moveWindowMagnifier(TEST_DISPLAY, 200, 300);
    }

    @Test
    public void showMagnificationButton() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        mWindowMagnificationManager.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        verify(mMockConnection.getConnection()).showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        mWindowMagnificationManager.removeMagnificationButton(TEST_DISPLAY);
        verify(mMockConnection.getConnection()).removeMagnificationButton(TEST_DISPLAY);
    }

    @Test
    public void pointersInWindow_returnCorrectValue() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 3.0f, NaN, NaN);
        mMockConnection.getConnectionCallback().onWindowMagnifierBoundsChanged(TEST_DISPLAY,
                new Rect(0, 0, 500, 500));
        PointF[] pointersLocation = new PointF[2];
        pointersLocation[0] = new PointF(600, 700);
        pointersLocation[1] = new PointF(300, 400);
        MotionEvent event = generatePointersDownEvent(pointersLocation);

        assertEquals(mWindowMagnificationManager.pointersInWindow(TEST_DISPLAY, event), 1);
    }

    @Test
    public void binderDied_windowMagnifierIsEnabled_resetState() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnifier(TEST_DISPLAY, 3f, NaN, NaN);

        mMockConnection.getDeathRecipient().binderDied();

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void isConnected_returnExpectedValue() throws RemoteException {
        assertFalse(mWindowMagnificationManager.isConnected());

        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        assertTrue(mWindowMagnificationManager.isConnected());
    }

    private MotionEvent generatePointersDownEvent(PointF[] pointersLocation) {
        final int len = pointersLocation.length;

        final MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[len];
        for (int i = 0; i < len; i++) {
            MotionEvent.PointerProperties pointerProperty = new MotionEvent.PointerProperties();
            pointerProperty.id = i;
            pointerProperty.toolType = MotionEvent.TOOL_TYPE_FINGER;
            pp[i] = pointerProperty;
        }

        final MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[len];
        for (int i = 0; i < len; i++) {
            MotionEvent.PointerCoords pointerCoord = new MotionEvent.PointerCoords();
            pointerCoord.x = pointersLocation[i].x;
            pointerCoord.y = pointersLocation[i].y;
            pc[i] = pointerCoord;
        }

        return MotionEvent.obtain(
                /* downTime */ SystemClock.uptimeMillis(),
                /* eventTime */ SystemClock.uptimeMillis(),
                /* action */ MotionEvent.ACTION_POINTER_DOWN,
                /* pointerCount */ pc.length,
                /* pointerProperties */ pp,
                /* pointerCoords */ pc,
                /* metaState */ 0,
                /* buttonState */ 0,
                /* xPrecision */ 1.0f,
                /* yPrecision */ 1.0f,
                /* deviceId */ 0,
                /* edgeFlags */ 0,
                /* source */ InputDevice.SOURCE_TOUCHSCREEN,
                /* flags */ 0);
    }


}
