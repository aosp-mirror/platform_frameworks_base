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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link ActivitySnapshotController}.
 *
 * Build/Install/Run:
 *  *  atest WmTests:ActivitySnapshotControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivitySnapshotControllerTests extends WindowTestsBase {

    private ActivitySnapshotController mActivitySnapshotController;
    @Before
    public void setUp() throws Exception {
        mActivitySnapshotController = mWm.mSnapshotController.mActivitySnapshotController;
        mActivitySnapshotController.resetTmpFields();
    }
    @Test
    public void testOpenActivityTransition() {
        final SnapshotController.TransitionState transitionState =
                new SnapshotController.TransitionState();
        final Task task = createTask(mDisplayContent);
        // note for createAppWindow: the new child is added at index 0
        final WindowState openingWindow = createAppWindow(task,
                ACTIVITY_TYPE_STANDARD, "openingWindow");
        openingWindow.mActivityRecord.commitVisibility(
                true /* visible */, true /* performLayout */);
        final WindowState closingWindow = createAppWindow(task, ACTIVITY_TYPE_STANDARD,
                "closingWindow");
        closingWindow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        transitionState.addParticipant(closingWindow.mActivityRecord, false);
        transitionState.addParticipant(openingWindow.mActivityRecord, true);
        mActivitySnapshotController.handleOpenActivityTransition(transitionState);

        assertEquals(1, mActivitySnapshotController.mPendingCaptureActivity.size());
        assertEquals(0, mActivitySnapshotController.mPendingRemoveActivity.size());
        assertEquals(closingWindow.mActivityRecord,
                mActivitySnapshotController.mPendingCaptureActivity.valueAt(0));
        mActivitySnapshotController.resetTmpFields();

        // simulate three activity
        final WindowState belowClose = createAppWindow(task, ACTIVITY_TYPE_STANDARD,
                "belowClose");
        belowClose.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        mActivitySnapshotController.handleOpenActivityTransition(transitionState);
        assertEquals(1, mActivitySnapshotController.mPendingCaptureActivity.size());
        assertEquals(1, mActivitySnapshotController.mPendingRemoveActivity.size());
        assertEquals(closingWindow.mActivityRecord,
                mActivitySnapshotController.mPendingCaptureActivity.valueAt(0));
        assertEquals(belowClose.mActivityRecord,
                mActivitySnapshotController.mPendingRemoveActivity.valueAt(0));
    }

    @Test
    public void testCloseActivityTransition() {
        final SnapshotController.TransitionState transitionState =
                new SnapshotController.TransitionState();
        final Task task = createTask(mDisplayContent);
        // note for createAppWindow: the new child is added at index 0
        final WindowState closingWindow = createAppWindow(task, ACTIVITY_TYPE_STANDARD,
                "closingWindow");
        closingWindow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        final WindowState openingWindow = createAppWindow(task,
                ACTIVITY_TYPE_STANDARD, "openingWindow");
        openingWindow.mActivityRecord.commitVisibility(
                true /* visible */, true /* performLayout */);
        transitionState.addParticipant(closingWindow.mActivityRecord, false);
        transitionState.addParticipant(openingWindow.mActivityRecord, true);
        mActivitySnapshotController.handleCloseActivityTransition(transitionState);
        assertEquals(0, mActivitySnapshotController.mPendingCaptureActivity.size());
        assertEquals(1, mActivitySnapshotController.mPendingDeleteActivity.size());
        assertEquals(openingWindow.mActivityRecord,
                mActivitySnapshotController.mPendingDeleteActivity.valueAt(0));
        mActivitySnapshotController.resetTmpFields();

        // simulate three activity
        final WindowState belowOpen = createAppWindow(task, ACTIVITY_TYPE_STANDARD,
                "belowOpen");
        belowOpen.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        mActivitySnapshotController.handleCloseActivityTransition(transitionState);
        assertEquals(0, mActivitySnapshotController.mPendingCaptureActivity.size());
        assertEquals(1, mActivitySnapshotController.mPendingDeleteActivity.size());
        assertEquals(1, mActivitySnapshotController.mPendingLoadActivity.size());
        assertEquals(openingWindow.mActivityRecord,
                mActivitySnapshotController.mPendingDeleteActivity.valueAt(0));
        assertEquals(belowOpen.mActivityRecord,
                mActivitySnapshotController.mPendingLoadActivity.valueAt(0));
    }

    @Test
    public void testTaskTransition() {
        final SnapshotController.TransitionState taskCloseTransition =
                new SnapshotController.TransitionState();
        final SnapshotController.TransitionState taskOpenTransition =
                new SnapshotController.TransitionState();
        final Task closeTask = createTask(mDisplayContent);
        // note for createAppWindow: the new child is added at index 0
        final WindowState closingWindow = createAppWindow(closeTask, ACTIVITY_TYPE_STANDARD,
                "closingWindow");
        closingWindow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        final WindowState closingWindowBelow = createAppWindow(closeTask, ACTIVITY_TYPE_STANDARD,
                "closingWindowBelow");
        closingWindowBelow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);

        final Task openTask = createTask(mDisplayContent);
        final WindowState openingWindow = createAppWindow(openTask, ACTIVITY_TYPE_STANDARD,
                "openingWindow");
        openingWindow.mActivityRecord.commitVisibility(
                true /* visible */, true /* performLayout */);
        final WindowState openingWindowBelow = createAppWindow(openTask, ACTIVITY_TYPE_STANDARD,
                "openingWindowBelow");
        openingWindowBelow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        taskCloseTransition.addParticipant(closeTask, false);
        taskOpenTransition.addParticipant(openTask, true);
        mActivitySnapshotController.handleCloseTaskTransition(taskCloseTransition);
        mActivitySnapshotController.handleOpenTaskTransition(taskOpenTransition);

        assertEquals(1, mActivitySnapshotController.mPendingRemoveActivity.size());
        assertEquals(closingWindowBelow.mActivityRecord,
                mActivitySnapshotController.mPendingRemoveActivity.valueAt(0));
        assertEquals(1, mActivitySnapshotController.mPendingLoadActivity.size());
        assertEquals(openingWindowBelow.mActivityRecord,
                mActivitySnapshotController.mPendingLoadActivity.valueAt(0));
    }
}
