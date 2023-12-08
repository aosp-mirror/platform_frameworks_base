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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

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
        spyOn(mWm.mSnapshotController.mActivitySnapshotController);
        mActivitySnapshotController = mWm.mSnapshotController.mActivitySnapshotController;
        doReturn(false).when(mActivitySnapshotController).shouldDisableSnapshots();
        mActivitySnapshotController.resetTmpFields();
    }
    @Test
    public void testOpenActivityTransition() {
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
        final ArrayList<WindowContainer> windows = new ArrayList<>();
        windows.add(openingWindow.mActivityRecord);
        windows.add(closingWindow.mActivityRecord);
        mActivitySnapshotController.handleTransitionFinish(windows);

        assertEquals(0, mActivitySnapshotController.mPendingRemoveActivity.size());
        mActivitySnapshotController.resetTmpFields();

        // simulate three activity
        final WindowState belowClose = createAppWindow(task, ACTIVITY_TYPE_STANDARD,
                "belowClose");
        belowClose.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        windows.add(belowClose.mActivityRecord);
        mActivitySnapshotController.handleTransitionFinish(windows);
        assertEquals(1, mActivitySnapshotController.mPendingRemoveActivity.size());
        assertEquals(belowClose.mActivityRecord,
                mActivitySnapshotController.mPendingRemoveActivity.valueAt(0));
    }

    @Test
    public void testCloseActivityTransition() {
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
        final ArrayList<WindowContainer> windows = new ArrayList<>();
        windows.add(openingWindow.mActivityRecord);
        windows.add(closingWindow.mActivityRecord);
        mActivitySnapshotController.handleTransitionFinish(windows);
        assertEquals(1, mActivitySnapshotController.mPendingDeleteActivity.size());
        assertEquals(openingWindow.mActivityRecord,
                mActivitySnapshotController.mPendingDeleteActivity.valueAt(0));
        mActivitySnapshotController.resetTmpFields();

        // simulate three activity
        final WindowState belowOpen = createAppWindow(task, ACTIVITY_TYPE_STANDARD,
                "belowOpen");
        belowOpen.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);
        windows.add(belowOpen.mActivityRecord);
        mActivitySnapshotController.handleTransitionFinish(windows);
        assertEquals(1, mActivitySnapshotController.mPendingDeleteActivity.size());
        assertEquals(1, mActivitySnapshotController.mPendingLoadActivity.size());
        assertEquals(openingWindow.mActivityRecord,
                mActivitySnapshotController.mPendingDeleteActivity.valueAt(0));
        assertEquals(belowOpen.mActivityRecord,
                mActivitySnapshotController.mPendingLoadActivity.valueAt(0));
    }

    @Test
    public void testTaskTransition() {
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
        final ArrayList<WindowContainer> windows = new ArrayList<>();
        windows.add(closeTask);
        windows.add(openTask);
        mActivitySnapshotController.handleTransitionFinish(windows);

        assertEquals(1, mActivitySnapshotController.mPendingRemoveActivity.size());
        assertEquals(closingWindowBelow.mActivityRecord,
                mActivitySnapshotController.mPendingRemoveActivity.valueAt(0));
        assertEquals(1, mActivitySnapshotController.mPendingLoadActivity.size());
        assertEquals(openingWindowBelow.mActivityRecord,
                mActivitySnapshotController.mPendingLoadActivity.valueAt(0));
    }
}
