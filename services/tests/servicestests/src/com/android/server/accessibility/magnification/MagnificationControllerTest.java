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
import static com.android.server.wm.WindowManagerInternal.AccessibilityControllerInternal.UiChangesForAccessibilityCallbacks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.MagnificationConfig;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.testing.DexmakerShareClassLoaderRule;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.MagnificationAnimationCallback;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

/**
 * Tests for MagnificationController.
 */
@RunWith(AndroidJUnit4.class)
public class MagnificationControllerTest {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;
    private static final int TEST_SERVICE_ID = 1;
    private static final Region INITIAL_SCREEN_MAGNIFICATION_REGION =
            new Region(0, 0, 500, 600);
    private static final Rect TEST_RECT = new Rect(0, 50, 100, 51);
    private static final float MAGNIFIED_CENTER_X = 100;
    private static final float MAGNIFIED_CENTER_Y = 200;
    private static final float DEFAULT_SCALE = 3f;
    private static final int CURRENT_USER_ID = UserHandle.USER_SYSTEM;
    private static final int SECOND_USER_ID = CURRENT_USER_ID + 1;
    private static final int MODE_WINDOW = Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
    private static final int MODE_FULLSCREEN =
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;

    @Mock
    private AccessibilityTraceManager mTraceManager;
    @Mock
    private AccessibilityManagerService mService;
    @Mock
    private MagnificationController.TransitionCallBack mTransitionCallBack;
    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;

    @Mock
    private FullScreenMagnificationController.ControllerContext mControllerCtx;
    @Mock
    private ValueAnimator mValueAnimator;
    @Mock
    private MessageCapturingHandler mMessageCapturingHandler;

    private FullScreenMagnificationController mScreenMagnificationController;
    private final FullScreenMagnificationCtrInfoChangedCallbackDelegate
            mScreenMagnificationInfoChangedCallbackDelegate =
            new FullScreenMagnificationCtrInfoChangedCallbackDelegate();

    private MagnificationScaleProvider mScaleProvider;
    @Captor
    private ArgumentCaptor<MagnificationAnimationCallback> mCallbackArgumentCaptor;

    private MockWindowMagnificationConnection mMockConnection;
    private WindowMagnificationManager mWindowMagnificationManager;
    private MockContentResolver mMockResolver;
    private MagnificationController mMagnificationController;
    private final WindowMagnificationMgrCallbackDelegate
            mWindowMagnificationCallbackDelegate =
            new WindowMagnificationMgrCallbackDelegate();

    @Mock
    private WindowManagerInternal mWindowManagerInternal;
    @Mock
    private WindowManagerInternal.AccessibilityControllerInternal mA11yController;

    @Mock
    private DisplayManagerInternal mDisplayManagerInternal;

    // To mock package-private class
    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        FakeSettingsProvider.clearSettingsProvider();
        final Object globalLock = new Object();

        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManagerInternal);
        when(mWindowManagerInternal.getAccessibilityController()).thenReturn(
                mA11yController);
        when(mWindowManagerInternal.setMagnificationCallbacks(eq(TEST_DISPLAY), any()))
                .thenReturn(true);
        doAnswer((Answer<Void>) invocationOnMock -> {
            Object[] args = invocationOnMock.getArguments();
            Region regionArg = (Region) args[1];
            regionArg.set(INITIAL_SCREEN_MAGNIFICATION_REGION);
            return null;
        }).when(mWindowManagerInternal).getMagnificationRegion(anyInt(), any(Region.class));

        mMockResolver = new MockContentResolver();
        mMockResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        Looper looper = InstrumentationRegistry.getContext().getMainLooper();
        // Pretending ID of the Thread associated with looper as main thread ID in controller
        when(mContext.getMainLooper()).thenReturn(looper);
        when(mContext.getContentResolver()).thenReturn(mMockResolver);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        Settings.Secure.putFloatForUser(mMockResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, DEFAULT_SCALE,
                CURRENT_USER_ID);
        mScaleProvider = spy(new MagnificationScaleProvider(mContext));

        when(mControllerCtx.getContext()).thenReturn(mContext);
        when(mControllerCtx.getTraceManager()).thenReturn(mTraceManager);
        when(mControllerCtx.getWindowManager()).thenReturn(mWindowManagerInternal);
        when(mControllerCtx.getHandler()).thenReturn(mMessageCapturingHandler);
        when(mControllerCtx.getAnimationDuration()).thenReturn(1000L);
        when(mControllerCtx.newValueAnimator()).thenReturn(mValueAnimator);

        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalDensityDpi = 300;
        doReturn(displayInfo).when(mDisplayManagerInternal).getDisplayInfo(anyInt());
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternal);

        mScreenMagnificationController = spy(new FullScreenMagnificationController(
                mControllerCtx, new Object(),
                mScreenMagnificationInfoChangedCallbackDelegate, mScaleProvider));
        mScreenMagnificationController.register(TEST_DISPLAY);

        mWindowMagnificationManager = spy(new WindowMagnificationManager(mContext, globalLock,
                mWindowMagnificationCallbackDelegate, mTraceManager, mScaleProvider));
        mMockConnection = new MockWindowMagnificationConnection(true);
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());

        mMagnificationController = new MagnificationController(mService, globalLock, mContext,
                mScreenMagnificationController, mWindowMagnificationManager, mScaleProvider);
        mMagnificationController.setMagnificationCapabilities(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);

        mScreenMagnificationInfoChangedCallbackDelegate.setDelegate(mMagnificationController);
        mWindowMagnificationCallbackDelegate.setDelegate(mMagnificationController);
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

        verify(mTransitionCallBack).onResult(TEST_DISPLAY, true);
        verify(mScreenMagnificationController, never()).reset(anyInt(),
                any(MagnificationAnimationCallback.class));
        verify(mMockConnection.getConnection(), never()).enableWindowMagnification(anyInt(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(),
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
        verify(mTransitionCallBack).onResult(TEST_DISPLAY, true);
        assertEquals(MAGNIFIED_CENTER_X, mWindowMagnificationManager.getCenterX(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_Y, mWindowMagnificationManager.getCenterY(TEST_DISPLAY), 0);
    }

    @Test
    public void transitionToWindowModeFailedByReset_fullScreenMagnifying_notifyTransitionFailed()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW,
                mTransitionCallBack);

        verify(mScreenMagnificationController).reset(eq(TEST_DISPLAY),
                mCallbackArgumentCaptor.capture());
        // The transition is interrupted and failed by calling reset.
        mCallbackArgumentCaptor.getValue().onResult(false);
        verify(mTransitionCallBack).onResult(TEST_DISPLAY, false);
        final ArgumentCaptor<MagnificationConfig> configCaptor = ArgumentCaptor.forClass(
                MagnificationConfig.class);
        // The first time is for notifying full-screen enabled.
        // The second time is for notifying the target mode transitions failed.
        verify(mService, times(2)).notifyMagnificationChanged(eq(TEST_DISPLAY), any(Region.class),
                configCaptor.capture());
        final MagnificationConfig actualConfig = configCaptor.getValue();
        assertEquals(MODE_FULLSCREEN, actualConfig.getMode(), 0);
        assertEquals(1.0f, actualConfig.getScale(), 0);
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
        verify(mTransitionCallBack).onResult(TEST_DISPLAY, true);
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

        // The first time is triggered when window mode is activated.
        // The second time is triggered when activating the window mode again.
        // The third time is triggered when the transition is completed.
        verify(mWindowMagnificationManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
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
        verify(mTransitionCallBack).onResult(TEST_DISPLAY, true);
    }

    @Test
    public void transitionToFullScreen_centerNotInTheBounds_magnifyBoundsCenter()
            throws RemoteException {
        final Rect magnificationBounds = INITIAL_SCREEN_MAGNIFICATION_REGION.getBounds();
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
        verify(mTransitionCallBack).onResult(TEST_DISPLAY, true);
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

        assertEquals(DEFAULT_SCALE, mScreenMagnificationController.getScale(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_X, mScreenMagnificationController.getCenterX(TEST_DISPLAY),
                0);
        assertEquals(MAGNIFIED_CENTER_Y, mScreenMagnificationController.getCenterY(TEST_DISPLAY),
                0);
        verify(mTransitionCallBack).onResult(TEST_DISPLAY, true);
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
                Float.NaN, Float.NaN, null, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        assertTrue(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        verify(mScreenMagnificationController, never()).setScaleAndCenter(TEST_DISPLAY,
                DEFAULT_SCALE, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y,
                true, MAGNIFICATION_GESTURE_HANDLER_ID);
        verify(mTransitionCallBack).onResult(TEST_DISPLAY, false);
    }

    @Test
    public void configTransitionToWindowMode_fullScreenMagnifying_disableFullScreenAndEnableWindow()
            throws RemoteException {
        activateMagnifier(MODE_FULLSCREEN, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);

        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                obtainMagnificationConfig(MODE_WINDOW),
                false, TEST_SERVICE_ID);

        verify(mScreenMagnificationController).reset(eq(TEST_DISPLAY), eq(false));
        mMockConnection.invokeCallbacks();
        assertEquals(MAGNIFIED_CENTER_X, mWindowMagnificationManager.getCenterX(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_Y, mWindowMagnificationManager.getCenterY(TEST_DISPLAY), 0);
    }

    @Test
    public void configTransitionToFullScreen_windowMagnifying_disableWindowAndEnableFullScreen()
            throws RemoteException {
        final boolean animate = true;
        activateMagnifier(MODE_WINDOW, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                obtainMagnificationConfig(MODE_FULLSCREEN),
                animate, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        verify(mScreenMagnificationController).setScaleAndCenter(TEST_DISPLAY,
                DEFAULT_SCALE, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y,
                animate, TEST_SERVICE_ID);
    }

    @Test
    public void configTransitionToFullScreen_userSettingsDisablingFullScreen_enableFullScreen()
            throws RemoteException {
        activateMagnifier(MODE_FULLSCREEN, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
        // User-setting mode
        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW, mTransitionCallBack);

        // Config-setting mode
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                obtainMagnificationConfig(MODE_FULLSCREEN),
                true, TEST_SERVICE_ID);

        assertEquals(DEFAULT_SCALE, mScreenMagnificationController.getScale(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_X, mScreenMagnificationController.getCenterX(TEST_DISPLAY),
                0);
        assertEquals(MAGNIFIED_CENTER_Y, mScreenMagnificationController.getCenterY(TEST_DISPLAY),
                0);
    }

    @Test
    public void interruptDuringTransitionToWindow_disablingFullScreen_discardPreviousTransition()
            throws RemoteException {
        activateMagnifier(MODE_FULLSCREEN, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
        // User-setting mode
        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW, mTransitionCallBack);

        // Config-setting mode
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                obtainMagnificationConfig(MODE_FULLSCREEN),
                true, TEST_SERVICE_ID);

        verify(mTransitionCallBack, never()).onResult(TEST_DISPLAY, true);
    }

    @Test
    public void onDisplayRemoved_notifyAllModules() {
        mMagnificationController.onDisplayRemoved(TEST_DISPLAY);

        verify(mScreenMagnificationController).onDisplayRemoved(TEST_DISPLAY);
        verify(mWindowMagnificationManager).onDisplayRemoved(TEST_DISPLAY);
        verify(mScaleProvider).onDisplayRemoved(TEST_DISPLAY);
    }

    @Test
    public void updateUserIdIfNeeded_AllModulesAvailable_disableMagnificationAndChangeUserId() {
        mMagnificationController.updateUserIdIfNeeded(SECOND_USER_ID);

        verify(mScreenMagnificationController).resetAllIfNeeded(false);
        verify(mWindowMagnificationManager).disableAllWindowMagnifiers();
        verify(mScaleProvider).onUserChanged(SECOND_USER_ID);
    }

    @Test
    public void onMagnificationRequest_windowMagnifying_disableWindow() throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onRequestMagnificationSpec(TEST_DISPLAY, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        assertFalse(mWindowMagnificationManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void magnifyThroughExternalRequest_showMagnificationButton() {
        mScreenMagnificationController.setScaleAndCenter(TEST_DISPLAY, DEFAULT_SCALE,
                MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y, false, TEST_SERVICE_ID);

        // The first time is trigger when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        verify(mWindowMagnificationManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
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
    public void enableWindowMode_notifyMagnificationChanged() throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        final ArgumentCaptor<MagnificationConfig> configCaptor = ArgumentCaptor.forClass(
                MagnificationConfig.class);
        final ArgumentCaptor<Region> regionCaptor = ArgumentCaptor.forClass(
                Region.class);
        verify(mService).notifyMagnificationChanged(eq(TEST_DISPLAY), regionCaptor.capture(),
                configCaptor.capture());

        final Rect actualRect = regionCaptor.getValue().getBounds();
        final MagnificationConfig actualConfig = configCaptor.getValue();
        assertEquals(actualRect.exactCenterX(), actualConfig.getCenterX(), 0);
        assertEquals(actualRect.exactCenterY(), actualConfig.getCenterY(), 0);
        assertEquals(DEFAULT_SCALE, actualConfig.getScale(), 0);
    }

    @Test
    public void onFullScreenMagnificationChanged_fullScreenEnabled_notifyMagnificationChanged()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        final MagnificationConfig config = obtainMagnificationConfig(MODE_FULLSCREEN);
        mScreenMagnificationController.setScaleAndCenter(TEST_DISPLAY,
                config.getScale(), config.getCenterX(), config.getCenterY(),
                true, TEST_SERVICE_ID);

        // The notify method is triggered when setting magnification enabled.
        // The setScaleAndCenter call should not trigger notify method due to same scale and center.
        final ArgumentCaptor<MagnificationConfig> configCaptor = ArgumentCaptor.forClass(
                MagnificationConfig.class);
        verify(mService).notifyMagnificationChanged(eq(TEST_DISPLAY),
                eq(INITIAL_SCREEN_MAGNIFICATION_REGION),
                configCaptor.capture());
        final MagnificationConfig actualConfig = configCaptor.getValue();
        assertEquals(config.getCenterX(), actualConfig.getCenterX(), 0);
        assertEquals(config.getCenterY(), actualConfig.getCenterY(), 0);
        assertEquals(config.getScale(), actualConfig.getScale(), 0);
    }

    @Test
    public void transitionMagnificationMode_windowEnabled_notifyTargetMagnificationChanged()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN, mTransitionCallBack);
        mMockConnection.invokeCallbacks();

        final ArgumentCaptor<MagnificationConfig> configCaptor = ArgumentCaptor.forClass(
                MagnificationConfig.class);
        // The first time is for notifying window enabled.
        // The second time is for notifying the target mode transitions.
        verify(mService, times(2)).notifyMagnificationChanged(eq(TEST_DISPLAY), any(Region.class),
                configCaptor.capture());
        final MagnificationConfig actualConfig = configCaptor.getValue();
        assertEquals(MODE_FULLSCREEN, actualConfig.getMode(), 0);
    }

    @Test
    public void transitionConfigMode_windowEnabled_notifyTargetMagnificationChanged()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        final MagnificationConfig config = obtainMagnificationConfig(MODE_FULLSCREEN);
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                config, true, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        final ArgumentCaptor<MagnificationConfig> configCaptor = ArgumentCaptor.forClass(
                MagnificationConfig.class);
        // The first time is for notifying window enabled.
        // The second time is for notifying the target mode transitions.
        verify(mService, times(2)).notifyMagnificationChanged(eq(TEST_DISPLAY), any(Region.class),
                configCaptor.capture());
        final MagnificationConfig actualConfig = configCaptor.getValue();
        assertEquals(config.getCenterX(), actualConfig.getCenterX(), 0);
        assertEquals(config.getCenterY(), actualConfig.getCenterY(), 0);
        assertEquals(config.getScale(), actualConfig.getScale(), 0);
    }

    @Test
    public void transitionMagnificationMode_fullScreenEnabled_notifyTargetMagnificationChanged()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW, mTransitionCallBack);
        verify(mScreenMagnificationController).reset(eq(TEST_DISPLAY),
                mCallbackArgumentCaptor.capture());
        mCallbackArgumentCaptor.getValue().onResult(true);
        mMockConnection.invokeCallbacks();

        final ArgumentCaptor<MagnificationConfig> configCaptor = ArgumentCaptor.forClass(
                MagnificationConfig.class);
        // The first time is for notifying full-screen enabled.
        // The second time is for notifying the target mode transitions.
        verify(mService, times(2)).notifyMagnificationChanged(eq(TEST_DISPLAY), any(Region.class),
                configCaptor.capture());
        final MagnificationConfig actualConfig = configCaptor.getValue();
        assertEquals(MODE_WINDOW, actualConfig.getMode(), 0);
    }

    @Test
    public void transitionConfigMode_fullScreenEnabled_notifyTargetMagnificationChanged()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        final MagnificationConfig config = obtainMagnificationConfig(MODE_WINDOW);
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                config, true, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        final ArgumentCaptor<MagnificationConfig> configCaptor = ArgumentCaptor.forClass(
                MagnificationConfig.class);
        // The first time is for notifying full-screen enabled.
        // The second time is for notifying the target mode transitions.
        verify(mService, times(2)).notifyMagnificationChanged(eq(TEST_DISPLAY), any(Region.class),
                configCaptor.capture());
        final MagnificationConfig actualConfig = configCaptor.getValue();
        assertEquals(config.getCenterX(), actualConfig.getCenterX(), 0);
        assertEquals(config.getCenterY(), actualConfig.getCenterY(), 0);
        assertEquals(config.getScale(), actualConfig.getScale(), 0);
    }


    @Test
    public void onAccessibilityActionPerformed_magnifierEnabled_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onAccessibilityActionPerformed(TEST_DISPLAY);

        // The first time is triggered when window mode is activated.
        // The second time is triggered when accessibility action performed.
        verify(mWindowMagnificationManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void onAccessibilityActionPerformed_capabilityNotAll_removeMagnificationButton()
            throws RemoteException {
        mMagnificationController.setMagnificationCapabilities(
                ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onAccessibilityActionPerformed(TEST_DISPLAY);

        // The first time is triggered when window mode is activated.
        // The second time is triggered when accessibility action performed.
        verify(mWindowMagnificationManager, times(2)).removeMagnificationButton(eq(TEST_DISPLAY));
    }

    @Test
    public void onWindowMagnificationActivationState_windowActivated_logWindowDuration() {
        MagnificationController spyController = spy(mMagnificationController);
        spyController.onWindowMagnificationActivationState(TEST_DISPLAY, true);

        spyController.onWindowMagnificationActivationState(TEST_DISPLAY, false);

        verify(spyController).logMagnificationUsageState(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW), anyLong());
    }

    @Test
    public void setPreferenceMagnificationFollowTypingEnabled_setPrefDisabled_disableAll() {
        mMagnificationController.setMagnificationFollowTypingEnabled(false);

        verify(mWindowMagnificationManager).setMagnificationFollowTypingEnabled(eq(false));
        verify(mScreenMagnificationController).setMagnificationFollowTypingEnabled(eq(false));
    }

    @Test
    public void onRectangleOnScreenRequested_fullScreenIsActivated_fullScreenDispatchEvent() {
        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY,
                true);
        UiChangesForAccessibilityCallbacks callbacks = getUiChangesForAccessibilityCallbacks();

        callbacks.onRectangleOnScreenRequested(TEST_DISPLAY,
                TEST_RECT.left, TEST_RECT.top, TEST_RECT.right, TEST_RECT.bottom);

        verify(mScreenMagnificationController).onRectangleOnScreenRequested(eq(TEST_DISPLAY),
                eq(TEST_RECT.left), eq(TEST_RECT.top), eq(TEST_RECT.right), eq(TEST_RECT.bottom));
        verify(mWindowMagnificationManager, never()).onRectangleOnScreenRequested(anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onRectangleOnScreenRequested_fullScreenIsInactivated_noneDispatchEvent() {
        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY,
                true);
        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY,
                false);
        UiChangesForAccessibilityCallbacks callbacks = getUiChangesForAccessibilityCallbacks();

        callbacks.onRectangleOnScreenRequested(TEST_DISPLAY,
                TEST_RECT.left, TEST_RECT.top, TEST_RECT.right, TEST_RECT.bottom);

        verify(mScreenMagnificationController, never()).onRectangleOnScreenRequested(anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt());
        verify(mWindowMagnificationManager, never()).onRectangleOnScreenRequested(anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onRectangleOnScreenRequested_noneIsActivated_noneDispatchEvent() {
        UiChangesForAccessibilityCallbacks callbacks = getUiChangesForAccessibilityCallbacks();

        callbacks.onRectangleOnScreenRequested(TEST_DISPLAY,
                TEST_RECT.left, TEST_RECT.top, TEST_RECT.right, TEST_RECT.bottom);

        verify(mScreenMagnificationController, never()).onRectangleOnScreenRequested(
                eq(TEST_DISPLAY), anyInt(), anyInt(), anyInt(), anyInt());
        verify(mWindowMagnificationManager, never()).onRectangleOnScreenRequested(anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onRectangleOnScreenRequested_otherDisplayIsActivated_noneEventOnDefaultDisplay() {
        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY + 1,
                true);
        UiChangesForAccessibilityCallbacks callbacks = getUiChangesForAccessibilityCallbacks();

        callbacks.onRectangleOnScreenRequested(TEST_DISPLAY,
                TEST_RECT.left, TEST_RECT.top, TEST_RECT.right, TEST_RECT.bottom);

        verify(mScreenMagnificationController, never()).onRectangleOnScreenRequested(
                eq(TEST_DISPLAY), anyInt(), anyInt(), anyInt(), anyInt());
        verify(mWindowMagnificationManager, never()).onRectangleOnScreenRequested(anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onWindowModeActivated_fullScreenIsActivatedByExternal_fullScreenIsDisabled() {
        mScreenMagnificationController.setScaleAndCenter(TEST_DISPLAY,
                DEFAULT_SCALE, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y,
                true, TEST_SERVICE_ID);

        mMagnificationController.onWindowMagnificationActivationState(TEST_DISPLAY, true);

        verify(mScreenMagnificationController).reset(eq(TEST_DISPLAY), eq(false));
    }

    @Test
    public void getLastActivatedMode_switchMode_returnExpectedLastActivatedMode()
            throws RemoteException {
        activateMagnifier(MODE_WINDOW, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);

        final int lastActivatedMode = mMagnificationController
                .getLastMagnificationActivatedMode(TEST_DISPLAY);

        assertEquals(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW, lastActivatedMode);
    }

    @Test
    public void getLastActivatedMode_switchModeAtOtherDisplay_returnExpectedLastActivatedMode()
            throws RemoteException {
        activateMagnifier(TEST_DISPLAY, MODE_WINDOW, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
        activateMagnifier(TEST_DISPLAY + 1, MODE_FULLSCREEN, MAGNIFIED_CENTER_X,
                MAGNIFIED_CENTER_Y);

        final int lastActivatedMode = mMagnificationController
                .getLastMagnificationActivatedMode(TEST_DISPLAY);

        assertEquals(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW, lastActivatedMode);
    }

    @Test
    public void getLastActivatedMode_otherDisplayIsActivated_defaultModeOnDefaultDisplay()
            throws RemoteException {
        activateMagnifier(TEST_DISPLAY + 1, MODE_WINDOW, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);

        int lastActivatedMode = mMagnificationController
                .getLastMagnificationActivatedMode(TEST_DISPLAY);

        assertEquals(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN, lastActivatedMode);
    }

    @Test
    public void onFullScreenMagnificationActivationState_fullScreenEnabled_logFullScreenDuration() {
        MagnificationController spyController = spy(mMagnificationController);
        spyController.onFullScreenMagnificationActivationState(TEST_DISPLAY, true);

        spyController.onFullScreenMagnificationActivationState(TEST_DISPLAY, false);

        verify(spyController).logMagnificationUsageState(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN), anyLong());
    }

    @Test
    public void onFullScreenMagnificationActivationState_windowActivated_disableMagnification()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY, true);

        verify(mWindowMagnificationManager).disableWindowMagnification(eq(TEST_DISPLAY), eq(false));
    }

    @Test
    public void onTouchInteractionStart_fullScreenAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_FULLSCREEN);

        // The first time is triggered when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        // The third time is triggered when user interaction changed.
        verify(mWindowMagnificationManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void onTouchInteractionEnd_fullScreenAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_FULLSCREEN);

        // The first time is triggered when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        // The third time is triggered when user interaction changed.
        verify(mWindowMagnificationManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void onTouchInteractionStart_windowModeAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_WINDOW);

        // The first time is triggered when the window mode is activated.
        // The second time is triggered when user interaction changed.
        verify(mWindowMagnificationManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void onTouchInteractionEnd_windowModeAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_WINDOW);

        // The first time is triggered when the window mode is activated.
        // The second time is triggered when user interaction changed.
        verify(mWindowMagnificationManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
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
    public void enableWindowMode_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        verify(mWindowMagnificationManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void onFullScreenActivated_fullscreenEnabledAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY, true);

        // The first time is triggered when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        // The third time is triggered when fullscreen mode activation state is updated.
        verify(mWindowMagnificationManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void disableWindowMode_windowEnabled_removeMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mWindowMagnificationManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mWindowMagnificationManager).removeMagnificationButton(eq(TEST_DISPLAY));
    }

    @Test
    public void onFullScreenDeactivated_fullScreenEnabled_removeMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mScreenMagnificationController.reset(TEST_DISPLAY, /* animate= */ true);

        verify(mWindowMagnificationManager).removeMagnificationButton(eq(TEST_DISPLAY));
    }

    @Test
    public void transitionToFullScreenMode_windowEnabled_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_FULLSCREEN, mTransitionCallBack);
        mMockConnection.invokeCallbacks();

        // The first time is triggered when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        // The third time is triggered when the disable-magnification callback is triggered.
        verify(mWindowMagnificationManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
    }

    @Test
    public void transitionToWindow_fullScreenEnabled_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.transitionMagnificationModeLocked(TEST_DISPLAY,
                MODE_WINDOW, mTransitionCallBack);

        verify(mScreenMagnificationController).reset(eq(TEST_DISPLAY),
                mCallbackArgumentCaptor.capture());
        mCallbackArgumentCaptor.getValue().onResult(true);
        mMockConnection.invokeCallbacks();

        // The first time is triggered when window mode is activated.
        // The second time is triggered when the disable-magnification callback is triggered.
        verify(mWindowMagnificationManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
    }

    @Test
    public void imeWindowStateShown_windowMagnifying_logWindowMode() {
        MagnificationController spyController = spy(mMagnificationController);
        spyController.onWindowMagnificationActivationState(TEST_DISPLAY, true);

        spyController.onImeWindowVisibilityChanged(TEST_DISPLAY, true);

        verify(spyController).logMagnificationModeWithIme(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
    }

    @Test
    public void imeWindowStateShown_fullScreenMagnifying_logFullScreenMode() {
        MagnificationController spyController = spy(mMagnificationController);
        spyController.onFullScreenMagnificationActivationState(TEST_DISPLAY, true);

        spyController.onImeWindowVisibilityChanged(TEST_DISPLAY, true);

        verify(spyController).logMagnificationModeWithIme(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN));
    }

    @Test
    public void imeWindowStateShown_noMagnifying_noLogAnyMode() {
        MagnificationController spyController = spy(mMagnificationController);
        spyController.onImeWindowVisibilityChanged(TEST_DISPLAY, true);

        verify(spyController, never()).logMagnificationModeWithIme(anyInt());
    }

    @Test
    public void imeWindowStateHidden_windowMagnifying_noLogAnyMode() {
        MagnificationController spyController = spy(mMagnificationController);
        spyController.onFullScreenMagnificationActivationState(TEST_DISPLAY, true);

        verify(spyController, never()).logMagnificationModeWithIme(anyInt());
    }

    @Test
    public void imeWindowStateHidden_fullScreenMagnifying_noLogAnyMode() {
        MagnificationController spyController = spy(mMagnificationController);
        spyController.onWindowMagnificationActivationState(TEST_DISPLAY, true);

        verify(spyController, never()).logMagnificationModeWithIme(anyInt());
    }

    @Test
    public void onUserRemoved_notifyScaleProvider() {
        mMagnificationController.onUserRemoved(SECOND_USER_ID);

        verify(mScaleProvider).onUserRemoved(SECOND_USER_ID);
    }

    @Test
    public void onChangeMagnificationMode_delegateToService() {
        mMagnificationController.onChangeMagnificationMode(TEST_DISPLAY, MODE_WINDOW);

        verify(mService).changeMagnificationMode(TEST_DISPLAY, MODE_WINDOW);
    }

    private void setMagnificationEnabled(int mode) throws RemoteException {
        setMagnificationEnabled(mode, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
    }

    private void setMagnificationEnabled(int mode, float centerX, float centerY)
            throws RemoteException {
        setMagnificationModeSettings(mode);
        activateMagnifier(mode, centerX, centerY);
    }

    private void activateMagnifier(int mode, float centerX, float centerY) throws RemoteException {
        activateMagnifier(TEST_DISPLAY, mode, centerX, centerY);
    }

    private void activateMagnifier(int displayId, int mode, float centerX, float centerY)
            throws RemoteException {
        final boolean windowMagnifying = mWindowMagnificationManager.isWindowMagnifierEnabled(
                displayId);
        if (windowMagnifying) {
            mWindowMagnificationManager.disableWindowMagnification(displayId, false);
            mMockConnection.invokeCallbacks();
        }
        if (mode == MODE_FULLSCREEN) {
            mScreenMagnificationController.setScaleAndCenter(displayId, DEFAULT_SCALE, centerX,
                    centerY, true, AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
        } else {
            mWindowMagnificationManager.enableWindowMagnification(displayId, DEFAULT_SCALE,
                    centerX, centerY, null, TEST_SERVICE_ID);
            mMockConnection.invokeCallbacks();
        }
    }

    private void setMagnificationModeSettings(int mode) {
        Settings.Secure.putIntForUser(mMockResolver,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, mode, CURRENT_USER_ID);
    }

    private static MagnificationConfig obtainMagnificationConfig(int mode) {
        return obtainMagnificationConfig(mode, true);
    }

    private static MagnificationConfig obtainMagnificationConfig(int mode, boolean defaultScale) {
        MagnificationConfig.Builder builder = new MagnificationConfig.Builder();
        if (defaultScale) {
            builder = builder.setScale(DEFAULT_SCALE);
        }
        return builder.setMode(mode).setCenterX(MAGNIFIED_CENTER_X)
                .setCenterY(MAGNIFIED_CENTER_Y).build();
    }

    private UiChangesForAccessibilityCallbacks getUiChangesForAccessibilityCallbacks() {
        ArgumentCaptor<WindowManagerInternal.AccessibilityControllerInternal
                .UiChangesForAccessibilityCallbacks> captor = ArgumentCaptor.forClass(
                WindowManagerInternal.AccessibilityControllerInternal
                        .UiChangesForAccessibilityCallbacks.class);
        verify(mA11yController).setUiChangesForAccessibilityCallbacks(captor.capture());
        return captor.getValue();
    }

    private static class WindowMagnificationMgrCallbackDelegate implements
            WindowMagnificationManager.Callback {
        private WindowMagnificationManager.Callback mCallback;

        public void setDelegate(WindowMagnificationManager.Callback callback) {
            mCallback = callback;
        }

        @Override
        public void onPerformScaleAction(int displayId, float scale) {
            if (mCallback != null) {
                mCallback.onPerformScaleAction(displayId, scale);
            }
        }

        @Override
        public void onAccessibilityActionPerformed(int displayId) {
            if (mCallback != null) {
                mCallback.onAccessibilityActionPerformed(displayId);
            }
        }

        @Override
        public void onWindowMagnificationActivationState(int displayId, boolean activated) {
            if (mCallback != null) {
                mCallback.onWindowMagnificationActivationState(displayId, activated);
            }
        }

        @Override
        public void onSourceBoundsChanged(int displayId, Rect bounds) {
            if (mCallback != null) {
                mCallback.onSourceBoundsChanged(displayId, bounds);
            }
        }

        @Override
        public void onChangeMagnificationMode(int displayId, int magnificationMode) {
            if (mCallback != null) {
                mCallback.onChangeMagnificationMode(displayId, magnificationMode);
            }
        }
    }

    private static class FullScreenMagnificationCtrInfoChangedCallbackDelegate implements
            FullScreenMagnificationController.MagnificationInfoChangedCallback {
        private FullScreenMagnificationController.MagnificationInfoChangedCallback mCallback;

        public void setDelegate(
                FullScreenMagnificationController.MagnificationInfoChangedCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onRequestMagnificationSpec(int displayId, int serviceId) {
            if (mCallback != null) {
                mCallback.onRequestMagnificationSpec(displayId, serviceId);
            }
        }

        @Override
        public void onFullScreenMagnificationActivationState(int displayId, boolean activated) {
            if (mCallback != null) {
                mCallback.onFullScreenMagnificationActivationState(displayId, activated);
            }
        }

        @Override
        public void onImeWindowVisibilityChanged(int displayId, boolean shown) {
            if (mCallback != null) {
                mCallback.onImeWindowVisibilityChanged(displayId, shown);
            }
        }

        @Override
        public void onFullScreenMagnificationChanged(int displayId, @NonNull Region region,
                @NonNull MagnificationConfig config) {
            if (mCallback != null) {
                mCallback.onFullScreenMagnificationChanged(displayId, region, config);
            }
        }
    }
}
