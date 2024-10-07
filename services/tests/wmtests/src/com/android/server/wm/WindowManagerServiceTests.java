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

import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.permission.flags.Flags.FLAG_SENSITIVE_CONTENT_IMPROVEMENTS;
import static android.permission.flags.Flags.FLAG_SENSITIVE_CONTENT_RECENTS_SCREENSHOT_BUGFIX;
import static android.permission.flags.Flags.FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_OWN_FOCUS;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_SENSITIVE_FOR_PRIVACY;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_SPY;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.flags.Flags.FLAG_SENSITIVE_CONTENT_APP_PROTECTION;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.InputConfig;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.ArraySet;
import android.util.MergedConfiguration;
import android.view.ContentRecordingSession;
import android.view.IWindow;
import android.view.InputChannel;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.view.WindowRelayoutResult;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;
import android.window.InputTransferToken;
import android.window.ScreenCapture;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.server.wm.WindowManagerService.WindowContainerInfo;
import com.android.window.flags.Flags;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

/**
 * Build/Install/Run:
 * atest WmTests:WindowManagerServiceTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowManagerServiceTests extends WindowTestsBase {

    private final IApplicationThread mAppThread = ActivityThread.currentActivityThread()
            .getApplicationThread();

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ADD_TRUSTED_DISPLAY);

    @Rule
    public Expect mExpect = Expect.create();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @After
    public void tearDown() {
        mWm.mSensitiveContentPackages.clearBlockedApps();
    }

    @Test
    public void testIsRequestedOrientationMapped() {
        mWm.setOrientationRequestPolicy(/* isIgnoreOrientationRequestDisabled*/ true,
                /* fromOrientations */ new int[]{1}, /* toOrientations */ new int[]{2});
        assertThat(mWm.mapOrientationRequest(1)).isEqualTo(2);
        assertThat(mWm.mapOrientationRequest(3)).isEqualTo(3);

        // Mapping disabled
        mWm.setOrientationRequestPolicy(/* isIgnoreOrientationRequestDisabled*/ false,
                /* fromOrientations */ null, /* toOrientations */ null);
        assertThat(mWm.mapOrientationRequest(1)).isEqualTo(1);
        assertThat(mWm.mapOrientationRequest(3)).isEqualTo(3);
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
    public void testTrackOverlayWindow() {
        final WindowProcessController wpc = mSystemServicesTestRule.addProcess(
                "pkgName", "processName", 1000 /* pid */, Process.SYSTEM_UID);
        final Session session = createTestSession(mAtm, wpc.getPid(), wpc.mUid);
        spyOn(session);
        assertTrue(session.mCanAddInternalSystemWindow);
        final WindowState window = createWindow(null, LayoutParams.TYPE_PHONE, "win");
        session.onWindowSurfaceVisibilityChanged(window, true /* visible */);
        verify(session).setHasOverlayUi(true);
        session.onWindowSurfaceVisibilityChanged(window, false /* visible */);
        verify(session).setHasOverlayUi(false);
    }

    @Test
    public void testRelayoutExitingWindow() {
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, "appWin");
        win.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        win.mWinAnimator.mSurfaceControl = mock(SurfaceControl.class);
        spyOn(win.mTransitionController);
        doReturn(true).when(win.mTransitionController).isShellTransitionsEnabled();
        doReturn(true).when(win.mTransitionController).inTransition(
                eq(win.mActivityRecord));
        win.mViewVisibility = View.VISIBLE;
        win.mHasSurface = true;
        win.mActivityRecord.mAppStopped = true;
        mWm.mWindowMap.put(win.mClient.asBinder(), win);
        spyOn(mWm.mWindowPlacerLocked);
        // Skip unnecessary operations of relayout.
        doNothing().when(mWm.mWindowPlacerLocked).performSurfacePlacement(anyBoolean());
        final int w = 100;
        final int h = 200;
        final ClientWindowFrames outFrames = new ClientWindowFrames();
        final MergedConfiguration outConfig = new MergedConfiguration();
        final SurfaceControl outSurfaceControl = new SurfaceControl();
        final InsetsState outInsetsState = new InsetsState();
        final InsetsSourceControl.Array outControls = new InsetsSourceControl.Array();
        final WindowRelayoutResult outRelayoutResult = new WindowRelayoutResult(outFrames,
                outConfig, outSurfaceControl, outInsetsState, outControls);
        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.GONE, 0, 0, 0,
                outRelayoutResult);
        // The window is in transition, so its destruction is deferred.
        assertTrue(win.mAnimatingExit);
        assertFalse(win.mDestroying);
        assertTrue(win.mTransitionController.mAnimatingExitWindows.contains(win));

        win.mAnimatingExit = false;
        win.mViewVisibility = View.VISIBLE;
        win.mActivityRecord.setVisibleRequested(false);
        win.mActivityRecord.setVisible(false);
        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.GONE, 0, 0, 0,
                outRelayoutResult);
        // Because the window is already invisible, it doesn't need to apply exiting animation
        // and WMS#tryStartExitingAnimation() will destroy the surface directly.
        assertFalse(win.mAnimatingExit);
        assertFalse(win.mHasSurface);
        assertNull(win.mWinAnimator.mSurfaceControl);

        // Invisible requested activity should not get the last config even if its view is visible.
        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outRelayoutResult);
        assertEquals(0, outConfig.getMergedConfiguration().densityDpi);
        // Non activity window can still get the last config.
        win.mActivityRecord = null;
        win.fillClientWindowFramesAndConfiguration(outFrames, outConfig,
                null /* outActivityWindowInfo*/, false /* useLatestConfig */,
                true /* relayoutVisible */);
        assertEquals(win.getConfiguration().densityDpi,
                outConfig.getMergedConfiguration().densityDpi);
    }

    @Test
    public void testRelayout_firstLayout_dwpcHelperCalledWithCorrectFlags() {
        // When doing the first layout, the initial flags should be reported as changed to
        // keepActivityOnWindowFlagsChanged.
        testRelayoutFlagChanges(
                /*firstRelayout=*/ true,
                /*startFlags=*/ FLAG_SECURE,
                /*startPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*newFlags=*/ FLAG_SECURE,
                /*newPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedChangedFlags=*/ FLAG_SECURE,
                /*expectedChangedPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedFlagsValue=*/ FLAG_SECURE,
                /*expectedPrivateFlagsValue=*/ PRIVATE_FLAG_TRUSTED_OVERLAY);
    }

    @Test
    public void testRelayout_secondLayoutFlagAdded_dwpcHelperCalledWithCorrectFlags() {
        testRelayoutFlagChanges(
                /*firstRelayout=*/ false,
                /*startFlags=*/ 0,
                /*startPrivateFlags=*/ 0,
                /*newFlags=*/ FLAG_SECURE,
                /*newPrivateFlags=*/ 0,
                /*expectedChangedFlags=*/ FLAG_SECURE,
                /*expectedChangedPrivateFlags=*/ 0,
                /*expectedFlagsValue=*/ FLAG_SECURE,
                /*expectedPrivateFlagsValue=*/ 0);
    }

    @Test
    public void testRelayout_secondLayoutMultipleFlagsAddOne_dwpcHelperCalledWithCorrectFlags() {
        testRelayoutFlagChanges(
                /*firstRelayout=*/ false,
                /*startFlags=*/ FLAG_NOT_FOCUSABLE,
                /*startPrivateFlags=*/ 0,
                /*newFlags=*/ FLAG_SECURE | FLAG_NOT_FOCUSABLE,
                /*newPrivateFlags=*/ 0,
                /*expectedChangedFlags=*/ FLAG_SECURE,
                /*expectedChangedPrivateFlags=*/ 0,
                /*expectedFlagsValue=*/ FLAG_SECURE | FLAG_NOT_FOCUSABLE,
                /*expectedPrivateFlagsValue=*/ 0);
    }

    @Test
    public void testRelayout_secondLayoutPrivateFlagAdded_dwpcHelperCalledWithCorrectFlags() {
        testRelayoutFlagChanges(
                /*firstRelayout=*/ false,
                /*startFlags=*/ 0,
                /*startPrivateFlags=*/ 0,
                /*newFlags=*/ 0,
                /*newPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedChangedFlags=*/ 0,
                /*expectedChangedPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedFlagsValue=*/ 0,
                /*expectedPrivateFlagsValue=*/ PRIVATE_FLAG_TRUSTED_OVERLAY);
    }

    @Test
    public void testRelayout_secondLayoutFlagsRemoved_dwpcHelperCalledWithCorrectFlags() {
        testRelayoutFlagChanges(
                /*firstRelayout=*/ false,
                /*startFlags=*/ FLAG_SECURE,
                /*startPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*newFlags=*/ 0,
                /*newPrivateFlags=*/ 0,
                /*expectedChangedFlags=*/ FLAG_SECURE,
                /*expectedChangedPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedFlagsValue=*/ 0,
                /*expectedPrivateFlagsValue=*/ 0);
    }

    // Helper method to test relayout of a window, either for the initial layout, or a subsequent
    // one, and makes sure that the flags and private flags changes and final values are properly
    // reported to mDwpcHelper.keepActivityOnWindowFlagsChanged.
    private void testRelayoutFlagChanges(boolean firstRelayout, int startFlags,
            int startPrivateFlags, int newFlags, int newPrivateFlags, int expectedChangedFlags,
            int expectedChangedPrivateFlags, int expectedFlagsValue,
            int expectedPrivateFlagsValue) {
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, "appWin");
        win.mRelayoutCalled = !firstRelayout;
        mWm.mWindowMap.put(win.mClient.asBinder(), win);
        spyOn(mDisplayContent.mDwpcHelper);
        when(mDisplayContent.mDwpcHelper.hasController()).thenReturn(true);

        win.mAttrs.flags = startFlags;
        win.mAttrs.privateFlags = startPrivateFlags;

        LayoutParams newParams = new LayoutParams();
        newParams.copyFrom(win.mAttrs);
        newParams.flags = newFlags;
        newParams.privateFlags = newPrivateFlags;

        int seq = 1;
        if (!firstRelayout) {
            win.mRelayoutSeq = 1;
            seq = 2;
        }
        mWm.relayoutWindow(win.mSession, win.mClient, newParams, 100, 200, View.VISIBLE, 0, seq,
                0, new WindowRelayoutResult());

        ArgumentCaptor<Integer> changedFlags = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> changedPrivateFlags = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> flagsValue = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> privateFlagsValue = ArgumentCaptor.forClass(Integer.class);

        verify(mDisplayContent.mDwpcHelper).keepActivityOnWindowFlagsChanged(
                any(ActivityInfo.class), changedFlags.capture(), changedPrivateFlags.capture(),
                flagsValue.capture(), privateFlagsValue.capture());

        assertThat(changedFlags.getValue()).isEqualTo(expectedChangedFlags);
        assertThat(changedPrivateFlags.getValue()).isEqualTo(expectedChangedPrivateFlags);
        assertThat(flagsValue.getValue()).isEqualTo(expectedFlagsValue);
        assertThat(privateFlagsValue.getValue()).isEqualTo(expectedPrivateFlagsValue);
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

        mWm.attachWindowContextToWindowToken(mAppThread, new Binder(), new Binder());

        verify(mWm.mWindowContextListenerController, never()).getWindowType(any());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachWindowContextToWindowToken_InvalidWindowType_ThrowException() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(INVALID_WINDOW_TYPE).when(mWm.mWindowContextListenerController)
                .getWindowType(any());

        mWm.attachWindowContextToWindowToken(mAppThread, new Binder(), windowToken.token);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachWindowContextToWindowToken_DifferentWindowType_ThrowException() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(TYPE_APPLICATION).when(mWm.mWindowContextListenerController)
                .getWindowType(any());

        mWm.attachWindowContextToWindowToken(mAppThread, new Binder(), windowToken.token);
    }

    @Test
    public void testAttachWindowContextToWindowToken_CallerNotValid_EarlyReturn() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(TYPE_INPUT_METHOD).when(mWm.mWindowContextListenerController)
                .getWindowType(any());
        doReturn(false).when(mWm.mWindowContextListenerController)
                .assertCallerCanModifyListener(any(), anyBoolean(), anyInt());

        mWm.attachWindowContextToWindowToken(mAppThread, new Binder(), windowToken.token);

        verify(mWm.mWindowContextListenerController, never()).registerWindowContainerListener(
                any(), any(), any(), anyInt(), any(), anyBoolean());
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
        mWm.attachWindowContextToWindowToken(mAppThread, clientToken, windowToken.token);
        final WindowProcessController wpc = mAtm.getProcessController(mAppThread);
        verify(mWm.mWindowContextListenerController).registerWindowContainerListener(wpc,
                clientToken, windowToken, TYPE_INPUT_METHOD, windowToken.mOptions,
                false /* shouldDispatchConfigWhenRegistering */);
    }

    @Test
    public void testAddWindowWithSubWindowTypeByWindowContext() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowState parentWin = createWindow(null, TYPE_INPUT_METHOD, "ime");
        final IBinder parentToken = parentWin.mToken.token;
        parentWin.mAttrs.token = parentToken;
        mWm.mWindowMap.put(parentToken, parentWin);
        final Session session = parentWin.mSession;
        session.onWindowAdded(parentWin);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                TYPE_APPLICATION_ATTACHED_DIALOG);
        params.token = parentToken;
        params.setTitle("attached-dialog");
        final IBinder windowContextToken = new Binder();
        params.setWindowContextToken(windowContextToken);
        doReturn(true).when(mWm.mWindowContextListenerController)
                .hasListener(eq(windowContextToken));
        doReturn(TYPE_INPUT_METHOD).when(mWm.mWindowContextListenerController)
                .getWindowType(eq(windowContextToken));
        doReturn(true).when(mWm.mUmInternal).isUserVisible(anyInt(), anyInt());

        mWm.addWindow(session, new TestIWindow(), params, View.VISIBLE, DEFAULT_DISPLAY,
                UserHandle.USER_SYSTEM, WindowInsets.Type.defaultVisible(), null, new InsetsState(),
                new InsetsSourceControl.Array(), new Rect(), new float[1]);

        verify(mWm.mWindowContextListenerController, never()).registerWindowContainerListener(any(),
                any(), any(), anyInt(), any(), anyBoolean());

        // Even if the given display id is INVALID_DISPLAY, the specified params.token should be
        // able to map the corresponding display.
        final int result = mWm.addWindow(
                session, new TestIWindow(), params, View.VISIBLE, INVALID_DISPLAY,
                UserHandle.USER_SYSTEM, WindowInsets.Type.defaultVisible(), null, new InsetsState(),
                new InsetsSourceControl.Array(), new Rect(), new float[1]);
        assertThat(result).isAtLeast(WindowManagerGlobal.ADD_OKAY);

        assertTrue(parentWin.hasChild());
        assertTrue(parentWin.isAttached());
        session.binderDied();
        assertFalse(parentWin.hasChild());
        assertFalse(parentWin.isAttached());
    }

    @Test
    public void testIsInTouchMode_returnsDefaultInTouchModeForinexistingDisplay() {
        assertThat(mWm.isInTouchMode(INVALID_DISPLAY)).isEqualTo(
                mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_defaultInTouchMode));
    }

    @Test
    public void testSetInTouchMode_instrumentedProcessGetPermissionToSwitchTouchMode() {
        // Enable global touch mode
        mWm.mPerDisplayFocusEnabled = true;

        // Get current touch mode state and setup WMS to run setInTouchMode
        boolean currentTouchMode = mWm.isInTouchMode(DEFAULT_DISPLAY);
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        mWm.setInTouchMode(!currentTouchMode, DEFAULT_DISPLAY);

        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ true,
                DEFAULT_DISPLAY);
    }

    @Test
    public void testSetInTouchMode_nonInstrumentedProcessDontGetPermissionToSwitchTouchMode() {
        // Enable global touch mode
        mWm.mPerDisplayFocusEnabled = true;

        // Get current touch mode state and setup WMS to run setInTouchMode
        boolean currentTouchMode = mWm.isInTouchMode(DEFAULT_DISPLAY);
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(false);

        mWm.setInTouchMode(!currentTouchMode, DEFAULT_DISPLAY);

        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ false,
                DEFAULT_DISPLAY);
    }

    @Test
    public void testSetInTouchMode_multiDisplay_globalTouchModeUpdate() {
        // Disable global touch mode
        mWm.mPerDisplayFocusEnabled = false;

        // Create one extra display
        final DisplayContent display = createMockSimulatedDisplay();
        display.getDisplayInfo().flags &= ~FLAG_OWN_FOCUS;
        final DisplayContent displayOwnTouchMode = createMockSimulatedDisplay();
        displayOwnTouchMode.getDisplayInfo().flags |= FLAG_OWN_FOCUS;
        final int numberOfDisplays = mWm.mRoot.mChildren.size();
        assertThat(numberOfDisplays).isAtLeast(3);
        final int numberOfGlobalTouchModeDisplays = (int) mWm.mRoot.mChildren.stream()
                .filter(d -> (d.getDisplayInfo().flags & FLAG_OWN_FOCUS) == 0)
                .count();
        assertThat(numberOfGlobalTouchModeDisplays).isAtLeast(2);

        // Get current touch mode state and setup WMS to run setInTouchMode
        boolean currentTouchMode = mWm.isInTouchMode(DEFAULT_DISPLAY);
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        mWm.setInTouchMode(!currentTouchMode, DEFAULT_DISPLAY);

        verify(mWm.mInputManager, times(numberOfGlobalTouchModeDisplays)).setInTouchMode(
                eq(!currentTouchMode), eq(callingPid), eq(callingUid),
                /* hasPermission= */ eq(true), /* displayId= */ anyInt());
    }

    @Test
    public void testSetInTouchMode_multiDisplay_singleDisplayTouchModeUpdate() {
        // Enable global touch mode
        mWm.mPerDisplayFocusEnabled = true;

        // Create one extra display
        final DisplayContent virtualDisplay = createMockSimulatedDisplay();
        virtualDisplay.getDisplayInfo().flags &= ~FLAG_OWN_FOCUS;
        final int numberOfDisplays = mWm.mRoot.mChildren.size();
        assertThat(numberOfDisplays).isAtLeast(2);

        // Get current touch mode state and setup WMS to run setInTouchMode
        boolean currentTouchMode = mWm.isInTouchMode(DEFAULT_DISPLAY);
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        mWm.setInTouchMode(!currentTouchMode, virtualDisplay.mDisplayId);

        // Ensure that new display touch mode state has changed.
        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ true,
                virtualDisplay.mDisplayId);

        // Disable global touch mode and make the virtual display own focus.
        mWm.mPerDisplayFocusEnabled = false;
        virtualDisplay.getDisplayInfo().flags |= FLAG_OWN_FOCUS;
        clearInvocations(mWm.mInputManager);
        mWm.setInTouchMode(!currentTouchMode, virtualDisplay.mDisplayId);

        // Ensure that new display touch mode state has changed.
        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ true,
                virtualDisplay.mDisplayId);
    }

    @Test
    public void testSetInTouchModeOnAllDisplays() {
        // Create a couple of extra displays.
        // setInTouchModeOnAllDisplays should ignore the ownFocus setting.
        final DisplayContent display = createMockSimulatedDisplay();
        display.getDisplayInfo().flags &= ~FLAG_OWN_FOCUS;
        final DisplayContent displayOwnTouchMode = createMockSimulatedDisplay();
        displayOwnTouchMode.getDisplayInfo().flags |= FLAG_OWN_FOCUS;

        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(true).when(mWm.mInputManager).setInTouchMode(anyBoolean(), anyInt(),
                anyInt(), anyBoolean(), anyInt());
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        final Runnable verification = () -> {
            for (boolean inTouchMode : new boolean[] { true, false }) {
                mWm.setInTouchModeOnAllDisplays(inTouchMode);
                for (int i = 0; i < mRootWindowContainer.getChildCount(); ++i) {
                    final DisplayContent dc = mRootWindowContainer.getChildAt(i);
                    // All displays that are not already in the desired touch mode are requested to
                    // change their touch mode.
                    if (dc.isInTouchMode() != inTouchMode) {
                        verify(mWm.mInputManager, description("perDisplayFocusEnabled="
                                + mWm.mPerDisplayFocusEnabled)).setInTouchMode(true,
                                callingPid, callingUid, /* hasPermission= */ true, dc.mDisplayId);
                    }
                }
            }
        };

        mWm.mPerDisplayFocusEnabled = false;
        verification.run();

        clearInvocations(mWm.mInputManager);
        mWm.mPerDisplayFocusEnabled = true;
        verification.run();
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_invalidCookie() {
        Binder cookie = new Binder("test cookie");
        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(cookie));
        assertThat(wci).isNull();

        final ActivityRecord testActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .build();

        wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(cookie));
        assertThat(wci).isNull();
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_validCookie() {
        final Binder cookie = new Binder("ginger cookie");
        final WindowContainerToken launchRootTask = mock(WindowContainerToken.class);
        final int uid = 123;
        setupActivityWithLaunchCookie(cookie, launchRootTask, uid);

        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(cookie));
        mExpect.that(wci.getToken()).isEqualTo(launchRootTask);
        mExpect.that(wci.getUid()).isEqualTo(uid);
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_validTaskId() {
        final WindowContainerToken launchRootTask = mock(WindowContainerToken.class);
        final WindowContainer.RemoteToken remoteToken = mock(WindowContainer.RemoteToken.class);
        when(remoteToken.toWindowContainerToken()).thenReturn(launchRootTask);

        final int uid = 123;
        final ActivityRecord testActivity =
                new ActivityBuilder(mAtm).setCreateTask(true).setUid(uid).build();
        testActivity.mLaunchCookie = null;
        testActivity.getTask().mRemoteToken = remoteToken;

        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(
                        new Binder("cookie"), testActivity.getTask().mTaskId));
        mExpect.that(wci.getToken()).isEqualTo(launchRootTask);
        mExpect.that(wci.getUid()).isEqualTo(uid);
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_multipleCookies() {
        final Binder cookie1 = new Binder("ginger cookie");
        final WindowContainerToken launchRootTask1 = mock(WindowContainerToken.class);
        final int uid1 = 123;
        setupActivityWithLaunchCookie(cookie1, launchRootTask1, uid1);

        setupActivityWithLaunchCookie(new Binder("choc chip cookie"),
                mock(WindowContainerToken.class), /* uid= */ 456);

        setupActivityWithLaunchCookie(new Binder("peanut butter cookie"),
                mock(WindowContainerToken.class), /* uid= */ 789);

        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(cookie1));
        mExpect.that(wci.getToken()).isEqualTo(launchRootTask1);
        mExpect.that(wci.getUid()).isEqualTo(uid1);
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_multipleCookies_noneValid() {
        setupActivityWithLaunchCookie(new Binder("ginger cookie"),
                mock(WindowContainerToken.class), /* uid= */ 123);

        setupActivityWithLaunchCookie(new Binder("choc chip cookie"),
                mock(WindowContainerToken.class), /* uid= */ 456);

        setupActivityWithLaunchCookie(new Binder("peanut butter cookie"),
                mock(WindowContainerToken.class), /* uid= */ 789);

        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(new Binder("some other cookie")));
        assertThat(wci).isNull();
    }

    @Test
    public void setContentRecordingSession_sessionNull_returnsTrue() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);

        boolean result = wmInternal.setContentRecordingSession(/* incomingSession= */ null);

        assertThat(result).isTrue();
    }

    @Test
    public void setContentRecordingSession_sessionContentDisplay_returnsTrue() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);

        boolean result = wmInternal.setContentRecordingSession(session);

        assertThat(result).isTrue();
    }

    @Test
    public void setContentRecordingSession_sessionContentTask_noMatchingTask_returnsFalse() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        IBinder launchCookie = new Binder();
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(launchCookie);

        boolean result = wmInternal.setContentRecordingSession(session);

        assertThat(result).isFalse();
    }

    @Test
    public void setContentRecordingSession_sessionContentTask_matchingTask_returnsTrue() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        ActivityRecord activityRecord = createActivityRecord(createTask(mDefaultDisplay));
        activityRecord.mLaunchCookie = new Binder();
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(
                activityRecord.mLaunchCookie);

        boolean result = wmInternal.setContentRecordingSession(session);

        assertThat(result).isTrue();
    }

    @Test
    public void setContentRecordingSession_matchingTask_mutatesSessionWithWindowContainerInfo() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        Task task = createTask(mDefaultDisplay);
        ActivityRecord activityRecord = createActivityRecord(task);
        activityRecord.mLaunchCookie = new Binder();
        ContentRecordingSession session =
                ContentRecordingSession.createTaskSession(activityRecord.mLaunchCookie);

        wmInternal.setContentRecordingSession(session);

        mExpect.that(session.getTokenToRecord())
                .isEqualTo(task.mRemoteToken.toWindowContainerToken().asBinder());
        mExpect.that(session.getTargetUid()).isEqualTo(task.effectiveUid);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION)
    public void shouldBlockScreenCaptureForNotificationApps() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        verify(mWm).refreshScreenCaptureDisabled();

        // window client token parameter is ignored for this feature.
        assertTrue(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, new Binder()));
        assertFalse(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId2, new Binder()));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_CONTENT_APP_PROTECTION)
    public void shouldBlockScreenCaptureForSensitiveContentOnScreenApps() {
        String testPackage = "test";
        int ownerId1 = 20;
        final IBinder windowClientToken = new Binder("window client token");
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1, windowClientToken);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        verify(mWm).refreshScreenCaptureDisabled();

        assertTrue(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, windowClientToken));
        assertFalse(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, new Binder()));
    }

    @Test
    @RequiresFlagsEnabled(
            {FLAG_SENSITIVE_CONTENT_APP_PROTECTION, FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION})
    public void shouldBlockScreenCaptureForApps() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        final IBinder windowClientToken = new Binder("window client token");
        PackageInfo blockedPackage1 = new PackageInfo(testPackage, ownerId1);
        PackageInfo blockedPackage2 = new PackageInfo(testPackage, ownerId1, windowClientToken);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage1);
        blockedPackages.add(blockedPackage2);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        verify(mWm).refreshScreenCaptureDisabled();

        assertTrue(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, windowClientToken));
        assertTrue(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, new Binder()));
        assertFalse(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId2, new Binder()));
    }

    @Test
    public void addBlockScreenCaptureForApps_duplicate_verifyNoRefresh() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);

        verify(mWm, times(1)).refreshScreenCaptureDisabled();
    }

    @Test
    public void addBlockScreenCaptureForApps_notDuplicate_verifyRefresh() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        PackageInfo blockedPackage2 = new PackageInfo(testPackage, ownerId2);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);
        ArraySet<PackageInfo> blockedPackages2 = new ArraySet();
        blockedPackages2.add(blockedPackage);
        blockedPackages2.add(blockedPackage2);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages2);

        verify(mWm, times(2)).refreshScreenCaptureDisabled();
    }

    @Test
    @RequiresFlagsEnabled(
            {FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION, FLAG_SENSITIVE_CONTENT_IMPROVEMENTS,
                    FLAG_SENSITIVE_CONTENT_RECENTS_SCREENSHOT_BUGFIX})
    public void addBlockScreenCaptureForApps_appNotInForeground_invalidateSnapshot() {
        spyOn(mWm.mTaskSnapshotController);

        // createAppWindow uses package name of "test" and uid of "0"
        String testPackage = "test";
        int ownerId1 = 0;

        final Task task = createTask(mDisplayContent);
        final WindowState win = createAppWindow(task, ACTIVITY_TYPE_STANDARD, "appWindow");
        mWm.mWindowMap.put(win.mClient.asBinder(), win);
        final ActivityRecord activity = win.mActivityRecord;
        activity.setVisibleRequested(false);
        activity.setVisible(false);
        win.setHasSurface(false);

        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);

        verify(mWm.mTaskSnapshotController).removeAndDeleteSnapshot(anyInt(), eq(ownerId1));
    }

    @Test
    public void clearBlockedApps_clearsCache() {
        String testPackage = "test";
        int ownerId1 = 20;
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        wmInternal.clearBlockedApps();
        verify(mWm, times(2)).refreshScreenCaptureDisabled();
    }

    @Test
    public void clearBlockedApps_alreadyEmpty_verifyNoRefresh() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.clearBlockedApps();

        verify(mWm, never()).refreshScreenCaptureDisabled();
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

    @Test
    public void testCaptureDisplay() {
        Rect displayBounds = new Rect(0, 0, 100, 200);
        spyOn(mDisplayContent);
        when(mDisplayContent.getBounds()).thenReturn(displayBounds);

        // Null captureArgs
        ScreenCapture.LayerCaptureArgs resultingArgs =
                mWm.getCaptureArgs(DEFAULT_DISPLAY, null /* captureArgs */);
        assertEquals(displayBounds, resultingArgs.mSourceCrop);

        // Non null captureArgs, didn't set rect
        ScreenCapture.CaptureArgs captureArgs = new ScreenCapture.CaptureArgs.Builder<>().build();
        resultingArgs = mWm.getCaptureArgs(DEFAULT_DISPLAY, captureArgs);
        assertEquals(displayBounds, resultingArgs.mSourceCrop);

        // Non null captureArgs, invalid rect
        captureArgs = new ScreenCapture.CaptureArgs.Builder<>()
                .setSourceCrop(new Rect(0, 0, -1, -1))
                .build();
        resultingArgs = mWm.getCaptureArgs(DEFAULT_DISPLAY, captureArgs);
        assertEquals(displayBounds, resultingArgs.mSourceCrop);

        // Non null captureArgs, null rect
        captureArgs = new ScreenCapture.CaptureArgs.Builder<>()
                .setSourceCrop(null)
                .build();
        resultingArgs = mWm.getCaptureArgs(DEFAULT_DISPLAY, captureArgs);
        assertEquals(displayBounds, resultingArgs.mSourceCrop);

        // Non null captureArgs, valid rect
        Rect validRect = new Rect(0, 0, 10, 50);
        captureArgs = new ScreenCapture.CaptureArgs.Builder<>()
                .setSourceCrop(validRect)
                .build();
        resultingArgs = mWm.getCaptureArgs(DEFAULT_DISPLAY, captureArgs);
        assertEquals(validRect, resultingArgs.mSourceCrop);
    }

    @Test
    public void testGrantInputChannel_sanitizeSpyWindowForApplications() {
        final Session session = mock(Session.class);
        final int callingUid = Process.FIRST_APPLICATION_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final InputChannel inputChannel = new InputChannel();
        assertThrows(IllegalArgumentException.class, () ->
                mWm.grantInputChannel(session, callingUid, callingPid, DEFAULT_DISPLAY,
                        surfaceControl, window, null /* hostInputToken */, FLAG_NOT_FOCUSABLE,
                        PRIVATE_FLAG_TRUSTED_OVERLAY, INPUT_FEATURE_SPY, TYPE_APPLICATION,
                        null /* windowToken */, inputTransferToken, "TestInputChannel",
                        inputChannel));
    }

    @Test
    public void testGrantInputChannel_allowSpyWindowForInputMonitorPermission() {
        final Session session = mock(Session.class);
        final int callingUid = Process.SYSTEM_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final InputChannel inputChannel = new InputChannel();
        mWm.grantInputChannel(session, callingUid, callingPid, DEFAULT_DISPLAY, surfaceControl,
                window, null /* hostInputToken */, FLAG_NOT_FOCUSABLE, PRIVATE_FLAG_TRUSTED_OVERLAY,
                INPUT_FEATURE_SPY, TYPE_APPLICATION, null /* windowToken */, inputTransferToken,
                "TestInputChannel", inputChannel);

        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SPY) == InputConfig.SPY));
    }

    @Test
    public void testUpdateInputChannel_sanitizeSpyWindowForApplications() {
        final Session session = mock(Session.class);
        final int callingUid = Process.FIRST_APPLICATION_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final InputChannel inputChannel = new InputChannel();
        mWm.grantInputChannel(session, callingUid, callingPid, DEFAULT_DISPLAY, surfaceControl,
                window, null /* hostInputToken */, FLAG_NOT_FOCUSABLE, PRIVATE_FLAG_TRUSTED_OVERLAY,
                0 /* inputFeatures */, TYPE_APPLICATION, null /* windowToken */, inputTransferToken,
                "TestInputChannel", inputChannel);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SPY) == 0));

        assertThrows(IllegalArgumentException.class, () ->
                mWm.updateInputChannel(inputChannel.getToken(), DEFAULT_DISPLAY, surfaceControl,
                        FLAG_NOT_FOCUSABLE, PRIVATE_FLAG_TRUSTED_OVERLAY, INPUT_FEATURE_SPY,
                        null /* region */));
    }

    @Test
    public void testUpdateInputChannel_allowSpyWindowForInputMonitorPermission() {
        final Session session = mock(Session.class);
        final int callingUid = Process.SYSTEM_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final InputChannel inputChannel = new InputChannel();
        mWm.grantInputChannel(session, callingUid, callingPid, DEFAULT_DISPLAY, surfaceControl,
                window, null /* hostInputToken */, FLAG_NOT_FOCUSABLE, PRIVATE_FLAG_TRUSTED_OVERLAY,
                0 /* inputFeatures */, TYPE_APPLICATION, null /* windowToken */, inputTransferToken,
                "TestInputChannel", inputChannel);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SPY) == 0));

        mWm.updateInputChannel(inputChannel.getToken(), DEFAULT_DISPLAY, surfaceControl,
                FLAG_NOT_FOCUSABLE, PRIVATE_FLAG_TRUSTED_OVERLAY, INPUT_FEATURE_SPY,
                null /* region */);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SPY) == InputConfig.SPY));
    }

    @Test
    public void testUpdateInputChannel_sanitizeInputFeatureSensitive_forUntrustedWindows() {
        final Session session = mock(Session.class);
        final int callingUid = Process.FIRST_APPLICATION_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final InputChannel inputChannel = new InputChannel();
        mWm.grantInputChannel(session, callingUid, callingPid, DEFAULT_DISPLAY, surfaceControl,
                window, null /* hostInputToken */, FLAG_NOT_FOCUSABLE, 0 /* privateFlags */,
                INPUT_FEATURE_SENSITIVE_FOR_PRIVACY, TYPE_APPLICATION, null /* windowToken */,
                inputTransferToken,
                "TestInputChannel", inputChannel);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SENSITIVE_FOR_PRIVACY) == 0));

        mWm.updateInputChannel(inputChannel.getToken(), DEFAULT_DISPLAY, surfaceControl,
                FLAG_NOT_FOCUSABLE, PRIVATE_FLAG_TRUSTED_OVERLAY,
                INPUT_FEATURE_SENSITIVE_FOR_PRIVACY,
                null /* region */);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SENSITIVE_FOR_PRIVACY) != 0));
    }

    @RequiresFlagsDisabled(Flags.FLAG_ALWAYS_DRAW_MAGNIFICATION_FULLSCREEN_BORDER)
    @Test
    public void testDrawMagnifiedViewport() {
        final int displayId = mDisplayContent.mDisplayId;
        // Use real surface, so ViewportWindow's BlastBufferQueue can be created.
        final ArrayList<SurfaceControl> surfaceControls = new ArrayList<>();
        mWm.mSurfaceControlFactory = () -> new SurfaceControl.Builder() {
            @Override
            public SurfaceControl build() {
                final SurfaceControl sc = super.build();
                surfaceControls.add(sc);
                return sc;
            }
        };
        mWm.mAccessibilityController.setMagnificationCallbacks(displayId,
                mock(WindowManagerInternal.MagnificationCallbacks.class));
        final boolean[] lockCanvasInWmLock = { false };
        final Surface surface = mWm.mAccessibilityController.forceShowMagnifierSurface(displayId);
        spyOn(surface);
        doAnswer(invocationOnMock -> {
            lockCanvasInWmLock[0] |= Thread.holdsLock(mWm.mGlobalLock);
            invocationOnMock.callRealMethod();
            return null;
        }).when(surface).lockCanvas(any());
        mWm.mAccessibilityController
                .recomputeMagnifiedRegionAndDrawMagnifiedRegionBorderIfNeeded(displayId);
        waitUntilHandlersIdle();
        try {
            verify(surface).lockCanvas(any());

            clearInvocations(surface);
            // Invalidate and redraw.
            mWm.mAccessibilityController.onDisplaySizeChanged(mDisplayContent);
            mWm.mAccessibilityController
                    .recomputeMagnifiedRegionAndDrawMagnifiedRegionBorderIfNeeded(displayId);
            // Turn off magnification to release surface.
            mWm.mAccessibilityController.setMagnificationCallbacks(displayId, null);
            waitUntilHandlersIdle();
            // lockCanvas must not be called after releasing.
            verify(surface, never()).lockCanvas(any());
            verify(surface).release();
            assertFalse(lockCanvasInWmLock[0]);
        } finally {
            for (int i = surfaceControls.size() - 1; i >= 0; --i) {
                surfaceControls.get(i).release();
            }
        }
    }

    @Test
    public void testRequestKeyboardShortcuts_noWindow() {
        doNothing().when(mWm.mContext).enforceCallingOrSelfPermission(anyString(), anyString());
        doReturn(null).when(mWm).getFocusedWindowLocked();
        doReturn(null).when(mWm.mRoot).getCurrentInputMethodWindow();

        TestResultReceiver receiver = new TestResultReceiver();
        mWm.requestAppKeyboardShortcuts(receiver, 0);
        assertNotNull(receiver.resultData);
        assertTrue(receiver.resultData.isEmpty());

        receiver = new TestResultReceiver();
        mWm.requestImeKeyboardShortcuts(receiver, 0);
        assertNotNull(receiver.resultData);
        assertTrue(receiver.resultData.isEmpty());
    }

    @Test
    public void testRequestKeyboardShortcuts() throws RemoteException {
        final IWindow window = mock(IWindow.class);
        final IBinder binder = mock(IBinder.class);
        doReturn(binder).when(window).asBinder();
        final WindowState windowState =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "appWin", window);
        doNothing().when(mWm.mContext).enforceCallingOrSelfPermission(anyString(), anyString());
        doReturn(windowState).when(mWm).getFocusedWindowLocked();
        doReturn(windowState).when(mWm.mRoot).getCurrentInputMethodWindow();

        TestResultReceiver receiver = new TestResultReceiver();
        mWm.requestAppKeyboardShortcuts(receiver, 0);
        mWm.requestImeKeyboardShortcuts(receiver, 0);
        verify(window, times(2)).requestAppKeyboardShortcuts(receiver, 0);
    }

    @Test
    public void testReportSystemGestureExclusionChanged_invalidWindow() {
        final Session session = mock(Session.class);
        final IWindow window = mock(IWindow.class);
        final IBinder binder = mock(IBinder.class);
        doReturn(binder).when(window).asBinder();

        // No exception even if the window doesn't exist
        mWm.reportSystemGestureExclusionChanged(session, window, new ArrayList<>());
    }

    @Test
    public void testReportKeepClearAreasChanged_invalidWindow() {
        final Session session = mock(Session.class);
        final IWindow window = mock(IWindow.class);
        final IBinder binder = mock(IBinder.class);
        doReturn(binder).when(window).asBinder();

        // No exception even if the window doesn't exist
        mWm.reportKeepClearAreasChanged(session, window, new ArrayList<>(), new ArrayList<>());
    }

    @Test
    public void testRelayout_appWindowSendActivityWindowInfo() {
        // Skip unnecessary operations of relayout.
        spyOn(mWm.mWindowPlacerLocked);
        doNothing().when(mWm.mWindowPlacerLocked).performSurfacePlacement(anyBoolean());

        final Task task = createTask(mDisplayContent);
        final WindowState win = createAppWindow(task, ACTIVITY_TYPE_STANDARD, "appWindow");
        mWm.mWindowMap.put(win.mClient.asBinder(), win);

        final int w = 100;
        final int h = 200;
        final ClientWindowFrames outFrames = new ClientWindowFrames();
        final MergedConfiguration outConfig = new MergedConfiguration();
        final SurfaceControl outSurfaceControl = new SurfaceControl();
        final InsetsState outInsetsState = new InsetsState();
        final InsetsSourceControl.Array outControls = new InsetsSourceControl.Array();
        final WindowRelayoutResult outRelayoutResult = new WindowRelayoutResult(outFrames,
                outConfig, outSurfaceControl, outInsetsState, outControls);

        final ActivityRecord activity = win.mActivityRecord;
        final ActivityWindowInfo expectedInfo = new ActivityWindowInfo();
        expectedInfo.set(true, new Rect(0, 0, 1000, 2000), new Rect(0, 0, 500, 2000));
        doReturn(expectedInfo).when(activity).getActivityWindowInfo();
        activity.setVisibleRequested(false);
        activity.setVisible(false);

        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outRelayoutResult);

        // No latest reported value, so return empty when activity is invisible
        final ActivityWindowInfo activityWindowInfo = outRelayoutResult.activityWindowInfo;
        assertEquals(new ActivityWindowInfo(), activityWindowInfo);

        activity.setVisibleRequested(true);
        activity.setVisible(true);

        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outRelayoutResult);

        // Report the latest when activity is visible.
        final ActivityWindowInfo activityWindowInfo2 = outRelayoutResult.activityWindowInfo;
        assertEquals(expectedInfo, activityWindowInfo2);

        expectedInfo.set(false, new Rect(0, 0, 1000, 2000), new Rect(0, 0, 1000, 2000));
        activity.setVisibleRequested(false);
        activity.setVisible(false);

        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outRelayoutResult);

        // Report the last reported value when activity is invisible.
        final ActivityWindowInfo activityWindowInfo3 = outRelayoutResult.activityWindowInfo;
        assertEquals(activityWindowInfo2, activityWindowInfo3);
    }

    @Test
    public void testAddOverlayWindowToUnassignedDisplay_notAllowed_ForVisibleBackgroundUsers() {
        doReturn(true).when(() -> UserManager.isVisibleBackgroundUsersEnabled());
        int uid = 100000; // uid for non-system user
        Session session = createTestSession(mAtm, 1234 /* pid */, uid);
        DisplayContent dc = createNewDisplay();
        int displayId = dc.getDisplayId();
        int userId = UserHandle.getUserId(uid);
        doReturn(false).when(mWm.mUmInternal).isUserVisible(eq(userId), eq(displayId));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                LayoutParams.TYPE_APPLICATION_OVERLAY);

        int result = mWm.addWindow(session, new TestIWindow(), params, View.VISIBLE, displayId,
                userId, WindowInsets.Type.defaultVisible(), null, new InsetsState(),
                new InsetsSourceControl.Array(), new Rect(), new float[1]);

        assertThat(result).isEqualTo(WindowManagerGlobal.ADD_INVALID_DISPLAY);
    }

    class TestResultReceiver implements IResultReceiver {
        public android.os.Bundle resultData;
        private final IBinder mBinder = mock(IBinder.class);

        @Override
        public void send(int resultCode, android.os.Bundle resultData)
                throws android.os.RemoteException {
            this.resultData = resultData;
        }

        @Override
        public android.os.IBinder asBinder() {
            return mBinder;
        }
    }

    private void setupActivityWithLaunchCookie(
            IBinder launchCookie, WindowContainerToken wct, int uid) {
        final WindowContainer.RemoteToken remoteToken = mock(WindowContainer.RemoteToken.class);
        when(remoteToken.toWindowContainerToken()).thenReturn(wct);
        final ActivityRecord testActivity =
                new ActivityBuilder(mAtm).setCreateTask(true).setUid(uid).build();
        testActivity.mLaunchCookie = launchCookie;
        testActivity.getTask().mRemoteToken = remoteToken;
    }

    private boolean setupLetterboxConfigurationWithBackgroundType(
            @AppCompatConfiguration.LetterboxBackgroundType int letterboxBackgroundType) {
        mWm.mAppCompatConfiguration.setLetterboxBackgroundTypeOverride(letterboxBackgroundType);
        return mWm.isLetterboxBackgroundMultiColored();
    }
}
