/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.compatui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link ReachabilityEduWindowManager}.
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:ReachabilityEduWindowManagerTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ReachabilityEduWindowManagerTest extends ShellTestCase {

    private static final int USER_ID = 1;
    private static final int TASK_ID = 1;

    @Mock
    private SyncTransactionQueue mSyncTransactionQueue;
    @Mock
    private ShellTaskOrganizer.TaskListener mTaskListener;
    @Mock
    private CompatUIController.CompatUICallback mCallback;
    @Mock
    private CompatUIConfiguration mCompatUIConfiguration;
    @Mock
    private DisplayLayout mDisplayLayout;

    private TestShellExecutor mExecutor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mExecutor = new TestShellExecutor();
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testCreateLayout_notEligible_doesNotCreateLayout() {
        final ReachabilityEduWindowManager windowManager = createReachabilityEduWindowManager(
                createTaskInfo(/* userId= */ USER_ID, /*isLetterboxDoubleTapEnabled  */ false));

        assertFalse(windowManager.createLayout(/* canShow= */ true));

        assertNull(windowManager.mLayout);
    }

    @Test
    public void testCreateLayout_letterboxPositionChanged_doubleTapIsDetected() {
        // Initial left position
        final TaskInfo initialTaskInfo = createTaskInfoForHorizontalTapping(USER_ID, 0, 1000);
        final ReachabilityEduWindowManager windowManager =
                createReachabilityEduWindowManager(initialTaskInfo);
        // Move to the right
        final TaskInfo newPositionTaskInfo = createTaskInfoForHorizontalTapping(USER_ID, 1, 1000);
        windowManager.updateCompatInfo(newPositionTaskInfo, mTaskListener, /* canShow */ true);

        verify(mCompatUIConfiguration).setDontShowReachabilityEducationAgain(newPositionTaskInfo);
    }


    private ReachabilityEduWindowManager createReachabilityEduWindowManager(TaskInfo taskInfo) {
        return new ReachabilityEduWindowManager(mContext, taskInfo,
                mSyncTransactionQueue, mCallback, mTaskListener, mDisplayLayout,
                mCompatUIConfiguration, mExecutor);
    }

    private static TaskInfo createTaskInfo(int userId, boolean isLetterboxDoubleTapEnabled) {
        return createTaskInfo(userId, /* isLetterboxDoubleTapEnabled */ isLetterboxDoubleTapEnabled,
                /* topActivityLetterboxVerticalPosition */ -1,
                /* topActivityLetterboxHorizontalPosition */ -1,
                /* topActivityLetterboxWidth */ -1,
                /* topActivityLetterboxHeight */ -1);
    }

    private static TaskInfo createTaskInfoForHorizontalTapping(int userId,
            int topActivityLetterboxHorizontalPosition, int topActivityLetterboxWidth) {
        return createTaskInfo(userId, /* isLetterboxDoubleTapEnabled */ true,
                /* topActivityLetterboxVerticalPosition */ -1,
                topActivityLetterboxHorizontalPosition, topActivityLetterboxWidth,
                /* topActivityLetterboxHeight */ -1);
    }

    private static TaskInfo createTaskInfo(int userId, boolean isLetterboxDoubleTapEnabled,
            int topActivityLetterboxVerticalPosition, int topActivityLetterboxHorizontalPosition,
            int topActivityLetterboxWidth, int topActivityLetterboxHeight) {
        ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.userId = userId;
        taskInfo.taskId = TASK_ID;
        taskInfo.isLetterboxDoubleTapEnabled = isLetterboxDoubleTapEnabled;
        taskInfo.topActivityLetterboxVerticalPosition = topActivityLetterboxVerticalPosition;
        taskInfo.topActivityLetterboxHorizontalPosition = topActivityLetterboxHorizontalPosition;
        taskInfo.topActivityLetterboxWidth = topActivityLetterboxWidth;
        taskInfo.topActivityLetterboxHeight = topActivityLetterboxHeight;
        return taskInfo;
    }
}
