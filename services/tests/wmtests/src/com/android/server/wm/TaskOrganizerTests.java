/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.os.Binder;
import android.os.RemoteException;
import android.view.ITaskOrganizer;
import android.view.SurfaceControl;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link TaskOrganizer}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskOrganizerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskOrganizerTests extends WindowTestsBase {
    private ITaskOrganizer registerMockOrganizer(int windowingMode) {
        final ITaskOrganizer organizer = mock(ITaskOrganizer.class);
        when(organizer.asBinder()).thenReturn(new Binder());

        mWm.mAtmService.registerTaskOrganizer(organizer, windowingMode);

        return organizer;
    }

    private ITaskOrganizer registerMockOrganizer() {
        return registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
    }

    @Test
    public void testAppearVanish() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer();

        task.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any(), any());

        task.removeImmediately();
        verify(organizer).taskVanished(any());
    }

    @Test
    public void testSwapOrganizer() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        final ITaskOrganizer organizer2 = registerMockOrganizer(WINDOWING_MODE_PINNED);

        task.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any(), any());
        task.setTaskOrganizer(organizer2);
        verify(organizer).taskVanished(any());
        verify(organizer2).taskAppeared(any(), any());
    }

    @Test
    public void testSwapWindowingModes() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_MULTI_WINDOW);
        final ITaskOrganizer organizer2 = registerMockOrganizer(WINDOWING_MODE_PINNED);
 
        stack.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        verify(organizer).taskAppeared(any(), any());
        stack.setWindowingMode(WINDOWING_MODE_PINNED);
        verify(organizer).taskVanished(any());
        verify(organizer2).taskAppeared(any(), any());
    }

    @Test
    public void testClearOrganizer() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = registerMockOrganizer();

        stack.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any(), any());
        assertTrue(stack.isControlledByTaskOrganizer());

        stack.setTaskOrganizer(null);
        verify(organizer).taskVanished(any());
        assertFalse(stack.isControlledByTaskOrganizer());
    }

    @Test
    public void testRegisterTaskOrganizerStackWindowingModeChanges() throws RemoteException {
        final ITaskOrganizer organizer = registerMockOrganizer(WINDOWING_MODE_PINNED);

        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final Task task2 = createTaskInStack(stack, 0 /* userId */);
        stack.setWindowingMode(WINDOWING_MODE_PINNED);
        verify(organizer, times(1)).taskAppeared(any(), any());

        stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        verify(organizer, times(1)).taskVanished(any());
    }
}
