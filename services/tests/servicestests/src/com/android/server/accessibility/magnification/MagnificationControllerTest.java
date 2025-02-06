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
import static android.util.MathUtils.sqrt;

import static com.android.internal.accessibility.common.MagnificationConstants.SCALE_MAX_VALUE;
import static com.android.internal.accessibility.common.MagnificationConstants.SCALE_MIN_VALUE;
import static com.android.server.accessibility.AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID;
import static com.android.server.wm.WindowManagerInternal.AccessibilityControllerInternal.UiChangesForAccessibilityCallbacks;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.floatThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.MagnificationConfig;
import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.testing.DexmakerShareClassLoaderRule;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.MagnificationAnimationCallback;
import android.widget.Scroller;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.input.InputManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import com.google.common.truth.Expect;

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

    @Rule
    public final Expect expect = Expect.create();

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
    private Resources mResources;
    @Mock
    private PackageManager mPackageManager;

    @Mock
    private FullScreenMagnificationController.ControllerContext mControllerCtx;
    @Mock
    private ValueAnimator mValueAnimator;
    @Mock
    private TimeAnimator mTimeAnimator;
    @Mock
    private MessageCapturingHandler mMessageCapturingHandler;

    private FullScreenMagnificationController mScreenMagnificationController;
    private final FullScreenMagnificationCtrInfoChangedCallbackDelegate
            mScreenMagnificationInfoChangedCallbackDelegate =
            new FullScreenMagnificationCtrInfoChangedCallbackDelegate();

    private MagnificationScaleProvider mScaleProvider;
    @Captor
    private ArgumentCaptor<MagnificationAnimationCallback> mCallbackArgumentCaptor;

    private MockMagnificationConnection mMockConnection;
    private MagnificationConnectionManager mMagnificationConnectionManager;
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
    private InputManagerInternal mInputManagerInternal;

    @Mock
    private DisplayManagerInternal mDisplayManagerInternal;
    private Display mDisplay;

    @Mock
    private Scroller mMockScroller;

    private TestLooper mTestLooper;

    private static class FakeSystemClock implements MagnificationController.SystemClock {
        private long mUptimeMillis = 1984;

        @Override
        public long uptimeMillis() {
            return mUptimeMillis;
        }

        public void advanceTime(long ms) {
            mUptimeMillis += ms;
        }
    }
    private FakeSystemClock mSystemClock;

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
        mSystemClock = new FakeSystemClock();
        mTestLooper = new TestLooper();
        when(mContext.getMainLooper()).thenReturn(
                InstrumentationRegistry.getContext().getMainLooper());
        when(mContext.getContentResolver()).thenReturn(mMockResolver);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        Settings.Secure.putFloatForUser(mMockResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, DEFAULT_SCALE,
                CURRENT_USER_ID);
        Settings.Secure.putFloatForUser(mMockResolver, Settings.Secure.KEY_REPEAT_ENABLED, 1,
                CURRENT_USER_ID);
        mScaleProvider = spy(new MagnificationScaleProvider(mContext));

        when(mControllerCtx.getContext()).thenReturn(mContext);
        when(mControllerCtx.getTraceManager()).thenReturn(mTraceManager);
        when(mControllerCtx.getWindowManager()).thenReturn(mWindowManagerInternal);
        when(mControllerCtx.getInputManager()).thenReturn(mInputManagerInternal);
        when(mControllerCtx.getHandler()).thenReturn(mMessageCapturingHandler);
        when(mControllerCtx.getAnimationDuration()).thenReturn(1000L);
        when(mControllerCtx.newValueAnimator()).thenReturn(mValueAnimator);

        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalDensityDpi = 300;
        doReturn(displayInfo).when(mDisplayManagerInternal).getDisplayInfo(anyInt());
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternal);

        DisplayManager displayManager = new DisplayManager(mContext);
        mDisplay = displayManager.getDisplay(TEST_DISPLAY);
        when(mContext.getSystemServiceName(DisplayManager.class)).thenReturn(
                Context.DISPLAY_SERVICE);
        when(mContext.getSystemService(DisplayManager.class)).thenReturn(displayManager);

        mScreenMagnificationController =
                spy(
                        new FullScreenMagnificationController(
                                mControllerCtx,
                                new Object(),
                                mScreenMagnificationInfoChangedCallbackDelegate,
                                mScaleProvider,
                                () -> null,
                                ConcurrentUtils.DIRECT_EXECUTOR,
                                () -> mMockScroller,
                                () -> mTimeAnimator,
                                () -> true));
        mScreenMagnificationController.register(TEST_DISPLAY);

        mMagnificationConnectionManager = spy(
                new MagnificationConnectionManager(mContext, globalLock,
                        mWindowMagnificationCallbackDelegate, mTraceManager, mScaleProvider));
        mMockConnection = new MockMagnificationConnection(true);
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());

        mMagnificationController = spy(new MagnificationController(mService, globalLock, mContext,
                mScreenMagnificationController, mMagnificationConnectionManager, mScaleProvider,
                ConcurrentUtils.DIRECT_EXECUTOR, mTestLooper.getLooper(), mSystemClock));
        mMagnificationController.setMagnificationCapabilities(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);

        mScreenMagnificationInfoChangedCallbackDelegate.setDelegate(mMagnificationController);
        mWindowMagnificationCallbackDelegate.setDelegate(mMagnificationController);
    }

    @After
    public void tearDown() {
        mTestLooper.dispatchAll();
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
        assertEquals(MAGNIFIED_CENTER_X,
                mMagnificationConnectionManager.getCenterX(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_Y,
                mMagnificationConnectionManager.getCenterY(TEST_DISPLAY), 0);
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
        assertEquals(MAGNIFIED_CENTER_X,
                mMagnificationConnectionManager.getCenterX(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_Y,
                mMagnificationConnectionManager.getCenterY(TEST_DISPLAY), 0);
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
        verify(mMagnificationConnectionManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
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

        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
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

        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
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
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, DEFAULT_SCALE,
                Float.NaN, Float.NaN, null, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        assertTrue(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        verify(mScreenMagnificationController, never()).setScaleAndCenter(TEST_DISPLAY,
                DEFAULT_SCALE, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y,
                true, true, MAGNIFICATION_GESTURE_HANDLER_ID);
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
        assertEquals(MAGNIFIED_CENTER_X,
                mMagnificationConnectionManager.getCenterX(TEST_DISPLAY), 0);
        assertEquals(MAGNIFIED_CENTER_Y,
                mMagnificationConnectionManager.getCenterY(TEST_DISPLAY), 0);
    }

    @Test
    public void
            configTransitionToWindowModeAndActivatedFalse_fullScreenMagnifying_doNotEnableWindow()
            throws RemoteException {
        activateMagnifier(MODE_FULLSCREEN, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);

        MagnificationConfig config = (new MagnificationConfig.Builder())
                .setMode(MODE_WINDOW).setActivated(false).build();
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                config, false, TEST_SERVICE_ID);

        verify(mMockConnection.getConnection(), never()).enableWindowMagnification(anyInt(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                nullable(IRemoteMagnificationAnimationCallback.class));
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

        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
        verify(mScreenMagnificationController).setScaleAndCenter(eq(TEST_DISPLAY),
                eq(DEFAULT_SCALE), eq(MAGNIFIED_CENTER_X), eq(MAGNIFIED_CENTER_Y),
                eq(false), any(MagnificationAnimationCallback.class), eq(TEST_SERVICE_ID));
    }

    @Test
    public void
            configTransitionToFullScreenAndActivatedFalse_windowMagnifying_doNotEnableFullScreen()
            throws RemoteException {
        final boolean animate = true;
        activateMagnifier(MODE_WINDOW, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
        MagnificationConfig config = (new MagnificationConfig.Builder())
                .setMode(MODE_FULLSCREEN).setActivated(false).build();
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                config, animate, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        verify(mScreenMagnificationController, never()).setScaleAndCenter(anyInt(),
                anyFloat(), anyFloat(), anyFloat(),
                anyBoolean(), anyBoolean(), anyInt());
    }

    @Test
    public void configTransitionToActivatedFalse_fullScreenMagnifying_disableFullScreen()
            throws RemoteException {
        activateMagnifier(MODE_FULLSCREEN, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
        MagnificationConfig config = (new MagnificationConfig.Builder())
                .setMode(MODE_FULLSCREEN).setActivated(false).build();
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                config, false, TEST_SERVICE_ID);

        verify(mScreenMagnificationController).reset(eq(TEST_DISPLAY), eq(false));
    }

    @Test
    public void configTransitionToActivatedFalse_windowMagnifying_disableWindow()
            throws RemoteException {
        final boolean animate = true;
        activateMagnifier(MODE_WINDOW, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);
        MagnificationConfig config = (new MagnificationConfig.Builder())
                .setMode(MODE_WINDOW).setActivated(false).build();
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                config, animate, TEST_SERVICE_ID);

        verify(mMockConnection.getConnection()).disableWindowMagnification(anyInt(),
                nullable(IRemoteMagnificationAnimationCallback.class));
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
    public void configTransitionToFullScreenWithAnimation_windowMagnifying_notifyService()
            throws RemoteException {
        final boolean animate = true;
        activateMagnifier(MODE_WINDOW, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);

        reset(mService);
        MagnificationConfig config = (new MagnificationConfig.Builder())
                .setMode(MODE_FULLSCREEN).build();
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                config, animate, TEST_SERVICE_ID);
        verify(mScreenMagnificationController).setScaleAndCenter(eq(TEST_DISPLAY),
                /* scale= */ anyFloat(), /* centerX= */ anyFloat(), /* centerY= */ anyFloat(),
                anyBoolean(), mCallbackArgumentCaptor.capture(), /* id= */ anyInt());
        mCallbackArgumentCaptor.getValue().onResult(true);
        mMockConnection.invokeCallbacks();

        verify(mService).changeMagnificationMode(TEST_DISPLAY, MODE_FULLSCREEN);
    }

    @Test
    public void configTransitionToFullScreenWithoutAnimation_windowMagnifying_notifyService()
            throws RemoteException {
        final boolean animate = false;
        activateMagnifier(MODE_WINDOW, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y);

        reset(mService);
        MagnificationConfig config = (new MagnificationConfig.Builder())
                .setMode(MODE_FULLSCREEN).build();
        mMagnificationController.transitionMagnificationConfigMode(TEST_DISPLAY,
                config, animate, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        verify(mService).changeMagnificationMode(TEST_DISPLAY, MODE_FULLSCREEN);
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
        verify(mMagnificationConnectionManager).onDisplayRemoved(TEST_DISPLAY);
        verify(mScaleProvider).onDisplayRemoved(TEST_DISPLAY);
    }

    @Test
    public void updateUserIdIfNeeded_AllModulesAvailable_disableMagnificationAndChangeUserId() {
        mMagnificationController.updateUserIdIfNeeded(SECOND_USER_ID);

        verify(mScreenMagnificationController).resetAllIfNeeded(false);
        verify(mMagnificationConnectionManager).disableAllWindowMagnifiers();
        verify(mScaleProvider).onUserChanged(SECOND_USER_ID);
    }

    @Test
    public void onMagnificationRequest_windowMagnifying_disableWindow() throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onRequestMagnificationSpec(TEST_DISPLAY, TEST_SERVICE_ID);
        mMockConnection.invokeCallbacks();

        assertFalse(mMagnificationConnectionManager.isWindowMagnifierEnabled(TEST_DISPLAY));
    }

    @Test
    public void magnifyThroughExternalRequest_showMagnificationButton() {
        mScreenMagnificationController.setScaleAndCenter(TEST_DISPLAY, DEFAULT_SCALE,
                MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y, true, false, TEST_SERVICE_ID);

        // The first time is trigger when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        verify(mMagnificationConnectionManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test
    public void onPerformScaleAction_fullScreenMagnifierEnabled_handleScaleChange()
            throws RemoteException {
        final float newScale = 4.0f;
        final boolean updatePersistence = true;
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, newScale, updatePersistence);

        verify(mScreenMagnificationController).setScaleAndCenter(eq(TEST_DISPLAY), eq(newScale),
                anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), anyInt());
        verify(mScreenMagnificationController).persistScale(eq(TEST_DISPLAY));
    }

    @Test
    public void onPerformScaleAction_windowMagnifierEnabled_handleScaleChange()
            throws RemoteException {
        final float newScale = 4.0f;
        final boolean updatePersistence = false;
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, newScale, updatePersistence);

        verify(mMagnificationConnectionManager).setScale(eq(TEST_DISPLAY), eq(newScale));
        verify(mMagnificationConnectionManager, never()).persistScale(eq(TEST_DISPLAY));
    }

    @Test
    public void scaleMagnificationStep_fullscreenMode_stepInAndOut() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 1.0f, false);
        reset(mScreenMagnificationController);

        // Verify the zoom scale factor increases by
        // {@code MagnificationController.DefaultMagnificationScaleStepProvider
        // .ZOOM_STEP_SCALE_FACTOR} and the center coordinates are
        // unchanged (Float.NaN as values denotes unchanged center).
        mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                MagnificationController.ZOOM_DIRECTION_IN);
        verify(mScreenMagnificationController).setScaleAndCenter(eq(TEST_DISPLAY),
                eq(MagnificationController
                        .DefaultMagnificationScaleStepProvider.ZOOM_STEP_SCALE_FACTOR),
                eq(Float.NaN), eq(Float.NaN), anyBoolean(), anyInt());
        mMagnificationController.onScaleMagnificationStop(
                MagnificationController.ZOOM_DIRECTION_IN);

        mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                MagnificationController.ZOOM_DIRECTION_OUT);
        verify(mScreenMagnificationController).setScaleAndCenter(eq(TEST_DISPLAY),
                eq(SCALE_MIN_VALUE), eq(Float.NaN), eq(Float.NaN), anyBoolean(), anyInt());
        mMagnificationController.onScaleMagnificationStop(
                MagnificationController.ZOOM_DIRECTION_OUT);
    }

    @Test
    public void scaleMagnificationStep_testMaxScaling() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, SCALE_MIN_VALUE, false);
        reset(mScreenMagnificationController);

        float currentScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
        while (currentScale < SCALE_MAX_VALUE) {
            mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                    MagnificationController.ZOOM_DIRECTION_IN);
            final float nextScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
            assertThat(nextScale).isGreaterThan(currentScale);
            currentScale = nextScale;
            mMagnificationController.onScaleMagnificationStop(
                    MagnificationController.ZOOM_DIRECTION_IN);
        }

        assertThat(currentScale).isEqualTo(SCALE_MAX_VALUE);
        // Trying to scale further does not change the scale.
        mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                MagnificationController.ZOOM_DIRECTION_IN);
        final float finalScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
        assertThat(finalScale).isEqualTo(currentScale);
        mMagnificationController.onScaleMagnificationStop(
                MagnificationController.ZOOM_DIRECTION_IN);
    }

    @Test
    public void scaleMagnificationStep_testMinScaling() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, SCALE_MAX_VALUE, false);
        reset(mScreenMagnificationController);

        float currentScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
        while (currentScale > SCALE_MIN_VALUE) {
            mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                    MagnificationController.ZOOM_DIRECTION_OUT);
            final float nextScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
            assertThat(nextScale).isLessThan(currentScale);
            currentScale = nextScale;
            mMagnificationController.onScaleMagnificationStop(
                    MagnificationController.ZOOM_DIRECTION_OUT);
        }

        assertThat(currentScale).isEqualTo(SCALE_MIN_VALUE);
        // Trying to scale further does not change the scale.
        mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                MagnificationController.ZOOM_DIRECTION_OUT);
        final float finalScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
        assertThat(finalScale).isEqualTo(currentScale);
        mMagnificationController.onScaleMagnificationStop(
                MagnificationController.ZOOM_DIRECTION_OUT);
    }

    @Test
    public void scaleMagnificationStep_windowedMode_stepInAndOut() throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, SCALE_MIN_VALUE, false);
        reset(mMagnificationConnectionManager);

        // Verify the zoom scale factor increases by
        // {@code MagnificationController.DefaultMagnificationScaleStepProvider
        // .ZOOM_STEP_SCALE_FACTOR}.
        mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                MagnificationController.ZOOM_DIRECTION_IN);
        verify(mMagnificationConnectionManager).setScale(eq(TEST_DISPLAY),
                eq(MagnificationController
                        .DefaultMagnificationScaleStepProvider.ZOOM_STEP_SCALE_FACTOR));
        mMagnificationController.onScaleMagnificationStop(
                MagnificationController.ZOOM_DIRECTION_IN);

        mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                MagnificationController.ZOOM_DIRECTION_OUT);
        verify(mMagnificationConnectionManager).setScale(eq(TEST_DISPLAY),
                eq(SCALE_MIN_VALUE));
        mMagnificationController.onScaleMagnificationStop(
                MagnificationController.ZOOM_DIRECTION_OUT);
    }

    @Test
    public void panMagnificationStep_fullscreenMode_stepSizeAtScale2() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        // At scale 8.0f, each step should be about 27 dip.
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 2.0f, false);
        reset(mScreenMagnificationController);

        testFullscreenMagnificationPanWithStepSize(40.0f);
    }

    @Test
    public void panMagnificationStep_fullscreenMode_stepSizeAtScale8() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        // At scale 8.0f, each step should be about 27 dip.
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 8.0f, false);
        reset(mScreenMagnificationController);

        testFullscreenMagnificationPanWithStepSize(27.0f);
    }

    @Test
    public void panMagnificationStep_windowMode_stepSizeAtScale2() throws RemoteException {
        mMagnificationConnectionManager.enableWindowMagnification(TEST_DISPLAY, 2.0f, 100f, 200f);

        testWindowMagnificationPanWithStepSize(40.0f);
    }

    @Test
    public void panMagnificationStep_windowMode_stepSizeAtScale8() throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        // At scale 8.0f, each step should be about 27 dip.
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 8.0f, false);
        reset(mMagnificationConnectionManager);

        testWindowMagnificationPanWithStepSize(27.0f);
    }

    @Test
    public void panMagnificationStep_fullscreenMode_reachesRightEdgeOfScreen()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        // At scale 2.0f, each step should be about 40 dip.
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, DEFAULT_SCALE, false);
        reset(mScreenMagnificationController);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        float expectedStep = 40.0f * metrics.density;

        // Move right, eventually we should reach the edge.
        int maxNumSteps = (int) (metrics.widthPixels / expectedStep) + 1;
        int numSteps = 0;
        while (numSteps < maxNumSteps) {
            mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                    MagnificationController.PAN_DIRECTION_RIGHT);
            mMagnificationController.onPanMagnificationStop(
                    MagnificationController.PAN_DIRECTION_RIGHT);
            float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            assertThat(currentCenterY).isEqualTo(newCenterY);

            assertThat(newCenterX).isAtLeast(currentCenterX);
            if (newCenterX == currentCenterX) {
                break;
            }

            currentCenterX = newCenterX;
            currentCenterY = newCenterY;
            numSteps++;
        }
        assertWithMessage("Still not at edge after panning right " + numSteps
                + " steps. Current position: " + currentCenterX + "," + currentCenterY)
                .that(numSteps).isLessThan(maxNumSteps);
    }

    @Test
    public void panMagnificationStep_fullscreenMode_reachesBottomEdgeOfScreen()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        // At scale 2.0f, each step should be about 40 dip.
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, DEFAULT_SCALE, false);
        reset(mScreenMagnificationController);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        float expectedStep = 40.0f * metrics.density;

        // Move down, eventually we should reach the edge.
        int maxNumSteps = (int) (metrics.heightPixels / expectedStep) + 1;
        int numSteps = 0;
        while (numSteps < maxNumSteps) {
            mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                    MagnificationController.PAN_DIRECTION_DOWN);
            mMagnificationController.onPanMagnificationStop(
                    MagnificationController.PAN_DIRECTION_DOWN);
            float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            assertThat(currentCenterX).isEqualTo(newCenterX);

            assertThat(newCenterY).isAtLeast(currentCenterY);
            if (newCenterY == currentCenterY) {
                break;
            }

            currentCenterX = newCenterX;
            currentCenterY = newCenterY;
            numSteps++;
        }
        assertWithMessage("Still not at edge after panning down "
                + numSteps + " steps. Current position: " + currentCenterX + "," + currentCenterY)
                .that(numSteps).isLessThan(maxNumSteps);
    }

    @Test
    public void panMagnificationStep_windowMode_reachesRightEdgeOfScreen()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        // At scale 8.0f, each step should be about 27 dip.
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 8.0f, false);
        reset(mMagnificationConnectionManager);

        float currentCenterX = mMagnificationConnectionManager.getCenterX(TEST_DISPLAY);
        float currentCenterY = mMagnificationConnectionManager.getCenterY(TEST_DISPLAY);

        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        float expectedStep = 40.0f * metrics.density;

        // Move right, eventually we should reach the edge.
        int maxNumSteps = (int) (metrics.widthPixels / expectedStep) + 1;
        int numSteps = 0;
        while (numSteps < maxNumSteps) {
            mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                    MagnificationController.PAN_DIRECTION_RIGHT);
            mMagnificationController.onPanMagnificationStop(
                    MagnificationController.PAN_DIRECTION_RIGHT);
            float newCenterX = mMagnificationConnectionManager.getCenterX(TEST_DISPLAY);
            float newCenterY = mMagnificationConnectionManager.getCenterY(TEST_DISPLAY);
            assertThat(currentCenterY).isEqualTo(newCenterY);

            assertThat(newCenterX).isAtLeast(currentCenterX);
            if (newCenterX == currentCenterX) {
                break;
            }

            currentCenterX = newCenterX;
            currentCenterY = newCenterY;
            numSteps++;
        }
        assertWithMessage("Still not at edge after panning right " + numSteps
                + " steps. Current position: " + currentCenterX + "," + currentCenterY)
                .that(numSteps).isLessThan(maxNumSteps);
    }

    @Test
    public void magnificationCallbacks_scaleMagnificationContinuous() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        float currentScale = 2.0f;
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, currentScale, false);
        reset(mScreenMagnificationController);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start zooming in using keyboard callbacks.
        mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                MagnificationController.ZOOM_DIRECTION_IN);

        // The center is unchanged.
        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isWithin(1.0f).of(newCenterX);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        // The scale is increased.
        float newScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
        expect.that(currentScale).isLessThan(newScale);
        currentScale = newScale;

        // Wait for the initial delay to occur.
        int initialMs = mMagnificationController.getInitialKeyboardRepeatIntervalMs() + 1;
        advanceTime(initialMs);

        // It should have scaled again after the handler was triggered.
        newScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
        expect.that(currentScale).isLessThan(newScale);
        currentScale = newScale;

        for (int i = 0; i < 3; i++) {
            // Wait for repeat delay to occur.
            advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

            // It should have scaled another time.
            newScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
            expect.that(currentScale).isLessThan(newScale);
            currentScale = newScale;
        }

        // Stop magnification scale.
        mMagnificationController.onScaleMagnificationStop(
                MagnificationController.ZOOM_DIRECTION_IN);

        // It should not scale again, even after the appropriate delay.
        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        newScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
        expect.that(currentScale).isEqualTo(newScale);
    }

    @Test
    public void magnificationCallbacks_panMagnificationContinuous_repeatKeysTimeout200()
            throws RemoteException {
        // Shorter than default.
        testMagnificationContinuousPanningWithTimeout(200);
    }

    @Test
    public void magnificationCallbacks_panMagnificationContinuous_repeatKeysTimeout1000()
            throws RemoteException {
        // Longer than default.
        testMagnificationContinuousPanningWithTimeout(1000);
    }

    @Test
    public void magnificationCallbacks_panMagnification_notContinuousWithRepeatKeysDisabled()
            throws RemoteException {
        mMagnificationController.setRepeatKeysEnabled(false);
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 4.0f, false);
        reset(mScreenMagnificationController);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start moving down using keyboard callbacks.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_DOWN);

        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterY).isLessThan(newCenterY);
        expect.that(currentCenterX).isEqualTo(newCenterX);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Wait for the initial delay to occur.
        advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs() + 1);

        for (int i = 0; i < 3; i++) {
            // It should not have moved again because repeat keys is disabled.
            newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            expect.that(currentCenterX).isEqualTo(newCenterX);
            expect.that(currentCenterY).isEqualTo(newCenterY);
            currentCenterX = newCenterX;
            currentCenterY = newCenterY;

            // Try waiting even longer. Nothing should ever happen.
            advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs()
                    + MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);
        }

        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_DOWN);
    }

    @Test
    public void magnificationCallbacks_scaleMagnification_notContinuousWithRepeatKeysDisabled()
            throws RemoteException {
        mMagnificationController.setRepeatKeysEnabled(false);
        setMagnificationEnabled(MODE_FULLSCREEN);
        float currentScale = 8.0f;
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, currentScale, false);
        reset(mScreenMagnificationController);

        // Start scaling out using keyboard callbacks.
        mMagnificationController.onScaleMagnificationStart(TEST_DISPLAY,
                MagnificationController.ZOOM_DIRECTION_OUT);

        float newScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
        expect.that(currentScale).isGreaterThan(newScale);

        currentScale = newScale;

        // Wait for the initial delay to occur.
        advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs() + 1);

        for (int i = 0; i < 3; i++) {
            // It should not have scaled again because repeat keys is disabled.
            newScale = mScreenMagnificationController.getScale(TEST_DISPLAY);
            expect.that(currentScale).isEqualTo(newScale);

            // Try waiting even longer. Nothing should ever happen.
            advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs()
                    + MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);
        }

        mMagnificationController.onScaleMagnificationStop(
                MagnificationController.ZOOM_DIRECTION_OUT);
    }

    @Test
    public void panMagnification_continuousDiagonalPanning_rightDown() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 8.0f, false);
        reset(mScreenMagnificationController);

        // At scale 8.0f, each step should be about 27 dip.
        float expectedStep = 27.0f;

        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        expectedStep *= metrics.density;

        float expectedDiagonalStep = expectedStep / sqrt(2);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start panning right.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_RIGHT);

        // A step right should be taken immediately.
        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedStep);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Start panning down.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_DOWN);

        // The diagonal step should be taken immediately.
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedDiagonalStep);
        expect.that(newCenterY).isGreaterThan(currentCenterY);
        expect.that(newCenterY - currentCenterY).isWithin(0.01f).of(expectedDiagonalStep);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // If we wait for the timeout from the initial pan start, we will move diagonally again.
        advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs());

        for (int i = 0; i < 3; i++) {
            newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            expect.that(currentCenterX).isLessThan(newCenterX);
            expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedDiagonalStep);
            expect.that(newCenterY).isGreaterThan(currentCenterY);
            expect.that(newCenterY - currentCenterY).isWithin(0.01f).of(expectedDiagonalStep);

            currentCenterX = newCenterX;
            currentCenterY = newCenterY;

            // Wait for next animation step.
            if (i < 2) {
                advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);
            }
        }

        // Release the "right" key. Should continue panning down only at the full step size.
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_RIGHT);
        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(newCenterY).isGreaterThan(currentCenterY);
        expect.that(newCenterY - currentCenterY).isWithin(0.01f).of(expectedStep);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Release the "down" key. No more panning.
        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_DOWN);

        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(currentCenterY).isEqualTo(newCenterY);
    }

    @Test
    public void panMagnification_continuousDiagonalPanning_rightThenDown() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 8.0f, false);
        reset(mScreenMagnificationController);

        // At scale 8.0f, each step should be about 27 dip.
        float expectedStep = 27.0f;

        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        expectedStep *= metrics.density;

        float expectedDiagonalStep = expectedStep / sqrt(2);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start panning right.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_RIGHT);

        // A step right should be taken immediately.
        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedStep);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // If we wait for the timeout from the initial pan start, we will move right again.
        advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs());

        for (int i = 0; i < 2; i++) {
            newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            expect.that(currentCenterX).isLessThan(newCenterX);
            expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedStep);
            expect.that(currentCenterY).isEqualTo(newCenterY);

            currentCenterX = newCenterX;
            currentCenterY = newCenterY;

            // Wait for next animation step.
            if (i == 0) {
                advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);
            }
        }

        // Wait halfway through the next repeat interval.
        int halfTimeStep = (int) (MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS / 2.0);
        advanceTime(halfTimeStep);

        // Start panning down.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_DOWN);

        // The diagonal step should be taken immediately.
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedDiagonalStep);
        expect.that(newCenterY).isGreaterThan(currentCenterY);
        expect.that(newCenterY - currentCenterY).isWithin(0.01f).of(expectedDiagonalStep);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Wait for the rest of the original interval.
        advanceTime(halfTimeStep);

        // It has not advanced yet because it hasn't been enough time since we last panned.
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(newCenterY).isEqualTo(currentCenterY);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Now it moves diagonally automatically.
        for (int i = 0; i < 2; i++) {
            advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

            newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            expect.that(currentCenterX).isLessThan(newCenterX);
            expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedDiagonalStep);
            expect.that(newCenterY).isGreaterThan(currentCenterY);
            expect.that(newCenterY - currentCenterY).isWithin(0.01f).of(expectedDiagonalStep);

            currentCenterX = newCenterX;
            currentCenterY = newCenterY;
        }


        // Release the "right" key. Should continue panning down only at the full step size.
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_RIGHT);
        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(newCenterY).isGreaterThan(currentCenterY);
        expect.that(newCenterY - currentCenterY).isWithin(0.01f).of(expectedStep);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Release the "down" key. No more panning.
        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_DOWN);

        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(currentCenterY).isEqualTo(newCenterY);
    }

    @Test
    public void panMagnification_continuousDiagonalPanning_upLeft() throws RemoteException {
        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);

        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 8.0f, false);
        reset(mScreenMagnificationController);
        mScreenMagnificationController.setCenter(TEST_DISPLAY, metrics.widthPixels,
                metrics.heightPixels, false, 0);

        // At scale 8.0f, each step should be about 27 dip.
        float expectedStep = 27.0f;
        expectedStep *= metrics.density;
        float expectedDiagonalStep = expectedStep / sqrt(2);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start panning left.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_LEFT);

        // A step left should be taken immediately.
        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isGreaterThan(newCenterX);
        expect.that(currentCenterX - newCenterX).isWithin(0.01f).of(expectedStep);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Start panning up after a moment.
        advanceTime(10);
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_UP);

        // The diagonal step should be taken immediately.
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isGreaterThan(newCenterX);
        expect.that(currentCenterX - newCenterX).isWithin(0.01f).of(expectedDiagonalStep);
        expect.that(newCenterY).isLessThan(currentCenterY);
        expect.that(currentCenterY - newCenterY).isWithin(0.01f).of(expectedDiagonalStep);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // If we wait for the timeout from the initial pan start, we will move diagonally again.
        advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs() - 10);

        for (int i = 0; i < 3; i++) {
            newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            expect.that(currentCenterX).isGreaterThan(newCenterX);
            expect.that(currentCenterX - newCenterX).isWithin(0.01f).of(expectedDiagonalStep);
            expect.that(newCenterY).isLessThan(currentCenterY);
            expect.that(currentCenterY - newCenterY).isWithin(0.01f).of(expectedDiagonalStep);

            currentCenterX = newCenterX;
            currentCenterY = newCenterY;

            if (i < 2) {
                advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);
            }
        }

        // Release the "left" key. Should continue panning up only at the full step size.
        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_LEFT);
        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(newCenterY).isLessThan(currentCenterY);
        expect.that(currentCenterY - newCenterY).isWithin(0.01f).of(expectedStep);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Release the "up" key. No more panning.
        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_UP);

        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(currentCenterY).isEqualTo(newCenterY);
    }

    @Test
    public void panMagnification_directionsCancel_leftRight() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 2.0f, false);
        reset(mScreenMagnificationController);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start panning right.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_RIGHT);

        // A step right should be taken immediately.
        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Start panning left. Nothing should happen as the two directions cancel.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_LEFT);

        for (int i = 0; i < 3; i++) {
            newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            expect.that(currentCenterX).isEqualTo(newCenterX);
            expect.that(currentCenterY).isEqualTo(newCenterY);
            advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs());
        }

        // Now stop going right.
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_RIGHT);
        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        // After the timeout, we've gone left since it is still held down.
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isGreaterThan(newCenterX);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_RIGHT);
    }

    @Test
    public void panMagnification_directionsCancel_upDown() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 2.0f, false);
        reset(mScreenMagnificationController);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start panning down.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_DOWN);

        // A step down should be taken immediately.
        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(currentCenterY).isLessThan(newCenterY);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Start panning up. Nothing should happen no matter how long we wait,
        // as the two directions cancel.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_UP);

        for (int i = 0; i < 3; i++) {
            newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            expect.that(currentCenterX).isEqualTo(newCenterX);
            expect.that(currentCenterY).isEqualTo(newCenterY);
            advanceTime(mMagnificationController.getInitialKeyboardRepeatIntervalMs());
        }

        // Now stop going up.
        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_UP);
        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        // After the timeout, we've gone down again since it is still held down.
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(currentCenterY).isLessThan(newCenterY);

        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_DOWN);
    }

    @Test
    public void panMagnification_directionsCancel_upDownRight() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 2.0f, false);
        reset(mScreenMagnificationController);

        // Start panning down and up.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_DOWN);
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_UP);

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start panning right. Only the x direction should change, not y, which cancel out.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_RIGHT);
        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_UP);
        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_DOWN);
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_RIGHT);
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
                true, true, TEST_SERVICE_ID);

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

        verify(mMagnificationConnectionManager).onUserMagnificationScaleChanged(
                /* userId= */ anyInt(), eq(TEST_DISPLAY), eq(config.getScale()));
    }

    @Test
    public void onSourceBoundChanged_windowEnabled_notifyMagnificationChanged()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        reset(mMagnificationConnectionManager);

        mMagnificationController.onSourceBoundsChanged(TEST_DISPLAY, TEST_RECT);

        verify(mMagnificationConnectionManager).onUserMagnificationScaleChanged(
                /* userId= */ anyInt(), eq(TEST_DISPLAY), eq(DEFAULT_SCALE));
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
        verify(mMagnificationConnectionManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
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
        verify(mMagnificationConnectionManager, times(2))
                .removeMagnificationButton(eq(TEST_DISPLAY));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test public void activateWindowMagnification_triggerCallback() throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        verify(mMagnificationController).onWindowMagnificationActivationState(
                eq(TEST_DISPLAY), eq(true));
    }

    @Test
    public void deactivateWindowMagnification_windowActivated_triggerCallbackAndLogUsage()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);
        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, /* clear= */ true);

        verify(mMagnificationController).onWindowMagnificationActivationState(
                eq(TEST_DISPLAY), eq(false));
        verify(mMagnificationController).logMagnificationUsageState(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW), anyLong(), eq(DEFAULT_SCALE));
    }

    @Test
    public void setPreferenceMagnificationFollowTypingEnabled_setPrefDisabled_disableAll() {
        mMagnificationController.setMagnificationFollowTypingEnabled(false);

        verify(mMagnificationConnectionManager).setMagnificationFollowTypingEnabled(eq(false));
        verify(mScreenMagnificationController).setMagnificationFollowTypingEnabled(eq(false));
    }

    @Test
    public void setPreferenceAlwaysOnMagnificationEnabled_setPrefEnabled_enableOnFullScreen() {
        mMagnificationController.setAlwaysOnMagnificationEnabled(true);

        verify(mScreenMagnificationController).setAlwaysOnMagnificationEnabled(eq(true));
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
        verify(mMagnificationConnectionManager, never()).onRectangleOnScreenRequested(anyInt(),
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
        verify(mMagnificationConnectionManager, never()).onRectangleOnScreenRequested(anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onRectangleOnScreenRequested_noneIsActivated_noneDispatchEvent() {
        UiChangesForAccessibilityCallbacks callbacks = getUiChangesForAccessibilityCallbacks();

        callbacks.onRectangleOnScreenRequested(TEST_DISPLAY,
                TEST_RECT.left, TEST_RECT.top, TEST_RECT.right, TEST_RECT.bottom);

        verify(mScreenMagnificationController, never()).onRectangleOnScreenRequested(
                eq(TEST_DISPLAY), anyInt(), anyInt(), anyInt(), anyInt());
        verify(mMagnificationConnectionManager, never()).onRectangleOnScreenRequested(anyInt(),
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
        verify(mMagnificationConnectionManager, never()).onRectangleOnScreenRequested(anyInt(),
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onWindowModeActivated_fullScreenIsActivatedByExternal_fullScreenIsDisabled() {
        mScreenMagnificationController.setScaleAndCenter(TEST_DISPLAY,
                DEFAULT_SCALE, MAGNIFIED_CENTER_X, MAGNIFIED_CENTER_Y,
                true, true, TEST_SERVICE_ID);

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
    public void activateFullScreenMagnification_triggerCallback() throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        verify(mMagnificationController).onFullScreenMagnificationActivationState(
                eq(TEST_DISPLAY), eq(true));
    }

    @Test
    public void deactivateFullScreenMagnification_fullScreenEnabled_triggerCallbackAndLogUsage()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mScreenMagnificationController.reset(TEST_DISPLAY, /* animate= */ false);

        verify(mMagnificationController).onFullScreenMagnificationActivationState(
                eq(TEST_DISPLAY), eq(false));
        verify(mMagnificationController).logMagnificationUsageState(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN), anyLong(), eq(DEFAULT_SCALE));
    }

    @Test
    public void onFullScreenMagnificationActivationState_windowActivated_disableMagnification()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY, true);

        verify(mMagnificationConnectionManager)
                .disableWindowMagnification(eq(TEST_DISPLAY), eq(false));
    }

    @Test
    public void onTouchInteractionStart_fullScreenAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_FULLSCREEN);

        // The first time is triggered when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        // The third time is triggered when user interaction changed.
        verify(mMagnificationConnectionManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test
    public void onTouchInteractionEnd_fullScreenAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_FULLSCREEN);

        // The first time is triggered when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        // The third time is triggered when user interaction changed.
        verify(mMagnificationConnectionManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test
    public void onTouchInteractionStart_windowModeAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_WINDOW);

        // The first time is triggered when the window mode is activated.
        // The second time is triggered when user interaction changed.
        verify(mMagnificationConnectionManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test
    public void onTouchInteractionEnd_windowModeAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_WINDOW);

        // The first time is triggered when the window mode is activated.
        // The second time is triggered when user interaction changed.
        verify(mMagnificationConnectionManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test
    public void onTouchInteractionChanged_notCapabilitiesAll_notShowMagnificationButton()
            throws RemoteException {
        mMagnificationController.setMagnificationCapabilities(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_FULLSCREEN);
        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_FULLSCREEN);

        verify(mMagnificationConnectionManager, never()).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
        // The first time is triggered when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        verify(mMagnificationConnectionManager, times(2)).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }


    @Test
    public void
            onTouchInteractionChanged_fullscreenNotActivated_notShowMagnificationButton()
            throws RemoteException {
        setMagnificationModeSettings(MODE_FULLSCREEN);

        mMagnificationController.onTouchInteractionStart(TEST_DISPLAY, MODE_FULLSCREEN);
        mMagnificationController.onTouchInteractionEnd(TEST_DISPLAY, MODE_FULLSCREEN);

        verify(mMagnificationConnectionManager, never()).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
        verify(mMagnificationConnectionManager, times(2)).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test
    public void enableWindowMode_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        verify(mMagnificationConnectionManager).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test
    public void onFullScreenActivated_fullscreenEnabledAndCapabilitiesAll_showMagnificationButton()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);

        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY, true);

        // The first time is triggered when fullscreen mode is activated.
        // The second time is triggered when magnification spec is changed.
        // The third time is triggered when fullscreen mode activation state is updated.
        verify(mMagnificationConnectionManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
        // Never call removeMagnificationSettingsPanel if it is allowed to show the settings panel
        // in current capability and mode, and the magnification is activated.
        verify(mMagnificationConnectionManager, never()).removeMagnificationSettingsPanel(
                eq(TEST_DISPLAY));
    }

    @Test
    public void disableWindowMode_windowEnabled_removeMagnificationButtonAndSettingsPanel()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mMagnificationConnectionManager).removeMagnificationButton(eq(TEST_DISPLAY));
        verify(mMagnificationConnectionManager).removeMagnificationSettingsPanel(eq(TEST_DISPLAY));
    }

    @Test
    public void
            onFullScreenDeactivated_fullScreenEnabled_removeMagnificationButtonAneSettingsPanel()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mScreenMagnificationController.reset(TEST_DISPLAY, /* animate= */ true);

        verify(mMagnificationConnectionManager).removeMagnificationButton(eq(TEST_DISPLAY));
        verify(mMagnificationConnectionManager).removeMagnificationSettingsPanel(eq(TEST_DISPLAY));
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
        verify(mMagnificationConnectionManager, times(3)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_FULLSCREEN));
        // It is triggered when the disable-magnification callback is triggered.
        verify(mMagnificationConnectionManager).removeMagnificationSettingsPanel(eq(TEST_DISPLAY));
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
        verify(mMagnificationConnectionManager, times(2)).showMagnificationButton(eq(TEST_DISPLAY),
                eq(MODE_WINDOW));
        // It is triggered when the disable-magnification callback is triggered.
        verify(mMagnificationConnectionManager).removeMagnificationSettingsPanel(eq(TEST_DISPLAY));
    }

    @Test
    public void disableWindowMode_windowEnabled_removeMagnificationSettingsPanel()
            throws RemoteException {
        setMagnificationEnabled(MODE_WINDOW);

        mMagnificationConnectionManager.disableWindowMagnification(TEST_DISPLAY, false);

        verify(mMagnificationConnectionManager).removeMagnificationSettingsPanel(eq(TEST_DISPLAY));
    }

    @Test
    public void onFullScreenDeactivated_fullScreenEnabled_removeMagnificationSettingsPanel()
            throws RemoteException {
        setMagnificationEnabled(MODE_FULLSCREEN);
        mScreenMagnificationController.reset(TEST_DISPLAY, /* animate= */ true);

        verify(mMagnificationConnectionManager).removeMagnificationSettingsPanel(eq(TEST_DISPLAY));
    }

    @Test
    public void imeWindowStateShown_windowMagnifying_logWindowMode() {
        mMagnificationController.onWindowMagnificationActivationState(TEST_DISPLAY, true);

        mMagnificationController.onImeWindowVisibilityChanged(TEST_DISPLAY, true);

        verify(mMagnificationController).logMagnificationModeWithIme(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
    }

    @Test
    public void imeWindowStateShown_fullScreenMagnifying_logFullScreenMode() {
        mMagnificationController.onFullScreenMagnificationActivationState(TEST_DISPLAY, true);

        mMagnificationController.onImeWindowVisibilityChanged(TEST_DISPLAY, true);

        verify(mMagnificationController).logMagnificationModeWithIme(
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN));
    }

    @Test
    public void imeWindowStateShown_noMagnifying_noLogAnyMode() {
        mMagnificationController.onImeWindowVisibilityChanged(TEST_DISPLAY, true);

        verify(mMagnificationController, never()).logMagnificationModeWithIme(anyInt());
    }

    @Test
    public void imeWindowStateHidden_windowMagnifying_noLogAnyMode() {
        mMagnificationController.onFullScreenMagnificationActivationState(
                TEST_DISPLAY, true);

        verify(mMagnificationController, never()).logMagnificationModeWithIme(anyInt());
    }

    @Test
    public void imeWindowStateHidden_fullScreenMagnifying_noLogAnyMode() {
        mMagnificationController.onWindowMagnificationActivationState(TEST_DISPLAY, true);

        verify(mMagnificationController, never()).logMagnificationModeWithIme(anyInt());
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

    @Test
    public void onFullscreenMagnificationActivationState_notifyConnection() {
        mMagnificationController.onFullScreenMagnificationActivationState(
                TEST_DISPLAY, /* activated= */ true);

        verify(mMagnificationConnectionManager)
                .onFullscreenMagnificationActivationChanged(TEST_DISPLAY, /* activated= */ true);
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
        final boolean windowMagnifying = mMagnificationConnectionManager.isWindowMagnifierEnabled(
                displayId);
        if (windowMagnifying) {
            mMagnificationConnectionManager.disableWindowMagnification(displayId, false);
            mMockConnection.invokeCallbacks();
        }
        if (mode == MODE_FULLSCREEN) {
            mScreenMagnificationController.setScaleAndCenter(displayId, DEFAULT_SCALE, centerX,
                    centerY, true, true,
                    AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
        } else {
            mMagnificationConnectionManager.enableWindowMagnification(displayId, DEFAULT_SCALE,
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

    private void testFullscreenMagnificationPanWithStepSize(float expectedStep) {
        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        expectedStep *= metrics.density;

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Move right using keyboard callbacks.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_RIGHT);
        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedStep);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_RIGHT);
        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Move left.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_LEFT);
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isGreaterThan(newCenterX);
        expect.that(currentCenterX - newCenterX).isWithin(0.01f).of(expectedStep);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_LEFT);
        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Move down.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_DOWN);
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(currentCenterY).isLessThan(newCenterY);
        expect.that(newCenterY - currentCenterY).isWithin(0.1f).of(expectedStep);

        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_DOWN);
        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Move up.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_UP);
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isEqualTo(newCenterX);
        expect.that(currentCenterY).isGreaterThan(newCenterY);
        expect.that(currentCenterY - newCenterY).isWithin(0.01f).of(expectedStep);

        mMagnificationController.onPanMagnificationStop(MagnificationController.PAN_DIRECTION_UP);
    }

    private void testWindowMagnificationPanWithStepSize(float expectedStepDip)
            throws RemoteException {
        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        final float expectedStep = expectedStepDip * metrics.density;

        // Move right.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_RIGHT);
        verify(mMockConnection.getConnection()).moveWindowMagnifier(eq(TEST_DISPLAY),
                floatThat(step -> Math.abs(step - expectedStep) < 0.0001), eq(0.0f));
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_RIGHT);

        // Move left.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_LEFT);
        verify(mMockConnection.getConnection()).moveWindowMagnifier(eq(TEST_DISPLAY),
                floatThat(step -> Math.abs(expectedStep - step) < 0.0001), eq(0.0f));
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_LEFT);

        // Move down.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_DOWN);
        verify(mMockConnection.getConnection()).moveWindowMagnifier(eq(TEST_DISPLAY),
                eq(0.0f), floatThat(step -> Math.abs(expectedStep - step) < 0.0001));
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_DOWN);

        // Move up.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_UP);
        verify(mMockConnection.getConnection()).moveWindowMagnifier(eq(TEST_DISPLAY),
                eq(0.0f), floatThat(step -> Math.abs(expectedStep - step) < 0.0001));
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_UP);
    }

    private void
            testMagnificationContinuousPanningWithTimeout(int timeoutMs) throws RemoteException {
        mMagnificationController.setRepeatKeysTimeoutMs(timeoutMs);
        expect.that(timeoutMs).isEqualTo(
                mMagnificationController.getInitialKeyboardRepeatIntervalMs());

        setMagnificationEnabled(MODE_FULLSCREEN);
        mMagnificationController.onPerformScaleAction(TEST_DISPLAY, 8.0f, false);
        reset(mScreenMagnificationController);

        DisplayMetrics metrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        // At scale 8.0f, each step should be about 27 dip.
        float expectedStep = 27 * metrics.density;

        float currentCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float currentCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);

        // Start moving right using keyboard callbacks.
        mMagnificationController.onPanMagnificationStart(TEST_DISPLAY,
                MagnificationController.PAN_DIRECTION_RIGHT);

        float newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        float newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedStep);
        expect.that(currentCenterY).isEqualTo(newCenterY);

        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        // Wait for the initial delay to occur.
        advanceTime(timeoutMs + 1);

        // It should have moved again after the handler was triggered.
        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(currentCenterX).isLessThan(newCenterX);
        expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedStep);
        expect.that(currentCenterY).isEqualTo(newCenterY);
        currentCenterX = newCenterX;
        currentCenterY = newCenterY;

        for (int i = 0; i < 3; i++) {
            // Wait for repeat delay to occur.
            advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

            // It should have moved another time.
            newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
            newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
            expect.that(currentCenterX).isLessThan(newCenterX);
            expect.that(newCenterX - currentCenterX).isWithin(0.01f).of(expectedStep);
            expect.that(currentCenterY).isEqualTo(newCenterY);
            currentCenterX = newCenterX;
            currentCenterY = newCenterY;
        }

        // Stop magnification pan.
        mMagnificationController.onPanMagnificationStop(
                MagnificationController.PAN_DIRECTION_RIGHT);

        // It should not move again, even after the appropriate delay.
        advanceTime(MagnificationController.KEYBOARD_REPEAT_INTERVAL_MS + 1);

        newCenterX = mScreenMagnificationController.getCenterX(TEST_DISPLAY);
        newCenterY = mScreenMagnificationController.getCenterY(TEST_DISPLAY);
        expect.that(newCenterX).isEqualTo(currentCenterX);
        expect.that(newCenterY).isEqualTo(currentCenterY);
    }

    private void advanceTime(long timeMs) {
        mSystemClock.advanceTime(timeMs);
        mTestLooper.moveTimeForward(timeMs);
        mTestLooper.dispatchAll();
    }

    private static class WindowMagnificationMgrCallbackDelegate implements
            MagnificationConnectionManager.Callback {
        private MagnificationConnectionManager.Callback mCallback;

        public void setDelegate(MagnificationConnectionManager.Callback callback) {
            mCallback = callback;
        }

        @Override
        public void onPerformScaleAction(int displayId, float scale, boolean updatePersistence) {
            if (mCallback != null) {
                mCallback.onPerformScaleAction(displayId, scale, updatePersistence);
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
