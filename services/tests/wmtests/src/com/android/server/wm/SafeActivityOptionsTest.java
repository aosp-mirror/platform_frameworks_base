/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.view.RemoteAnimationAdapter;
import android.window.RemoteTransition;
import android.window.WindowContainerToken;

import androidx.test.filters.MediumTest;

import org.junit.Test;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Build/Install/Run:
 *  atest WmTests:SafeActivityOptionsTest
 */
@MediumTest
@Presubmit
public class SafeActivityOptionsTest {

    @Test
    public void testMerge() {
        final ActivityOptions opts1 = ActivityOptions.makeBasic();
        opts1.setLaunchDisplayId(5);
        final ActivityOptions opts2 = ActivityOptions.makeBasic();
        opts2.setLaunchDisplayId(6);
        final SafeActivityOptions options = new SafeActivityOptions(opts1);
        final ActivityOptions result = options.mergeActivityOptions(opts1, opts2);
        assertEquals(6, result.getLaunchDisplayId());
    }

    @Test
    public void test_selectiveCloneDisplayOptions() {
        final WindowContainerToken token = mock(WindowContainerToken.class);
        final int launchDisplayId = 5;
        final int callerDisplayId = 6;

        final SafeActivityOptions clone = new SafeActivityOptions(ActivityOptions.makeBasic()
                .setLaunchTaskDisplayArea(token)
                .setLaunchDisplayId(launchDisplayId)
                .setCallerDisplayId(callerDisplayId))
                .selectiveCloneLaunchOptions();

        assertSame(clone.getOriginalOptions().getLaunchTaskDisplayArea(), token);
        assertEquals(clone.getOriginalOptions().getLaunchDisplayId(), launchDisplayId);
        assertEquals(clone.getOriginalOptions().getCallerDisplayId(), callerDisplayId);
    }

    @Test
    public void test_selectiveCloneLunchRootTask() {
        final WindowContainerToken token = mock(WindowContainerToken.class);
        final SafeActivityOptions clone = new SafeActivityOptions(ActivityOptions.makeBasic()
                .setLaunchRootTask(token))
                .selectiveCloneLaunchOptions();

        assertSame(clone.getOriginalOptions().getLaunchRootTask(), token);
    }

    @Test
    public void test_selectiveCloneLunchRemoteTransition() {
        final RemoteTransition transition = mock(RemoteTransition.class);
        final SafeActivityOptions clone = new SafeActivityOptions(
                ActivityOptions.makeRemoteTransition(transition))
                .selectiveCloneLaunchOptions();

        assertSame(clone.getOriginalOptions().getRemoteTransition(), transition);
    }

    @Test
    public void test_getOptions() {
        // Mock everything necessary
        MockitoSession mockingSession = mockitoSession()
                .mockStatic(ActivityTaskManagerService.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        doReturn(PERMISSION_DENIED).when(() -> ActivityTaskManagerService.checkPermission(
                any(), anyInt(), anyInt()));

        final LockTaskController lockTaskController = mock(LockTaskController.class);
        doReturn(false).when(lockTaskController).isPackageAllowlisted(anyInt(), any());

        final ActivityTaskManagerService atm = mock(ActivityTaskManagerService.class);
        doReturn(lockTaskController).when(atm).getLockTaskController();

        final ActivityTaskSupervisor taskSupervisor =
                new ActivityTaskSupervisor(atm, mock(Looper.class));
        spyOn(taskSupervisor);
        doReturn(false).when(taskSupervisor).isCallerAllowedToLaunchOnDisplay(anyInt(),
                anyInt(), anyInt(), any());
        doReturn(false).when(taskSupervisor).isCallerAllowedToLaunchOnTaskDisplayArea(anyInt(),
                anyInt(), any(), any());

        taskSupervisor.mRecentTasks = mock(RecentTasks.class);
        doReturn(false).when(taskSupervisor.mRecentTasks).isCallerRecents(anyInt());

        // Ensure exceptions are thrown when lack of permissions.
        ActivityOptions activityOptions = ActivityOptions.makeBasic();
        try {
            activityOptions.setLaunchTaskId(100);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setDisableStartingWindow(true);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setTransientLaunch();
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setDismissKeyguardIfInsecure();
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setLaunchActivityType(ACTIVITY_TYPE_STANDARD);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setLaunchedFromBubble(true);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setLaunchDisplayId(DEFAULT_DISPLAY);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setLockTaskEnabled(true);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeCustomTaskAnimation(
                    getInstrumentation().getContext(), 0, 0, null, null, null);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            RemoteAnimationAdapter remoteAnimationAdapter = mock(RemoteAnimationAdapter.class);
            RemoteTransition remoteTransition = mock(RemoteTransition.class);
            activityOptions = ActivityOptions.makeRemoteAnimation(remoteAnimationAdapter);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeRemoteAnimation(remoteAnimationAdapter,
                    remoteTransition);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setRemoteAnimationAdapter(remoteAnimationAdapter);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeRemoteTransition(remoteTransition);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            activityOptions = ActivityOptions.makeBasic();
            activityOptions.setRemoteTransition(remoteTransition);
            verifySecureExceptionThrown(activityOptions, taskSupervisor);

            verifySecureExceptionThrown(activityOptions, taskSupervisor,
                    mock(TaskDisplayArea.class));
        } finally {
            mockingSession.finishMocking();
        }
    }

    private void verifySecureExceptionThrown(ActivityOptions activityOptions,
            ActivityTaskSupervisor taskSupervisor) {
        verifySecureExceptionThrown(activityOptions, taskSupervisor, null /* mockTda */);
    }

    private void verifySecureExceptionThrown(ActivityOptions activityOptions,
            ActivityTaskSupervisor taskSupervisor, TaskDisplayArea mockTda) {
        SafeActivityOptions safeActivityOptions = new SafeActivityOptions(activityOptions);
        if (mockTda != null) {
            spyOn(safeActivityOptions);
            doReturn(mockTda).when(safeActivityOptions).getLaunchTaskDisplayArea(any(), any());
        }

        boolean isExceptionThrow = false;
        final ActivityInfo aInfo = mock(ActivityInfo.class);
        try {
            safeActivityOptions.getOptions(null, aInfo, null, taskSupervisor);
        } catch (SecurityException ex) {
            isExceptionThrow = true;
        }
        assertTrue(isExceptionThrow);
    }
}
