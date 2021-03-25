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
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:WindowManagerServiceTests
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

        mWm.handleTaskFocusChange(tappedTask);

        verify(mWm.mAtmService).setFocusedTask(tappedTask.mTaskId);
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

        mWm.handleTaskFocusChange(tappedTask);

        verify(mWm.mAtmService, never()).setFocusedTask(tappedTask.mTaskId);
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

        mWm.handleTaskFocusChange(tappedTask);

        verify(mWm.mAtmService).setFocusedTask(tappedTask.mTaskId);
    }

    @Test
    public void testDismissKeyguardCanWakeUp() {
        doReturn(true).when(mWm).checkCallingPermission(anyString(), anyString());
        spyOn(mWm.mAtmInternal);
        doReturn(true).when(mWm.mAtmInternal).isDreaming();
        doNothing().when(mWm.mAtmService.mTaskSupervisor).wakeUp(anyString());
        mWm.dismissKeyguard(null, "test-dismiss-keyguard");
        verify(mWm.mAtmService.mTaskSupervisor).wakeUp(anyString());
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
}
