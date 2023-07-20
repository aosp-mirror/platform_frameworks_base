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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.MergedConfiguration;
import android.view.IWindowSessionCallback;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsVisibilities;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.window.ClientWindowFrames;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 * atest WmTests:WindowManagerServiceTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowManagerServiceTests extends WindowTestsBase {
    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    private boolean isAutomotive() {
        return getInstrumentation().getTargetContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE);
    }

    @Test
    public void testAddWindowToken() {
        IBinder token = mock(IBinder.class);
        mWm.addWindowToken(token, TYPE_TOAST, mDisplayContent.getDisplayId(), null /* options */);

        WindowToken windowToken = mWm.mRoot.getWindowToken(token);
        assertFalse(windowToken.mRoundedCornerOverlay);
        assertFalse(windowToken.isFromClient());
    }

    @Test
    public void testTaskFocusChange_rootTaskNotHomeType_focusChanges() throws RemoteException {
        DisplayContent display = createNewDisplay();
        // Current focused window
        Task focusedRootTask = createTask(
                display, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        Task focusedTask = createTaskInRootTask(focusedRootTask, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped task
        Task tappedRootTask = createTask(
                display, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        Task tappedTask = createTaskInRootTask(tappedRootTask, 0 /* userId */);
        spyOn(mWm.mAtmService);

        mWm.handleTaskFocusChange(tappedTask, null /* window */);

        verify(mWm.mAtmService).setFocusedTask(tappedTask.mTaskId, null);
    }

    @Test
    public void testTaskFocusChange_rootTaskHomeTypeWithSameTaskDisplayArea_focusDoesNotChange()
            throws RemoteException {
        DisplayContent display = createNewDisplay();
        // Current focused window
        Task focusedRootTask = createTask(
                display, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        Task focusedTask = createTaskInRootTask(focusedRootTask, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped home task
        Task tappedRootTask = createTask(
                display, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        Task tappedTask = createTaskInRootTask(tappedRootTask, 0 /* userId */);
        spyOn(mWm.mAtmService);

        mWm.handleTaskFocusChange(tappedTask, null /* window */);

        verify(mWm.mAtmService, never()).setFocusedTask(tappedTask.mTaskId, null);
    }

    @Test
    public void testTaskFocusChange_rootTaskHomeTypeWithDifferentTaskDisplayArea_focusChanges()
            throws RemoteException {
        final DisplayContent display = createNewDisplay();
        final TaskDisplayArea secondTda = createTaskDisplayArea(
                display, mWm, "Tapped TDA", FEATURE_VENDOR_FIRST);
        // Current focused window
        Task focusedRootTask = createTask(
                display, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        Task focusedTask = createTaskInRootTask(focusedRootTask, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped home task on another task display area
        Task tappedRootTask = createTask(secondTda, WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD);
        Task tappedTask = createTaskInRootTask(tappedRootTask, 0 /* userId */);
        spyOn(mWm.mAtmService);

        mWm.handleTaskFocusChange(tappedTask, null /* window */);

        verify(mWm.mAtmService).setFocusedTask(tappedTask.mTaskId, null);
    }

    @Test
    public void testDismissKeyguardCanWakeUp() {
        doReturn(true).when(mWm).checkCallingPermission(anyString(), anyString());
        doReturn(true).when(mWm.mAtmService.mKeyguardController).isShowingDream();
        doNothing().when(mWm.mAtmService.mTaskSupervisor).wakeUp(anyString());
        mWm.dismissKeyguard(null, "test-dismiss-keyguard");
        verify(mWm.mAtmService.mTaskSupervisor).wakeUp(anyString());
    }

    @Test
    public void testRelayoutExitingWindow() {
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, "appWin");
        final WindowSurfaceController surfaceController = mock(WindowSurfaceController.class);
        doReturn(true).when(surfaceController).hasSurface();
        spyOn(win);
        doReturn(true).when(win).isExitAnimationRunningSelfOrParent();
        win.mWinAnimator.mSurfaceController = surfaceController;
        win.mViewVisibility = View.VISIBLE;
        win.mHasSurface = true;
        win.mActivityRecord.mAppStopped = true;
        win.mActivityRecord.setVisibleRequested(false);
        win.mActivityRecord.setVisible(false);
        mWm.mWindowMap.put(win.mClient.asBinder(), win);
        final int w = 100;
        final int h = 200;
        final ClientWindowFrames outFrames = new ClientWindowFrames();
        final MergedConfiguration outConfig = new MergedConfiguration();
        final SurfaceControl outSurfaceControl = new SurfaceControl();
        final InsetsState outInsetsState = new InsetsState();
        final InsetsSourceControl[] outControls = new InsetsSourceControl[0];
        final Bundle outBundle = new Bundle();
        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.GONE, 0, 0, 0,
                outFrames, outConfig, outSurfaceControl, outInsetsState, outControls, outBundle);
        // Because the window is already invisible, it doesn't need to apply exiting animation
        // and WMS#tryStartExitingAnimation() will destroy the surface directly.
        assertFalse(win.mAnimatingExit);
        assertFalse(win.mHasSurface);
        assertNull(win.mWinAnimator.mSurfaceController);

        doReturn(mSystemServicesTestRule.mTransaction).when(SurfaceControl::getGlobalTransaction);
        // Invisible requested activity should not get the last config even if its view is visible.
        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outFrames, outConfig, outSurfaceControl, outInsetsState, outControls, outBundle);
        assertEquals(0, outConfig.getMergedConfiguration().densityDpi);
        // Non activity window can still get the last config.
        win.mActivityRecord = null;
        win.fillClientWindowFramesAndConfiguration(outFrames, outConfig,
                false /* useLatestConfig */, true /* relayoutVisible */);
        assertEquals(win.getConfiguration().densityDpi,
                outConfig.getMergedConfiguration().densityDpi);
    }

    @Test
    public void testMoveWindowTokenToDisplay_NullToken_DoNothing() {
        mWm.moveWindowTokenToDisplay(null, mDisplayContent.getDisplayId());

        verify(mDisplayContent, never()).reParentWindowToken(any());
    }

    @Test
    public void testMoveWindowTokenToDisplay_SameDisplay_DoNothing() {
        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD_DIALOG,
                mDisplayContent);

        mWm.moveWindowTokenToDisplay(windowToken.token, mDisplayContent.getDisplayId());

        verify(mDisplayContent, never()).reParentWindowToken(any());
    }

    @Test
    public void testMoveWindowTokenToDisplay_DifferentDisplay_DoMoveDisplay() {
        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD_DIALOG,
                mDisplayContent);

        mWm.moveWindowTokenToDisplay(windowToken.token, DEFAULT_DISPLAY);

        assertThat(windowToken.getDisplayContent()).isEqualTo(mDefaultDisplay);
    }

    @Test
    public void testAttachWindowContextToWindowToken_InvalidToken_EarlyReturn() {
        spyOn(mWm.mWindowContextListenerController);

        mWm.attachWindowContextToWindowToken(new Binder(), new Binder());

        verify(mWm.mWindowContextListenerController, never()).getWindowType(any());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachWindowContextToWindowToken_InvalidWindowType_ThrowException() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(INVALID_WINDOW_TYPE).when(mWm.mWindowContextListenerController)
                .getWindowType(any());

        mWm.attachWindowContextToWindowToken(new Binder(), windowToken.token);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachWindowContextToWindowToken_DifferentWindowType_ThrowException() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(TYPE_APPLICATION).when(mWm.mWindowContextListenerController)
                .getWindowType(any());

        mWm.attachWindowContextToWindowToken(new Binder(), windowToken.token);
    }

    @Test
    public void testAttachWindowContextToWindowToken_CallerNotValid_EarlyReturn() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(TYPE_INPUT_METHOD).when(mWm.mWindowContextListenerController)
                .getWindowType(any());
        doReturn(false).when(mWm.mWindowContextListenerController)
                .assertCallerCanModifyListener(any(), anyBoolean(), anyInt());

        mWm.attachWindowContextToWindowToken(new Binder(), windowToken.token);

        verify(mWm.mWindowContextListenerController, never()).registerWindowContainerListener(
                any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    public void testAttachWindowContextToWindowToken_CallerValid_DoRegister() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(TYPE_INPUT_METHOD).when(mWm.mWindowContextListenerController)
                .getWindowType(any());
        doReturn(true).when(mWm.mWindowContextListenerController)
                .assertCallerCanModifyListener(any(), anyBoolean(), anyInt());

        final IBinder clientToken = new Binder();
        mWm.attachWindowContextToWindowToken(clientToken, windowToken.token);

        verify(mWm.mWindowContextListenerController).registerWindowContainerListener(
                eq(clientToken), eq(windowToken), anyInt(), eq(TYPE_INPUT_METHOD),
                eq(windowToken.mOptions));
    }

    @Test
    public void testAddWindowWithSubWindowTypeByWindowContext() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        final Session session = new Session(mWm, new IWindowSessionCallback.Stub() {
            @Override
            public void onAnimatorScaleChanged(float v) throws RemoteException {
            }
        });
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                TYPE_APPLICATION_ATTACHED_DIALOG);
        params.token = windowToken.token;
        final IBinder windowContextToken = new Binder();
        params.setWindowContextToken(windowContextToken);
        doReturn(true).when(mWm.mWindowContextListenerController)
                .hasListener(eq(windowContextToken));
        doReturn(TYPE_INPUT_METHOD).when(mWm.mWindowContextListenerController)
                .getWindowType(eq(windowContextToken));

        mWm.addWindow(session, new TestIWindow(), params, View.VISIBLE, DEFAULT_DISPLAY,
                UserHandle.USER_SYSTEM, new InsetsVisibilities(), null, new InsetsState(),
                new InsetsSourceControl[0], new Rect(), new float[1]);

        verify(mWm.mWindowContextListenerController, never()).registerWindowContainerListener(any(),
                any(), anyInt(), anyInt(), any());
    }

    @Test
    public void testSetInTouchMode_instrumentedProcessGetPermissionToSwitchTouchMode() {
        boolean currentTouchMode = mWm.getInTouchMode();
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        mWm.setInTouchMode(!currentTouchMode);

        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ true);
    }

    @Test
    public void testSetInTouchMode_nonInstrumentedProcessDontGetPermissionToSwitchTouchMode() {
        boolean currentTouchMode = mWm.getInTouchMode();
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(false);

        mWm.setInTouchMode(!currentTouchMode);

        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ false);
    }

    @Test
    public void testGetTaskWindowContainerTokenForLaunchCookie_nullCookie() {
        WindowContainerToken wct = mWm.getTaskWindowContainerTokenForLaunchCookie(null);
        assertThat(wct).isNull();
    }

    @Test
    public void testGetTaskWindowContainerTokenForLaunchCookie_invalidCookie() {
        Binder cookie = new Binder("test cookie");
        WindowContainerToken wct = mWm.getTaskWindowContainerTokenForLaunchCookie(cookie);
        assertThat(wct).isNull();

        final ActivityRecord testActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .build();

        wct = mWm.getTaskWindowContainerTokenForLaunchCookie(cookie);
        assertThat(wct).isNull();
    }

    @Test
    public void testGetTaskWindowContainerTokenForLaunchCookie_validCookie() {
        final Binder cookie = new Binder("ginger cookie");
        final WindowContainerToken launchRootTask = mock(WindowContainerToken.class);
        setupActivityWithLaunchCookie(cookie, launchRootTask);

        WindowContainerToken wct = mWm.getTaskWindowContainerTokenForLaunchCookie(cookie);
        assertThat(wct).isEqualTo(launchRootTask);
    }

    @Test
    public void testGetTaskWindowContainerTokenForLaunchCookie_multipleCookies() {
        final Binder cookie1 = new Binder("ginger cookie");
        final WindowContainerToken launchRootTask1 = mock(WindowContainerToken.class);
        setupActivityWithLaunchCookie(cookie1, launchRootTask1);

        setupActivityWithLaunchCookie(new Binder("choc chip cookie"),
                mock(WindowContainerToken.class));

        setupActivityWithLaunchCookie(new Binder("peanut butter cookie"),
                mock(WindowContainerToken.class));

        WindowContainerToken wct = mWm.getTaskWindowContainerTokenForLaunchCookie(cookie1);
        assertThat(wct).isEqualTo(launchRootTask1);
    }

    @Test
    public void testGetTaskWindowContainerTokenForLaunchCookie_multipleCookies_noneValid() {
        setupActivityWithLaunchCookie(new Binder("ginger cookie"),
                mock(WindowContainerToken.class));

        setupActivityWithLaunchCookie(new Binder("choc chip cookie"),
                mock(WindowContainerToken.class));

        setupActivityWithLaunchCookie(new Binder("peanut butter cookie"),
                mock(WindowContainerToken.class));

        WindowContainerToken wct = mWm.getTaskWindowContainerTokenForLaunchCookie(
                new Binder("some other cookie"));
        assertThat(wct).isNull();
    }

    @Test
    public void testisLetterboxBackgroundMultiColored() {
        assertThat(setupLetterboxConfigurationWithBackgroundType(
                LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING)).isTrue();
        assertThat(setupLetterboxConfigurationWithBackgroundType(
                LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND)).isTrue();
        assertThat(setupLetterboxConfigurationWithBackgroundType(
                LETTERBOX_BACKGROUND_WALLPAPER)).isTrue();
        assertThat(setupLetterboxConfigurationWithBackgroundType(
                LETTERBOX_BACKGROUND_SOLID_COLOR)).isFalse();
    }

    private void setupActivityWithLaunchCookie(IBinder launchCookie, WindowContainerToken wct) {
        final WindowContainer.RemoteToken remoteToken = mock(WindowContainer.RemoteToken.class);
        when(remoteToken.toWindowContainerToken()).thenReturn(wct);
        final ActivityRecord testActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .build();
        testActivity.mLaunchCookie = launchCookie;
        testActivity.getTask().mRemoteToken = remoteToken;
    }

    private boolean setupLetterboxConfigurationWithBackgroundType(
            @LetterboxConfiguration.LetterboxBackgroundType int letterboxBackgroundType) {
        mWm.mLetterboxConfiguration.setLetterboxBackgroundType(letterboxBackgroundType);
        return mWm.isLetterboxBackgroundMultiColored();
    }
}
