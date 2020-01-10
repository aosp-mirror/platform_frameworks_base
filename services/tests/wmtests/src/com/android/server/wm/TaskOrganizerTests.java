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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

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
    private ITaskOrganizer makeAndRegisterMockOrganizer() {
        final ITaskOrganizer organizer = mock(ITaskOrganizer.class);
        when(organizer.asBinder()).thenReturn(new Binder());

        mWm.mAtmService.registerTaskOrganizer(organizer, WINDOWING_MODE_PINNED);

        return organizer;
    }

    @Test
    public void testAppearVanish() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = makeAndRegisterMockOrganizer();

        task.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any(), any());
        assertTrue(task.isControlledByTaskOrganizer());

        task.removeImmediately();
        verify(organizer).taskVanished(any());
    }

    @Test
    public void testSwapOrganizer() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = makeAndRegisterMockOrganizer();
        final ITaskOrganizer organizer2 = makeAndRegisterMockOrganizer();

        task.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any(), any());
        task.setTaskOrganizer(organizer2);
        verify(organizer).taskVanished(any());
        verify(organizer2).taskAppeared(any(), any());
    }

    @Test
    public void testClearOrganizer() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = makeAndRegisterMockOrganizer();

        task.setTaskOrganizer(organizer);
        verify(organizer).taskAppeared(any(), any());
        assertTrue(task.isControlledByTaskOrganizer());

        task.setTaskOrganizer(null);
        verify(organizer).taskVanished(any());
        assertFalse(task.isControlledByTaskOrganizer());
    }

    @Test
    public void testTransferStackToOrganizer() throws RemoteException {
        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final Task task2 = createTaskInStack(stack, 0 /* userId */);
        final ITaskOrganizer organizer = makeAndRegisterMockOrganizer();

        stack.transferToTaskOrganizer(organizer);

        verify(organizer, times(2)).taskAppeared(any(), any());
        assertTrue(task.isControlledByTaskOrganizer());
        assertTrue(task2.isControlledByTaskOrganizer());

        stack.transferToTaskOrganizer(null);

        verify(organizer, times(2)).taskVanished(any());
        assertFalse(task.isControlledByTaskOrganizer());
        assertFalse(task2.isControlledByTaskOrganizer());
    }

    @Test
    public void testRegisterTaskOrganizerTaskWindowingModeChanges() throws RemoteException {
        final ITaskOrganizer organizer = makeAndRegisterMockOrganizer();

        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        task.setWindowingMode(WINDOWING_MODE_PINNED);
        verify(organizer).taskAppeared(any(), any());
        assertTrue(task.isControlledByTaskOrganizer());

        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        verify(organizer).taskVanished(any());
        assertFalse(task.isControlledByTaskOrganizer());
    }

    @Test
    public void testRegisterTaskOrganizerStackWindowingModeChanges() throws RemoteException {
        final ITaskOrganizer organizer = makeAndRegisterMockOrganizer();

        final ActivityStack stack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final Task task2 = createTaskInStack(stack, 0 /* userId */);
        stack.setWindowingMode(WINDOWING_MODE_PINNED);
        verify(organizer, times(2)).taskAppeared(any(), any());
        assertTrue(task.isControlledByTaskOrganizer());
        assertTrue(task2.isControlledByTaskOrganizer());

        stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        verify(organizer, times(2)).taskVanished(any());
        assertFalse(task.isControlledByTaskOrganizer());
        assertFalse(task2.isControlledByTaskOrganizer());
    }
}
