/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.wm.SnapshotController.ACTIVITY_CLOSE;
import static com.android.server.wm.SnapshotController.ACTIVITY_OPEN;
import static com.android.server.wm.SnapshotController.TASK_CLOSE;
import static com.android.server.wm.SnapshotController.TASK_OPEN;

import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


/**
 * Test class for {@link SnapshotController}.
 *
 * Build/Install/Run:
 *  *  atest WmTests:AppSnapshotControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppSnapshotControllerTests extends WindowTestsBase {
    final ArraySet<ActivityRecord> mClosingApps = new ArraySet<>();
    final ArraySet<ActivityRecord> mOpeningApps = new ArraySet<>();

    final TransitionMonitor mOpenActivityMonitor = new TransitionMonitor();
    final TransitionMonitor mCloseActivityMonitor = new TransitionMonitor();
    final TransitionMonitor mOpenTaskMonitor = new TransitionMonitor();
    final TransitionMonitor mCloseTaskMonitor = new TransitionMonitor();

    @Before
    public void setUp() throws Exception {
        resetStatus();
        mWm.mSnapshotController.registerTransitionStateConsumer(
                ACTIVITY_CLOSE, mCloseActivityMonitor::handleTransition);
        mWm.mSnapshotController.registerTransitionStateConsumer(
                ACTIVITY_OPEN, mOpenActivityMonitor::handleTransition);
        mWm.mSnapshotController.registerTransitionStateConsumer(
                TASK_CLOSE, mCloseTaskMonitor::handleTransition);
        mWm.mSnapshotController.registerTransitionStateConsumer(
                TASK_OPEN, mOpenTaskMonitor::handleTransition);
    }

    @After
    public void tearDown() throws Exception {
        mWm.mSnapshotController.unregisterTransitionStateConsumer(
                ACTIVITY_CLOSE, mCloseActivityMonitor::handleTransition);
        mWm.mSnapshotController.unregisterTransitionStateConsumer(
                ACTIVITY_OPEN, mOpenActivityMonitor::handleTransition);
        mWm.mSnapshotController.unregisterTransitionStateConsumer(
                TASK_CLOSE, mCloseTaskMonitor::handleTransition);
        mWm.mSnapshotController.unregisterTransitionStateConsumer(
                TASK_OPEN, mOpenTaskMonitor::handleTransition);
    }

    private static class TransitionMonitor {
        private final ArraySet<WindowContainer> mOpenParticipant = new ArraySet<>();
        private final ArraySet<WindowContainer> mCloseParticipant = new ArraySet<>();
        void handleTransition(SnapshotController.TransitionState<ActivityRecord> state) {
            mOpenParticipant.addAll(state.getParticipant(true /* open */));
            mCloseParticipant.addAll(state.getParticipant(false /* close */));
        }
        void reset() {
            mOpenParticipant.clear();
            mCloseParticipant.clear();
        }
    }

    private void resetStatus() {
        mClosingApps.clear();
        mOpeningApps.clear();
        mOpenActivityMonitor.reset();
        mCloseActivityMonitor.reset();
        mOpenTaskMonitor.reset();
        mCloseTaskMonitor.reset();
    }

    @Test
    public void testHandleAppTransition_openActivityTransition() {
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
        mClosingApps.add(closingWindow.mActivityRecord);
        mOpeningApps.add(openingWindow.mActivityRecord);
        mWm.mSnapshotController.handleAppTransition(mClosingApps, mOpeningApps);
        assertTrue(mOpenActivityMonitor.mCloseParticipant.contains(closingWindow.mActivityRecord));
        assertTrue(mOpenActivityMonitor.mOpenParticipant.contains(openingWindow.mActivityRecord));
    }

    @Test
    public void testHandleAppTransition_closeActivityTransition() {
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
        mClosingApps.add(closingWindow.mActivityRecord);
        mOpeningApps.add(openingWindow.mActivityRecord);
        mWm.mSnapshotController.handleAppTransition(mClosingApps, mOpeningApps);
        assertTrue(mCloseActivityMonitor.mCloseParticipant.contains(closingWindow.mActivityRecord));
        assertTrue(mCloseActivityMonitor.mOpenParticipant.contains(openingWindow.mActivityRecord));
    }

    @Test
    public void testHandleAppTransition_TaskTransition() {
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

        mClosingApps.add(closingWindow.mActivityRecord);
        mOpeningApps.add(openingWindow.mActivityRecord);
        mWm.mSnapshotController.handleAppTransition(mClosingApps, mOpeningApps);
        assertTrue(mCloseTaskMonitor.mCloseParticipant.contains(closeTask));
        assertTrue(mOpenTaskMonitor.mOpenParticipant.contains(openTask));
    }
}
