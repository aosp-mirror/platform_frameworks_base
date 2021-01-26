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

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;

import static com.android.server.accessibility.AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.Display;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.MagnificationAnimationCallback;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.accessibility.AccessibilityManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for MagnificationController.
 */
@RunWith(AndroidJUnit4.class)
public class MagnificationControllerTest {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;
    private static final Region MAGNIFICATION_REGION = new Region(0, 0, 500, 600);
    private static final float MAGNIFIED_CENTER_X = 100;
    private static final float MAGNIFIED_CENTER_Y = 200;
    private static final float DEFAULT_SCALE = 3f;
    private static final int CURRENT_USER_ID = UserHandle.USER_CURRENT;
    private static final int MODE_WINDOW = Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
    private static final int MODE_FULLSCREEN =
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;

    @Mock
    private AccessibilityManagerService mService;
    @Mock
    private MagnificationController.TransitionCallBack mTransitionCallBack;
    @Mock
    private Context mContext;
    @Mock
    private FullScreenMagnificationController mScreenMagnificationController;
    @Captor
    private ArgumentCaptor<MagnificationAnimationCallback> mCallbackArgumentCaptor;

    private MockWindowMagnificationConnection mMockConnection;
    private WindowMagnificationManager mWindowMagnificationManager;
    private MockContentResolver mMockResolver;
    private MagnificationController mMagnificationController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        FakeSettingsProvider.clearSettingsProvider();
        mMockResolver = new MockContentResolver();
        mMockResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mMockResolver);
        Settings.Secure.putFloatForUser(mMockResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, DEFAULT_SCALE,
                CURRENT_USER_ID);
        mWindowMagnificationManager = Mockito.spy(
                new WindowMagnificationManager(mContext, CURRENT_USER_ID,
                        mock(WindowMagnificationManager.Callback.class)));
        mMockConnection = new MockWindowMagnificationConnection(true);
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mMagnificationController = spy(new MagnificationController(mService, new Object(), mContext,
                mScreenMagnificationController, mWindowMagnificationManager));
        mMagnificationController.setMagnificationCapabilities(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
    }

    @After
    public void tearDown() {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Test
    public void transitionToWindowMode_notMagnifying_doNothing() throws RemoteException {
        setMagnificationModeSettings(MODE_FULLSCREEN);
        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW,
                mTransitionCallBack);

        verify(mTransitionCallBack).onResult(true);
        verify(mScreenMagnificationController, never()).reset(anyInt(),
                any(MagnificationAnimationCallback.class));
        verify(mMockConnection.getConnection(), never()).enableWindowMagnification(anyInt(),
                anyFloat(), anyFloat(), anyFloat(),
                nullable(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void transitionToWindowMode_fullScreenMagnifying_disableFullScreenAndEnableWindow()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW,
                mTransitionCallBack);

        verify(mScreenMagnificationController).reset(eq(TEST_DISPLAY),
                mCallbackArgumentCaptor.capture());
        mCallbackArgumentCaptor.getValue().onResult(true);
        mMockConnection.invokeCallbacks();
        verify(mTransitionCallBack).onResult(true);
        assertEquals(MAGNIFIED_CENTER_X, mWindowMagnificationManager.getCenterX(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_Y, mWindowMagnificationManager.getCenterY(TEST_DISPLAY), 0);
    }

    @Test
    public void transitionToWindowMode_disablingWindowMode_enablingWindowWithFormerCenter()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN,
                mTransitionCallBack);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW,
                mTransitionCallBack);

        mMockConnection.invokeCallbacks();
        verify(mTransitionCallBack).onResult(true);
        assertEquals(MAGNIFIED_CENTER_X, mWindowMagnificationManager.getCenterX(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_Y, mWindowMagnificationManager.getCenterY(TEST_DISPLAY), 0);
    }

    @Test
    public void transitionToWindowMode_disablingWindowMode_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN,
                mTransitionCallBack);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW,
                mTransitionCallBack);

        mMockConnection.invokeCallbacks();
        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void transitionToFullScreenMode_windowMagnifying_disableWindowAndEnableFullScreen()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN,
                mTransitionCallBack);
        mMockConnection.invokeCallbacks();

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        verify(mScreenMagnificationController).setScaleAndCenter(TEST_DISPLAY,
                DEFAULT_SCALE, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y,
                true, MAGNIFICATION_GESTURE_HANDLER_ID);
        verify(mTransitionCallBack).onResult(true);
    }

    @Test
    public void transitionToFullScreen_centerNotInTheBounds_magnifyTheCenterOfMagnificationBounds()
            throws RemoteException {
        final Rect magnificationBounds = MAGNIFICATION_REGION.getBounds();
        final PointF magnifiedCenter = new PointF(magnificationBounds.right + 100,
                magnificationBounds.bottom + 100);
        setMagnificationEnabled(MODE_WINDOW, magnifiedCenter.x, magnifiedCenter.y);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN,
                mTransitionCallBack);
        mMockConnection.invokeCallbacks();

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        verify(mScreenMagnificationController).setScaleAndCenter(TEST_DISPLAY, DEFAULT_SCALE,
                magnificationBounds.exactCenterX(), magnificationBounds.exactCenterY(), true,
                MAGNIFICATION_GESTURE_HANDLER_ID);
        verify(mTransitionCallBack).onResult(true);
    }

    @Test
    public void transitionToFullScreenMode_disablingFullScreen_enableFullScreenWithFormerCenter()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW,
                mTransitionCallBack);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN,
                mTransitionCallBack);

        verify(mScreenMagnificationController).setScaleAndCenter(TEST_DISPLAY,
                DEFAULT_SCALE, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y,
                true, MAGNIFICATION_GESTURE_HANDLER_ID);
        verify(mTransitionCallBack).onResult(true);
    }

    @Test
    public void interruptDuringTransitionToFullScreenMode_windowMagnifying_notifyTransitionFailed()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN,
                mTransitionCallBack);

        // Enable window magnification while animating.
        mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, DEFAULT_SCALE,
                Float.NaN, Float.NaN, null);
        mMockConnection.invokeCallbacks();

        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        verify(mScreenMagnificationController, never()).setScaleAndCenter(TEST_DISPLAY,
                DEFAULT_SCALE, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y,
                true, MAGNIFICATION_GESTURE_HANDLER_ID);
        verify(mTransitionCallBack).onResult(false);
    }

    @Test
    public void onDisplayRemoved_notifyAllModules() {
        mMagnificationController.onDisplayRemoved(TEST_DISPLAY);

        verify(mScreenMagnificationController).onDisplayRemoved(TEST_DISPLAY);
        verify(mWindowMagnificationManager).onDisplayRemoved(TEST_DISPLAY);
    }

    @Test
    public void updateUserIdIfNeeded_AllModulesAvailable_setUserId() {
        mMagnificationController.updateUserIdIfNeeded(CURRENT_USER_ID);

        verify(mScreenMagnificationController).setUserId(CURRENT_USER_ID);
        verify(mWindowMagnificationManager).setUserId(CURRENT_USER_ID);
    }

    @Test
    public void onMagnificationRequest_windowMagnifying_disableWindow() throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onRequestMagnificationSpec(TEST_DISPLAY, 1);
        mMockConnection.invokeCallbacks();

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void onPerformScaleAction_magnifierEnabled_handleScaleChange() throws RemoteException {
        final float newScale = 4.0f;
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, newScale);

        verify(mWindowMagnificationManager).setScale(eq(TEST_DISPLAY), eq(newScale));
        verify(mWindowMagnificationManager).persistScale(eq(TEST_DISPLAY));
    }

    @Test
    public void onWindowMagnificationActivationState_windowActivated_logWindowDuration() {
        mMagnificationController.onWindowMagnificationActivationState(true);

        mMagnificationController.onWindowMagnificationActivationState(false);

        verify(mMagnificationController).logMagnificationUsageState(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW), anyLong());
    }

    @Test
    public void
            onFullScreenMagnificationActivationState_fullScreenActivated_logFullScreenDuration() {
        mMagnificationController.onFullScreenMagnificationActivationState(true);

        mMagnificationController.onFullScreenMagnificationActivationState(false);

        verify(mMagnificationController).logMagnificationUsageState(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN), anyLong());
    }

    @Test
    public void onTouchInteractionStart_fullScreenAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_FULLSCREEN);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void onTouchInteractionEnd_fullScreenAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_FULLSCREEN);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void onTouchInteractionStart_windowModeAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_WINDOW);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void onTouchInteractionEnd_windowModeAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_WINDOW);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void onTouchInteractionChanged_notCapabilitiesAll_notShowMagnificationButton()
            throws RemoteException {
        mMagnificationController.setMagnificationCapabilities(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_FULLSCREEN);
        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_FULLSCREEN);

        verify(mWindowMagnificationManager, never()).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }


    @Test
    public void onTouchInteractionChanged_fullscreenNotActivated_notShowMagnificationButton()
            throws RemoteException {
        setMagnificationModeSettings(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_FULLSCREEN);
        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_FULLSCREEN);

        verify(mWindowMagnificationManager, never()).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void onShortcutTriggered_windowModeEnabledAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onShortcutTriggered(TEST_DISPLAY, MODE_WINDOW);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void onShortcutTriggered_fullscreenEnabledAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onShortcutTriggered(TEST_DISPLAY, MODE_FULLSCREEN);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void onShortcutTriggered_windowModeDisabled_removeMagnificationButton()
            throws RemoteException {

        mMagnificationController.onShortcutTriggered(TEST_DISPLAY, MODE_WINDOW);

        verify(mWindowMagnificationManager).removeMagnificationButton(eq(TEST_DISPLAY));
    }

    @Test
    public void onTripleTap_windowModeEnabledAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onTripleTapped(TEST_DISPLAY, MODE_WINDOW);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void onTripleTap_fullscreenEnabledAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTripleTapped(TEST_DISPLAY, MODE_FULLSCREEN);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void onTripleTap_windowModeDisabled_removeMagnificationButton()
            throws RemoteException {

        mMagnificationController.onTripleTapped(TEST_DISPLAY, MODE_WINDOW);

        verify(mWindowMagnificationManager).removeMagnificationButton(eq(TEST_DISPLAY));
    }

    @Test
    public void transitionToFullScreenMode_fullscreenModeActivated_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN, mTransitionCallBack);
        mMockConnection.invokeCallbacks();

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void transitionToWindow_windowModeActivated_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW, mTransitionCallBack);

        verify(mScreenMagnificationController).reset(eq(TEST_DISPLAY),
                mCallbackArgumentCaptor.capture());
        mCallbackArgumentCaptor.getValue().onResult(true);
        mMockConnection.invokeCallbacks();
        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void imeWindowStateShown_windowMagnifying_logWindowMode() {
        mMagnificationController.onWindowMagnificationActivationState(true);

        mMagnificationController.onImeWindowVisibilityChanged(true);

        verify(mMagnificationController).logMagnificationModeWithIme(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
    }

    @Test
    public void imeWindowStateShown_fullScreenMagnifying_logFullScreenMode() {
        mMagnificationController.onFullScreenMagnificationActivationState(true);

        mMagnificationController.onImeWindowVisibilityChanged(true);

        verify(mMagnificationController).logMagnificationModeWithIme(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN));
    }

    @Test
    public void imeWindowStateShown_noMagnifying_noLogAnyMode() {
        mMagnificationController.onImeWindowVisibilityChanged(true);

        verify(mMagnificationController, never()).logMagnificationModeWithIme(anyInt());
    }

    @Test
    public void imeWindowStateHidden_windowMagnifying_noLogAnyMode() {
        mMagnificationController.onFullScreenMagnificationActivationState(true);

        verify(mMagnificationController, never()).logMagnificationModeWithIme(anyInt());
    }

    @Test
    public void imeWindowStateHidden_fullScreenMagnifying_noLogAnyMode() {
        mMagnificationController.onWindowMagnificationActivationState(true);

        verify(mMagnificationController, never()).logMagnificationModeWithIme(anyInt());
    }

    private void setMagnificationEnabled(int mode) throws RemoteException {

        setMagnificationEnabled(mode, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
    }

    private void setMagnificationEnabled(int mode, float centerX, float centerY)
            throws RemoteException {
        setMagnificationModeSettings(mode);
        Mockito.reset(mScreenMagnificationController);
        doAnswer(invocation -> {
            final Region outRegion = invocation.getArgument(1);
            outRegion.set(MAGNIFICATION_REGION);
            return null;
        }).when(mScreenMagnificationController).getMagnificationRegion(anyInt(), any(Region.class));

        final boolean windowMagnifying = mWindowMagnificationManager.isWindowMagnifierEnabled(
                TEST_DISPLAY);
        if (windowMagnifying) {
            mWindowMagnificationManager.disableWindowMagnification(TEST_DISPLAY, false);
            mMockConnection.invokeCallbacks();
        }
        if (mode == MODE_FULLSCREEN) {
            when(mScreenMagnificationController.isMagnifying(TEST_DISPLAY)).thenReturn(true);
            when(mScreenMagnificationController.isForceShowMagnifiableBounds(
                    TEST_DISPLAY)).thenReturn(true);
            when(mScreenMagnificationController.getPersistedScale()).thenReturn(DEFAULT_SCALE);
            when(mScreenMagnificationController.getScale(TEST_DISPLAY)).thenReturn(DEFAULT_SCALE);
            when(mScreenMagnificationController.getCenterX(TEST_DISPLAY)).thenReturn(
                    centerX);
            when(mScreenMagnificationController.getCenterY(TEST_DISPLAY)).thenReturn(
                    centerY);
        } else {
            doAnswer(invocation -> {
                when(mScreenMagnificationController.isMagnifying(TEST_DISPLAY)).thenReturn(true);
                return null;
            }).when(mScreenMagnificationController).setScaleAndCenter(eq(TEST_DISPLAY),
                    eq(DEFAULT_SCALE), anyFloat(), anyFloat(), anyBoolean(), anyInt());
            mWindowMagnificationManager.enableWindowMagnification(TEST_DISPLAY, DEFAULT_SCALE,
                    centerX, centerY, null);
            mMockConnection.invokeCallbacks();
        }
    }

    private void setMagnificationModeSettings(int mode) {
        Settings.Secure.putIntForUser(mMockResolver,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, mode, CURRENT_USER_ID);
    }
}
