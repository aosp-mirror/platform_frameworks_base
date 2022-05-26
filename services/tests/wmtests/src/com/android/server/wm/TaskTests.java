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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.IWindowManager.FIXED_TO_USER_ROTATION_ENABLED;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.policy.WindowManagerPolicy.USER_ROTATION_FREE;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_TASK_ORG;
import static com.android.server.wm.TaskFragment.TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.filters.MediumTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * Test class for {@link Task}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskTests extends WindowTestsBase {

    private static final String TASK_TAG = "task";

    private Rect mParentBounds;

    @Before
    public void setUp() throws Exception {
        mParentBounds = new Rect(10 /*left*/, 30 /*top*/, 80 /*right*/, 60 /*bottom*/);
        removeGlobalMinSizeRestriction();
    }

    @Test
    public void testRemoveContainer() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);

        task.remove(false /* withTransition */, "testRemoveContainer");
        // There is still an activity to be destroyed, so the task is not removed immediately.
        assertNotNull(task.getParent());
        assertTrue(rootTask.hasChild());
        assertTrue(task.hasChild());
        assertTrue(activity.finishing);

        activity.destroyed("testRemoveContainer");
        // Assert that the container was removed after the activity is destroyed.
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
        assertNull(activity.getParent());
        verify(mAtm.getLockTaskController(), atLeast(1)).clearLockedTask(task);
        verify(mAtm.getLockTaskController(), atLeast(1)).clearLockedTask(rootTask);
    }

    @Test
    public void testRemoveContainer_multipleNestedTasks() {
        final Task rootTask = createTask(mDisplayContent);
        rootTask.mCreatedByOrganizer = true;
        final Task task1 = new TaskBuilder(mSupervisor).setParentTaskFragment(rootTask).build();
        final Task task2 = new TaskBuilder(mSupervisor).setParentTaskFragment(rootTask).build();
        final ActivityRecord activity1 = createActivityRecord(task1);
        final ActivityRecord activity2 = createActivityRecord(task2);
        activity1.setVisible(false);

        // All activities under the root task should be finishing.
        rootTask.remove(true /* withTransition */, "test");
        assertTrue(activity1.finishing);
        assertTrue(activity2.finishing);

        // After all activities activities are destroyed, the root task should also be removed.
        activity1.removeImmediately();
        activity2.removeImmediately();
        assertFalse(rootTask.isAttached());
    }

    @Test
    public void testRemoveContainer_deferRemoval() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);

        doReturn(true).when(task).shouldDeferRemoval();

        task.removeIfPossible();
        // For the case of deferred removal the task will still be connected to the its app token
        // until the task window container is removed.
        assertNotNull(task.getParent());
        assertNotEquals(0, task.getChildCount());
        assertNotNull(activity.getParent());

        task.removeImmediately();
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
        assertNull(activity.getParent());
    }

    @Test
    public void testReparent() {
        final Task taskController1 = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(taskController1, 0 /* userId */);
        final Task taskController2 = createTask(mDisplayContent);
        final Task task2 = createTaskInRootTask(taskController2, 0 /* userId */);

        boolean gotException = false;
        try {
            task.reparent(taskController1, 0, false/* moveParents */, "testReparent");
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue("Should not be able to reparent to the same parent", gotException);

        gotException = false;
        try {
            task.reparent(null, 0, false/* moveParents */, "testReparent");
        } catch (Exception e) {
            gotException = true;
        }
        assertTrue("Should not be able to reparent to a task that doesn't exist", gotException);

        task.reparent(taskController2, 0, false/* moveParents */, "testReparent");
        assertEquals(taskController2, task.getParent());
        assertEquals(0, task.getParent().mChildren.indexOf(task));
        assertEquals(1, task2.getParent().mChildren.indexOf(task2));
    }

    @Test
    public void testReparent_BetweenDisplays() {
        // Create first task on primary display.
        final Task rootTask1 = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask1, 0 /* userId */);
        assertEquals(mDisplayContent, rootTask1.getDisplayContent());

        // Create second display and put second task on it.
        final DisplayContent dc = createNewDisplay();
        final Task rootTask2 = createTask(dc);
        final Task task2 = createTaskInRootTask(rootTask2, 0 /* userId */);
        // Reparent and check state
        clearInvocations(task);  // reset the number of onDisplayChanged for task.
        task.reparent(rootTask2, 0, false /* moveParents */, "testReparent_BetweenDisplays");
        assertEquals(rootTask2, task.getParent());
        assertEquals(0, task.getParent().mChildren.indexOf(task));
        assertEquals(1, task2.getParent().mChildren.indexOf(task2));
        verify(task, times(1)).onDisplayChanged(any());
    }

    @Test
    public void testBounds() {
        final Task rootTask1 = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask1, 0 /* userId */);

        // Check that setting bounds also updates surface position
        task.setWindowingMode(WINDOWING_MODE_FREEFORM);
        Rect bounds = new Rect(10, 10, 100, 200);
        task.setBounds(bounds);
        assertEquals(new Point(bounds.left, bounds.top), task.getLastSurfacePosition());
    }

    @Test
    public void testIsInTask() {
        final Task task1 = createTask(mDisplayContent);
        final Task task2 = createTask(mDisplayContent);
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent, task1);
        final ActivityRecord activity2 = createActivityRecord(mDisplayContent, task2);
        assertEquals(activity1, task1.isInTask(activity1));
        assertNull(task1.isInTask(activity2));
    }

    @Test
    public void testPerformClearTop() {
        final Task task = createTask(mDisplayContent);
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setTask(task).build();
        // Detach from process so the activities can be removed from hierarchy when finishing.
        activity1.detachFromProcess();
        activity2.detachFromProcess();
        assertNull(task.performClearTop(activity1, 0 /* launchFlags */));
        assertFalse(task.hasChild());
        // In real case, the task should be preserved for adding new activity.
        assertTrue(task.isAttached());
    }

    @Test
    public void testRemoveChildForOverlayTask() {
        final Task task = createTask(mDisplayContent);
        final int taskId = task.mTaskId;
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent, task);
        final ActivityRecord activity2 = createActivityRecord(mDisplayContent, task);
        final ActivityRecord activity3 = createActivityRecord(mDisplayContent, task);
        activity1.setTaskOverlay(true);
        activity2.setTaskOverlay(true);
        activity3.setTaskOverlay(true);

        assertEquals(3, task.getChildCount());
        assertTrue(task.onlyHasTaskOverlayActivities(true));

        task.removeChild(activity1);

        verify(task.mTaskSupervisor).removeTask(any(), anyBoolean(), anyBoolean(), anyString());
        assertEquals(2, task.getChildCount());
        task.forAllActivities((r) -> {
            assertTrue(r.finishing);
        });
    }

    @Test
    public void testSwitchUser() {
        final Task rootTask = createTask(mDisplayContent);
        final Task childTask = createTaskInRootTask(rootTask, 0 /* userId */);
        final Task leafTask1 = createTaskInRootTask(childTask, 10 /* userId */);
        final Task leafTask2 = createTaskInRootTask(childTask, 0 /* userId */);
        assertEquals(1, rootTask.getChildCount());
        assertEquals(leafTask2, childTask.getTopChild());

        doReturn(true).when(leafTask1).showToCurrentUser();
        rootTask.switchUser(10);
        assertEquals(1, rootTask.getChildCount());
        assertEquals(leafTask1, childTask.getTopChild());
    }

    @Test
    public void testEnsureActivitiesVisible() {
        final Task rootTask = createTask(mDisplayContent);
        final Task leafTask1 = createTaskInRootTask(rootTask, 0 /* userId */);
        final Task leafTask2 = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent, leafTask1);
        final ActivityRecord activity2 = createActivityRecord(mDisplayContent, leafTask2);

        // Check visibility of occluded tasks
        doReturn(false).when(leafTask1).shouldBeVisible(any());
        doReturn(true).when(leafTask2).shouldBeVisible(any());
        rootTask.ensureActivitiesVisible(
                null /* starting */ , 0 /* configChanges */, false /* preserveWindows */);
        assertFalse(activity1.isVisible());
        assertTrue(activity2.isVisible());

        // Check visibility of not occluded tasks
        doReturn(true).when(leafTask1).shouldBeVisible(any());
        doReturn(true).when(leafTask2).shouldBeVisible(any());
        rootTask.ensureActivitiesVisible(
                null /* starting */ , 0 /* configChanges */, false /* preserveWindows */);
        assertTrue(activity1.isVisible());
        assertTrue(activity2.isVisible());
    }

    @Test
    public void testResolveNonResizableTaskWindowingMode() {
        // Test with no support non-resizable in multi window regardless the screen size.
        mAtm.mSupportsNonResizableMultiWindow = -1;

        final Task task = createTask(mDisplayContent);
        Configuration parentConfig = task.getParent().getConfiguration();
        parentConfig.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);
        doReturn(false).when(task).isResizeable();
        WindowConfiguration requestedOverride =
                task.getRequestedOverrideConfiguration().windowConfiguration;
        WindowConfiguration resolvedOverride =
                task.getResolvedOverrideConfiguration().windowConfiguration;

        // The resolved override windowing mode of a non-resizeable task should be resolved as
        // fullscreen even as a child of a freeform display.
        requestedOverride.setWindowingMode(WINDOWING_MODE_UNDEFINED);
        task.resolveOverrideConfiguration(parentConfig);
        assertThat(resolvedOverride.getWindowingMode()).isEqualTo(WINDOWING_MODE_FULLSCREEN);

        // The resolved override windowing mode of a non-resizeable task should be resolved as
        // fullscreen, even when requested as freeform windowing mode
        requestedOverride.setWindowingMode(WINDOWING_MODE_FREEFORM);
        task.resolveOverrideConfiguration(parentConfig);
        assertThat(resolvedOverride.getWindowingMode()).isEqualTo(WINDOWING_MODE_FULLSCREEN);

        // The resolved override windowing mode of a non-resizeable task can be undefined as long
        // as its parents is not in multi-window mode.
        parentConfig.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        requestedOverride.setWindowingMode(WINDOWING_MODE_UNDEFINED);
        task.resolveOverrideConfiguration(parentConfig);
        assertThat(resolvedOverride.getWindowingMode()).isEqualTo(WINDOWING_MODE_UNDEFINED);
    }

    @Test
    public void testHandlesOrientationChangeFromDescendant() {
        final Task rootTask = createTask(mDisplayContent,
                WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        final Task leafTask1 = createTaskInRootTask(rootTask, 0 /* userId */);
        final Task leafTask2 = createTaskInRootTask(rootTask, 0 /* userId */);
        leafTask1.getWindowConfiguration().setActivityType(ACTIVITY_TYPE_HOME);
        leafTask2.getWindowConfiguration().setActivityType(ACTIVITY_TYPE_STANDARD);

        assertEquals(leafTask2, rootTask.getTopChild());
        assertTrue(rootTask.handlesOrientationChangeFromDescendant());
        // Treat orientation request from home as handled.
        assertTrue(leafTask1.handlesOrientationChangeFromDescendant());
        // Orientation request from standard activity in multi window will not be handled.
        assertFalse(leafTask2.handlesOrientationChangeFromDescendant());
    }

    @Test
    public void testAlwaysOnTop() {
        final Task task = createTask(mDisplayContent);
        task.setAlwaysOnTop(true);
        task.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertTrue(task.isAlwaysOnTop());

        task.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, true /* set */);
        assertFalse(task.isAlwaysOnTop());
    }

    @Test
    public void testRestoreWindowedTask() throws Exception {
        final Task expected = createTask(64);
        expected.mLastNonFullscreenBounds = new Rect(50, 50, 100, 100);

        final byte[] serializedBytes = serializeToBytes(expected);
        final Task actual = restoreFromBytes(serializedBytes);
        assertEquals(expected.mTaskId, actual.mTaskId);
        assertEquals(expected.mLastNonFullscreenBounds, actual.mLastNonFullscreenBounds);
    }

    /** Ensure we have no chance to modify the original intent. */
    @Test
    public void testCopyBaseIntentForTaskInfo() {
        final Task task = createTask(1);
        task.setTaskDescription(new ActivityManager.TaskDescription());
        final TaskInfo info = task.getTaskInfo();

        // The intent of info should be a copy so assert that they are different instances.
        Assert.assertThat(info.baseIntent, not(sameInstance(task.getBaseIntent())));
    }

    @Test
    public void testReturnsToHomeRootTask() throws Exception {
        final Task task = createTask(1);
        spyOn(task);
        doReturn(true).when(task).hasChild();
        assertFalse(task.returnsToHomeRootTask());
        task.intent = null;
        assertFalse(task.returnsToHomeRootTask());
        task.intent = new Intent();
        assertFalse(task.returnsToHomeRootTask());
        task.intent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME);
        assertTrue(task.returnsToHomeRootTask());
    }

    /** Ensures that empty bounds cause appBounds to inherit from parent. */
    @Test
    public void testAppBounds_EmptyBounds() {
        final Rect emptyBounds = new Rect();
        testRootTaskBoundsConfiguration(WINDOWING_MODE_FULLSCREEN, mParentBounds, emptyBounds,
                mParentBounds);
    }

    /** Ensures that bounds on freeform root tasks are not clipped. */
    @Test
    public void testAppBounds_FreeFormBounds() {
        final Rect freeFormBounds = new Rect(mParentBounds);
        freeFormBounds.offset(10, 10);
        testRootTaskBoundsConfiguration(WINDOWING_MODE_FREEFORM, mParentBounds, freeFormBounds,
                freeFormBounds);
    }

    /** Ensures that fully contained bounds are not clipped. */
    @Test
    public void testAppBounds_ContainedBounds() {
        final Rect insetBounds = new Rect(mParentBounds);
        insetBounds.inset(5, 5, 5, 5);
        testRootTaskBoundsConfiguration(
                WINDOWING_MODE_FREEFORM, mParentBounds, insetBounds, insetBounds);
    }

    @Test
    public void testFitWithinBounds() {
        final Rect parentBounds = new Rect(10, 10, 200, 200);
        TaskDisplayArea taskDisplayArea = mAtm.mRootWindowContainer.getDefaultTaskDisplayArea();
        Task rootTask = taskDisplayArea.createRootTask(WINDOWING_MODE_FREEFORM,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        Task task = new TaskBuilder(mSupervisor).setParentTaskFragment(rootTask).build();
        final Configuration parentConfig = rootTask.getConfiguration();
        parentConfig.windowConfiguration.setBounds(parentBounds);
        parentConfig.densityDpi = DisplayMetrics.DENSITY_DEFAULT;

        // check top and left
        Rect reqBounds = new Rect(-190, -190, 0, 0);
        task.setBounds(reqBounds);
        // Make sure part of it is exposed
        assertTrue(task.getBounds().right > parentBounds.left);
        assertTrue(task.getBounds().bottom > parentBounds.top);
        // Should still be more-or-less in that corner
        assertTrue(task.getBounds().left <= parentBounds.left);
        assertTrue(task.getBounds().top <= parentBounds.top);

        assertEquals(reqBounds.width(), task.getBounds().width());
        assertEquals(reqBounds.height(), task.getBounds().height());

        // check bottom and right
        reqBounds = new Rect(210, 210, 400, 400);
        task.setBounds(reqBounds);
        // Make sure part of it is exposed
        assertTrue(task.getBounds().left < parentBounds.right);
        assertTrue(task.getBounds().top < parentBounds.bottom);
        // Should still be more-or-less in that corner
        assertTrue(task.getBounds().right >= parentBounds.right);
        assertTrue(task.getBounds().bottom >= parentBounds.bottom);

        assertEquals(reqBounds.width(), task.getBounds().width());
        assertEquals(reqBounds.height(), task.getBounds().height());
    }

    /**
     * Tests that a task with forced orientation has orientation-consistent bounds within the
     * parent.
     */
    @Test
    public void testFullscreenBoundsForcedOrientation() {
        final Rect fullScreenBounds = new Rect(0, 0, 1920, 1080);
        final Rect fullScreenBoundsPort = new Rect(0, 0, 1080, 1920);
        final DisplayContent display = new TestDisplayContent.Builder(mAtm,
                fullScreenBounds.width(), fullScreenBounds.height()).setCanRotate(false).build();
        assertNotNull(mRootWindowContainer.getDisplayContent(display.mDisplayId));
        // Fix the display orientation to landscape which is the natural rotation (0) for the test
        // display.
        final DisplayRotation dr = display.mDisplayContent.getDisplayRotation();
        dr.setFixedToUserRotation(FIXED_TO_USER_ROTATION_ENABLED);
        dr.setUserRotation(USER_ROTATION_FREE, ROTATION_0);

        final Task rootTask = new TaskBuilder(mSupervisor).setCreateActivity(true)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).setDisplay(display).build();
        final Task task = rootTask.getBottomMostTask();
        final ActivityRecord root = task.getTopNonFinishingActivity();

        assertEquals(fullScreenBounds, task.getBounds());

        // Setting app to fixed portrait fits within parent
        root.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(root, task.getRootActivity());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, task.getRootActivity().getOrientation());
        // Portrait orientation is enforced on activity level. Task should fill fullscreen bounds.
        assertThat(task.getBounds().height()).isLessThan(task.getBounds().width());
        assertEquals(fullScreenBounds, task.getBounds());

        // Top activity gets used
        final ActivityRecord top = new ActivityBuilder(mAtm).setTask(task).setParentTask(rootTask)
                .build();
        assertEquals(top, task.getTopNonFinishingActivity());
        top.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertThat(task.getBounds().width()).isGreaterThan(task.getBounds().height());
        assertEquals(task.getBounds().width(), fullScreenBounds.width());

        // Setting app to unspecified restores
        top.setRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        assertEquals(fullScreenBounds, task.getBounds());

        // Setting app to fixed landscape and changing display
        top.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        // Fix the display orientation to portrait which is 90 degrees for the test display.
        dr.setUserRotation(USER_ROTATION_FREE, ROTATION_90);

        // Fixed orientation request should be resolved on activity level. Task fills display
        // bounds.
        assertThat(task.getBounds().height()).isGreaterThan(task.getBounds().width());
        assertThat(top.getBounds().width()).isGreaterThan(top.getBounds().height());
        assertEquals(fullScreenBoundsPort, task.getBounds());

        // in FREEFORM, no constraint
        final Rect freeformBounds = new Rect(display.getBounds());
        freeformBounds.inset((int) (freeformBounds.width() * 0.2),
                (int) (freeformBounds.height() * 0.2));
        rootTask.setWindowingMode(WINDOWING_MODE_FREEFORM);
        task.setBounds(freeformBounds);
        assertEquals(freeformBounds, task.getBounds());

        // FULLSCREEN letterboxes bounds on activity level, no constraint on task level.
        rootTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        rootTask.setBounds(null);
        assertThat(task.getBounds().height()).isGreaterThan(task.getBounds().width());
        assertThat(top.getBounds().width()).isGreaterThan(top.getBounds().height());
        assertEquals(fullScreenBoundsPort, task.getBounds());

        // FREEFORM restores bounds as before
        rootTask.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(freeformBounds, task.getBounds());
    }

    @Test
    public void testReportsOrientationRequestInLetterboxForOrientation() {
        final Rect fullScreenBounds = new Rect(0, 0, 1920, 1080);
        final Rect fullScreenBoundsPort = new Rect(0, 0, 1080, 1920);
        final DisplayContent display = new TestDisplayContent.Builder(mAtm,
                fullScreenBounds.width(), fullScreenBounds.height()).setCanRotate(false).build();
        assertNotNull(mRootWindowContainer.getDisplayContent(display.mDisplayId));
        // Fix the display orientation to landscape which is the natural rotation (0) for the test
        // display.
        final DisplayRotation dr = display.mDisplayContent.getDisplayRotation();
        dr.setFixedToUserRotation(FIXED_TO_USER_ROTATION_ENABLED);
        dr.setUserRotation(USER_ROTATION_FREE, ROTATION_0);

        final Task rootTask = new TaskBuilder(mSupervisor).setCreateActivity(true)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).setDisplay(display).build();
        final Task task = rootTask.getBottomMostTask();
        ActivityRecord root = task.getTopNonFinishingActivity();

        assertEquals(fullScreenBounds, task.getBounds());

        // Setting app to fixed portrait fits within parent on activity level. Task fills parent.
        root.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertThat(root.getBounds().width()).isLessThan(root.getBounds().height());
        assertEquals(task.getBounds(), fullScreenBounds);

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, task.getOrientation());
    }

    @Test
    public void testIgnoresForcedOrientationWhenParentHandles() {
        final Rect fullScreenBounds = new Rect(0, 0, 1920, 1080);
        DisplayContent display = new TestDisplayContent.Builder(
                mAtm, fullScreenBounds.width(), fullScreenBounds.height()).build();

        display.getRequestedOverrideConfiguration().orientation =
                Configuration.ORIENTATION_LANDSCAPE;
        display.onRequestedOverrideConfigurationChanged(
                display.getRequestedOverrideConfiguration());
        Task rootTask = new TaskBuilder(mSupervisor).setCreateActivity(true)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).setDisplay(display).build();
        Task task = rootTask.getBottomMostTask();
        ActivityRecord root = task.getTopNonFinishingActivity();

        final WindowContainer parentWindowContainer =
                new WindowContainer(mSystemServicesTestRule.getWindowManagerService());
        spyOn(parentWindowContainer);
        parentWindowContainer.setBounds(fullScreenBounds);
        doReturn(parentWindowContainer).when(task).getParent();
        doReturn(display.getDefaultTaskDisplayArea()).when(task).getDisplayArea();
        doReturn(rootTask).when(task).getRootTask();
        doReturn(true).when(parentWindowContainer).handlesOrientationChangeFromDescendant();

        // Setting app to fixed portrait fits within parent, but Task shouldn't adjust the
        // bounds because its parent says it will handle it at a later time.
        root.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(root, task.getRootActivity());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, task.getRootActivity().getOrientation());
        assertEquals(fullScreenBounds, task.getBounds());
    }

    @Test
    public void testComputeConfigResourceOverrides() {
        final Rect fullScreenBounds = new Rect(0, 0, 1080, 1920);
        TestDisplayContent display = new TestDisplayContent.Builder(
                mAtm, fullScreenBounds.width(), fullScreenBounds.height()).build();
        final Task task = new TaskBuilder(mSupervisor).setDisplay(display).build();
        final Configuration inOutConfig = new Configuration();
        final Configuration parentConfig = new Configuration();
        final Rect parentBounds = new Rect(0, 0, 250, 500);
        final Rect parentAppBounds = new Rect(0, 0, 250, 480);
        parentConfig.windowConfiguration.setBounds(parentBounds);
        parentConfig.windowConfiguration.setAppBounds(parentAppBounds);
        parentConfig.densityDpi = 400;
        parentConfig.screenHeightDp = (parentBounds.bottom * 160) / parentConfig.densityDpi; // 200
        parentConfig.screenWidthDp = (parentBounds.right * 160) / parentConfig.densityDpi; // 100
        parentConfig.windowConfiguration.setRotation(ROTATION_0);

        // By default, the input bounds will fill parent.
        task.computeConfigResourceOverrides(inOutConfig, parentConfig);

        assertEquals(parentConfig.screenHeightDp, inOutConfig.screenHeightDp);
        assertEquals(parentConfig.screenWidthDp, inOutConfig.screenWidthDp);
        assertEquals(parentAppBounds, inOutConfig.windowConfiguration.getAppBounds());
        assertEquals(Configuration.ORIENTATION_PORTRAIT, inOutConfig.orientation);

        // If bounds are overridden, config properties should be made to match. Surface hierarchy
        // will crop for policy.
        inOutConfig.setToDefaults();
        final int longSide = 960;
        final int shortSide = 540;
        parentConfig.densityDpi = 192;
        final Rect largerPortraitBounds = new Rect(0, 0, shortSide, longSide);
        inOutConfig.windowConfiguration.setBounds(largerPortraitBounds);
        task.computeConfigResourceOverrides(inOutConfig, parentConfig);
        // The override bounds are beyond the parent, the out appBounds should not be intersected
        // by parent appBounds.
        assertEquals(largerPortraitBounds, inOutConfig.windowConfiguration.getAppBounds());
        assertEquals(800, inOutConfig.screenHeightDp); // 960/(192/160) = 800
        assertEquals(450, inOutConfig.screenWidthDp); // 540/(192/160) = 450

        inOutConfig.setToDefaults();
        // Landscape bounds.
        final Rect largerLandscapeBounds = new Rect(0, 0, longSide, shortSide);
        inOutConfig.windowConfiguration.setBounds(largerLandscapeBounds);

        // Setup the display with a top stable inset. The later assertion will ensure the inset is
        // excluded from screenHeightDp.
        final int statusBarHeight = 100;
        final DisplayPolicy policy = display.getDisplayPolicy();
        doAnswer(invocationOnMock -> {
            final Rect insets = invocationOnMock.<Rect>getArgument(0);
            insets.top = statusBarHeight;
            return null;
        }).when(policy).convertNonDecorInsetsToStableInsets(any(), eq(ROTATION_0));

        // Without limiting to be inside the parent bounds, the out screen size should keep relative
        // to the input bounds.
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();
        final ActivityRecord.CompatDisplayInsets compatInsets =
                new ActivityRecord.CompatDisplayInsets(
                        display, activity, /* fixedOrientationBounds= */ null);
        task.computeConfigResourceOverrides(inOutConfig, parentConfig, compatInsets);

        assertEquals(largerLandscapeBounds, inOutConfig.windowConfiguration.getAppBounds());
        final float density = parentConfig.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        final int expectedHeightDp = (int) ((shortSide - statusBarHeight) / density + 0.5f);
        assertEquals(expectedHeightDp, inOutConfig.screenHeightDp);
        final int expectedWidthDp = (int) (longSide / density + 0.5f);
        assertEquals(expectedWidthDp, inOutConfig.screenWidthDp);
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, inOutConfig.orientation);
    }

    @Test
    public void testComputeConfigResourceLayoutOverrides() {
        final Rect fullScreenBounds = new Rect(0, 0, 1000, 2500);
        TestDisplayContent display = new TestDisplayContent.Builder(
                mAtm, fullScreenBounds.width(), fullScreenBounds.height()).build();
        final Task task = new TaskBuilder(mSupervisor).setDisplay(display).build();
        final Configuration inOutConfig = new Configuration();
        final Configuration parentConfig = new Configuration();
        final Rect nonLongBounds = new Rect(0, 0, 1000, 1250);
        parentConfig.windowConfiguration.setBounds(fullScreenBounds);
        parentConfig.windowConfiguration.setAppBounds(fullScreenBounds);
        parentConfig.densityDpi = 400;
        parentConfig.screenHeightDp = (fullScreenBounds.bottom * 160) / parentConfig.densityDpi;
        parentConfig.screenWidthDp = (fullScreenBounds.right * 160) / parentConfig.densityDpi;
        parentConfig.windowConfiguration.setRotation(ROTATION_0);

        // Set BOTH screenW/H to an override value
        inOutConfig.screenWidthDp = nonLongBounds.width() * 160 / parentConfig.densityDpi;
        inOutConfig.screenHeightDp = nonLongBounds.height() * 160 / parentConfig.densityDpi;
        task.computeConfigResourceOverrides(inOutConfig, parentConfig);

        // screenLayout should honor override when both screenW/H are set.
        assertTrue((inOutConfig.screenLayout & Configuration.SCREENLAYOUT_LONG_NO) != 0);
    }

    @Test
    public void testComputeNestedConfigResourceOverrides() {
        final Task task = new TaskBuilder(mSupervisor).build();
        assertTrue(task.getResolvedOverrideBounds().isEmpty());
        int origScreenH = task.getConfiguration().screenHeightDp;
        Configuration rootTaskConfig = new Configuration();
        rootTaskConfig.setTo(task.getRootTask().getRequestedOverrideConfiguration());
        rootTaskConfig.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);

        // Set bounds on root task (not task) and verify that the task resource configuration
        // changes despite it's override bounds being empty.
        Rect bounds = new Rect(task.getRootTask().getBounds());
        bounds.bottom = (int) (bounds.bottom * 0.6f);
        rootTaskConfig.windowConfiguration.setBounds(bounds);
        task.getRootTask().onRequestedOverrideConfigurationChanged(rootTaskConfig);
        assertNotEquals(origScreenH, task.getConfiguration().screenHeightDp);
    }

    @Test
    public void testFullScreenTaskNotAdjustedByMinimalSize() {
        final Task fullscreenTask = new TaskBuilder(mSupervisor).build();
        final Rect originalTaskBounds = new Rect(fullscreenTask.getBounds());
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.windowLayout = new ActivityInfo.WindowLayout(0 /* width */, 0 /* widthFraction */,
                0 /* height */, 0 /* heightFraction */, 0 /* gravity */,
                originalTaskBounds.width() * 2 /* minWidth */,
                originalTaskBounds.height() * 2 /* minHeight */);
        fullscreenTask.setMinDimensions(aInfo);
        fullscreenTask.onConfigurationChanged(fullscreenTask.getParent().getConfiguration());

        assertEquals(originalTaskBounds, fullscreenTask.getBounds());
    }

    @Test
    public void testInsetDisregardedWhenFreeformOverlapsNavBar() {
        TaskDisplayArea taskDisplayArea = mAtm.mRootWindowContainer.getDefaultTaskDisplayArea();
        Task rootTask = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        DisplayInfo displayInfo = new DisplayInfo();
        mAtm.mContext.getDisplay().getDisplayInfo(displayInfo);
        final int displayHeight = displayInfo.logicalHeight;
        final Task task = new TaskBuilder(mSupervisor).setParentTaskFragment(rootTask).build();
        final Configuration inOutConfig = new Configuration();
        final Configuration parentConfig = new Configuration();
        final int longSide = 1200;
        final int shortSide = 600;
        parentConfig.densityDpi = 400;
        parentConfig.screenHeightDp = 200; // 200 * 400 / 160 = 500px
        parentConfig.screenWidthDp = 100; // 100 * 400 / 160 = 250px
        parentConfig.windowConfiguration.setRotation(ROTATION_0);

        final int longSideDp = 480; // longSide / density = 1200 / 400 * 160
        final int shortSideDp = 240; // shortSide / density = 600 / 400 * 160
        final int screenLayout = parentConfig.screenLayout
                & (Configuration.SCREENLAYOUT_LONG_MASK | Configuration.SCREENLAYOUT_SIZE_MASK);
        final int reducedScreenLayout =
                Configuration.reduceScreenLayout(screenLayout, longSideDp, shortSideDp);

        // Portrait bounds overlapping with navigation bar, without insets.
        final Rect freeformBounds = new Rect(0,
                displayHeight - 10 - longSide,
                shortSide,
                displayHeight - 10);
        inOutConfig.windowConfiguration.setBounds(freeformBounds);
        // Set to freeform mode to verify bug fix.
        inOutConfig.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);

        task.computeConfigResourceOverrides(inOutConfig, parentConfig);

        // screenW/H should not be effected by parent since overridden and freeform
        assertEquals(freeformBounds.width() * 160 / parentConfig.densityDpi,
                inOutConfig.screenWidthDp);
        assertEquals(freeformBounds.height() * 160 / parentConfig.densityDpi,
                inOutConfig.screenHeightDp);
        assertEquals(reducedScreenLayout, inOutConfig.screenLayout);

        inOutConfig.setToDefaults();
        // Landscape bounds overlapping with navigtion bar, without insets.
        freeformBounds.set(0,
                displayHeight - 10 - shortSide,
                longSide,
                displayHeight - 10);
        inOutConfig.windowConfiguration.setBounds(freeformBounds);
        inOutConfig.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);

        task.computeConfigResourceOverrides(inOutConfig, parentConfig);

        assertEquals(freeformBounds.width() * 160 / parentConfig.densityDpi,
                inOutConfig.screenWidthDp);
        assertEquals(freeformBounds.height() * 160 / parentConfig.densityDpi,
                inOutConfig.screenHeightDp);
        assertEquals(reducedScreenLayout, inOutConfig.screenLayout);
    }

    /** Ensures that the alias intent won't have target component resolved. */
    @Test
    public void testTaskIntentActivityAlias() {
        final String aliasClassName = DEFAULT_COMPONENT_PACKAGE_NAME + ".aliasActivity";
        final String targetClassName = DEFAULT_COMPONENT_PACKAGE_NAME + ".targetActivity";
        final ComponentName aliasComponent =
                new ComponentName(DEFAULT_COMPONENT_PACKAGE_NAME, aliasClassName);
        final ComponentName targetComponent =
                new ComponentName(DEFAULT_COMPONENT_PACKAGE_NAME, targetClassName);

        final Intent intent = new Intent();
        intent.setComponent(aliasComponent);
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.packageName = DEFAULT_COMPONENT_PACKAGE_NAME;
        info.targetActivity = targetClassName;

        final Task task = new Task.Builder(mAtm)
                .setTaskId(1)
                .setActivityInfo(info)
                .setIntent(intent)
                .build();
        assertEquals("The alias activity component should be saved in task intent.", aliasClassName,
                task.intent.getComponent().getClassName());

        ActivityRecord aliasActivity = new ActivityBuilder(mAtm).setComponent(
                aliasComponent).setTargetActivity(targetClassName).build();
        assertEquals("Should be the same intent filter.", true,
                task.isSameIntentFilter(aliasActivity));

        ActivityRecord targetActivity = new ActivityBuilder(mAtm).setComponent(
                targetComponent).build();
        assertEquals("Should be the same intent filter.", true,
                task.isSameIntentFilter(targetActivity));

        ActivityRecord defaultActivity = new ActivityBuilder(mAtm).build();
        assertEquals("Should not be the same intent filter.", false,
                task.isSameIntentFilter(defaultActivity));
    }

    /** Test that root activity index is reported correctly for several activities in the task. */
    @Test
    public void testFindRootIndex() {
        final Task task = getTestTask();
        // Add an extra activity on top of the root one
        new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals("The root activity in the task must be reported.", task.getChildAt(0),
                task.getRootActivity(
                        true /*ignoreRelinquishIdentity*/, true /*setToBottomIfNone*/));
    }

    /**
     * Test that root activity index is reported correctly for several activities in the task when
     * the activities on the bottom are finishing.
     */
    @Test
    public void testFindRootIndex_finishing() {
        final Task task = getTestTask();
        // Add extra two activities and mark the two on the bottom as finishing.
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.finishing = true;
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        activity1.finishing = true;
        new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals("The first non-finishing activity in the task must be reported.",
                task.getChildAt(2), task.getRootActivity(
                        true /*ignoreRelinquishIdentity*/, true /*setToBottomIfNone*/));
    }

    /**
     * Test that root activity index is reported correctly for several activities in the task when
     * looking for the 'effective root'.
     */
    @Test
    public void testFindRootIndex_effectiveRoot() {
        final Task task = getTestTask();
        // Add an extra activity on top of the root one
        new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals("The root activity in the task must be reported.",
                task.getChildAt(0), task.getRootActivity(
                        false /*ignoreRelinquishIdentity*/, true /*setToBottomIfNone*/));
    }

    /**
     * Test that root activity index is reported correctly when looking for the 'effective root' in
     * case when bottom activities are relinquishing task identity or finishing.
     */
    @Test
    public void testFindRootIndex_effectiveRoot_finishingAndRelinquishing() {
        final ActivityRecord activity0 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = activity0.getTask();
        // Add extra two activities. Mark the one on the bottom with "relinquishTaskIdentity" and
        // one above as finishing.
        activity0.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        activity1.finishing = true;
        new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals("The first non-finishing activity and non-relinquishing task identity "
                + "must be reported.", task.getChildAt(2), task.getRootActivity(
                false /*ignoreRelinquishIdentity*/, true /*setToBottomIfNone*/));
    }

    /**
     * Test that root activity index is reported correctly when looking for the 'effective root'
     * for the case when there is only a single activity that also has relinquishTaskIdentity set.
     */
    @Test
    public void testFindRootIndex_effectiveRoot_relinquishingAndSingleActivity() {
        final Task task = getTestTask();
        // Set relinquishTaskIdentity for the only activity in the task
        task.getBottomMostActivity().info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;

        assertEquals("The root activity in the task must be reported.",
                task.getChildAt(0), task.getRootActivity(
                        false /*ignoreRelinquishIdentity*/, true /*setToBottomIfNone*/));
    }

    /**
     * Test that the topmost activity index is reported correctly when looking for the
     * 'effective root' for the case when all activities have relinquishTaskIdentity set.
     */
    @Test
    public void testFindRootIndex_effectiveRoot_relinquishingMultipleActivities() {
        final ActivityRecord activity0 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = activity0.getTask();
        // Set relinquishTaskIdentity for all activities in the task
        activity0.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        activity1.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;

        assertEquals("The topmost activity in the task must be reported.",
                task.getChildAt(task.getChildCount() - 1), task.getRootActivity(
                        false /*ignoreRelinquishIdentity*/, true /*setToBottomIfNone*/));
    }

    /** Test that bottom-most activity is reported in {@link Task#getRootActivity()}. */
    @Test
    public void testGetRootActivity() {
        final Task task = getTestTask();
        // Add an extra activity on top of the root one
        new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals("The root activity in the task must be reported.",
                task.getBottomMostActivity(), task.getRootActivity());
    }

    /**
     * Test that first non-finishing activity is reported in {@link Task#getRootActivity()}.
     */
    @Test
    public void testGetRootActivity_finishing() {
        final Task task = getTestTask();
        // Add an extra activity on top of the root one
        new ActivityBuilder(mAtm).setTask(task).build();
        // Mark the root as finishing
        task.getBottomMostActivity().finishing = true;

        assertEquals("The first non-finishing activity in the task must be reported.",
                task.getChildAt(1), task.getRootActivity());
    }

    /**
     * Test that relinquishTaskIdentity flag is ignored in {@link Task#getRootActivity()}.
     */
    @Test
    public void testGetRootActivity_relinquishTaskIdentity() {
        final Task task = getTestTask();
        // Mark the bottom-most activity with FLAG_RELINQUISH_TASK_IDENTITY.
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;
        // Add an extra activity on top of the root one.
        new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals("The root activity in the task must be reported.",
                task.getBottomMostActivity(), task.getRootActivity());
    }

    /**
     * Test that no activity is reported in {@link Task#getRootActivity()} when all activities
     * in the task are finishing.
     */
    @Test
    public void testGetRootActivity_allFinishing() {
        final Task task = getTestTask();
        // Mark the bottom-most activity as finishing.
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.finishing = true;
        // Add an extra activity on top of the root one and mark it as finishing
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        activity1.finishing = true;

        assertNull("No activity must be reported if all are finishing", task.getRootActivity());
    }

    /**
     * Test that first non-finishing activity is the root of task.
     */
    @Test
    public void testIsRootActivity() {
        final Task task = getTestTask();
        // Mark the bottom-most activity as finishing.
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.finishing = true;
        // Add an extra activity on top of the root one.
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();

        assertFalse("Finishing activity must not be the root of task", activity0.isRootOfTask());
        assertTrue("Non-finishing activity must be the root of task", activity1.isRootOfTask());
    }

    /**
     * Test that if all activities in the task are finishing, then the one on the bottom is the
     * root of task.
     */
    @Test
    public void testIsRootActivity_allFinishing() {
        final Task task = getTestTask();
        // Mark the bottom-most activity as finishing.
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.finishing = true;
        // Add an extra activity on top of the root one and mark it as finishing
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        activity1.finishing = true;

        assertTrue("Bottom activity must be the root of task", activity0.isRootOfTask());
        assertFalse("Finishing activity on top must not be the root of task",
                activity1.isRootOfTask());
    }

    /**
     * Test {@link ActivityRecord#getTaskForActivityLocked(IBinder, boolean)}.
     */
    @Test
    public void testGetTaskForActivity() {
        final Task task0 = getTestTask();
        final ActivityRecord activity0 = task0.getBottomMostActivity();

        final Task task1 = getTestTask();
        final ActivityRecord activity1 = task1.getBottomMostActivity();

        assertEquals(task0.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity0.token, false /* onlyRoot */));
        assertEquals(task1.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity1.token,  false /* onlyRoot */));
    }

    /**
     * Test {@link ActivityRecord#getTaskForActivityLocked(IBinder, boolean)} with finishing
     * activity.
     */
    @Test
    public void testGetTaskForActivity_onlyRoot_finishing() {
        final Task task = getTestTask();
        // Make the current root activity finishing
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.finishing = true;
        // Add an extra activity on top - this will be the new root
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        // Add one more on top
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity0.token, true /* onlyRoot */));
        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity1.token, true /* onlyRoot */));
        assertEquals("No task must be reported for activity that is above root", INVALID_TASK_ID,
                ActivityRecord.getTaskForActivityLocked(activity2.token, true /* onlyRoot */));
    }

    /**
     * Test {@link ActivityRecord#getTaskForActivityLocked(IBinder, boolean)} with activity that
     * relinquishes task identity.
     */
    @Test
    public void testGetTaskForActivity_onlyRoot_relinquishTaskIdentity() {
        final ActivityRecord activity0 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = activity0.getTask();
        // Make the current root activity relinquish task identity
        activity0.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;
        // Add an extra activity on top - this will be the new root
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        // Add one more on top
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity0.token, true /* onlyRoot */));
        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity1.token, true /* onlyRoot */));
        assertEquals("No task must be reported for activity that is above root", INVALID_TASK_ID,
                ActivityRecord.getTaskForActivityLocked(activity2.token, true /* onlyRoot */));
    }

    /**
     * Test {@link ActivityRecord#getTaskForActivityLocked(IBinder, boolean)} allowing non-root
     * entries.
     */
    @Test
    public void testGetTaskForActivity_notOnlyRoot() {
        final Task task = getTestTask();
        // Mark the bottom-most activity as finishing.
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.finishing = true;

        // Add an extra activity on top of the root one and make it relinquish task identity
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        activity1.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;

        // Add one more activity on top
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setTask(task).build();

        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity0.token, false /* onlyRoot */));
        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity1.token, false /* onlyRoot */));
        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity2.token, false /* onlyRoot */));
    }

    /**
     * Test {@link Task#updateEffectiveIntent()}.
     */
    @Test
    public void testUpdateEffectiveIntent() {
        // Test simple case with a single activity.
        final Task task = getTestTask();
        final ActivityRecord activity0 = task.getBottomMostActivity();

        spyOn(task);
        task.updateEffectiveIntent();
        verify(task).setIntent(eq(activity0));
    }

    /**
     * Test {@link Task#updateEffectiveIntent()} with root activity marked as finishing. This
     * should make the task use the second activity when updating the intent.
     */
    @Test
    public void testUpdateEffectiveIntent_rootFinishing() {
        // Test simple case with a single activity.
        final Task task = getTestTask();
        final ActivityRecord activity0 = task.getBottomMostActivity();
        // Mark the bottom-most activity as finishing.
        activity0.finishing = true;
        // Add an extra activity on top of the root one
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();

        spyOn(task);
        task.updateEffectiveIntent();
        verify(task).setIntent(eq(activity1));
    }

    /**
     * Test {@link Task#updateEffectiveIntent()} when all activities are finishing or
     * relinquishing task identity. In this case the root activity should still be used when
     * updating the intent (legacy behavior).
     */
    @Test
    public void testUpdateEffectiveIntent_allFinishing() {
        // Test simple case with a single activity.
        final Task task = getTestTask();
        final ActivityRecord activity0 = task.getBottomMostActivity();
        // Mark the bottom-most activity as finishing.
        activity0.finishing = true;
        // Add an extra activity on top of the root one and make it relinquish task identity
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        activity1.finishing = true;

        // Task must still update the intent using the root activity (preserving legacy behavior).
        spyOn(task);
        task.updateEffectiveIntent();
        verify(task).setIntent(eq(activity0));
    }

    /**
     * Test {@link Task#updateEffectiveIntent()} when activity with relinquishTaskIdentity but
     * another with different uid. This should make the task use the root activity when updating the
     * intent.
     */
    @Test
    public void testUpdateEffectiveIntent_relinquishingWithDifferentUid() {
        final ActivityRecord activity0 = new ActivityBuilder(mAtm)
                .setActivityFlags(FLAG_RELINQUISH_TASK_IDENTITY).setCreateTask(true).build();
        final Task task = activity0.getTask();

        // Add an extra activity on top
        new ActivityBuilder(mAtm).setUid(11).setTask(task).build();

        spyOn(task);
        task.updateEffectiveIntent();
        verify(task).setIntent(eq(activity0));
    }

    /**
     * Test {@link Task#updateEffectiveIntent()} with activities set as relinquishTaskIdentity.
     * This should make the task use the topmost activity when updating the intent.
     */
    @Test
    public void testUpdateEffectiveIntent_relinquishingMultipleActivities() {
        final ActivityRecord activity0 = new ActivityBuilder(mAtm)
                .setActivityFlags(FLAG_RELINQUISH_TASK_IDENTITY).setCreateTask(true).build();
        final Task task = activity0.getTask();
        // Add an extra activity on top
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setTask(task).build();
        activity1.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;

        // Add an extra activity on top
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setTask(task).build();

        spyOn(task);
        task.updateEffectiveIntent();
        verify(task).setIntent(eq(activity2));
    }

    @Test
    public void testSaveLaunchingStateWhenConfigurationChanged() {
        LaunchParamsPersister persister = mAtm.mTaskSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = getTestTask();
        task.setHasBeenVisible(false);
        task.getDisplayContent().setDisplayWindowingMode(WINDOWING_MODE_FREEFORM);
        task.getRootTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        task.setHasBeenVisible(true);
        task.onConfigurationChanged(task.getParent().getConfiguration());

        verify(persister).saveTask(task, task.getDisplayContent());
    }

    @Test
    public void testSaveLaunchingStateWhenClearingParent() {
        LaunchParamsPersister persister = mAtm.mTaskSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = getTestTask();
        task.setHasBeenVisible(false);
        task.getDisplayContent().setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        task.getRootTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        final DisplayContent oldDisplay = task.getDisplayContent();

        LaunchParamsController.LaunchParams params = new LaunchParamsController.LaunchParams();
        params.mWindowingMode = WINDOWING_MODE_UNDEFINED;
        persister.getLaunchParams(task, null, params);
        assertEquals(WINDOWING_MODE_UNDEFINED, params.mWindowingMode);

        task.setHasBeenVisible(true);
        task.removeImmediately();

        verify(persister).saveTask(task, oldDisplay);

        persister.getLaunchParams(task, null, params);
        assertEquals(WINDOWING_MODE_FULLSCREEN, params.mWindowingMode);
    }

    @Test
    public void testNotSaveLaunchingStateNonFreeformDisplay() {
        LaunchParamsPersister persister = mAtm.mTaskSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = getTestTask();
        task.setHasBeenVisible(false);
        task.getRootTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        task.setHasBeenVisible(true);
        task.onConfigurationChanged(task.getParent().getConfiguration());

        Mockito.verify(persister, never()).saveTask(same(task), any());
    }

    @Test
    public void testNotSaveLaunchingStateWhenNotFullscreenOrFreeformWindow() {
        LaunchParamsPersister persister = mAtm.mTaskSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = getTestTask();
        task.setHasBeenVisible(false);
        task.getDisplayContent().setDisplayWindowingMode(WINDOWING_MODE_FREEFORM);
        task.getRootTask().setWindowingMode(WINDOWING_MODE_PINNED);

        task.setHasBeenVisible(true);
        task.onConfigurationChanged(task.getParent().getConfiguration());

        Mockito.verify(persister, never()).saveTask(same(task), any());
    }

    @Test
    public void testNotSaveLaunchingStateForNonLeafTask() {
        LaunchParamsPersister persister = mAtm.mTaskSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true)
                .setCreateParentTask(true).build().getRootTask();
        task.setHasBeenVisible(false);
        task.getDisplayContent().setDisplayWindowingMode(WINDOWING_MODE_FREEFORM);
        task.getRootTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        final Task leafTask = createTaskInRootTask(task, 0 /* userId */);

        leafTask.setHasBeenVisible(true);
        task.setHasBeenVisible(true);
        task.onConfigurationChanged(task.getParent().getConfiguration());

        Mockito.verify(persister, never()).saveTask(same(task), any());
        verify(persister).saveTask(same(leafTask), any());
    }

    @Test
    public void testNotSpecifyOrientationByFloatingTask() {
        final Task task = new TaskBuilder(mSupervisor)
                .setCreateActivity(true).setCreateParentTask(true).build();
        final ActivityRecord activity = task.getTopMostActivity();
        final WindowContainer<?> parentContainer = task.getParent();
        final TaskDisplayArea taskDisplayArea = task.getDisplayArea();
        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, parentContainer.getOrientation());
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, taskDisplayArea.getOrientation());

        task.setWindowingMode(WINDOWING_MODE_PINNED);

        // TDA returns the last orientation when child returns UNSET
        assertEquals(SCREEN_ORIENTATION_UNSET, parentContainer.getOrientation());
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, taskDisplayArea.getOrientation());
    }

    @Test
    public void testNotSpecifyOrientation_taskDisplayAreaNotFocused() {
        final TaskDisplayArea firstTaskDisplayArea = mDisplayContent.getDefaultTaskDisplayArea();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                mDisplayContent, mRootWindowContainer.mWmService, "TestTaskDisplayArea",
                FEATURE_VENDOR_FIRST);
        final Task firstRootTask = firstTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task secondRootTask = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(firstRootTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(secondRootTask).build();
        firstActivity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        secondActivity.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);

        // Activity on TDA1 is focused
        mDisplayContent.setFocusedApp(firstActivity);

        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, firstTaskDisplayArea.getOrientation());
        assertEquals(SCREEN_ORIENTATION_UNSET, secondTaskDisplayArea.getOrientation());

        // No focused app, TDA1 is still recorded as last focused.
        mDisplayContent.setFocusedApp(null);

        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, firstTaskDisplayArea.getOrientation());
        assertEquals(SCREEN_ORIENTATION_UNSET, secondTaskDisplayArea.getOrientation());

        // Activity on TDA2 is focused
        mDisplayContent.setFocusedApp(secondActivity);

        assertEquals(SCREEN_ORIENTATION_UNSET, firstTaskDisplayArea.getOrientation());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, secondTaskDisplayArea.getOrientation());
    }

    @Test
    public void testTaskOrientationOnDisplayWindowingModeChange() {
        // Skip unnecessary operations to speed up the test.
        mAtm.deferWindowLayout();
        final Task task = getTestTask();
        final ActivityRecord activity = task.getTopMostActivity();
        final DisplayContent display = task.getDisplayContent();
        mWm.setWindowingMode(display.mDisplayId, WINDOWING_MODE_FREEFORM);

        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertEquals(SCREEN_ORIENTATION_UNSET, task.getOrientation());
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, display.getLastOrientation());

        mWm.setWindowingMode(display.mDisplayId, WINDOWING_MODE_FULLSCREEN);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, task.getOrientation());
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, display.getLastOrientation());
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, display.getConfiguration().orientation);
    }

    @Test
    public void testGetNonNullDimmerOnUntrustedDisplays() {
        final DisplayInfo untrustedDisplayInfo = new DisplayInfo(mDisplayInfo);
        untrustedDisplayInfo.flags &= ~Display.FLAG_TRUSTED;
        final DisplayContent untrustedDisplay = createNewDisplay(untrustedDisplayInfo);
        final ActivityRecord activity = createActivityRecord(untrustedDisplay);
        activity.setOccludesParent(false);
        assertNotNull(activity.getTask().getDimmer());
    }

    @Test
    public void testResumeTask_doNotResumeTaskFragmentBehindTranslucent() {
        final Task task = createTask(mDisplayContent);
        final TaskFragment tfBehind = createTaskFragmentWithParentTask(
                task, false /* createEmbeddedTask */);
        final TaskFragment tfFront = createTaskFragmentWithParentTask(
                task, false /* createEmbeddedTask */);
        spyOn(tfFront);
        doReturn(true).when(tfFront).isTranslucent(any());

        // TaskFragment behind another translucent TaskFragment should not be resumed.
        assertEquals(TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
                tfBehind.getVisibility(null /* starting */));
        assertTrue(tfBehind.isFocusable());
        assertFalse(tfBehind.canBeResumed(null /* starting */));

        spyOn(tfBehind);
        task.resumeTopActivityUncheckedLocked(null /* prev */, ActivityOptions.makeBasic(),
                false /* deferPause */);

        verify(tfBehind, never()).resumeTopActivity(any(), any(), anyBoolean());
    }

    private Task getTestTask() {
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        return task.getBottomMostTask();
    }

    private void testRootTaskBoundsConfiguration(int windowingMode, Rect parentBounds, Rect bounds,
            Rect expectedConfigBounds) {

        TaskDisplayArea taskDisplayArea = mAtm.mRootWindowContainer.getDefaultTaskDisplayArea();
        Task rootTask = taskDisplayArea.createRootTask(windowingMode, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        Task task = new TaskBuilder(mSupervisor).setParentTaskFragment(rootTask).build();

        final Configuration parentConfig = rootTask.getConfiguration();
        parentConfig.windowConfiguration.setAppBounds(parentBounds);
        task.setBounds(bounds);

        task.resolveOverrideConfiguration(parentConfig);
        // Assert that both expected and actual are null or are equal to each other
        assertEquals(expectedConfigBounds,
                task.getResolvedOverrideConfiguration().windowConfiguration.getAppBounds());
    }

    private byte[] serializeToBytes(Task r) throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            final TypedXmlSerializer serializer = Xml.newFastSerializer();
            serializer.setOutput(os, "UTF-8");
            serializer.startDocument(null, true);
            serializer.startTag(null, TASK_TAG);
            r.saveToXml(serializer);
            serializer.endTag(null, TASK_TAG);
            serializer.endDocument();

            os.flush();
            return os.toByteArray();
        }
    }

    private Task restoreFromBytes(byte[] in) throws IOException, XmlPullParserException {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(in))) {
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(reader);
            assertEquals(XmlPullParser.START_TAG, parser.next());
            assertEquals(TASK_TAG, parser.getName());
            return Task.restoreFromXml(parser, mAtm.mTaskSupervisor);
        }
    }

    private Task createTask(int taskId) {
        return new Task.Builder(mAtm)
                .setTaskId(taskId)
                .setIntent(new Intent())
                .setRealActivity(ActivityBuilder.getDefaultComponent())
                .setEffectiveUid(10050)
                .buildInner();
    }
}
