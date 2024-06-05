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

import static com.android.server.accessibility.magnification.MockMagnificationConnection.TEST_DISPLAY;
import static com.android.server.accessibility.magnification.MockMagnificationConnection.TEST_DISPLAY_2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
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
import android.view.accessibility.IMagnificationConnectionCallback;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.MagnificationAnimationCallback;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.FlakyTest;

import com.android.compatibility.common.util.TestUtils;
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
public class MagnificationConnectionManagerTest {

    private static final int WAIT_CONNECTION_TIMEOUT_SECOND = 1;
    private static final int CURRENT_USER_ID = UserHandle.USER_SYSTEM;
    private static final int SERVICE_ID = 1;

    private MockMagnificationConnection mMockConnection;
    @Mock
    private Context mContext;
    @Mock
    private AccessibilityTraceManager mMockTrace;
    @Mock
    private StatusBarManagerInternal mMockStatusBarManagerInternal;
    @Mock
    private MagnificationAnimationCallback mAnimationCallback;
    @Mock
    private MagnificationConnectionManager.Callback mMockCallback;
    private MockContentResolver mResolver;
    private MagnificationConnectionManager mMagnificationConnectionManager;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mMockStatusBarManagerInternal);
        mResolver = new MockContentResolver();
        mMockConnection = new MockMagnificationConnection();
        mMagnificationConnectionManager = new MagnificationConnectionManager(mContext, new Object(),
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
            // Use post to simulate setConnection() called by another process.
            final Context context = ApplicationProvider.getApplicationContext();
            if (needDelay) {
                context.getMainThreadHandler().postDelayed(
                        () -> {
                            mMagnificationConnectionManager.setConnection(
                                    connect ? mMockConnection.getConnection() : null);
                        }, 10);
            } else {
                context.getMainThreadHandler().post(() -> {
                    mMagnificationConnectionManager.setConnection(
                            connect ? mMockConnection.getConnection() : null);
                });
            }
            return true;
        }).when(mMockStatusBarManagerInternal).requestMagnificationConnection(anyBoolean());
    }

    @Test
    public void setConnection_connectionIsNull_wrapperIsNullAndLinkToDeath() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        assertTrue(mMagnificationConnectionManager.isConnected());
        verify(mMockConnection.asBinder()).linkToDeath(any(IBinder.DeathRecipient.class), eq(0));
    }

    @Test
    public void setConnection_connectionIsNull_setMirrorWindowCallbackAndHasWrapper()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        assertTrue(mMagnificationConnectionManager.isConnected());
        verify(mMockConnection.asBinder()).linkToDeath(any(IBinder.DeathRecipient.class), eq(0));
        verify(mMockConnection.getConnection()).setConnectionCallback(
                any(IMagnificationConnectionCallback.class));
    }

    @Test
    public void binderDied_hasConnection_wrapperIsNullAndUnlinkToDeath() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        mMockConnection.getDeathRecipient().binderDied();

        assertFalse(mMagnificationConnectionManager.isConnected());
        verify(mMockConnection.asBinder()).unlinkToDeath(mMockConnection.getDeathRecipient(),
                0);
    }

    /**
     * This test simulates {@link MagnificationConnectionManager#setConnection} is called by thread
     * A and then the former connection is called by thread B. In this situation we should keep the
     * new connection.
     */
    @Test
    public void setSecondConnectionAndFormerConnectionBinderDead_hasWrapperAndNotCallUnlinkToDeath()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        MockMagnificationConnection secondConnection = new MockMagnificationConnection();

        mMagnificationConnectionManager.setConnection(secondConnection.getConnection());
        mMockConnection.getDeathRecipient().binderDied();

        assertTrue(mMagnificationConnectionManager.isConnected());
        verify(mMockConnection.asBinder()).unlinkToDeath(mMockConnection.getDeathRecipient(), 0);
        verify(secondConnection.asBinder(), never()).unlinkToDeath(
                secondConnection.getDeathRecipient(), 0);
    }

    @Test
    public void setNullConnection_hasConnection_wrapperIsNull() throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        mMagnificationConnectionManager.setConnection(null);

        assertFalse(mMagnificationConnectionManager.isConnected());
        verify(mMockConnection.getConnection()).setConnectionCallback(null);
    }

    @Test
    public void enableWithAnimation_hasConnection_enableWindowMagnification()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2f, 200f, 300f);

        verify(mMockConnection.getConnection()).enableWindowMagnification(eq(TEST_DISPLAY), eq(2f),
                eq(200f), eq(300f), eq(0f), eq(0f), notNull());
    }

    @Test
    public void enableWithCallback_hasConnection_enableWindowMagnification()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2f, 200f, 300f,
                mAnimationCallback, SERVICE_ID);

        verify(mMockConnection.getConnection()).enableWindowMagnification(eq(TEST_DISPLAY), eq(2f),
                eq(200f), eq(300f), eq(0f), eq(0f),
                any(IRemoteMagnificationAnimationCallback.class));
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void disable_hasConnectionAndEnabled_disableWindowMagnification()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f, NaN, NaN);

        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mMockConnection.getConnection()).disableWindowMagnification(eq(TEST_DISPLAY),
                notNull());
    }

    @Test
    public void disableWithCallback_hasConnectionAndEnabled_disableWindowMagnification()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f, NaN, NaN);

        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, false,
                mAnimationCallback);

        verify(mMockConnection.getConnection()).disableWindowMagnification(eq(TEST_DISPLAY),
                any(IRemoteMagnificationAnimationCallback.class));
        verify(mAnimationCallback).onResult(true);
    }

    @Test
    public void isWindowMagnifierEnabled_hasConnectionAndEnabled_returnExpectedValue() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));

        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2f, NaN, NaN);

        assertTrue(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void getPersistedScale() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        assertEquals(mMagnificationConnectionManager.getPersistedScale(TEST_DISPLAY), 2.5f);
    }

    @Test
    public void persistScale_setValue_expectedValueInProvider() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2.0f, NaN, NaN);
        mMagnificationConnectionManager.setScale(TEST_DISPLAY, 2.5f);

        mMagnificationConnectionManager.persistScale(TEST_DISPLAY);

        assertEquals(Settings.Secure.getFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 0f,
                CURRENT_USER_ID), 2.5f);
    }

    @Test
    public void persistScale_setValueWhenScaleIsOne_nothingChanged() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        final float persistedScale =
                mMagnificationConnectionManager.getPersistedScale(TEST_DISPLAY);

        mMagnificationConnectionManager.setScale(TEST_DISPLAY, 1.0f);
        mMagnificationConnectionManager.persistScale(TEST_DISPLAY);

        assertEquals(Settings.Secure.getFloatForUser(mResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 0f,
                CURRENT_USER_ID), persistedScale);
    }

    @Test
    public void scaleSetterGetter_enabledOnTestDisplay_expectedValue() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2.0f, NaN, NaN);

        mMagnificationConnectionManager.setScale(TEST_DISPLAY, 2.5f);

        assertEquals(mMagnificationConnectionManager.getScale(TEST_DISPLAY), 2.5f);
    }

    @Test
    public void scaleSetterGetter_scaleIsOutOfRang_getNormalizeValue() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2.5f, NaN, NaN);

        mMagnificationConnectionManager.setScale(TEST_DISPLAY, 10.0f);

        assertEquals(mMagnificationConnectionManager.getScale(TEST_DISPLAY),
                MagnificationScaleProvider.MAX_SCALE);
    }

    @FlakyTest(bugId = 297879435)
    @Test
    public void logTrackingTypingFocus_processScroll_logDuration() {
        MagnificationConnectionManager spyMagnificationConnectionManager = spy(
                mMagnificationConnectionManager);
        spyMagnificationConnectionManager.enableWindowMagnification(
                TEST_DISPLAY, 3.0f, 50f, 50f);
        spyMagnificationConnectionManager.onImeWindowVisibilityChanged(
                TEST_DISPLAY, /* shown */ true);

        spyMagnificationConnectionManager.processScroll(TEST_DISPLAY, 10f, 10f);

        verify(spyMagnificationConnectionManager).logTrackingTypingFocus(anyLong());
    }

    @Test
    public void onRectangleOnScreenRequested_trackingDisabledByOnDrag_withoutMovingMagnifier()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mMockConnection.getConnectionCallback().onMove(TEST_DISPLAY);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }


    @Test
    public void onRectangleOnScreenRequested_trackingDisabledByScroll_withoutMovingMagnifier()
            throws RemoteException {
        final float distanceX = 10f;
        final float distanceY = 10f;
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mMagnificationConnectionManager.processScroll(TEST_DISPLAY, distanceX, distanceY);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void onRectangleOnScreenRequested_requestRectangleInBound_withoutMovingMagnifier()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.inset(-10, -10);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }
    @Test
    public void onRectangleOnScreenRequested_imeVisibilityDefaultInvisible_withoutMovingMagnifier()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        final Region outRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void onRectangleOnScreenRequested_trackingEnabledByDefault_movingMagnifier()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection()).moveWindowMagnifierToPosition(eq(TEST_DISPLAY),
                eq(requestedRect.exactCenterX()), eq(requestedRect.exactCenterY()),
                any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void onRectangleOnScreenRequested_imeInvisible_withoutMovingMagnifier()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, false);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection(), never())
                .moveWindowMagnifierToPosition(anyInt(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void onRectangleOnScreenRequested_trackingEnabledByDragAndReset_movingMagnifier()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        mMockConnection.getConnectionCallback().onMove(TEST_DISPLAY);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region outRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, outRegion);
        final Rect requestedRect = outRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection()).moveWindowMagnifierToPosition(eq(TEST_DISPLAY),
                eq(requestedRect.exactCenterX()), eq(requestedRect.exactCenterY()),
                any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void onRectangleOnScreenRequested_followTypingIsDisabled_withoutMovingMagnifier() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        final Region beforeRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, beforeRegion);
        final Rect requestedRect = beforeRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mMagnificationConnectionManager.setMagnificationFollowTypingEnabled(false);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        final Region afterRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, afterRegion);
        assertEquals(afterRegion, beforeRegion);
    }

    @Test
    public void onRectangleOnScreenRequested_trackingDisabled_withoutMovingMagnifier() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        mMagnificationConnectionManager.setTrackingTypingFocusEnabled(TEST_DISPLAY, false);
        final Region beforeRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, beforeRegion);
        final Rect requestedRect = beforeRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        final Region afterRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, afterRegion);
        assertEquals(afterRegion, beforeRegion);
    }

    @Test
    public void onRectangleOnScreenRequested_trackingDisabledAndEnabledMagnifier_movingMagnifier()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, 50f, 50f);
        mMagnificationConnectionManager.onImeWindowVisibilityChanged(TEST_DISPLAY, true);
        mMagnificationConnectionManager.setTrackingTypingFocusEnabled(TEST_DISPLAY, false);
        final Region beforeRegion = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, beforeRegion);
        final Rect requestedRect = beforeRegion.getBounds();
        requestedRect.offsetTo(requestedRect.right + 10, requestedRect.bottom + 10);
        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, false);
        // Enabling a window magnifier again will turn on the tracking typing focus functionality.
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, NaN, NaN, NaN);

        mMagnificationConnectionManager.onRectangleOnScreenRequested(TEST_DISPLAY,
                requestedRect.left, requestedRect.top, requestedRect.right, requestedRect.bottom);

        verify(mMockConnection.getConnection()).moveWindowMagnifierToPosition(eq(TEST_DISPLAY),
                eq(requestedRect.exactCenterX()), eq(requestedRect.exactCenterY()),
                any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void moveWindowMagnifier_enabled_invokeConnectionMethod() throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2f, NaN, NaN);

        mMagnificationConnectionManager.moveWindowMagnification(TEST_DISPLAY, 200, 300);
        verify(mMockConnection.getConnection()).moveWindowMagnifier(TEST_DISPLAY, 200, 300);
    }

    @Test
    public void showMagnificationButton_hasConnection_invokeConnectionMethod()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        mMagnificationConnectionManager.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        verify(mMockConnection.getConnection()).showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        mMagnificationConnectionManager.removeMagnificationButton(TEST_DISPLAY);
        verify(mMockConnection.getConnection()).removeMagnificationButton(TEST_DISPLAY);
    }

    @Test
    public void removeMagnificationSettingsPanel_hasConnection_invokeConnectionMethod()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        mMagnificationConnectionManager.removeMagnificationSettingsPanel(TEST_DISPLAY);
        verify(mMockConnection.getConnection()).removeMagnificationSettingsPanel(TEST_DISPLAY);
    }

    @Test
    public void onUserMagnificationScaleChanged_hasConnection_invokeConnectionMethod()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        final float testScale = 3f;
        mMagnificationConnectionManager.onUserMagnificationScaleChanged(
                CURRENT_USER_ID, TEST_DISPLAY, testScale);
        verify(mMockConnection.getConnection()).onUserMagnificationScaleChanged(
                eq(CURRENT_USER_ID), eq(TEST_DISPLAY), eq(testScale));
    }

    @Test
    public void pointersInWindow_magnifierEnabled_returnCorrectValue() throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);
        mMockConnection.getConnectionCallback().onWindowMagnifierBoundsChanged(TEST_DISPLAY,
                new Rect(0, 0, 500, 500));
        PointF[] pointersLocation = new PointF[2];
        pointersLocation[0] = new PointF(600, 700);
        pointersLocation[1] = new PointF(300, 400);
        MotionEvent event = generatePointersDownEvent(pointersLocation);

        assertEquals(mMagnificationConnectionManager.pointersInWindow(TEST_DISPLAY, event), 1);
    }

    @Test
    public void onPerformScaleAction_magnifierEnabled_notifyAction() throws RemoteException {
        final float newScale = 4.0f;
        final boolean updatePersistence = true;
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);

        mMockConnection.getConnectionCallback().onPerformScaleAction(
                TEST_DISPLAY, newScale, updatePersistence);

        verify(mMockCallback).onPerformScaleAction(
                eq(TEST_DISPLAY), eq(newScale), eq(updatePersistence));
    }

    @Test
    public void onAccessibilityActionPerformed_magnifierEnabled_notifyAction()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);

        mMockConnection.getConnectionCallback().onAccessibilityActionPerformed(TEST_DISPLAY);

        verify(mMockCallback).onAccessibilityActionPerformed(eq(TEST_DISPLAY));
    }

    @Test
    public void binderDied_windowMagnifierIsEnabled_resetState() throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f, NaN, NaN);

        mMockConnection.getDeathRecipient().binderDied();

        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void
            requestConnectionToNull_disableAllMagnifiersAndRequestWindowMagnificationConnection()
            throws RemoteException {
        assertTrue(mMagnificationConnectionManager.requestConnection(true));
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f, NaN, NaN);

        assertTrue(mMagnificationConnectionManager.requestConnection(false));

        verify(mMockConnection.getConnection()).disableWindowMagnification(TEST_DISPLAY, null);
        verify(mMockStatusBarManagerInternal).requestMagnificationConnection(false);
    }

    @Test
    public void requestConnection_requestWindowMagnificationConnection() throws RemoteException {
        assertTrue(mMagnificationConnectionManager.requestConnection(true));
        verify(mMockStatusBarManagerInternal).requestMagnificationConnection(true);
    }

    @Test
    public void isConnected_requestConnection_expectedValue() throws Exception {
        mMagnificationConnectionManager.requestConnection(true);
        TestUtils.waitUntil("connection is not ready", WAIT_CONNECTION_TIMEOUT_SECOND,
                () -> mMagnificationConnectionManager.isConnected());

        mMagnificationConnectionManager.requestConnection(false);
        assertFalse(mMagnificationConnectionManager.isConnected());
    }

    @Test
    public void requestConnection_registerAndUnregisterBroadcastReceiver() {
        assertTrue(mMagnificationConnectionManager.requestConnection(true));
        verify(mContext).registerReceiver(any(BroadcastReceiver.class), any(IntentFilter.class));

        assertTrue(mMagnificationConnectionManager.requestConnection(false));
        verify(mContext).unregisterReceiver(any(BroadcastReceiver.class));
    }

    @Test
    public void requestConnectionToNull_expectedGetterResults() {
        mMagnificationConnectionManager.requestConnection(true);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f, 1, 1);

        mMagnificationConnectionManager.requestConnection(false);

        assertEquals(1f, mMagnificationConnectionManager.getScale(TEST_DISPLAY), 0);
        assertTrue(Float.isNaN(mMagnificationConnectionManager.getCenterX(TEST_DISPLAY)));
        assertTrue(Float.isNaN(mMagnificationConnectionManager.getCenterY(TEST_DISPLAY)));
        final Region bounds = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, bounds);
        assertTrue(bounds.isEmpty());
    }

    @Test
    public void enableWindowMagnification_connecting_invokeConnectionMethodAfterConnected()
            throws RemoteException {
        stubSetConnection(true);
        mMagnificationConnectionManager.requestConnection(true);

        assertTrue(mMagnificationConnectionManager.enableWindowMagnification(
                TEST_DISPLAY, 3f, 1, 1));

        // Invoke enableWindowMagnification if the connection is connected.
        verify(mMockConnection.getConnection()).enableWindowMagnification(
                eq(TEST_DISPLAY), eq(3f),
                eq(1f), eq(1f), eq(0f), eq(0f), notNull());
    }

    @Test
    public void resetAllMagnification_enabledBySameId_windowMagnifiersDisabled() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, null,
                MagnificationConnectionManager.WINDOW_POSITION_AT_CENTER, SERVICE_ID);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY_2, 3f,
                100f, 200f, null,
                MagnificationConnectionManager.WINDOW_POSITION_AT_CENTER, SERVICE_ID);
        assertTrue(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        assertTrue(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY_2));

        mMagnificationConnectionManager.resetAllIfNeeded(SERVICE_ID);

        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY_2));
    }

    @Test
    public void resetAllMagnification_enabledByDifferentId_windowMagnifierDisabled() {
        final int serviceId2 = SERVICE_ID + 1;
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, null,
                MagnificationConnectionManager.WINDOW_POSITION_AT_CENTER, SERVICE_ID);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY_2, 3f,
                100f, 200f, null,
                MagnificationConnectionManager.WINDOW_POSITION_AT_CENTER, serviceId2);
        assertTrue(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        assertTrue(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY_2));

        mMagnificationConnectionManager.resetAllIfNeeded(SERVICE_ID);

        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        assertTrue(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY_2));
    }

    @Test
    public void onScreenOff_windowMagnifierIsEnabled_removeButtonAndDisableWindowMagnification()
            throws RemoteException {
        mMagnificationConnectionManager.requestConnection(true);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2.5f, NaN, NaN);

        mMagnificationConnectionManager.mScreenStateReceiver.onReceive(mContext,
                new Intent(Intent.ACTION_SCREEN_OFF));

        verify(mMockConnection.getConnection()).removeMagnificationButton(TEST_DISPLAY);
        verify(mMockConnection.getConnection()).disableWindowMagnification(TEST_DISPLAY, null);
        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void centerGetter_enabledOnTestDisplay_expectedValues() {
        mMagnificationConnectionManager.requestConnection(true);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f, 100f, 200f);

        assertEquals(mMagnificationConnectionManager.getCenterX(TEST_DISPLAY), 100f);
        assertEquals(mMagnificationConnectionManager.getCenterY(TEST_DISPLAY), 200f);
    }

    @Test
    public void centerGetter_enabledOnTestDisplayWindowAtCenter_expectedValues()
            throws RemoteException {
        mMagnificationConnectionManager.requestConnection(true);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, MagnificationConnectionManager.WINDOW_POSITION_AT_CENTER);

        assertEquals(mMagnificationConnectionManager.getCenterX(TEST_DISPLAY), 100f);
        assertEquals(mMagnificationConnectionManager.getCenterY(TEST_DISPLAY), 200f);

        verify(mMockConnection.getConnection()).enableWindowMagnification(eq(TEST_DISPLAY), eq(3f),
                eq(100f), eq(200f), eq(0f), eq(0f), notNull());
    }

    @Test
    public void centerGetter_enabledOnTestDisplayWindowAtLeftTop_expectedValues()
            throws RemoteException {
        mMagnificationConnectionManager.requestConnection(true);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, MagnificationConnectionManager.WINDOW_POSITION_AT_TOP_LEFT);

        assertEquals(mMagnificationConnectionManager.getCenterX(TEST_DISPLAY), 100f);
        assertEquals(mMagnificationConnectionManager.getCenterY(TEST_DISPLAY), 200f);

        verify(mMockConnection.getConnection()).enableWindowMagnification(eq(TEST_DISPLAY), eq(3f),
                eq(100f), eq(200f), eq(-1f), eq(-1f), notNull());
    }

    @Test
    public void magnifierGetters_disabled_expectedValues() {
        mMagnificationConnectionManager.requestConnection(true);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f,
                100f, 200f, MagnificationConnectionManager.WINDOW_POSITION_AT_CENTER);

        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, false);

        assertEquals(1f, mMagnificationConnectionManager.getScale(TEST_DISPLAY), 0);
        assertTrue(Float.isNaN(mMagnificationConnectionManager.getCenterX(TEST_DISPLAY)));
        assertTrue(Float.isNaN(mMagnificationConnectionManager.getCenterY(TEST_DISPLAY)));
        final Region bounds = new Region();
        mMagnificationConnectionManager.getMagnificationSourceBounds(TEST_DISPLAY, bounds);
        assertTrue(bounds.isEmpty());
    }

    @Test
    public void onDisplayRemoved_enabledOnTestDisplay_disabled() {
        mMagnificationConnectionManager.requestConnection(true);
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3f, 100f, 200f);

        mMagnificationConnectionManager.onDisplayRemoved(TEST_DISPLAY);

        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void onWindowMagnificationActivationState_magnifierEnabled_notifyActivatedState() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);

        verify(mMockCallback).onWindowMagnificationActivationState(TEST_DISPLAY, true);
    }

    @Test
    public void onWindowMagnificationActivationState_magnifierDisabled_notifyDeactivatedState() {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 3.0f, NaN, NaN);
        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mMockCallback).onWindowMagnificationActivationState(TEST_DISPLAY, false);

        Mockito.reset(mMockCallback);
        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mMockCallback, never()).onWindowMagnificationActivationState(eq(TEST_DISPLAY),
                anyBoolean());
    }

    @Test
    public void onFullscreenMagnificationActivationChanged_hasConnection_notifyActivatedState()
            throws RemoteException {
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        mMagnificationConnectionManager
                .onFullscreenMagnificationActivationChanged(TEST_DISPLAY, /* activated= */ true);

        verify(mMockConnection.getConnection())
                .onFullscreenMagnificationActivationChanged(eq(TEST_DISPLAY), eq(true));
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
