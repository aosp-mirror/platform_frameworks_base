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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;
import static android.view.IWindowManager.FIXED_TO_USER_ROTATION_ENABLED;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.policy.WindowManagerPolicy.USER_ROTATION_FREE;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;
import android.util.Xml;
import android.view.DisplayInfo;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * Tests for exercising {@link Task}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskRecordTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskRecordTests extends ActivityTestsBase {

    private static final String TASK_TAG = "task";

    private Rect mParentBounds;

    @Before
    public void setUp() throws Exception {
        mParentBounds = new Rect(10 /*left*/, 30 /*top*/, 80 /*right*/, 60 /*bottom*/);
        removeGlobalMinSizeRestriction();
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
        assertThat(info.baseIntent, not(sameInstance(task.getBaseIntent())));
    }

    @Test
    public void testReturnsToHomeStack() throws Exception {
        final Task task = createTask(1);
        spyOn(task);
        doReturn(true).when(task).hasChild();
        assertFalse(task.returnsToHomeStack());
        task.intent = null;
        assertFalse(task.returnsToHomeStack());
        task.intent = new Intent();
        assertFalse(task.returnsToHomeStack());
        task.intent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME);
        assertTrue(task.returnsToHomeStack());
    }

    /** Ensures that empty bounds cause appBounds to inherit from parent. */
    @Test
    public void testAppBounds_EmptyBounds() {
        final Rect emptyBounds = new Rect();
        testStackBoundsConfiguration(WINDOWING_MODE_FULLSCREEN, mParentBounds, emptyBounds,
                mParentBounds);
    }

    /** Ensures that bounds on freeform stacks are not clipped. */
    @Test
    public void testAppBounds_FreeFormBounds() {
        final Rect freeFormBounds = new Rect(mParentBounds);
        freeFormBounds.offset(10, 10);
        testStackBoundsConfiguration(WINDOWING_MODE_FREEFORM, mParentBounds, freeFormBounds,
                freeFormBounds);
    }

    /** Ensures that fully contained bounds are not clipped. */
    @Test
    public void testAppBounds_ContainedBounds() {
        final Rect insetBounds = new Rect(mParentBounds);
        insetBounds.inset(5, 5, 5, 5);
        testStackBoundsConfiguration(
                WINDOWING_MODE_FREEFORM, mParentBounds, insetBounds, insetBounds);
    }

    @Test
    public void testFitWithinBounds() {
        final Rect parentBounds = new Rect(10, 10, 200, 200);
        TaskDisplayArea taskDisplayArea = mService.mRootWindowContainer.getDefaultTaskDisplayArea();
        ActivityStack stack = taskDisplayArea.createStack(WINDOWING_MODE_FREEFORM,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        Task task = new TaskBuilder(mSupervisor).setStack(stack).build();
        final Configuration parentConfig = stack.getConfiguration();
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

    /** Tests that the task bounds adjust properly to changes between FULLSCREEN and FREEFORM */
    @Test
    public void testBoundsOnModeChangeFreeformToFullscreen() {
        DisplayContent display = mService.mRootWindowContainer.getDefaultDisplay();
        ActivityStack stack = new StackBuilder(mRootWindowContainer).setDisplay(display)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        Task task = stack.getBottomMostTask();
        task.getRootActivity().setOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        DisplayInfo info = new DisplayInfo();
        display.mDisplay.getDisplayInfo(info);
        final Rect fullScreenBounds = new Rect(0, 0, info.logicalWidth, info.logicalHeight);
        final Rect freeformBounds = new Rect(fullScreenBounds);
        freeformBounds.inset((int) (freeformBounds.width() * 0.2),
                (int) (freeformBounds.height() * 0.2));
        task.setBounds(freeformBounds);

        assertEquals(freeformBounds, task.getBounds());

        // FULLSCREEN inherits bounds
        stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertEquals(fullScreenBounds, task.getBounds());
        assertEquals(freeformBounds, task.mLastNonFullscreenBounds);

        // FREEFORM restores bounds
        stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(freeformBounds, task.getBounds());
    }

    /**
     * Tests that a task with forced orientation has orientation-consistent bounds within the
     * parent.
     */
    @Test
    public void testFullscreenBoundsForcedOrientation() {
        final Rect fullScreenBounds = new Rect(0, 0, 1920, 1080);
        final Rect fullScreenBoundsPort = new Rect(0, 0, 1080, 1920);
        final DisplayContent display = new TestDisplayContent.Builder(mService,
                fullScreenBounds.width(), fullScreenBounds.height()).setCanRotate(false).build();
        assertTrue(mRootWindowContainer.getDisplayContent(display.mDisplayId) != null);
        // Fix the display orientation to landscape which is the natural rotation (0) for the test
        // display.
        final DisplayRotation dr = display.mDisplayContent.getDisplayRotation();
        dr.setFixedToUserRotation(FIXED_TO_USER_ROTATION_ENABLED);
        dr.setUserRotation(USER_ROTATION_FREE, ROTATION_0);

        ActivityStack stack = new StackBuilder(mRootWindowContainer)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).setDisplay(display).build();
        Task task = stack.getBottomMostTask();
        ActivityRecord root = task.getTopNonFinishingActivity();

        assertEquals(fullScreenBounds, task.getBounds());

        // Setting app to fixed portrait fits within parent
        root.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(root, task.getRootActivity());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, task.getRootActivity().getOrientation());
        assertThat(task.getBounds().width()).isLessThan(task.getBounds().height());
        assertEquals(fullScreenBounds.height(), task.getBounds().height());

        // Top activity gets used
        ActivityRecord top = new ActivityBuilder(mService).setTask(task).setStack(stack).build();
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

        assertThat(task.getBounds().width()).isGreaterThan(task.getBounds().height());
        assertEquals(fullScreenBoundsPort.width(), task.getBounds().width());

        // in FREEFORM, no constraint
        final Rect freeformBounds = new Rect(display.getBounds());
        freeformBounds.inset((int) (freeformBounds.width() * 0.2),
                (int) (freeformBounds.height() * 0.2));
        stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        task.setBounds(freeformBounds);
        assertEquals(freeformBounds, task.getBounds());

        // FULLSCREEN letterboxes bounds
        stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertThat(task.getBounds().width()).isGreaterThan(task.getBounds().height());
        assertEquals(fullScreenBoundsPort.width(), task.getBounds().width());

        // FREEFORM restores bounds as before
        stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(freeformBounds, task.getBounds());
    }

    @Test
    public void testIgnoresForcedOrientationWhenParentHandles() {
        final Rect fullScreenBounds = new Rect(0, 0, 1920, 1080);
        DisplayContent display = new TestDisplayContent.Builder(
                mService, fullScreenBounds.width(), fullScreenBounds.height()).build();

        display.getRequestedOverrideConfiguration().orientation =
                Configuration.ORIENTATION_LANDSCAPE;
        display.onRequestedOverrideConfigurationChanged(
                display.getRequestedOverrideConfiguration());
        ActivityStack stack = new StackBuilder(mRootWindowContainer)
                .setWindowingMode(WINDOWING_MODE_FULLSCREEN).setDisplay(display).build();
        Task task = stack.getBottomMostTask();
        ActivityRecord root = task.getTopNonFinishingActivity();

        final WindowContainer parentWindowContainer =
                new WindowContainer(mSystemServicesTestRule.getWindowManagerService());
        spyOn(parentWindowContainer);
        parentWindowContainer.setBounds(fullScreenBounds);
        doReturn(parentWindowContainer).when(task).getParent();
        doReturn(display.getDefaultTaskDisplayArea()).when(task).getDisplayArea();
        doReturn(stack).when(task).getStack();
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
                mService, fullScreenBounds.width(), fullScreenBounds.height()).build();
        final Task task = new TaskBuilder(mSupervisor).setDisplay(display).build();
        final Configuration inOutConfig = new Configuration();
        final Configuration parentConfig = new Configuration();
        final int longSide = 1200;
        final int shortSide = 600;
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
        final Rect largerPortraitBounds = new Rect(0, 0, shortSide, longSide);
        inOutConfig.windowConfiguration.setBounds(largerPortraitBounds);
        task.computeConfigResourceOverrides(inOutConfig, parentConfig);
        // The override bounds are beyond the parent, the out appBounds should not be intersected
        // by parent appBounds.
        assertEquals(largerPortraitBounds, inOutConfig.windowConfiguration.getAppBounds());
        assertEquals(longSide, inOutConfig.screenHeightDp * parentConfig.densityDpi / 160);
        assertEquals(shortSide, inOutConfig.screenWidthDp * parentConfig.densityDpi / 160);

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
        final ActivityRecord.CompatDisplayInsets compatIntsets =
                new ActivityRecord.CompatDisplayInsets(display, task);
        task.computeConfigResourceOverrides(inOutConfig, parentConfig, compatIntsets);

        assertEquals(largerLandscapeBounds, inOutConfig.windowConfiguration.getAppBounds());
        assertEquals((shortSide - statusBarHeight) * DENSITY_DEFAULT / parentConfig.densityDpi,
                inOutConfig.screenHeightDp);
        assertEquals(longSide * DENSITY_DEFAULT / parentConfig.densityDpi,
                inOutConfig.screenWidthDp);
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, inOutConfig.orientation);
    }

    @Test
    public void testComputeNestedConfigResourceOverrides() {
        final Task task = new TaskBuilder(mSupervisor).build();
        assertTrue(task.getResolvedOverrideBounds().isEmpty());
        int origScreenH = task.getConfiguration().screenHeightDp;
        Configuration stackConfig = new Configuration();
        stackConfig.setTo(task.getStack().getRequestedOverrideConfiguration());
        stackConfig.windowConfiguration.setWindowingMode(WINDOWING_MODE_FREEFORM);

        // Set bounds on stack (not task) and verify that the task resource configuration changes
        // despite it's override bounds being empty.
        Rect bounds = new Rect(task.getStack().getBounds());
        bounds.bottom = (int) (bounds.bottom * 0.6f);
        stackConfig.windowConfiguration.setBounds(bounds);
        task.getStack().onRequestedOverrideConfigurationChanged(stackConfig);
        assertNotEquals(origScreenH, task.getConfiguration().screenHeightDp);
    }

    @Test
    public void testInsetDisregardedWhenFreeformOverlapsNavBar() {
        TaskDisplayArea taskDisplayArea = mService.mRootWindowContainer.getDefaultTaskDisplayArea();
        ActivityStack stack = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        DisplayInfo displayInfo = new DisplayInfo();
        mService.mContext.getDisplay().getDisplayInfo(displayInfo);
        final int displayHeight = displayInfo.logicalHeight;
        final Task task = new TaskBuilder(mSupervisor).setStack(stack).build();
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

        final Task task = new ActivityStack(mService, 1 /* taskId */, info, intent,
                null /* voiceSession */, null /* voiceInteractor */, null /* taskDescriptor */,
                null /*stack*/);
        assertEquals("The alias activity component should be saved in task intent.", aliasClassName,
                task.intent.getComponent().getClassName());

        ActivityRecord aliasActivity = new ActivityBuilder(mService).setComponent(
                aliasComponent).setTargetActivity(targetClassName).build();
        assertEquals("Should be the same intent filter.", true,
                task.isSameIntentFilter(aliasActivity));

        ActivityRecord targetActivity = new ActivityBuilder(mService).setComponent(
                targetComponent).build();
        assertEquals("Should be the same intent filter.", true,
                task.isSameIntentFilter(targetActivity));

        ActivityRecord defaultActivity = new ActivityBuilder(mService).build();
        assertEquals("Should not be the same intent filter.", false,
                task.isSameIntentFilter(defaultActivity));
    }

    /** Test that root activity index is reported correctly for several activities in the task. */
    @Test
    public void testFindRootIndex() {
        final Task task = getTestTask();
        // Add an extra activity on top of the root one
        new ActivityBuilder(mService).setTask(task).build();

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
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
        activity1.finishing = true;
        new ActivityBuilder(mService).setTask(task).build();

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
        new ActivityBuilder(mService).setTask(task).build();

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
        final Task task = getTestTask();
        // Add extra two activities. Mark the one on the bottom with "relinquishTaskIdentity" and
        // one above as finishing.
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
        activity1.finishing = true;
        new ActivityBuilder(mService).setTask(task).build();

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
        final Task task = getTestTask();
        // Set relinquishTaskIdentity for all activities in the task
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
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
        new ActivityBuilder(mService).setTask(task).build();

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
        new ActivityBuilder(mService).setTask(task).build();
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
        new ActivityBuilder(mService).setTask(task).build();

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
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
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
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();

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
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
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
                ActivityRecord.getTaskForActivityLocked(activity0.appToken, false /* onlyRoot */));
        assertEquals(task1.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity1.appToken,  false /* onlyRoot */));
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
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
        // Add one more on top
        final ActivityRecord activity2 = new ActivityBuilder(mService).setTask(task).build();

        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity0.appToken, true /* onlyRoot */));
        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity1.appToken, true /* onlyRoot */));
        assertEquals("No task must be reported for activity that is above root", INVALID_TASK_ID,
                ActivityRecord.getTaskForActivityLocked(activity2.appToken, true /* onlyRoot */));
    }

    /**
     * Test {@link ActivityRecord#getTaskForActivityLocked(IBinder, boolean)} with activity that
     * relinquishes task identity.
     */
    @Test
    public void testGetTaskForActivity_onlyRoot_relinquishTaskIdentity() {
        final Task task = getTestTask();
        // Make the current root activity relinquish task identity
        final ActivityRecord activity0 = task.getBottomMostActivity();
        activity0.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;
        // Add an extra activity on top - this will be the new root
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
        // Add one more on top
        final ActivityRecord activity2 = new ActivityBuilder(mService).setTask(task).build();

        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity0.appToken, true /* onlyRoot */));
        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity1.appToken, true /* onlyRoot */));
        assertEquals("No task must be reported for activity that is above root", INVALID_TASK_ID,
                ActivityRecord.getTaskForActivityLocked(activity2.appToken, true /* onlyRoot */));
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
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
        activity1.info.flags |= FLAG_RELINQUISH_TASK_IDENTITY;

        // Add one more activity on top
        final ActivityRecord activity2 = new ActivityBuilder(mService).setTask(task).build();

        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity0.appToken, false /* onlyRoot */));
        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity1.appToken, false /* onlyRoot */));
        assertEquals(task.mTaskId,
                ActivityRecord.getTaskForActivityLocked(activity2.appToken, false /* onlyRoot */));
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
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();

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
        final ActivityRecord activity1 = new ActivityBuilder(mService).setTask(task).build();
        activity1.finishing = true;

        // Task must still update the intent using the root activity (preserving legacy behavior).
        spyOn(task);
        task.updateEffectiveIntent();
        verify(task).setIntent(eq(activity0));
    }

    @Test
    public void testSaveLaunchingStateWhenConfigurationChanged() {
        LaunchParamsPersister persister = mService.mStackSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = getTestTask();
        task.setHasBeenVisible(false);
        task.getDisplayContent().setDisplayWindowingMode(WINDOWING_MODE_FREEFORM);
        task.getStack().setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        task.setHasBeenVisible(true);
        task.onConfigurationChanged(task.getParent().getConfiguration());

        verify(persister).saveTask(task, task.getDisplayContent());
    }

    @Test
    public void testSaveLaunchingStateWhenClearingParent() {
        LaunchParamsPersister persister = mService.mStackSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = getTestTask();
        task.setHasBeenVisible(false);
        task.getDisplayContent().setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        task.getStack().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
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
        LaunchParamsPersister persister = mService.mStackSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = getTestTask();
        task.setHasBeenVisible(false);
        task.getStack().setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        task.setHasBeenVisible(true);
        task.onConfigurationChanged(task.getParent().getConfiguration());

        verify(persister, never()).saveTask(same(task), any());
    }

    @Test
    public void testNotSaveLaunchingStateWhenNotFullscreenOrFreeformWindow() {
        LaunchParamsPersister persister = mService.mStackSupervisor.mLaunchParamsPersister;
        spyOn(persister);

        final Task task = getTestTask();
        task.setHasBeenVisible(false);
        task.getDisplayContent().setDisplayWindowingMode(WINDOWING_MODE_FREEFORM);
        task.getStack().setWindowingMode(WINDOWING_MODE_PINNED);

        task.setHasBeenVisible(true);
        task.onConfigurationChanged(task.getParent().getConfiguration());

        verify(persister, never()).saveTask(same(task), any());
    }

    @Test
    public void testNotSpecifyOrientationByFloatingTask() {
        final Task task = getTestTask();
        final ActivityRecord activity = task.getTopMostActivity();
        final WindowContainer<?> taskDisplayArea = task.getParent();
        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, taskDisplayArea.getOrientation());

        task.setWindowingMode(WINDOWING_MODE_PINNED);

        assertEquals(SCREEN_ORIENTATION_UNSET, taskDisplayArea.getOrientation());
    }

    private Task getTestTask() {
        final ActivityStack stack = new StackBuilder(mRootWindowContainer).build();
        return stack.getBottomMostTask();
    }

    private void testStackBoundsConfiguration(int windowingMode, Rect parentBounds, Rect bounds,
            Rect expectedConfigBounds) {

        TaskDisplayArea taskDisplayArea = mService.mRootWindowContainer.getDefaultTaskDisplayArea();
        ActivityStack stack = taskDisplayArea.createStack(windowingMode, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);
        Task task = new TaskBuilder(mSupervisor).setStack(stack).build();

        final Configuration parentConfig = stack.getConfiguration();
        parentConfig.windowConfiguration.setAppBounds(parentBounds);
        task.setBounds(bounds);

        task.resolveOverrideConfiguration(parentConfig);
        // Assert that both expected and actual are null or are equal to each other
        assertEquals(expectedConfigBounds,
                task.getResolvedOverrideConfiguration().windowConfiguration.getAppBounds());
    }

    private byte[] serializeToBytes(Task r) throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            final XmlSerializer serializer = Xml.newSerializer();
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
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(reader);
            assertEquals(XmlPullParser.START_TAG, parser.next());
            assertEquals(TASK_TAG, parser.getName());
            return Task.restoreFromXml(parser, mService.mStackSupervisor);
        }
    }

    private Task createTask(int taskId) {
        return new ActivityStack(mService, taskId, new Intent(), null, null, null,
                ActivityBuilder.getDefaultComponent(), null, false, false, false, 0, 10050, null,
                0, false, null, 0, 0, 0, 0, 0, null, null, 0, false, false, false, 0,
                0, null /*ActivityInfo*/, null /*_voiceSession*/, null /*_voiceInteractor*/,
                null /*stack*/);
    }
}
