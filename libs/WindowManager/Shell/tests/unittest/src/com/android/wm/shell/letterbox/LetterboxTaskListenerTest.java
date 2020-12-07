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

package com.android.wm.shell.letterbox;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;
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
public final class LetterboxTaskListenerTest extends ShellTestCase {

    @Mock private SurfaceControl mLeash;
    @Mock private SurfaceControl.Transaction mTransaction;
    @Mock private WindowManager mWindowManager;
    @Mock private WindowMetrics mWindowMetrics;
    @Mock private WindowInsets mWindowInsets;
    private LetterboxTaskListener mLetterboxTaskListener;
    private LetterboxConfigController mLetterboxConfigController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mLetterboxConfigController = new LetterboxConfigController(getContext());
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
                        new Handler(Looper.getMainLooper())),
                mLetterboxConfigController,
                mWindowManager);

        when(mWindowManager.getMaximumWindowMetrics()).thenReturn(mWindowMetrics);
        when(mWindowMetrics.getWindowInsets()).thenReturn(mWindowInsets);
    }

    @Test
    public void testOnTaskInfoChanged_updatesPositionAndCrop() {
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                Insets.NONE);

        mLetterboxConfigController.setLandscapeGravity(Gravity.CENTER);
        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 200, 100),
                        /* activityBounds */ new Rect(75, 0, 125, 75),
                        /* taskBounds */ new Rect(50, 0, 125, 100),
                        /* activityInsets */ new Rect(0, 0, 0, 0)),
                mLeash);

        // Task doesn't need to repositioned
        verifySetPosition(50, 0);
        // Should return activity coordinates offset by task coordinates
        verifySetWindowCrop(new Rect(25, 0, 75, 75));

        mLetterboxTaskListener.onTaskInfoChanged(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 200, 100),
                        // Activity is offset by 25 to the left
                        /* activityBounds */ new Rect(50, 0, 100, 75),
                        /* taskBounds */ new Rect(50, 0, 125, 100),
                        /* activityInsets */ new Rect(0, 0, 0, 0)));

        // Task needs to be repositioned by 25 to the left
        verifySetPosition(75, 0);
        // Should return activity coordinates offset by task coordinates
        verifySetWindowCrop(new Rect(0, 0, 50, 75));
    }

    @Test
    public void testOnTaskInfoAppeared_landscapeWithLeftGravity() {
        mLetterboxConfigController.setLandscapeGravity(Gravity.LEFT);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                Insets.of(/* left= */ 10, /* top= */ 10, /* right= */ 10, /* bottom= */ 10));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 200, 100),
                        /* activityBounds */ new Rect(150, 0, 200, 75),
                        /* taskBounds */ new Rect(125, 0, 200, 100),
                        /* activityInsets */ new Rect(0, 10, 10, 0)),
                mLeash);

        verifySetPosition(-15, 0);
        // Should return activity coordinates offset by task coordinates minus unwanted right inset
        verifySetWindowCrop(new Rect(25, 0, 65, 75));
    }

    @Test
    public void testOnTaskInfoAppeared_landscapeWithCenterGravity() {
        mLetterboxConfigController.setLandscapeGravity(Gravity.CENTER);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                Insets.of(/* left= */ 10, /* top= */ 10, /* right= */ 10, /* bottom= */ 10));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 200, 100),
                        /* activityBounds */ new Rect(150, 0, 200, 75),
                        /* taskBounds */ new Rect(125, 0, 200, 100),
                        /* activityInsets */ new Rect(0, 10, 10, 0)),
                mLeash);

        verifySetPosition(55, 0);
        // Should return activity coordinates offset by task coordinates minus unwanted right inset
        verifySetWindowCrop(new Rect(25, 0, 65, 75));
    }

    @Test
    public void testOnTaskInfoAppeared_landscapeWithRightGravity() {
        mLetterboxConfigController.setLandscapeGravity(Gravity.RIGHT);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                Insets.of(/* left= */ 10, /* top= */ 10, /* right= */ 10, /* bottom= */ 10));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 200, 100), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 200, 100),
                        /* activityBounds */ new Rect(50, 0, 100, 75),
                        /* taskBounds */ new Rect(25, 0, 100, 100),
                        /* activityInsets */ new Rect(0, 10, 10, 0)),
                mLeash);

        verifySetPosition(115, 0);
        // Should return activity coordinates offset by task coordinates
        verifySetWindowCrop(new Rect(25, 0, 75, 75));
    }

    @Test
    public void testOnTaskInfoAppeared_portraitWithTopGravity() {
        mLetterboxConfigController.setPortraitGravity(Gravity.TOP);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 100, 150), // equal to parent bounds
                Insets.of(/* left= */ 10, /* top= */ 10, /* right= */ 10, /* bottom= */ 20));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 100, 150), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 100, 150),
                        /* activityBounds */ new Rect(0, 75, 50, 125),
                        /* taskBounds */ new Rect(0, 50, 100, 125),
                        /* activityInsets */ new Rect(10, 0, 0, 0)),
                mLeash);

        verifySetPosition(20, -15);
        // Should return activity coordinates offset by task coordinates minus unwanted left inset
        verifySetWindowCrop(new Rect(10, 25, 50, 75));
    }

    @Test
    public void testOnTaskInfoAppeared_portraitWithCenterGravity() {
        mLetterboxConfigController.setPortraitGravity(Gravity.CENTER);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 100, 150), // equal to parent bounds
                Insets.of(/* left= */ 10, /* top= */ 10, /* right= */ 10, /* bottom= */ 20));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 100, 150), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 100, 150),
                        /* activityBounds */ new Rect(0, 75, 50, 125),
                        /* taskBounds */ new Rect(0, 50, 100, 125),
                        /* activityInsets */ new Rect(10, 0, 0, 0)),
                mLeash);

        verifySetPosition(20, 20);
        // Should return activity coordinates offset by task coordinates minus unwanted left inset
        verifySetWindowCrop(new Rect(10, 25, 50, 75));
    }

    @Test
    public void testOnTaskInfoAppeared_portraitWithCenterGravity_visibleLeftInset() {
        mLetterboxConfigController.setPortraitGravity(Gravity.CENTER);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 100, 150), // equal to parent bounds
                Insets.of(/* left= */ 10, /* top= */ 10, /* right= */ 10, /* bottom= */ 20));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 100, 150), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 100, 150),
                        /* activityBounds */ new Rect(0, 75, 50, 125),
                        /* taskBounds */ new Rect(0, 50, 100, 125),
                        // Activity is drawn under the left inset.
                        /* activityInsets */ new Rect(0, 0, 0, 0)),
                mLeash);

        verifySetPosition(20, 20);
        // Should return activity coordinates offset by task coordinates
        verifySetWindowCrop(new Rect(0, 25, 50, 75));
    }

    @Test
    public void testOnTaskInfoAppeared_portraitWithBottomGravity() {
        mLetterboxConfigController.setPortraitGravity(Gravity.BOTTOM);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 100, 150), // equal to parent bounds
                Insets.of(/* left= */ 10, /* top= */ 10, /* right= */ 10, /* bottom= */ 20));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 100, 150), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 100, 150),
                        /* activityBounds */ new Rect(0, 75, 50, 125),
                        /* taskBounds */ new Rect(0, 50, 100, 125),
                        /* activityInsets */ new Rect(10, 0, 0, 0)),
                mLeash);

        verifySetPosition(20, 55);
        // Should return activity coordinates offset by task coordinates minus unwanted left inset
        verifySetWindowCrop(new Rect(10, 25, 50, 75));
    }

    @Test
    public void testOnTaskInfoAppeared_partlyOverlapsWithAllInsets() {
        mLetterboxConfigController.setPortraitGravity(Gravity.TOP);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 200, 125), // equal to parent bounds
                Insets.of(/* left= */ 25, /* top= */ 25, /* right= */ 35, /* bottom= */ 15));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 200, 125), // equal to parent bounds
                        /* parentBounds */ new Rect(0, 0, 200, 125),
                        /* activityBounds */ new Rect(15, 0, 175, 120),
                        /* taskBounds */ new Rect(0, 0, 200, 125),
                        /* activityInsets */ new Rect(10, 25, 10, 10)), // equal to parent bounds
                mLeash);

        // Activity fully covers parent bounds with insets so doesn't need to be moved.
        verifySetPosition(0, 0);
        // Should return activity coordinates offset by task coordinates
        verifySetWindowCrop(new Rect(15, 0, 175, 120));
    }

    @Test
    public void testOnTaskInfoAppeared_parentShiftedLikeInOneHandedMode() {
        mLetterboxConfigController.setPortraitGravity(Gravity.TOP);
        setWindowBoundsAndInsets(
                /* windowBounds= */ new Rect(0, 0, 100, 150),
                Insets.of(/* left= */ 0, /* top= */ 10, /* right= */ 0, /* bottom= */ 0));

        mLetterboxTaskListener.onTaskAppeared(
                createTaskInfo(
                        /* taskId */ 1,
                        /* maxBounds= */ new Rect(0, 0, 100, 150),
                        /* parentBounds */ new Rect(0, 75, 100, 225),
                        /* activityBounds */ new Rect(25, 75, 75, 125),
                        /* taskBounds */ new Rect(0, 75, 100, 125),
                        /* activityInsets */ new Rect(10, 0, 0, 0)),
                mLeash);

        verifySetPosition(0, 0);
        verifySetWindowCrop(new Rect(25, 0, 75, 50));
    }

    @Test(expected = IllegalStateException.class)
    public void testOnTaskAppeared_calledSecondTimeWithSameTaskId_throwsException() {
        setWindowBoundsAndInsets(new Rect(),  Insets.NONE);
        RunningTaskInfo taskInfo =
                createTaskInfo(/* taskId */ 1, new Rect(),  new Rect(), new Rect(), new Rect(),
                new Rect());
        mLetterboxTaskListener.onTaskAppeared(taskInfo, mLeash);
        mLetterboxTaskListener.onTaskAppeared(taskInfo, mLeash);
    }

    private void setWindowBoundsAndInsets(Rect windowBounds, Insets insets) {
        when(mWindowMetrics.getBounds()).thenReturn(windowBounds);
        when(mWindowInsets.getInsets(anyInt())).thenReturn(insets);
    }

    private void verifySetPosition(int x, int y) {
        verify(mTransaction).setPosition(eq(mLeash), eq((float) x), eq((float) y));
    }

    private void verifySetWindowCrop(final Rect crop) {
        // Should return activty coordinates offset by task coordinates
        verify(mTransaction).setWindowCrop(eq(mLeash), eq(crop));
    }

    private static RunningTaskInfo createTaskInfo(
                int taskId,
                final Rect maxBounds,
                final Rect parentBounds,
                final Rect activityBounds,
                final Rect taskBounds,
                final Rect activityInsets) {
        RunningTaskInfo taskInfo = new RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setMaxBounds(maxBounds);
        taskInfo.parentBounds = parentBounds;
        taskInfo.configuration.windowConfiguration.setBounds(taskBounds);
        taskInfo.letterboxActivityBounds = Rect.copyOrNull(activityBounds);
        taskInfo.letterboxActivityInsets = Rect.copyOrNull(activityInsets);

        return taskInfo;
    }
}
