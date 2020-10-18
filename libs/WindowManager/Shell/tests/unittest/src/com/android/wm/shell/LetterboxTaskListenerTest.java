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

package com.android.wm.shell;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.eq;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link LetterboxTaskListener}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LetterboxTaskListenerTest {

    private static final Rect ACTIVITY_BOUNDS = new Rect(300, 200, 700, 400);
    private static final Rect TASK_BOUNDS = new Rect(200, 100, 800, 500);
    private static final Rect TASK_BOUNDS_2 = new Rect(300, 200, 800, 500);
    private static final Point TASK_POSITION_IN_PARENT = new Point(100, 50);
    private static final Point TASK_POSITION_IN_PARENT_2 = new Point(200, 100);

    private static final Rect EXPECTED_WINDOW_CROP = new Rect(100, 100, 500, 300);
    private static final Rect EXPECTED_WINDOW_CROP_2 = new Rect(0, 0, 400, 200);

    private static final RunningTaskInfo TASK_INFO = createTaskInfo(
                /* taskId */ 1, ACTIVITY_BOUNDS, TASK_BOUNDS, TASK_POSITION_IN_PARENT);

    private static final RunningTaskInfo TASK_INFO_2 = createTaskInfo(
                /* taskId */ 1, ACTIVITY_BOUNDS, TASK_BOUNDS_2, TASK_POSITION_IN_PARENT_2);

    @Mock private SurfaceControl mLeash;
    @Mock private SurfaceControl.Transaction mTransaction;
    private LetterboxTaskListener mLetterboxTaskListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLetterboxTaskListener = new LetterboxTaskListener(
                new SyncTransactionQueue(
                        new TransactionPool() {
                            @Override
                            public SurfaceControl.Transaction acquire() {
                                return mTransaction;
                            }

                            @Override
                            public void release(SurfaceControl.Transaction t) {
                            }
                        },
                        new Handler(Looper.getMainLooper())));
    }

    @Test
    public void testOnTaskAppearedAndonTaskInfoChanged_setCorrectPositionAndCrop() {
        mLetterboxTaskListener.onTaskAppeared(TASK_INFO, mLeash);

        verify(mTransaction).setPosition(
                eq(mLeash),
                eq((float) TASK_POSITION_IN_PARENT.x),
                eq((float) TASK_POSITION_IN_PARENT.y));
        // Should return activty coordinates offset by task coordinates
        verify(mTransaction).setWindowCrop(eq(mLeash), eq(EXPECTED_WINDOW_CROP));

        mLetterboxTaskListener.onTaskInfoChanged(TASK_INFO_2);

        verify(mTransaction).setPosition(
                eq(mLeash),
                eq((float) TASK_POSITION_IN_PARENT_2.x),
                eq((float) TASK_POSITION_IN_PARENT_2.y));
        // Should return activty coordinates offset by task coordinates
        verify(mTransaction).setWindowCrop(eq(mLeash), eq(EXPECTED_WINDOW_CROP_2));
    }

    @Test(expected = RuntimeException.class)
    public void testOnTaskAppeared_calledSecondTimeWithSameTaskId_throwsException() {
        mLetterboxTaskListener.onTaskAppeared(TASK_INFO, mLeash);
        mLetterboxTaskListener.onTaskAppeared(TASK_INFO, mLeash);
    }

    private static RunningTaskInfo createTaskInfo(
                int taskId,
                final Rect activityBounds,
                final Rect taskBounds,
                final Point taskPositionInParent) {
        RunningTaskInfo taskInfo = new RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setBounds(taskBounds);
        taskInfo.letterboxActivityBounds = Rect.copyOrNull(activityBounds);
        taskInfo.positionInParent = new Point(taskPositionInParent);
        return taskInfo;
    }
}
