/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.window.TaskFragmentOrganizer.putExceptionInBundle;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.testing.Assert.assertThrows;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:TaskFragmentOrganizerControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskFragmentOrganizerControllerTest extends WindowTestsBase {

    private TaskFragmentOrganizerController mController;
    private TaskFragmentOrganizer mOrganizer;
    private ITaskFragmentOrganizer mIOrganizer;
    private TaskFragment mTaskFragment;
    private TaskFragmentInfo mTaskFragmentInfo;
    private IBinder mFragmentToken;

    @Before
    public void setup() {
        mController = mWm.mAtmService.mWindowOrganizerController.mTaskFragmentOrganizerController;
        mOrganizer = new TaskFragmentOrganizer(Runnable::run);
        mIOrganizer = mOrganizer.getIOrganizer();
        mTaskFragment = mock(TaskFragment.class);
        mTaskFragmentInfo = mock(TaskFragmentInfo.class);
        mFragmentToken = new Binder();

        spyOn(mController);
        spyOn(mOrganizer);
        doReturn(mIOrganizer).when(mTaskFragment).getTaskFragmentOrganizer();
        doReturn(mTaskFragmentInfo).when(mTaskFragment).getTaskFragmentInfo();
        doReturn(new SurfaceControl()).when(mTaskFragment).getSurfaceControl();
        doReturn(mFragmentToken).when(mTaskFragment).getFragmentToken();
        doReturn(new Configuration()).when(mTaskFragmentInfo).getConfiguration();
    }

    @Test
    public void testCallTaskFragmentCallbackWithoutRegister_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentInfoChanged(
                        mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment));

        assertThrows(IllegalArgumentException.class, () -> mController
                .onTaskFragmentParentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                        mTaskFragment));
    }

    @Test
    public void testOnTaskFragmentAppeared() {
        mController.registerOrganizer(mIOrganizer);

        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);

        verify(mOrganizer).onTaskFragmentAppeared(any());
    }

    @Test
    public void testOnTaskFragmentInfoChanged() {
        mController.registerOrganizer(mIOrganizer);
        mController.onTaskFragmentAppeared(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);

        // No callback if the info is not changed.
        doReturn(true).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());
        doReturn(new Configuration()).when(mTaskFragmentInfo).getConfiguration();

        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);

        verify(mOrganizer, never()).onTaskFragmentInfoChanged(any());

        // Trigger callback if the info is changed.
        doReturn(false).when(mTaskFragmentInfo).equalsForTaskFragmentOrganizer(any());

        mController.onTaskFragmentInfoChanged(mTaskFragment.getTaskFragmentOrganizer(),
                mTaskFragment);

        verify(mOrganizer).onTaskFragmentInfoChanged(mTaskFragmentInfo);
    }

    @Test
    public void testOnTaskFragmentVanished() {
        mController.registerOrganizer(mIOrganizer);

        mController.onTaskFragmentVanished(mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);

        verify(mOrganizer).onTaskFragmentVanished(any());
    }

    @Test
    public void testOnTaskFragmentParentInfoChanged() {
        mController.registerOrganizer(mIOrganizer);
        final Task parent = mock(Task.class);
        final Configuration parentConfig = new Configuration();
        parentConfig.smallestScreenWidthDp = 10;
        doReturn(parent).when(mTaskFragment).getParent();
        doReturn(parentConfig).when(parent).getConfiguration();
        doReturn(parent).when(parent).asTask();

        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);

        verify(mOrganizer).onTaskFragmentParentInfoChanged(eq(mFragmentToken), any());

        // No extra callback if the info is not changed.
        clearInvocations(mOrganizer);

        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);

        verify(mOrganizer, never()).onTaskFragmentParentInfoChanged(any(), any());

        // Trigger callback if the info is changed.
        parentConfig.smallestScreenWidthDp = 100;

        mController.onTaskFragmentParentInfoChanged(
                mTaskFragment.getTaskFragmentOrganizer(), mTaskFragment);

        verify(mOrganizer).onTaskFragmentParentInfoChanged(eq(mFragmentToken), any());
    }

    @Test
    public void testOnTaskFragmentError() throws RemoteException {
        final IBinder errorCallbackToken = new Binder();
        final Throwable exception = new IllegalArgumentException("Test exception");
        final Bundle exceptionBundle = putExceptionInBundle(exception);

        mIOrganizer.onTaskFragmentError(errorCallbackToken, exceptionBundle);

        verify(mOrganizer).onTaskFragmentError(eq(errorCallbackToken), eq(exception));
    }
}
