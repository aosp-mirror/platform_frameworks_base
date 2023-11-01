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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.content.ComponentName;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.view.Surface;
import android.window.TaskSnapshot;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

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

    /**
     * Simulate multiple TaskFragments inside a task.
     */
    @Test
    public void testMultipleActivitiesLoadSnapshot() {
        final Task testTask = createTask(mDisplayContent);
        final ActivityRecord activityA = createActivityRecord(testTask);
        final ActivityRecord activityB = createActivityRecord(testTask);
        final ActivityRecord activityC = createActivityRecord(testTask);
        final TaskSnapshot taskSnapshot = createSnapshot();

        final int[] mixedCode = new int[3];
        mixedCode[0] = ActivitySnapshotController.getSystemHashCode(activityA);
        mixedCode[1] = ActivitySnapshotController.getSystemHashCode(activityB);
        mixedCode[2] = ActivitySnapshotController.getSystemHashCode(activityC);

        mActivitySnapshotController.addUserSavedFile(testTask.mUserId, taskSnapshot, mixedCode);
        mActivitySnapshotController.mCache.putSnapshot(activityA, taskSnapshot);
        mActivitySnapshotController.mCache.putSnapshot(activityB, taskSnapshot);
        mActivitySnapshotController.mCache.putSnapshot(activityC, taskSnapshot);

        assertTrue(mActivitySnapshotController.hasRecord(activityA));
        assertTrue(mActivitySnapshotController.hasRecord(activityB));

        // If A is removed, B and C should also be removed because they share the same snapshot.
        mActivitySnapshotController.onAppRemoved(activityA);
        assertFalse(mActivitySnapshotController.hasRecord(activityA));
        assertFalse(mActivitySnapshotController.hasRecord(activityB));
        final ActivityRecord[] singleActivityList = new ActivityRecord[1];
        singleActivityList[0] = activityA;
        assertNull(mActivitySnapshotController.getSnapshot(singleActivityList));
        singleActivityList[0] = activityB;
        assertNull(mActivitySnapshotController.getSnapshot(singleActivityList));
        final ActivityRecord[] activities = new ActivityRecord[3];
        activities[0] = activityA;
        activities[1] = activityB;
        activities[2] = activityC;
        assertNull(mActivitySnapshotController.getSnapshot(activities));

        // Reset and test load snapshot
        mActivitySnapshotController.addUserSavedFile(testTask.mUserId, taskSnapshot, mixedCode);
        // Request to load by B, nothing will be loaded because the snapshot was [A,B,C].
        mActivitySnapshotController.mPendingLoadActivity.add(activityB);
        mActivitySnapshotController.loadActivitySnapshot();
        verify(mActivitySnapshotController, never()).loadSnapshotInner(any(), any());

        // Able to load snapshot when requesting for all A, B, C
        mActivitySnapshotController.mPendingLoadActivity.clear();
        mActivitySnapshotController.mPendingLoadActivity.add(activityA);
        mActivitySnapshotController.mPendingLoadActivity.add(activityB);
        mActivitySnapshotController.mPendingLoadActivity.add(activityC);
        final ArraySet<ActivityRecord> verifyList = new ArraySet<>();
        verifyList.add(activityA);
        verifyList.add(activityB);
        verifyList.add(activityC);
        mActivitySnapshotController.loadActivitySnapshot();
        verify(mActivitySnapshotController).loadSnapshotInner(argThat(
                argument -> {
                    final ArrayList<ActivityRecord> argumentList = new ArrayList<>(
                            Arrays.asList(argument));
                    return verifyList.containsAll(argumentList)
                            && argumentList.containsAll(verifyList);
                }),
                any());

        for (int i = activities.length - 1; i >= 0; --i) {
            mActivitySnapshotController.mCache.putSnapshot(activities[i], taskSnapshot);
        }
        // The loaded snapshot can be retrieved only if the activities match exactly.
        singleActivityList[0] = activityB;
        assertNull(mActivitySnapshotController.getSnapshot(singleActivityList));
        assertEquals(taskSnapshot, mActivitySnapshotController.getSnapshot(activities));
    }

    private TaskSnapshot createSnapshot() {
        HardwareBuffer buffer = mock(HardwareBuffer.class);
        doReturn(100).when(buffer).getWidth();
        doReturn(100).when(buffer).getHeight();
        return new TaskSnapshot(1, 0 /* captureTime */, new ComponentName("", ""), buffer,
                ColorSpace.get(ColorSpace.Named.SRGB), ORIENTATION_PORTRAIT,
                Surface.ROTATION_0, new Point(100, 100), new Rect() /* contentInsets */,
                new Rect() /* letterboxInsets*/, false /* isLowResolution */,
                true /* isRealSnapshot */, WINDOWING_MODE_FULLSCREEN, 0 /* mSystemUiVisibility */,
                false /* isTranslucent */, false /* hasImeSurface */);
    }
}
