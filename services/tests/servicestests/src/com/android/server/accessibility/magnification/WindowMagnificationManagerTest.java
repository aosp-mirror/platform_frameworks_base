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

import static com.android.server.accessibility.magnification.MockWindowMagnificationConnection.TEST_DISPLAY;
import static com.android.server.accessibility.magnification.MockWindowMagnificationConnection.TEST_DISPLAY_2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import static java.lang.Float.NaN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnectionCallback;
import android.view.accessibility.MagnificationAnimationCallback;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.statusbar.StatusBarManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

/**
 * Tests for WindowMagnificationManager.
 */
public class WindowMagnificationManagerTest {

    private static final int CURRENT_USER_ID = UserHandle.USER_SYSTEM;
    private static final int SERVICE_ID = 1;

    private MockWindowMagnificationConnection mMockConnection;
    @Mock
    private Context mContext;
    @Mock
    private AccessibilityTraceManager mMockTrace;
    @Mock
    private StatusBarManagerInternal mMockStatusBarManagerInternal;
    @Mock
    private MagnificationAnimationCallback mAnimationCallback;
    @Mock
    private WindowMagnificationManager.Callback mMockCallback;
    private MockContentResolver mResolver;
    private WindowMagnificationManager mWindowMagnificationManager;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mMockStatusBarManagerInternal);
        mResolver = new MockContentResolver();
        mMockConnection = new MockWindowMagnificationConnection();
        mWindowMagnificationManager = new WindowMagnificationManager(mContext, new Object(),
                mMockCallback, mMockTrace, new MagnificationScaleProvider(mContext));

        when(mContext.getContentResolver()).thenReturn(mResolver);
        stubSetConnection(false);

        mResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        Settings.Secure.putFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 2.5f,
                CURRENT_USER_ID);
    }

    private void stubSetConnection(boolean needDelay) {
        doAnswer((InvocationOnMock invocation) -> {
            final boolean connect = (Boolean) invocation.getArguments()[0];
            // Simulates setConnection() called by another process.
            if (needDelay) {
                final Context context = ApplicationProvider.getApplicationContext();
                context.getMainThreadHandler().postDelayed(
                        () -> {
                            mWindowMagnificationManager.setConnection(
                                    connect ? mMockConnection.getConnection() : null);
                        }, 10);
            } else {
                mWindowMagnificationManager.setConnection(
                        connect ? mMockConnection.getConnection() : null);
            }
            return true;
        }).when(mMockStatusBarManagerInternal).requestWindowMagnificationConnection(anyBoolean());
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
    public void setSecondConnectionAndFormerConnectionBinderDead_hasWrapperAndNotCallUnlinkToDeath()
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
    public void enableWithAnimation_hasConnection_enableWindowMagnification()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 2f, 200f, 300f);

        verify(mMockConnection.getConnection()).enableWindowMagnification(eq(TEST_DISPLAY), eq(2f),
                eq(200f), eq(300f), eq(0f), eq(0f), notNull());
    }

    @Test
    public void enableWithCallback_hasConnection_enableWindowMagnification()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 2f, 200f, 300f,
                mAnimationCallback, SERVICE_ID);

        verify(mMockConnection.getConnection()).enableWindowMagnification(eq(TEST_DISPLAY), eq(2f),
                eq(200f), eq(300f), eq(0f), eq(0f),
                any(IRemoteMagnificationAnimationCallback.class));
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void disable_hasConnectionAndEnabled_disableWindowMagnification()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f, NaN, NaN);

        mWindowMagnificationManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mMockConnection.getConnection()).disableWindowMagnification(eq(TEST_DISPLAY),
                notNull());
    }

    @Test
    public void disableWithCallback_hasConnectionAndEnabled_disableWindowMagnification()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f, NaN, NaN);

        mWindowMagnificationManager.disableWindowMagnification(TEST_DISPLAY, false,
                mAnimationCallback);

        verify(mMockConnection.getConnection()).disableWindowMagnification(eq(TEST_DISPLAY),
                any(IRemoteMagnificationAnimationCallback.class));
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void isWindowMagnifierEnabled_hasConnectionAndEnabled_returnExpectedValue() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));

        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 2f, NaN, NaN);

        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void getPersistedScale() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        assertEquals(mWindowMagnificationManager.getPersistedScale(TEST_DISPLAY), 2.5f);
    }

    @Test
    public void persistScale_setValue_expectedValueInProvider() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 2.0f, NaN, NaN);
        mWindowMagnificationManager.setScale(TEST_DISPLAY, 2.5f);

        mWindowMagnificationManager.persistScale(TEST_DISPLAY);

        assertEquals(Settings.Secure.getFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 0f,
                CURRENT_USER_ID), 2.5f);
    }

    @Test
    public void scaleSetterGetter_enabledOnTestDisplay_expectedValue() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 2.0f, NaN, NaN);

        mWindowMagnificationManager.setScale(TEST_DISPLAY, 2.5f);

        assertEquals(mWindowMagnificationManager.getScale(TEST_DISPLAY), 2.5f);
    }

    @Test
    public void scaleSetterGetter_scaleIsOutOfRang_getNormalizeValue() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 2.5f, NaN, NaN);

        mWindowMagnificationManager.setScale(TEST_DISPLAY, 10.0f);

        assertEquals(mWindowMagnificationManager.getScale(TEST_DISPLAY),
                MagnificationScaleProvider.MAX_SCALE);
    }

    @Test
    public void onRectangleOnScreenRequested_trackingDisabledByOnDrag_withoutMovingMagnifier()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mMockConnection.getConnectionCallback().onMove(TEST_DISPLAY);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }


    @Test
    public void onRectangleOnScreenRequested_trackingDisabledByScroll_withoutMovingMagnifier()
            throws RemoteException {
        final float distanceX = 10f;
        final float distanceY = 10f;
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mWindowMagnificationManager.processScroll(TEST_DISPLAY, distanceX, distanceY);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void onRectangleOnScreenRequested_requestRectangleInBound_withoutMovingMagnifier()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.inset(-10, -10);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }
    @Test
    public void onRectangleOnScreenRequested_imeVisibilityDefaultInvisible_withoutMovingMagnifier()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        final Region outRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void onRectangleOnScreenRequested_trackingEnabledByDefault_movingMagnifier()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection()).moveWindowMagnifierToPosition(eq(TEST_DISPLAY),
                eq(requestedRect.exactCenterX()), eq(requestedRect.exactCenterY()),
                any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void onRectangleOnScreenRequested_imeInvisible_withoutMovingMagnifier()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, false);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void onRectangleOnScreenRequested_trackingEnabledByDragAndReset_movingMagnifier()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        mMockConnection.getConnectionCallback().onMove(TEST_DISPLAY);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection()).moveWindowMagnifierToPosition(eq(TEST_DISPLAY),
                eq(requestedRect.exactCenterX()), eq(requestedRect.exactCenterY()),
                any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void onRectangleOnScreenRequested_followTypingIsDisabled_withoutMovingMagnifier() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region beforeRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, beforeRegion);
        final Rect requestedRect = beforeRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mWindowMagnificationManager.setMagnificationFollowTypingEnabled(false);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        final Region afterRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, afterRegion);
        assertEquals(afterRegion, beforeRegion);
    }

    @Test
    public void onRectangleOnScreenRequested_trackingDisabled_withoutMovingMagnifier() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        mWindowMagnificationManager.setTrackingTypingFocusEnabled(TEST_DISPLAY, false);
        final Region beforeRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, beforeRegion);
        final Rect requestedRect = beforeRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        final Region afterRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, afterRegion);
        assertEquals(afterRegion, beforeRegion);
    }

    @Test
    public void onRectangleOnScreenRequested_trackingDisabledAndEnabledMagnifier_movingMagnifier()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mWindowMagnificationManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        mWindowMagnificationManager.setTrackingTypingFocusEnabled(TEST_DISPLAY, false);
        final Region beforeRegion = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, beforeRegion);
        final Rect requestedRect = beforeRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mWindowMagnificationManager.disableWindowMagnification(TEST_DISPLAY, false);
        // Enabling a window magnifier again will turn on the tracking typing focus functionality.
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, NaN, NaN, NaN);

        mWindowMagnificationManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection()).moveWindowMagnifierToPosition(eq(TEST_DISPLAY),
                eq(requestedRect.exactCenterX()), eq(requestedRect.exactCenterY()),
                any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void moveWindowMagnifier_enabled_invokeConnectionMethod() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 2f, NaN, NaN);

        mWindowMagnificationManager.moveWindowMagnification(TEST_DISPLAY, 200, 300);
        verify(mMockConnection.getConnection()).moveWindowMagnifier(TEST_DISPLAY, 200, 300);
    }

    @Test
    public void showMagnificationButton_hasConnection_invokeConnectionMethod()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        mWindowMagnificationManager.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        verify(mMockConnection.getConnection()).showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        mWindowMagnificationManager.removeMagnificationButton(TEST_DISPLAY);
        verify(mMockConnection.getConnection()).removeMagnificationButton(TEST_DISPLAY);
    }

    @Test
    public void pointersInWindow_magnifierEnabled_returnCorrectValue() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);
        mMockConnection.getConnectionCallback().onWindowMagnifierBoundsChanged(TEST_DISPLAY,
                new Rect(0, 0, 500, 500));
        PointF[] pointersLocation = new PointF[2];
        pointersLocation[0] = new PointF(600, 700);
        pointersLocation[1] = new PointF(300, 400);
        MotionEvent event = generatePointersDownEvent(pointersLocation);

        assertEquals(mWindowMagnificationManager.pointersInWindow(TEST_DISPLAY, event), 1);
    }

    @Test
    public void onPerformScaleAction_magnifierEnabled_notifyAction() throws RemoteException {
        final float newScale = 4.0f;
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);

        mMockConnection.getConnectionCallback().onPerformScaleAction(TEST_DISPLAY, newScale);

        verify(mMockCallback).onPerformScaleAction(eq(TEST_DISPLAY), eq(newScale));
    }

    @Test
    public void onAccessibilityActionPerformed_magnifierEnabled_notifyAction()
            throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);

        mMockConnection.getConnectionCallback().onAccessibilityActionPerformed(TEST_DISPLAY);

        verify(mMockCallback).onAccessibilityActionPerformed(eq(TEST_DISPLAY));
    }

    @Test
    public void binderDied_windowMagnifierIsEnabled_resetState() throws RemoteException {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f, NaN, NaN);

        mMockConnection.getDeathRecipient().binderDied();

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void
            requestConnectionToNull_disableAllMagnifiersAndRequestWindowMagnificationConnection()
            throws RemoteException {
        assertTrue(mWindowMagnificationManager.requestConnection(true));
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f, NaN, NaN);

        assertTrue(mWindowMagnificationManager.requestConnection(false));

        verify(mMockConnection.getConnection()).disableWindowMagnification(TEST_DISPLAY, null);
        verify(mMockStatusBarManagerInternal).requestWindowMagnificationConnection(false);
    }

    @Test
    public void requestConnection_requestWindowMagnificationConnection() throws RemoteException {
        assertTrue(mWindowMagnificationManager.requestConnection(true));
        verify(mMockStatusBarManagerInternal).requestWindowMagnificationConnection(true);
    }

    @Test
    public void isConnected_requestConnection_expectedValue() throws RemoteException {
        mWindowMagnificationManager.requestConnection(true);
        assertTrue(mWindowMagnificationManager.isConnected());

        mWindowMagnificationManager.requestConnection(false);
        assertFalse(mWindowMagnificationManager.isConnected());
    }

    @Test
    public void requestConnection_registerAndUnregisterBroadcastReceiver() {
        assertTrue(mWindowMagnificationManager.requestConnection(true));
        verify(mContext).registerReceiver(any(BroadcastReceiver.class), any(IntentFilter.class));

        assertTrue(mWindowMagnificationManager.requestConnection(false));
        verify(mContext).unregisterReceiver(any(BroadcastReceiver.class));
    }

    @Test
    public void requestConnectionToNull_expectedGetterResults() {
        mWindowMagnificationManager.requestConnection(true);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f, 1, 1);

        mWindowMagnificationManager.requestConnection(false);

        assertEquals(1f, mWindowMagnificationManager.getScale(TEST_DISPLAY), 0);
        assertTrue(Float.isNaN(mWindowMagnificationManager.getCenterX(TEST_DISPLAY)));
        assertTrue(Float.isNaN(mWindowMagnificationManager.getCenterY(TEST_DISPLAY)));
        final Region bounds = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, bounds);
        assertTrue(bounds.isEmpty());
    }

    @Test
    public void enableWindowMagnification_connecting_invokeConnectionMethodAfterConnected()
            throws RemoteException {
        stubSetConnection(true);
        mWindowMagnificationManager.requestConnection(true);

        assertTrue(mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f, 1, 1));

        // Invoke enableWindowMagnification if the connection is connected.
        verify(mMockConnection.getConnection()).enableWindowMagnification(
                eq(TEST_DISPLAY), eq(3f),
                eq(1f), eq(1f), eq(0f), eq(0f), notNull());
    }

    @Test
    public void resetAllMagnification_enabledBySameId_windowMagnifiersDisabled() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, null, WindowMagnificationManager.WINDOW_POSITION_AT_CENTER, SERVICE_ID);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY_2, 3f,
                100f, 200f, null, WindowMagnificationManager.WINDOW_POSITION_AT_CENTER, SERVICE_ID);
        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY_2));

        mWindowMagnificationManager.resetAllIfNeeded(SERVICE_ID);

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY_2));
    }

    @Test
    public void resetAllMagnification_enabledByDifferentId_windowMagnifierDisabled() {
        final int serviceId2 = SERVICE_ID + 1;
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, null, WindowMagnificationManager.WINDOW_POSITION_AT_CENTER, SERVICE_ID);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY_2, 3f,
                100f, 200f, null, WindowMagnificationManager.WINDOW_POSITION_AT_CENTER, serviceId2);
        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY_2));

        mWindowMagnificationManager.resetAllIfNeeded(SERVICE_ID);

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY_2));
    }

    @Test
    public void onScreenOff_windowMagnifierIsEnabled_removeButtonAndDisableWindowMagnification()
            throws RemoteException {
        mWindowMagnificationManager.requestConnection(true);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 2.5f, NaN, NaN);

        mWindowMagnificationManager.mScreenStateReceiver.onReceive(mContext,
                new Intent(Intent.ACTION_SCREEN_OFF));

        verify(mMockConnection.getConnection()).removeMagnificationButton(TEST_DISPLAY);
        verify(mMockConnection.getConnection()).disableWindowMagnification(TEST_DISPLAY, null);
        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void centerGetter_enabledOnTestDisplay_expectedValues() {
        mWindowMagnificationManager.requestConnection(true);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f, 100f, 200f);

        assertEquals(mWindowMagnificationManager.getCenterX(TEST_DISPLAY), 100f);
        assertEquals(mWindowMagnificationManager.getCenterY(TEST_DISPLAY), 200f);
    }

    @Test
    public void centerGetter_enabledOnTestDisplayWindowAtCenter_expectedValues()
            throws RemoteException {
        mWindowMagnificationManager.requestConnection(true);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, WindowMagnificationManager.WINDOW_POSITION_AT_CENTER);

        assertEquals(mWindowMagnificationManager.getCenterX(TEST_DISPLAY), 100f);
        assertEquals(mWindowMagnificationManager.getCenterY(TEST_DISPLAY), 200f);

        verify(mMockConnection.getConnection()).enableWindowMagnification(eq(TEST_DISPLAY), eq(3f),
                eq(100f), eq(200f), eq(0f), eq(0f), notNull());
    }

    @Test
    public void centerGetter_enabledOnTestDisplayWindowAtLeftTop_expectedValues()
            throws RemoteException {
        mWindowMagnificationManager.requestConnection(true);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, WindowMagnificationManager.WINDOW_POSITION_AT_TOP_LEFT);

        assertEquals(mWindowMagnificationManager.getCenterX(TEST_DISPLAY), 100f);
        assertEquals(mWindowMagnificationManager.getCenterY(TEST_DISPLAY), 200f);

        verify(mMockConnection.getConnection()).enableWindowMagnification(eq(TEST_DISPLAY), eq(3f),
                eq(100f), eq(200f), eq(-1f), eq(-1f), notNull());
    }

    @Test
    public void magnifierGetters_disabled_expectedValues() {
        mWindowMagnificationManager.requestConnection(true);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, WindowMagnificationManager.WINDOW_POSITION_AT_CENTER);

        mWindowMagnificationManager.disableWindowMagnification(TEST_DISPLAY, false);

        assertEquals(1f, mWindowMagnificationManager.getScale(TEST_DISPLAY), 0);
        assertTrue(Float.isNaN(mWindowMagnificationManager.getCenterX(TEST_DISPLAY)));
        assertTrue(Float.isNaN(mWindowMagnificationManager.getCenterY(TEST_DISPLAY)));
        final Region bounds = new Region();
        mWindowMagnificationManager.getMagnificationSourceBounds(TEST_DISPLAY, bounds);
        assertTrue(bounds.isEmpty());
    }

    @Test
    public void onDisplayRemoved_enabledOnTestDisplay_disabled() {
        mWindowMagnificationManager.requestConnection(true);
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3f, 100f, 200f);

        mWindowMagnificationManager.onDisplayRemoved(TEST_DISPLAY);

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void onWindowMagnificationActivationState_magnifierEnabled_notifyActivatedState() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);

        verify(mMockCallback).onWindowMagnificationActivationState(TEST_DISPLAY, true);
    }

    @Test
    public void onWindowMagnificationActivationState_magnifierDisabled_notifyDeactivatedState() {
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);
        mWindowMagnificationManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mMockCallback).onWindowMagnificationActivationState(TEST_DISPLAY, false);

        Mockito.reset(mMockCallback);
        mWindowMagnificationManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mMockCallback, never()).onWindowMagnificationActivationState(eq(TEST_DISPLAY),
                anyBoolean());
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
