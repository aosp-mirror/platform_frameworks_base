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
 * limitations under the License
 */

package com.android.systemui.keyguard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IActivityTaskManager;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * runtest systemui -c com.android.systemui.keyguard.WorkLockActivityControllerTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WorkLockActivityControllerTest extends SysuiTestCase {
    private static final int TASK_USER_ID = 333;
    private static final int PROFILE_USER_ID = 555;
    private static final int TASK_ID = 444;
    private static final ActivityManager.RunningTaskInfo TASK_INFO =
            new ActivityManager.RunningTaskInfo();

    static {
        TASK_INFO.userId = TASK_USER_ID;
        TASK_INFO.taskId = TASK_ID;
    }

    private @Mock Context mContext;
    private @Mock TaskStackChangeListeners mTaskStackChangeListeners;
    private @Mock IActivityTaskManager mIActivityTaskManager;
    private @Mock UserTracker mUserTracker;

    private WorkLockActivityController mController;
    private TaskStackChangeListener mTaskStackListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Set a package name to use for checking ComponentName well-formedness in tests.
        doReturn("com.example.test").when(mContext).getPackageName();
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());

        // Construct controller. Save the TaskStackListener for injecting events.
        final ArgumentCaptor<TaskStackChangeListener> listenerCaptor =
                ArgumentCaptor.forClass(TaskStackChangeListener.class);
        mController = new WorkLockActivityController(mContext, mUserTracker,
                mTaskStackChangeListeners, mIActivityTaskManager);

        verify(mTaskStackChangeListeners).registerTaskStackListener(listenerCaptor.capture());
        mTaskStackListener = listenerCaptor.getValue();
    }

    @Test
    public void testOverlayStartedWhenLocked() throws Exception {
        // When starting an activity succeeds,
        setActivityStartCode(TASK_ID, true /*taskOverlay*/, ActivityManager.START_SUCCESS);

        // And the controller receives a message saying the profile is locked,
        mTaskStackListener.onTaskProfileLocked(TASK_INFO, PROFILE_USER_ID);

        // The overlay should start and the task the activity started in should not be removed.
        verifyStartActivity(TASK_ID, true /*taskOverlay*/, PROFILE_USER_ID);
        verify(mIActivityTaskManager, never()).removeTask(anyInt() /*taskId*/);
    }

    @Test
    public void testRemoveTaskOnFailureToStartOverlay() throws Exception {
        // When starting an activity fails,
        setActivityStartCode(TASK_ID, true /*taskOverlay*/, ActivityManager.START_CLASS_NOT_FOUND);

        // And the controller receives a message saying the profile is locked,
        mTaskStackListener.onTaskProfileLocked(TASK_INFO, PROFILE_USER_ID);

        // The task the activity started in should be removed to prevent the locked task from
        // being shown.
        verifyStartActivity(TASK_ID, true /*taskOverlay*/, PROFILE_USER_ID);
        verify(mIActivityTaskManager).removeTask(TASK_ID);
    }

    // End of tests, start of helpers
    // ------------------------------

    private void setActivityStartCode(int taskId, boolean taskOverlay, int code) throws Exception {
        doReturn(code).when(mIActivityTaskManager).startActivityAsUser(
                eq((IApplicationThread) null),
                eq((String) null),
                eq((String) null),
                any(Intent.class),
                eq((String) null),
                eq((IBinder) null),
                eq((String) null),
                anyInt(),
                anyInt(),
                eq((ProfilerInfo) null),
                argThat(hasOptions(taskId, taskOverlay)),
                eq(ActivityManager.getCurrentUser()));
    }

    private void verifyStartActivity(int taskId, boolean taskOverlay, int profileUserId)
            throws Exception {
        verify(mIActivityTaskManager).startActivityAsUser(
                eq((IApplicationThread) null),
                eq((String) null),
                eq((String) null),
                argThat(hasUserId(profileUserId)),
                eq((String) null),
                eq((IBinder) null),
                eq((String) null),
                anyInt(),
                anyInt(),
                eq((ProfilerInfo) null),
                argThat(hasOptions(taskId, taskOverlay)),
                eq(ActivityManager.getCurrentUser()));
    }

    private static ArgumentMatcher<Intent> hasUserId(int userId) {
        return intent -> intent.getIntExtra(Intent.EXTRA_USER_ID, -1) == userId;
    }

    private static ArgumentMatcher<Bundle> hasOptions(final int taskId, final boolean overlay) {
        return item -> {
            final ActivityOptions options = ActivityOptions.fromBundle(item);
            return (options.getLaunchTaskId() == taskId)
                    && (options.getTaskOverlay() == overlay);
        };
    }
}
