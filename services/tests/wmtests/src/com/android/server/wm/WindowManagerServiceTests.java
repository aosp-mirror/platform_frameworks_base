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
import static android.os.Process.INVALID_UID;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        mWm.addWindowToken(token, TYPE_TOAST, mDisplayContent.getDisplayId());

        WindowToken windowToken = mWm.mRoot.getWindowToken(token);
        assertFalse(windowToken.mRoundedCornerOverlay);
        assertFalse(windowToken.mFromClientToken);
    }

    @Test
    public void testAddWindowTokenWithOptions() {
        IBinder token = mock(IBinder.class);
        mWm.addWindowTokenWithOptions(token, TYPE_TOAST, mDisplayContent.getDisplayId(),
                null /* options */, null /* options */);

        WindowToken windowToken = mWm.mRoot.getWindowToken(token);
        assertFalse(windowToken.mRoundedCornerOverlay);
        assertTrue(windowToken.mFromClientToken);
    }

    @Test(expected = SecurityException.class)
    public void testRemoveWindowToken_ownerUidNotMatch_throwException() {
        IBinder token = mock(IBinder.class);
        mWm.addWindowTokenWithOptions(token, TYPE_TOAST, mDisplayContent.getDisplayId(),
                null /* options */, null /* options */);

        spyOn(mWm);
        when(mWm.checkCallingPermission(anyString(), anyString())).thenReturn(false);
        WindowToken windowToken = mWm.mRoot.getWindowToken(token);
        spyOn(windowToken);
        when(windowToken.getOwnerUid()).thenReturn(INVALID_UID);

        mWm.removeWindowToken(token, mDisplayContent.getDisplayId());
    }

    @Test
    public void testTaskFocusChange_stackNotHomeType_focusChanges() throws RemoteException {
        DisplayContent display = createNewDisplay();
        // Current focused window
        ActivityStack focusedStack = createTaskStackOnDisplay(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, display);
        Task focusedTask = createTaskInStack(focusedStack, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped task
        ActivityStack tappedStack = createTaskStackOnDisplay(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, display);
        Task tappedTask = createTaskInStack(tappedStack, 0 /* userId */);
        spyOn(mWm.mActivityTaskManager);

        mWm.handleTaskFocusChange(tappedTask);

        verify(mWm.mActivityTaskManager).setFocusedTask(tappedTask.mTaskId);
    }

    @Test
    public void testTaskFocusChange_stackHomeTypeWithSameTaskDisplayArea_focusDoesNotChange()
            throws RemoteException {
        DisplayContent display = createNewDisplay();
        // Current focused window
        ActivityStack focusedStack = createTaskStackOnDisplay(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, display);
        Task focusedTask = createTaskInStack(focusedStack, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped home task
        ActivityStack tappedStack = createTaskStackOnDisplay(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, display);
        Task tappedTask = createTaskInStack(tappedStack, 0 /* userId */);
        spyOn(mWm.mActivityTaskManager);

        mWm.handleTaskFocusChange(tappedTask);

        verify(mWm.mActivityTaskManager, never()).setFocusedTask(tappedTask.mTaskId);
    }

    @Test
    public void testTaskFocusChange_stackHomeTypeWithDifferentTaskDisplayArea_focusChanges()
            throws RemoteException {
        DisplayContent display = createNewDisplay();
        TaskDisplayArea secondTda =
                new TaskDisplayArea(display, mWm, "Tapped TDA", FEATURE_VENDOR_FIRST);
        display.mDisplayAreaPolicy.mRoot.addChild(secondTda, 1);
        display.mDisplayAreaPolicy.mTaskDisplayAreas.add(secondTda);
        // Current focused window
        ActivityStack focusedStack = createTaskStackOnDisplay(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, display);
        Task focusedTask = createTaskInStack(focusedStack, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped home task on another task display area
        ActivityStack tappedStack = createTaskStackOnTaskDisplayArea(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, secondTda);
        Task tappedTask = createTaskInStack(tappedStack, 0 /* userId */);
        spyOn(mWm.mActivityTaskManager);

        mWm.handleTaskFocusChange(tappedTask);

        verify(mWm.mActivityTaskManager).setFocusedTask(tappedTask.mTaskId);
    }
}
