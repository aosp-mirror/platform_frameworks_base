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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_ALWAYS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_NEVER;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.os.Process.NOBODY_UID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_OPEN;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_LEGACY_SPLASH_SCREEN;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.atLeast;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.FINISH_RESULT_CANCELLED;
import static com.android.server.wm.ActivityRecord.FINISH_RESULT_REMOVED;
import static com.android.server.wm.ActivityRecord.FINISH_RESULT_REQUESTED;
import static com.android.server.wm.Task.ActivityState.DESTROYED;
import static com.android.server.wm.Task.ActivityState.DESTROYING;
import static com.android.server.wm.Task.ActivityState.FINISHING;
import static com.android.server.wm.Task.ActivityState.INITIALIZING;
import static com.android.server.wm.Task.ActivityState.PAUSED;
import static com.android.server.wm.Task.ActivityState.PAUSING;
import static com.android.server.wm.Task.ActivityState.RESUMED;
import static com.android.server.wm.Task.ActivityState.STARTED;
import static com.android.server.wm.Task.ActivityState.STOPPED;
import static com.android.server.wm.Task.ActivityState.STOPPING;
import static com.android.server.wm.Task.TASK_VISIBILITY_INVISIBLE;
import static com.android.server.wm.Task.TASK_VISIBILITY_VISIBLE;
import static com.android.server.wm.Task.TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowStateAnimator.ROOT_TASK_CLIP_AFTER_ANIM;
import static com.android.server.wm.WindowStateAnimator.ROOT_TASK_CLIP_BEFORE_ANIM;
import static com.android.server.wm.WindowStateAnimator.ROOT_TASK_CLIP_NONE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;

import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.DestroyActivityItem;
import android.app.servertransaction.PauseActivityItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.util.MergedConfiguration;
import android.util.MutableBoolean;
import android.view.DisplayInfo;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner.Stub;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.TaskSnapshot;

import androidx.test.filters.MediumTest;

import com.android.internal.R;
import com.android.server.wm.Task.ActivityState;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;


/**
 * Tests for the {@link ActivityRecord} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityRecordTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityRecordTests extends WindowTestsBase {

    private final String mPackageName = getInstrumentation().getTargetContext().getPackageName();

    @Before
    public void setUp() throws Exception {
        setBooted(mAtm);
    }

    private TestStartingWindowOrganizer registerTestStartingWindowOrganizer() {
        return new TestStartingWindowOrganizer(mAtm,
                mSystemServicesTestRule.getPowerManagerWrapper());
    }

    @Test
    public void testStackCleanupOnClearingTask() {
        final ActivityRecord activity = createActivityWith2LevelTask();
        final Task task = activity.getTask();
        final Task rootTask = activity.getRootTask();
        activity.onParentChanged(null /*newParent*/, task);
        verify(rootTask, times(1)).cleanUpActivityReferences(any());
    }

    @Test
    public void testStackCleanupOnActivityRemoval() {
        final ActivityRecord activity = createActivityWith2LevelTask();
        final Task task = activity.getTask();
        final Task rootTask = activity.getRootTask();
        task.removeChild(activity);
        verify(rootTask, times(1)).cleanUpActivityReferences(any());
    }

    @Test
    public void testStackCleanupOnTaskRemoval() {
        final ActivityRecord activity = createActivityWith2LevelTask();
        final Task task = activity.getTask();
        final Task rootTask = activity.getRootTask();
        rootTask.removeChild(task, null /*reason*/);
        // parentTask should be gone on task removal.
        assertNull(mAtm.mRootWindowContainer.getRootTask(rootTask.mTaskId));
    }

    @Test
    public void testRemoveChildWithOverlayActivity() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        final ActivityRecord overlayActivity = new ActivityBuilder(mAtm).setTask(task).build();
        overlayActivity.setTaskOverlay(true);
        final ActivityRecord overlayActivity2 = new ActivityBuilder(mAtm).setTask(task).build();
        overlayActivity2.setTaskOverlay(true);

        task.removeChild(overlayActivity2, "test");
        verify(mSupervisor, never()).removeTask(any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testNoCleanupMovingActivityInSameStack() {
        final ActivityRecord activity = createActivityWith2LevelTask();
        final Task rootTask = activity.getRootTask();
        final Task newTask = new TaskBuilder(mAtm.mTaskSupervisor).setParentTask(rootTask).build();
        activity.reparent(newTask, 0, null /*reason*/);
        verify(rootTask, times(0)).cleanUpActivityReferences(any());
    }

    @Test
    public void testPausingWhenVisibleFromStopped() throws Exception {
        final ActivityRecord activity = createActivityWithTask();
        final MutableBoolean pauseFound = new MutableBoolean(false);
        doAnswer((InvocationOnMock invocationOnMock) -> {
            final ClientTransaction transaction = invocationOnMock.getArgument(0);
            if (transaction.getLifecycleStateRequest() instanceof PauseActivityItem) {
                pauseFound.value = true;
            }
            return null;
        }).when(activity.app.getThread()).scheduleTransaction(any());

        activity.setState(STOPPED, "testPausingWhenVisibleFromStopped");

        // The activity is in the focused stack so it should be resumed.
        activity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(activity.isState(RESUMED));
        assertFalse(pauseFound.value);

        // Make the activity non focusable
        activity.setState(STOPPED, "testPausingWhenVisibleFromStopped");
        doReturn(false).when(activity).isFocusable();

        // If the activity is not focusable, it should move to paused.
        activity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(activity.isState(PAUSING));
        assertTrue(pauseFound.value);

        // Make sure that the state does not change for current non-stopping states.
        activity.setState(INITIALIZING, "testPausingWhenVisibleFromStopped");
        doReturn(true).when(activity).isFocusable();

        activity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);

        assertTrue(activity.isState(INITIALIZING));

        // Make sure the state does not change if we are not the current top activity.
        activity.setState(STOPPED, "testPausingWhenVisibleFromStopped behind");

        final Task task = activity.getTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        task.mTranslucentActivityWaiting = topActivity;
        activity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(activity.isState(STARTED));

        task.mTranslucentActivityWaiting = null;
        topActivity.setOccludesParent(false);
        activity.setState(STOPPED, "testPausingWhenVisibleFromStopped behind non-opaque");
        activity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(activity.isState(STARTED));
    }

    private void ensureActivityConfiguration(ActivityRecord activity) {
        activity.ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
    }

    @Test
    public void testCanBeLaunchedOnDisplay() {
        mAtm.mSupportsMultiWindow = true;
        final ActivityRecord activity = new ActivityBuilder(mAtm).build();

        // An activity can be launched on default display.
        assertTrue(activity.canBeLaunchedOnDisplay(DEFAULT_DISPLAY));
        // An activity cannot be launched on a non-existent display.
        assertFalse(activity.canBeLaunchedOnDisplay(Integer.MAX_VALUE));
    }

    @Test
    public void testsApplyOptionsLocked() {
        final ActivityRecord activity = createActivityWithTask();
        ActivityOptions activityOptions = ActivityOptions.makeBasic();

        // Set and apply options for ActivityRecord. Pending options should be cleared
        activity.updateOptionsLocked(activityOptions);
        activity.applyOptionsAnimation();
        assertNull(activity.getOptions());

        // Set options for two ActivityRecords in same Task. Apply one ActivityRecord options.
        // Pending options should be cleared for both ActivityRecords
        ActivityRecord activity2 = new ActivityBuilder(mAtm).setTask(activity.getTask()).build();
        activity2.updateOptionsLocked(activityOptions);
        activity.updateOptionsLocked(activityOptions);
        activity.applyOptionsAnimation();
        assertNull(activity.getOptions());
        assertNull(activity2.getOptions());

        // Set options for two ActivityRecords in separate Tasks. Apply one ActivityRecord options.
        // Pending options should be cleared for only ActivityRecord that was applied
        activity2 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity2.updateOptionsLocked(activityOptions);
        activity.updateOptionsLocked(activityOptions);
        activity.applyOptionsAnimation();
        assertNull(activity.getOptions());
        assertNotNull(activity2.getOptions());
    }

    @Test
    public void testNewOverrideConfigurationIncrementsSeq() {
        final ActivityRecord activity = createActivityWithTask();
        final Configuration newConfig = new Configuration();

        final int prevSeq = activity.getMergedOverrideConfiguration().seq;
        activity.onRequestedOverrideConfigurationChanged(newConfig);
        assertEquals(prevSeq + 1, activity.getMergedOverrideConfiguration().seq);
    }

    @Test
    public void testNewParentConfigurationIncrementsSeq() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        final Configuration newConfig = new Configuration(
                task.getRequestedOverrideConfiguration());
        newConfig.orientation = newConfig.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;

        final int prevSeq = activity.getMergedOverrideConfiguration().seq;
        task.onRequestedOverrideConfigurationChanged(newConfig);
        assertEquals(prevSeq + 1, activity.getMergedOverrideConfiguration().seq);
    }

    @Test
    public void testSetsRelaunchReason_NotDragResizing() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        activity.setState(Task.ActivityState.RESUMED, "Testing");

        task.onRequestedOverrideConfigurationChanged(task.getConfiguration());
        activity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                activity.getConfiguration()));

        activity.info.configChanges &= ~CONFIG_ORIENTATION;
        final Configuration newConfig = new Configuration(task.getConfiguration());
        newConfig.orientation = newConfig.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE
                : ORIENTATION_PORTRAIT;
        task.onRequestedOverrideConfigurationChanged(newConfig);

        activity.mRelaunchReason = ActivityTaskManagerService.RELAUNCH_REASON_NONE;

        ensureActivityConfiguration(activity);

        assertEquals(ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE,
                activity.mRelaunchReason);
    }

    @Test
    public void testSetsRelaunchReason_DragResizing() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        activity.setState(Task.ActivityState.RESUMED, "Testing");

        task.onRequestedOverrideConfigurationChanged(task.getConfiguration());
        activity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                activity.getConfiguration()));

        activity.info.configChanges &= ~CONFIG_ORIENTATION;
        final Configuration newConfig = new Configuration(task.getConfiguration());
        newConfig.orientation = newConfig.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE
                : ORIENTATION_PORTRAIT;
        task.onRequestedOverrideConfigurationChanged(newConfig);

        doReturn(true).when(task).isDragResizing();

        activity.mRelaunchReason = ActivityTaskManagerService.RELAUNCH_REASON_NONE;

        ensureActivityConfiguration(activity);

        assertEquals(ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE,
                activity.mRelaunchReason);
    }

    @Test
    public void testRelaunchClearTopWaitingTranslucent() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        activity.setState(Task.ActivityState.RESUMED, "Testing");

        task.onRequestedOverrideConfigurationChanged(task.getConfiguration());
        activity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                activity.getConfiguration()));

        activity.info.configChanges &= ~CONFIG_ORIENTATION;
        final Configuration newConfig = new Configuration(task.getConfiguration());
        newConfig.orientation = newConfig.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE
                : ORIENTATION_PORTRAIT;
        task.onRequestedOverrideConfigurationChanged(newConfig);
        task.mTranslucentActivityWaiting = activity;
        ensureActivityConfiguration(activity);
        assertNull(task.mTranslucentActivityWaiting);
    }

    @Test
    public void testSetsRelaunchReason_NonResizeConfigChanges() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        activity.setState(Task.ActivityState.RESUMED, "Testing");

        task.onRequestedOverrideConfigurationChanged(task.getConfiguration());
        activity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                activity.getConfiguration()));

        activity.info.configChanges &= ~ActivityInfo.CONFIG_FONT_SCALE;
        final Configuration newConfig = new Configuration(task.getConfiguration());
        newConfig.fontScale = 5;
        task.onRequestedOverrideConfigurationChanged(newConfig);

        activity.mRelaunchReason =
                ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;

        ensureActivityConfiguration(activity);

        assertEquals(ActivityTaskManagerService.RELAUNCH_REASON_NONE,
                activity.mRelaunchReason);
    }

    @Test
    public void testDestroyedActivityNotScheduleConfigChanged() throws RemoteException {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setConfigChanges(CONFIG_ORIENTATION)
                .build();
        final Task task = activity.getTask();
        activity.setState(DESTROYED, "Testing");
        clearInvocations(mAtm.getLifecycleManager());

        final Configuration newConfig = new Configuration(task.getConfiguration());
        newConfig.orientation = newConfig.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE
                : ORIENTATION_PORTRAIT;
        task.onRequestedOverrideConfigurationChanged(newConfig);

        ensureActivityConfiguration(activity);

        verify(mAtm.getLifecycleManager(), never())
                .scheduleTransaction(any(), any(), isA(ActivityConfigurationChangeItem.class));
    }

    @Test
    public void testSetRequestedOrientationUpdatesConfiguration() throws Exception {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setConfigChanges(CONFIG_ORIENTATION | CONFIG_SCREEN_LAYOUT)
                .build();
        activity.setState(Task.ActivityState.RESUMED, "Testing");

        activity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                activity.getConfiguration()));

        clearInvocations(mAtm.getLifecycleManager());
        final Configuration newConfig = new Configuration(activity.getConfiguration());
        final int shortSide = Math.min(newConfig.screenWidthDp, newConfig.screenHeightDp);
        final int longSide = Math.max(newConfig.screenWidthDp, newConfig.screenHeightDp);
        if (newConfig.orientation == ORIENTATION_PORTRAIT) {
            newConfig.orientation = ORIENTATION_LANDSCAPE;
            newConfig.screenWidthDp = longSide;
            newConfig.screenHeightDp = shortSide;
        } else {
            newConfig.orientation = ORIENTATION_PORTRAIT;
            newConfig.screenWidthDp = shortSide;
            newConfig.screenHeightDp = longSide;
        }

        // Mimic the behavior that display doesn't handle app's requested orientation.
        final DisplayContent dc = activity.getTask().getDisplayContent();
        doReturn(false).when(dc).onDescendantOrientationChanged(any());
        doReturn(false).when(dc).handlesOrientationChangeFromDescendant();

        final int requestedOrientation;
        switch (newConfig.orientation) {
            case ORIENTATION_LANDSCAPE:
                requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case ORIENTATION_PORTRAIT:
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            default:
                throw new IllegalStateException("Orientation in new config should be either"
                        + "landscape or portrait.");
        }
        activity.setRequestedOrientation(requestedOrientation);

        final ActivityConfigurationChangeItem expected =
                ActivityConfigurationChangeItem.obtain(newConfig);
        verify(mAtm.getLifecycleManager()).scheduleTransaction(eq(activity.app.getThread()),
                eq(activity.appToken), eq(expected));
    }

    @Test
    public void ignoreRequestedOrientationInFreeformWindows() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        task.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final Rect stableRect = new Rect();
        task.mDisplayContent.getStableRect(stableRect);

        // Carve out non-decor insets from stableRect
        final Rect insets = new Rect();
        final DisplayInfo displayInfo = task.mDisplayContent.getDisplayInfo();
        final DisplayPolicy policy = task.mDisplayContent.getDisplayPolicy();
        policy.getNonDecorInsetsLw(displayInfo.rotation, displayInfo.logicalWidth,
                displayInfo.logicalHeight, displayInfo.displayCutout, insets);
        policy.convertNonDecorInsetsToStableInsets(insets, displayInfo.rotation);
        Task.intersectWithInsetsIfFits(stableRect, stableRect, insets);

        final boolean isScreenPortrait = stableRect.width() <= stableRect.height();
        final Rect bounds = new Rect(stableRect);
        if (isScreenPortrait) {
            // Landscape bounds
            final int newHeight = stableRect.width() - 10;
            bounds.top = stableRect.top + (stableRect.height() - newHeight) / 2;
            bounds.bottom = bounds.top + newHeight;
        } else {
            // Portrait bounds
            final int newWidth = stableRect.height() - 10;
            bounds.left = stableRect.left + (stableRect.width() - newWidth) / 2;
            bounds.right = bounds.left + newWidth;
        }
        task.setBounds(bounds);

        // Requests orientation that's different from its bounds.
        activity.setRequestedOrientation(
                isScreenPortrait ? SCREEN_ORIENTATION_PORTRAIT : SCREEN_ORIENTATION_LANDSCAPE);

        // Asserts it has orientation derived from bounds.
        assertEquals(isScreenPortrait ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT,
                activity.getConfiguration().orientation);
    }

    @Test
    public void ignoreRequestedOrientationForResizableInSplitWindows() {
        final ActivityRecord activity = createActivityWith2LevelTask();
        final Task task = activity.getTask();
        final Task rootTask = activity.getRootTask();
        rootTask.setWindowingMode(WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final Rect stableRect = new Rect();
        rootTask.mDisplayContent.getStableRect(stableRect);

        // Carve out non-decor insets from stableRect
        final Rect insets = new Rect();
        final DisplayInfo displayInfo = rootTask.mDisplayContent.getDisplayInfo();
        final DisplayPolicy policy = rootTask.mDisplayContent.getDisplayPolicy();
        policy.getNonDecorInsetsLw(displayInfo.rotation, displayInfo.logicalWidth,
                displayInfo.logicalHeight, displayInfo.displayCutout, insets);
        policy.convertNonDecorInsetsToStableInsets(insets, displayInfo.rotation);
        Task.intersectWithInsetsIfFits(stableRect, stableRect, insets);

        final boolean isScreenPortrait = stableRect.width() <= stableRect.height();
        final Rect bounds = new Rect(stableRect);
        if (isScreenPortrait) {
            // Landscape bounds
            final int newHeight = stableRect.width() - 10;
            bounds.top = stableRect.top + (stableRect.height() - newHeight) / 2;
            bounds.bottom = bounds.top + newHeight;
        } else {
            // Portrait bounds
            final int newWidth = stableRect.height() - 10;
            bounds.left = stableRect.left + (stableRect.width() - newWidth) / 2;
            bounds.right = bounds.left + newWidth;
        }
        task.setBounds(bounds);

        final int activityCurOrientation = activity.getConfiguration().orientation;

        // Requests orientation that's different from its bounds.
        activity.setRequestedOrientation(activityCurOrientation == ORIENTATION_LANDSCAPE
                ? SCREEN_ORIENTATION_PORTRAIT : SCREEN_ORIENTATION_LANDSCAPE);

        // Asserts fixed orientation request is ignored, and the orientation is not changed
        // (fill Task).
        assertEquals(activityCurOrientation, activity.getConfiguration().orientation);
        assertFalse(activity.isLetterboxedForFixedOrientationAndAspectRatio());
    }

    @Test
    public void respectRequestedOrientationForNonResizableInSplitWindows() {
        final Task task = new TaskBuilder(mSupervisor)
                .setCreateParentTask(true).setCreateActivity(true).build();
        final Task rootTask = task.getRootTask();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setParentTask(task)
                .setOnTop(true)
                .setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                .setScreenOrientation(SCREEN_ORIENTATION_PORTRAIT)
                .build();

        // Task in landscape.
        rootTask.setWindowingMode(WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        task.setBounds(0, 0, 1000, 500);
        assertEquals(ORIENTATION_LANDSCAPE, task.getConfiguration().orientation);

        // Asserts fixed orientation request is respected, and the orientation is not changed.
        assertEquals(ORIENTATION_PORTRAIT, activity.getConfiguration().orientation);

        // Clear size compat.
        activity.clearSizeCompatMode();
        activity.ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
        activity.mDisplayContent.sendNewConfiguration();

        // Relaunching the app should still respect the orientation request.
        assertEquals(ORIENTATION_PORTRAIT, activity.getConfiguration().orientation);
        assertTrue(activity.isLetterboxedForFixedOrientationAndAspectRatio());
    }

    @Test
    public void testShouldMakeActive_deferredResume() {
        final ActivityRecord activity = createActivityWithTask();
        activity.setState(Task.ActivityState.STOPPED, "Testing");

        mSupervisor.beginDeferResume();
        assertEquals(false, activity.shouldMakeActive(null /* activeActivity */));

        mSupervisor.endDeferResume();
        assertEquals(true, activity.shouldMakeActive(null /* activeActivity */));
    }

    @Test
    public void testShouldMakeActive_nonTopVisible() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        ActivityRecord finishingActivity = new ActivityBuilder(mAtm).setTask(task).build();
        finishingActivity.finishing = true;
        ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        activity.setState(Task.ActivityState.STOPPED, "Testing");

        assertEquals(false, activity.shouldMakeActive(null /* activeActivity */));
    }

    @Test
    public void testShouldResume_stackVisibility() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        activity.setState(Task.ActivityState.STOPPED, "Testing");

        doReturn(TASK_VISIBILITY_VISIBLE).when(task).getVisibility(null);
        assertEquals(true, activity.shouldResumeActivity(null /* activeActivity */));

        doReturn(TASK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT).when(task).getVisibility(null);
        assertEquals(false, activity.shouldResumeActivity(null /* activeActivity */));

        doReturn(TASK_VISIBILITY_INVISIBLE).when(task).getVisibility(null);
        assertEquals(false, activity.shouldResumeActivity(null /* activeActivity */));
    }

    @Test
    public void testShouldResumeOrPauseWithResults() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        activity.setState(Task.ActivityState.STOPPED, "Testing");

        ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        activity.addResultLocked(topActivity, "resultWho", 0, 0, new Intent());
        topActivity.finishing = true;

        doReturn(TASK_VISIBILITY_VISIBLE).when(task).getVisibility(null);
        assertEquals(true, activity.shouldResumeActivity(null /* activeActivity */));
        assertEquals(false, activity.shouldPauseActivity(null /*activeActivity */));
    }

    @Test
    public void testPushConfigurationWhenLaunchTaskBehind() throws Exception {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setLaunchTaskBehind(true)
                .setConfigChanges(CONFIG_ORIENTATION | CONFIG_SCREEN_LAYOUT)
                .build();
        final Task task = activity.getTask();
        activity.setState(Task.ActivityState.STOPPED, "Testing");

        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        try {
            clearInvocations(mAtm.getLifecycleManager());
            doReturn(false).when(stack).isTranslucent(any());
            assertTrue(task.shouldBeVisible(null /* starting */));

            activity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                    activity.getConfiguration()));

            final Configuration newConfig = new Configuration(activity.getConfiguration());
            final int shortSide = Math.min(newConfig.screenWidthDp, newConfig.screenHeightDp);
            final int longSide = Math.max(newConfig.screenWidthDp, newConfig.screenHeightDp);
            if (newConfig.orientation == ORIENTATION_PORTRAIT) {
                newConfig.orientation = ORIENTATION_LANDSCAPE;
                newConfig.screenWidthDp = longSide;
                newConfig.screenHeightDp = shortSide;
            } else {
                newConfig.orientation = ORIENTATION_PORTRAIT;
                newConfig.screenWidthDp = shortSide;
                newConfig.screenHeightDp = longSide;
            }

            task.onConfigurationChanged(newConfig);

            activity.ensureActivityConfiguration(0 /* globalChanges */,
                    false /* preserveWindow */, true /* ignoreStopState */);

            final ActivityConfigurationChangeItem expected =
                    ActivityConfigurationChangeItem.obtain(newConfig);
            verify(mAtm.getLifecycleManager()).scheduleTransaction(
                    eq(activity.app.getThread()), eq(activity.appToken), eq(expected));
        } finally {
            stack.getDisplayArea().removeChild(stack);
        }
    }

    @Test
    public void testShouldStartWhenMakeClientActive() {
        final ActivityRecord activity = createActivityWithTask();
        ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(activity.getTask()).build();
        topActivity.setOccludesParent(false);
        activity.setState(Task.ActivityState.STOPPED, "Testing");
        activity.setVisibility(true);
        activity.makeActiveIfNeeded(null /* activeActivity */);
        assertEquals(STARTED, activity.getState());
    }

    @Test
    public void testTakeOptions() {
        final ActivityRecord activity = createActivityWithTask();
        ActivityOptions opts = ActivityOptions.makeRemoteAnimation(
                new RemoteAnimationAdapter(new Stub() {

                    @Override
                    public void onAnimationStart(@WindowManager.TransitionOldType int transit,
                            RemoteAnimationTarget[] apps,
                            RemoteAnimationTarget[] wallpapers,
                            RemoteAnimationTarget[] nonApps,
                            IRemoteAnimationFinishedCallback finishedCallback) {
                    }

                    @Override
                    public void onAnimationCancelled() {
                    }
                }, 0, 0));
        activity.updateOptionsLocked(opts);
        assertNotNull(activity.takeOptions());
        assertNull(activity.getOptions());

        final AppTransition appTransition = activity.mDisplayContent.mAppTransition;
        spyOn(appTransition);
        activity.applyOptionsAnimation();

        verify(appTransition).overridePendingAppTransitionRemote(any());
    }

    @Test
    public void testCanLaunchHomeActivityFromChooser() {
        ComponentName chooserComponent = ComponentName.unflattenFromString(
                Resources.getSystem().getString(R.string.config_chooserActivity));
        ActivityRecord chooserActivity = new ActivityBuilder(mAtm).setComponent(
                chooserComponent).build();
        assertThat(chooserActivity.canLaunchHomeActivity(NOBODY_UID, chooserActivity)).isTrue();
    }

    /**
     * Verify that an {@link ActivityRecord} reports that it has saved state after creation, and
     * that it is cleared after activity is resumed.
     */
    @Test
    public void testHasSavedState() {
        final ActivityRecord activity = createActivityWithTask();
        assertTrue(activity.hasSavedState());

        ActivityRecord.activityResumedLocked(activity.appToken, false /* handleSplashScreenExit */);
        assertFalse(activity.hasSavedState());
        assertNull(activity.getSavedState());
    }

    /** Verify the behavior of {@link ActivityRecord#setSavedState(Bundle)}. */
    @Test
    public void testUpdateSavedState() {
        final ActivityRecord activity = createActivityWithTask();
        activity.setSavedState(null /* savedState */);
        assertFalse(activity.hasSavedState());
        assertNull(activity.getSavedState());

        final Bundle savedState = new Bundle();
        savedState.putString("test", "string");
        activity.setSavedState(savedState);
        assertTrue(activity.hasSavedState());
        assertEquals(savedState, activity.getSavedState());
    }

    /** Verify the correct updates of saved state when activity client reports stop. */
    @Test
    public void testUpdateSavedState_activityStopped() {
        final ActivityRecord activity = createActivityWithTask();
        final Bundle savedState = new Bundle();
        savedState.putString("test", "string");
        final PersistableBundle persistentSavedState = new PersistableBundle();
        persistentSavedState.putString("persist", "string");

        // Set state to STOPPING, or ActivityRecord#activityStoppedLocked() call will be ignored.
        activity.setState(STOPPING, "test");
        activity.activityStopped(savedState, persistentSavedState, "desc");
        assertTrue(activity.hasSavedState());
        assertEquals(savedState, activity.getSavedState());
        assertEquals(persistentSavedState, activity.getPersistentSavedState());

        // Sending 'null' for saved state can only happen due to timeout, so previously stored saved
        // states should not be overridden.
        activity.setState(STOPPING, "test");
        activity.activityStopped(null /* savedState */, null /* persistentSavedState */, "desc");
        assertTrue(activity.hasSavedState());
        assertEquals(savedState, activity.getSavedState());
        assertEquals(persistentSavedState, activity.getPersistentSavedState());
    }

    /**
     * Verify that activity finish request is not performed if activity is finishing or is in
     * incorrect state.
     */
    @Test
    public void testFinishActivityIfPossible_cancelled() {
        final ActivityRecord activity = createActivityWithTask();
        // Mark activity as finishing
        activity.finishing = true;
        assertEquals("Duplicate finish request must be ignored", FINISH_RESULT_CANCELLED,
                activity.finishIfPossible("test", false /* oomAdj */));
        assertTrue(activity.finishing);
        assertTrue(activity.isInRootTaskLocked());

        // Remove activity from task
        activity.finishing = false;
        activity.onParentChanged(null /*newParent*/, activity.getTask());
        assertEquals("Activity outside of task/stack cannot be finished", FINISH_RESULT_CANCELLED,
                activity.finishIfPossible("test", false /* oomAdj */));
        assertFalse(activity.finishing);
    }

    /**
     * Verify that activity finish request is placed, but not executed immediately if activity is
     * not ready yet.
     */
    @Test
    public void testFinishActivityIfPossible_requested() {
        final ActivityRecord activity = createActivityWithTask();
        activity.finishing = false;
        assertEquals("Currently resumed activity must be prepared removal", FINISH_RESULT_REQUESTED,
                activity.finishIfPossible("test", false /* oomAdj */));
        assertTrue(activity.finishing);
        assertTrue(activity.isInRootTaskLocked());

        // First request to finish activity must schedule a "destroy" request to the client.
        // Activity must be removed from history after the client reports back or after timeout.
        activity.finishing = false;
        activity.setState(STOPPED, "test");
        assertEquals("Activity outside of task/stack cannot be finished", FINISH_RESULT_REQUESTED,
                activity.finishIfPossible("test", false /* oomAdj */));
        assertTrue(activity.finishing);
        assertTrue(activity.isInRootTaskLocked());
    }

    /**
     * Verify that activity finish request removes activity immediately if it's ready.
     */
    @Test
    public void testFinishActivityIfPossible_removed() {
        final ActivityRecord activity = createActivityWithTask();
        // Prepare the activity record to be ready for immediate removal. It should be invisible and
        // have no process. Otherwise, request to finish it will send a message to client first.
        activity.setState(STOPPED, "test");
        activity.mVisibleRequested = false;
        activity.nowVisible = false;
        // Set process to 'null' to allow immediate removal, but don't call mActivity.setProcess() -
        // this will cause NPE when updating task's process.
        activity.app = null;

        // Put a visible activity on top, so the finishing activity doesn't have to wait until the
        // next activity reports idle to destroy it.
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(activity.getTask()).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.setState(RESUMED, "test");

        assertEquals("Activity outside of task/rootTask cannot be finished", FINISH_RESULT_REMOVED,
                activity.finishIfPossible("test", false /* oomAdj */));
        assertTrue(activity.finishing);
        assertFalse(activity.isInRootTaskLocked());
    }

    /**
     * Verify that when finishing the top focused activity on top display, the root task order
     * will be changed by adjusting focus.
     */
    @Test
    public void testFinishActivityIfPossible_adjustStackOrder() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        // Prepare the tasks with order (top to bottom): task, task1, task2.
        final Task task1 = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        task.moveToFront("test");
        // The task2 is needed here for moving back to simulate the
        // {@link DisplayContent#mPreferredTopFocusableStack} is cleared, so
        // {@link DisplayContent#getFocusedStack} will rely on the order of focusable-and-visible
        // tasks. Then when mActivity is finishing, its task will be invisible (no running
        // activities in the task) that is the key condition to verify.
        final Task task2 = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        task2.moveToBack("test", task2.getBottomMostTask());

        assertTrue(task.isTopRootTaskInDisplayArea());

        activity.setState(RESUMED, "test");
        activity.finishIfPossible(0 /* resultCode */, null /* resultData */,
                null /* resultGrants */, "test", false /* oomAdj */);

        assertTrue(task1.isTopRootTaskInDisplayArea());
    }

    /**
     * Verify that when finishing the top focused activity while root task was created by organizer,
     * the stack order will be changed by adjusting focus.
     */
    @Test
    public void testFinishActivityIfPossible_adjustStackOrderOrganizedRoot() {
        // Make mStack be a the root task that created by task organizer
        final Task rootableTask = new TaskBuilder(mSupervisor)
                .setCreateParentTask(true).setCreateActivity(true).build();
        final Task rootTask = rootableTask.getRootTask();
        rootTask.mCreatedByOrganizer = true;

        // Have two tasks (topRootableTask and rootableTask) as the children of rootTask.
        ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setParentTask(rootTask)
                .build();
        Task topRootableTask = topActivity.getTask();
        topRootableTask.moveToFront("test");
        assertTrue(rootTask.isTopRootTaskInDisplayArea());

        // Finish top activity and verify the next focusable rootable task has adjusted to top.
        topActivity.setState(RESUMED, "test");
        topActivity.finishIfPossible(0 /* resultCode */, null /* resultData */,
                null /* resultGrants */, "test", false /* oomAdj */);
        assertEquals(rootableTask, rootTask.getTopMostTask());
    }

    /**
     * Verify that when top focused activity is on secondary display, when finishing the top focused
     * activity on default display, the preferred top stack on default display should be changed by
     * adjusting focus.
     */
    @Test
    public void testFinishActivityIfPossible_PreferredTopStackChanged() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        final ActivityRecord topActivityOnNonTopDisplay =
                createActivityOnDisplay(true /* defaultDisplay */, null /* process */);
        Task topRootableTask = topActivityOnNonTopDisplay.getRootTask();
        topRootableTask.moveToFront("test");
        assertTrue(topRootableTask.isTopRootTaskInDisplayArea());
        assertEquals(topRootableTask, topActivityOnNonTopDisplay.getDisplayArea()
                .mPreferredTopFocusableRootTask);

        final ActivityRecord secondaryDisplayActivity =
                createActivityOnDisplay(false /* defaultDisplay */, null /* process */);
        topRootableTask = secondaryDisplayActivity.getRootTask();
        topRootableTask.moveToFront("test");
        assertTrue(topRootableTask.isTopRootTaskInDisplayArea());
        assertEquals(topRootableTask,
                secondaryDisplayActivity.getDisplayArea().mPreferredTopFocusableRootTask);

        // The global top focus activity is on secondary display now.
        // Finish top activity on default display and verify the next preferred top focusable stack
        // on default display has changed.
        topActivityOnNonTopDisplay.setState(RESUMED, "test");
        topActivityOnNonTopDisplay.finishIfPossible(0 /* resultCode */, null /* resultData */,
                null /* resultGrants */, "test", false /* oomAdj */);
        assertEquals(task, task.getTopMostTask());
        assertEquals(task, activity.getDisplayArea().mPreferredTopFocusableRootTask);
    }

    /**
     * Verify that resumed activity is paused due to finish request.
     */
    @Test
    public void testFinishActivityIfPossible_resumedStartsPausing() {
        final ActivityRecord activity = createActivityWithTask();
        activity.finishing = false;
        activity.setState(RESUMED, "test");
        assertEquals("Currently resumed activity must be paused before removal",
                FINISH_RESULT_REQUESTED, activity.finishIfPossible("test", false /* oomAdj */));
        assertEquals(PAUSING, activity.getState());
        verify(activity).setVisibility(eq(false));
        verify(activity.mDisplayContent).prepareAppTransition(eq(TRANSIT_CLOSE));
    }

    /**
     * Verify that finish request will be completed immediately for non-resumed activity.
     */
    @Test
    public void testFinishActivityIfPossible_nonResumedFinishCompletesImmediately() {
        final ActivityRecord activity = createActivityWithTask();
        final ActivityState[] states = {INITIALIZING, STARTED, PAUSED, STOPPING, STOPPED};
        for (ActivityState state : states) {
            activity.finishing = false;
            activity.setState(state, "test");
            reset(activity);
            assertEquals("Finish must be requested", FINISH_RESULT_REQUESTED,
                    activity.finishIfPossible("test", false /* oomAdj */));
            verify(activity).completeFinishing(anyString());
        }
    }

    /**
     * Verify that finishing will not be completed in PAUSING state.
     */
    @Test
    public void testFinishActivityIfPossible_pausing() {
        final ActivityRecord activity = createActivityWithTask();
        activity.finishing = false;
        activity.setState(PAUSING, "test");
        assertEquals("Finish must be requested", FINISH_RESULT_REQUESTED,
                activity.finishIfPossible("test", false /* oomAdj */));
        verify(activity, never()).completeFinishing(anyString());
    }

    /**
     * Verify that finish request for resumed activity will prepare an app transition but not
     * execute it immediately.
     */
    @Test
    public void testFinishActivityIfPossible_visibleResumedPreparesAppTransition() {
        final ActivityRecord activity = createActivityWithTask();
        clearInvocations(activity.mDisplayContent);
        activity.finishing = false;
        activity.mVisibleRequested = true;
        activity.setState(RESUMED, "test");
        activity.finishIfPossible("test", false /* oomAdj */);

        verify(activity).setVisibility(eq(false));
        verify(activity.mDisplayContent).prepareAppTransition(eq(TRANSIT_CLOSE));
        verify(activity.mDisplayContent, never()).executeAppTransition();
    }

    /**
     * Verify that finish request for paused activity will prepare and execute an app transition.
     */
    @Test
    public void testFinishActivityIfPossible_visibleNotResumedExecutesAppTransition() {
        final ActivityRecord activity = createActivityWithTask();
        clearInvocations(activity.mDisplayContent);
        activity.finishing = false;
        activity.mVisibleRequested = true;
        activity.setState(PAUSED, "test");
        activity.finishIfPossible("test", false /* oomAdj */);

        verify(activity, atLeast(1)).setVisibility(eq(false));
        verify(activity.mDisplayContent).prepareAppTransition(eq(TRANSIT_CLOSE));
        verify(activity.mDisplayContent).executeAppTransition();
    }

    /**
     * Verify that finish request for non-visible activity will not prepare any transitions.
     */
    @Test
    public void testFinishActivityIfPossible_nonVisibleNoAppTransition() {
        final ActivityRecord activity = createActivityWithTask();
        // Put an activity on top of test activity to make it invisible and prevent us from
        // accidentally resuming the topmost one again.
        new ActivityBuilder(mAtm).build();
        activity.mVisibleRequested = false;
        activity.setState(STOPPED, "test");

        activity.finishIfPossible("test", false /* oomAdj */);

        verify(activity.mDisplayContent, never()).prepareAppTransition(eq(TRANSIT_CLOSE));
    }

    /**
     * Verify that finish request for the last activity in a task will request a shell transition
     * with that task as a trigger.
     */
    @Test
    public void testFinishActivityIfPossible_lastInTaskRequestsTransitionWithTrigger() {
        // Set-up mock shell transitions
        final TestTransitionPlayer testPlayer = new TestTransitionPlayer(
                mAtm.getTransitionController(), mAtm.mWindowOrganizerController);
        mAtm.getTransitionController().registerTransitionPlayer(testPlayer);

        final ActivityRecord activity = createActivityWithTask();
        activity.finishing = false;
        activity.mVisibleRequested = true;
        activity.setState(RESUMED, "test");
        activity.finishIfPossible("test", false /* oomAdj */);

        verify(activity).setVisibility(eq(false));
        assertEquals(activity.getTask().mTaskId, testPlayer.mLastRequest.getTriggerTask().taskId);
    }

    /**
     * Verify that complete finish request for non-finishing activity is invalid.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCompleteFinishing_failNotFinishing() {
        final ActivityRecord activity = createActivityWithTask();
        activity.finishing = false;
        activity.completeFinishing("test");
    }

    /**
     * Verify that complete finish request for resumed activity is invalid.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCompleteFinishing_failResumed() {
        final ActivityRecord activity = createActivityWithTask();
        activity.setState(RESUMED, "test");
        activity.completeFinishing("test");
    }

    /**
     * Verify that finish request for pausing activity must be a no-op - activity will finish
     * once it completes pausing.
     */
    @Test
    public void testCompleteFinishing_pausing() {
        final ActivityRecord activity = createActivityWithTask();
        activity.setState(PAUSING, "test");
        activity.finishing = true;

        assertEquals("Activity must not be removed immediately - waiting for paused",
                activity, activity.completeFinishing("test"));
        assertEquals(PAUSING, activity.getState());
        verify(activity, never()).destroyIfPossible(anyString());
    }

    /**
     * Verify that finish request won't change the state of next top activity if the current
     * finishing activity doesn't need to be destroyed immediately. The case is usually like
     * from {@link ActivityStack#completePauseLocked(boolean, ActivityRecord)} to
     * {@link ActivityRecord#completeFinishing(String)}, so the complete-pause should take the
     * responsibility to resume the next activity with updating the state.
     */
    @Test
    public void testCompleteFinishing_keepStateOfNextInvisible() {
        final ActivityRecord currentTop = createActivityWithTask();
        final Task task = currentTop.getTask();

        currentTop.mVisibleRequested = currentTop.nowVisible = true;

        // Simulates that {@code currentTop} starts an existing activity from background (so its
        // state is stopped) and the starting flow just goes to place it at top.
        final Task nextStack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord nextTop = nextStack.getTopNonFinishingActivity();
        nextTop.setState(STOPPED, "test");

        task.setPausingActivity(currentTop);
        currentTop.finishing = true;
        currentTop.setState(PAUSED, "test");
        currentTop.completeFinishing("completePauseLocked");

        // Current top becomes stopping because it is visible and the next is invisible.
        assertEquals(STOPPING, currentTop.getState());
        // The state of next activity shouldn't be changed.
        assertEquals(STOPPED, nextTop.getState());
    }

    /**
     * Verify that finish bottom activity from a task won't boost it to top.
     */
    @Test
    public void testFinishBottomActivityIfPossible_noZBoost() {
        final ActivityRecord bottomActivity = createActivityWithTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(bottomActivity.getTask()).build();
        topActivity.mVisibleRequested = true;
        // simulating bottomActivity as a trampoline activity.
        bottomActivity.setState(RESUMED, "test");
        bottomActivity.finishIfPossible("test", false);
        assertFalse(bottomActivity.mNeedsZBoost);
    }

    /**
     * Verify that complete finish request for visible activity must be delayed before the next one
     * becomes visible.
     */
    @Test
    public void testCompleteFinishing_waitForNextVisible() {
        final ActivityRecord activity = createActivityWithTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(activity.getTask()).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as not visible, so that we will wait for it before removing
        // the top one.
        activity.mVisibleRequested = false;
        activity.nowVisible = false;
        activity.setState(STOPPED, "test");

        assertEquals("Activity must not be removed immediately - waiting for next visible",
                topActivity, topActivity.completeFinishing("test"));
        assertEquals("Activity must be stopped to make next one visible", STOPPING,
                topActivity.getState());
        assertTrue("Activity must be stopped to make next one visible",
                topActivity.mTaskSupervisor.mStoppingActivities.contains(topActivity));
        verify(topActivity, never()).destroyIfPossible(anyString());
    }

    /**
     * Verify that complete finish request for top invisible activity must not be delayed while
     * sleeping, but next invisible activity must be resumed (and paused/stopped)
     */
    @Test
    public void testCompleteFinishing_noWaitForNextVisible_sleeping() {
        final ActivityRecord activity = createActivityWithTask();
        // Create a top activity on a new task
        final ActivityRecord topActivity = createActivityWithTask();
        mDisplayContent.setIsSleeping(true);
        doReturn(true).when(activity).shouldBeVisible();
        topActivity.mVisibleRequested = false;
        topActivity.nowVisible = false;
        topActivity.finishing = true;
        topActivity.setState(STOPPED, "true");

        // Mark the activity behind (on a separate task) as not visible
        activity.mVisibleRequested = false;
        activity.nowVisible = false;
        activity.setState(STOPPED, "test");

        clearInvocations(activity);
        topActivity.completeFinishing("test");
        verify(activity).setState(eq(RESUMED), any());
        verify(topActivity).destroyIfPossible(anyString());
    }

    /**
     * Verify that complete finish request for invisible activity must not be delayed.
     */
    @Test
    public void testCompleteFinishing_noWaitForNextVisible_alreadyInvisible() {
        final ActivityRecord activity = createActivityWithTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(activity.getTask()).build();
        topActivity.mVisibleRequested = false;
        topActivity.nowVisible = false;
        topActivity.finishing = true;
        topActivity.setState(STOPPED, "true");
        // Mark the bottom activity as not visible, so that we would wait for it before removing
        // the top one.
        activity.mVisibleRequested = false;
        activity.nowVisible = false;
        activity.setState(STOPPED, "test");

        topActivity.completeFinishing("test");

        verify(topActivity).destroyIfPossible(anyString());
    }

    /**
     * Verify that paused finishing activity will be added to finishing list and wait for next one
     * to idle.
     */
    @Test
    public void testCompleteFinishing_waitForIdle() {
        final ActivityRecord activity = createActivityWithTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(activity.getTask()).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        activity.mVisibleRequested = true;
        activity.nowVisible = true;
        activity.setState(RESUMED, "test");

        topActivity.completeFinishing("test");

        verify(topActivity).addToFinishingAndWaitForIdle();
    }

    /**
     * Verify that complete finish request for visible activity must not be delayed if the next one
     * is already visible and it's not the focused stack.
     */
    @Test
    public void testCompleteFinishing_noWaitForNextVisible_stopped() {
        final ActivityRecord activity = createActivityWithTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(activity.getTask()).build();
        topActivity.mVisibleRequested = false;
        topActivity.nowVisible = false;
        topActivity.finishing = true;
        topActivity.setState(STOPPED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        activity.mVisibleRequested = true;
        activity.nowVisible = true;
        activity.setState(RESUMED, "test");

        topActivity.completeFinishing("test");

        verify(topActivity).destroyIfPossible(anyString());
    }

    /**
     * Verify that complete finish request for visible activity must not be delayed if the next one
     * is already visible and it's not the focused stack.
     */
    @Test
    public void testCompleteFinishing_noWaitForNextVisible_nonFocusedStack() {
        final ActivityRecord activity = createActivityWithTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setTask(activity.getTask()).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        activity.mVisibleRequested = true;
        activity.nowVisible = true;
        activity.setState(RESUMED, "test");

        // Add another stack to become focused and make the activity there visible. This way it
        // simulates finishing in non-focused stack in split-screen.
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord focusedActivity = stack.getTopMostActivity();
        focusedActivity.nowVisible = true;
        focusedActivity.mVisibleRequested = true;
        focusedActivity.setState(RESUMED, "test");
        stack.setResumedActivity(focusedActivity, "test");

        topActivity.completeFinishing("test");

        verify(topActivity).destroyIfPossible(anyString());
    }

    /**
     * Verify that complete finish request for a show-when-locked activity must ensure the
     * keyguard occluded state being updated.
     */
    @Test
    public void testCompleteFinishing_showWhenLocked() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        // Make keyguard locked and set the top activity show-when-locked.
        KeyguardController keyguardController = activity.mTaskSupervisor.getKeyguardController();
        doReturn(true).when(keyguardController).isKeyguardLocked();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.setState(RESUMED, "true");
        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyInt() /* configChanges */,
                anyBoolean() /* preserveWindows */, anyBoolean() /* notifyClients */);
        topActivity.setShowWhenLocked(true);

        // Verify the stack-top activity is occluded keyguard.
        assertEquals(topActivity, task.topRunningActivity());
        assertTrue(keyguardController.isDisplayOccluded(DEFAULT_DISPLAY));

        // Finish the top activity
        topActivity.setState(PAUSED, "true");
        topActivity.finishing = true;
        topActivity.completeFinishing("test");

        // Verify new top activity does not occlude keyguard.
        assertEquals(activity, task.topRunningActivity());
        assertFalse(keyguardController.isDisplayOccluded(DEFAULT_DISPLAY));
    }

    /**
     * Verify that complete finish request for an activity which the resume activity is translucent
     * must ensure the visibilities of activities being updated.
     */
    @Test
    public void testCompleteFinishing_ensureActivitiesVisible_withConditions() {
        testCompleteFinishing_ensureActivitiesVisible(false, PAUSED);
        testCompleteFinishing_ensureActivitiesVisible(false, STARTED);
        testCompleteFinishing_ensureActivitiesVisible(true, PAUSED);
        testCompleteFinishing_ensureActivitiesVisible(true, STARTED);
    }

    private void testCompleteFinishing_ensureActivitiesVisible(boolean diffTask,
            ActivityState secondActivityState) {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm).setTask(task).build();
        firstActivity.mVisibleRequested = false;
        firstActivity.nowVisible = false;
        firstActivity.setState(STOPPED, "test");

        final ActivityRecord secondActivity = new ActivityBuilder(mAtm).setTask(task).build();
        secondActivity.mVisibleRequested = true;
        secondActivity.nowVisible = true;
        secondActivity.setState(secondActivityState, "test");

        ActivityRecord translucentActivity;
        if (diffTask) {
            translucentActivity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        } else {
            translucentActivity = new ActivityBuilder(mAtm).setTask(task).build();
        }
        translucentActivity.mVisibleRequested = true;
        translucentActivity.nowVisible = true;
        translucentActivity.setState(RESUMED, "test");

        doReturn(true).when(firstActivity).occludesParent(true);
        doReturn(true).when(secondActivity).occludesParent(true);

        // Finish the second activity
        secondActivity.finishing = true;
        secondActivity.completeFinishing("test");
        verify(secondActivity.mDisplayContent).ensureActivitiesVisible(null /* starting */,
                0 /* configChanges */ , false /* preserveWindows */,
                true /* notifyClients */);

        // Finish the first activity
        firstActivity.finishing = true;
        firstActivity.mVisibleRequested = true;
        firstActivity.completeFinishing("test");
        verify(firstActivity.mDisplayContent, times(2)).ensureActivitiesVisible(null /* starting */,
                0 /* configChanges */ , false /* preserveWindows */,
                true /* notifyClients */);

        // Remove the translucent activity and clear invocations for next test
        translucentActivity.getTask().removeImmediately("test");
        clearInvocations(mDefaultDisplay);
    }

    /**
     * Verify destroy activity request completes successfully.
     */
    @Test
    public void testDestroyIfPossible() {
        final ActivityRecord activity = createActivityWithTask();
        doReturn(false).when(mRootWindowContainer).resumeFocusedTasksTopActivities();
        activity.destroyIfPossible("test");

        assertEquals(DESTROYING, activity.getState());
        assertTrue(activity.finishing);
        verify(activity).destroyImmediately(anyString());
    }

    /**
     * Verify that complete finish request for visible activity must not destroy it immediately if
     * it is the last running activity on a display with a home stack. We must wait for home
     * activity to come up to avoid a black flash in this case.
     */
    @Test
    public void testDestroyIfPossible_lastActivityAboveEmptyHomeStack() {
        final ActivityRecord activity = createActivityWithTask();
        // Empty the home stack.
        final Task homeStack = activity.getDisplayArea().getRootHomeTask();
        homeStack.forAllLeafTasks((t) -> {
            homeStack.removeChild(t, "test");
        }, true /* traverseTopToBottom */);
        activity.finishing = true;
        doReturn(false).when(mRootWindowContainer).resumeFocusedTasksTopActivities();

        // Try to destroy the last activity above the home stack.
        activity.destroyIfPossible("test");

        // Verify that the activity was not actually destroyed, but waits for next one to come up
        // instead.
        verify(activity, never()).destroyImmediately(anyString());
        assertEquals(FINISHING, activity.getState());
        assertTrue(activity.mTaskSupervisor.mFinishingActivities.contains(activity));
    }

    /**
     * Verify that complete finish request for visible activity must resume next home stack before
     * destroying it immediately if it is the last running activity on a display with a home stack.
     * We must wait for home activity to come up to avoid a black flash in this case.
     */
    @Test
    public void testCompleteFinishing_lastActivityAboveEmptyHomeStack() {
        final ActivityRecord activity = createActivityWithTask();
        // Empty the home root task.
        final Task homeRootTask = activity.getDisplayArea().getRootHomeTask();
        homeRootTask.forAllLeafTasks((t) -> {
            homeRootTask.removeChild(t, "test");
        }, true /* traverseTopToBottom */);
        activity.setState(STARTED, "test");
        activity.finishing = true;
        activity.mVisibleRequested = true;

        // Try to finish the last activity above the home stack.
        activity.completeFinishing("test");

        // Verify that the activity is not destroyed immediately, but waits for next one to come up.
        verify(activity, never()).destroyImmediately(anyString());
        assertEquals(FINISHING, activity.getState());
        assertTrue(activity.mTaskSupervisor.mFinishingActivities.contains(activity));
    }

    /**
     * Test that the activity will be moved to destroying state and the message to destroy will be
     * sent to the client.
     */
    @Test
    public void testDestroyImmediately_hadApp_finishing() {
        final ActivityRecord activity = createActivityWithTask();
        activity.finishing = true;
        activity.destroyImmediately("test");

        assertEquals(DESTROYING, activity.getState());
    }

    /**
     * Test that the activity will be moved to destroyed state immediately if it was not marked as
     * finishing before {@link ActivityRecord#destroyImmediately(String)}.
     */
    @Test
    public void testDestroyImmediately_hadApp_notFinishing() {
        final ActivityRecord activity = createActivityWithTask();
        activity.finishing = false;
        activity.destroyImmediately("test");

        assertEquals(DESTROYED, activity.getState());
    }

    /**
     * Test that an activity with no process attached and that is marked as finishing will be
     * removed from task when {@link ActivityRecord#destroyImmediately(String)} is called.
     */
    @Test
    public void testDestroyImmediately_noApp_finishing() {
        final ActivityRecord activity = createActivityWithTask();
        activity.app = null;
        activity.finishing = true;
        final Task task = activity.getTask();

        activity.destroyImmediately("test");

        assertEquals(DESTROYED, activity.getState());
        assertNull(activity.getTask());
        assertEquals(0, task.getChildCount());
    }

    /**
     * Test that an activity with no process attached and that is not marked as finishing will be
     * marked as DESTROYED but not removed from task.
     */
    @Test
    public void testDestroyImmediately_noApp_notFinishing() {
        final ActivityRecord activity = createActivityWithTask();
        activity.app = null;
        activity.finishing = false;
        final Task task = activity.getTask();

        activity.destroyImmediately("test");

        assertEquals(DESTROYED, activity.getState());
        assertEquals(task, activity.getTask());
        assertEquals(1, task.getChildCount());
    }

    /**
     * Test that an activity will not be destroyed if it is marked as non-destroyable.
     */
    @Test
    public void testSafelyDestroy_nonDestroyable() {
        final ActivityRecord activity = createActivityWithTask();
        doReturn(false).when(activity).isDestroyable();

        activity.safelyDestroy("test");

        verify(activity, never()).destroyImmediately(anyString());
    }

    /**
     * Test that an activity will not be destroyed if it is marked as non-destroyable.
     */
    @Test
    public void testSafelyDestroy_destroyable() {
        final ActivityRecord activity = createActivityWithTask();
        doReturn(true).when(activity).isDestroyable();

        activity.safelyDestroy("test");

        verify(activity).destroyImmediately(anyString());
    }

    @Test
    public void testRemoveImmediately() throws RemoteException {
        final ActivityRecord activity = createActivityWithTask();
        final WindowProcessController wpc = activity.app;
        activity.getTask().removeImmediately("test");

        verify(mAtm.getLifecycleManager()).scheduleTransaction(any(), eq(activity.appToken),
                isA(DestroyActivityItem.class));
        assertNull(activity.app);
        assertEquals(DESTROYED, activity.getState());
        assertFalse(wpc.hasActivities());
    }

    @Test
    public void testRemoveFromHistory() {
        final ActivityRecord activity = createActivityWithTask();
        final Task rootTask = activity.getRootTask();
        final Task task = activity.getTask();
        final WindowProcessController wpc = activity.app;
        assertTrue(wpc.hasActivities());

        activity.removeFromHistory("test");

        assertEquals(DESTROYED, activity.getState());
        assertNull(activity.app);
        assertNull(activity.getTask());
        assertFalse(wpc.hasActivities());
        assertEquals(0, task.getChildCount());
        assertEquals(task.getRootTask(), task);
        assertEquals(0, rootTask.getChildCount());
    }

    /**
     * Test that it's not allowed to call {@link ActivityRecord#destroyed(String)} if activity is
     * not in destroying or destroyed state.
     */
    @Test(expected = IllegalStateException.class)
    public void testDestroyed_notDestroying() {
        final ActivityRecord activity = createActivityWithTask();
        activity.setState(STOPPED, "test");
        activity.destroyed("test");
    }

    /**
     * Test that {@link ActivityRecord#destroyed(String)} can be called if an activity is destroying
     */
    @Test
    public void testDestroyed_destroying() {
        final ActivityRecord activity = createActivityWithTask();
        activity.setState(DESTROYING, "test");
        activity.destroyed("test");

        verify(activity).removeFromHistory(anyString());
    }

    /**
     * Test that {@link ActivityRecord#destroyed(String)} can be called if an activity is destroyed.
     */
    @Test
    public void testDestroyed_destroyed() {
        final ActivityRecord activity = createActivityWithTask();
        activity.setState(DESTROYED, "test");
        activity.destroyed("test");

        verify(activity).removeFromHistory(anyString());
    }

    @Test
    public void testActivityOverridesProcessConfig() {
        final ActivityRecord activity = createActivityWithTask();
        final WindowProcessController wpc = activity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());
        assertFalse(wpc.registeredForDisplayAreaConfigChanges());

        final ActivityRecord secondaryDisplayActivity =
                createActivityOnDisplay(false /* defaultDisplay */, null /* process */);

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, activity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
        assertNotEquals(activity.getConfiguration(),
                secondaryDisplayActivity.getConfiguration());
    }

    @Test
    public void testActivityOverridesProcessConfig_TwoActivities() {
        final ActivityRecord activity = createActivityWithTask();
        final WindowProcessController wpc = activity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());

        final Task firstTaskRecord = activity.getTask();
        final ActivityRecord secondActivityRecord =
                new ActivityBuilder(mAtm).setTask(firstTaskRecord).setUseProcess(wpc).build();

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivityRecord.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testActivityOverridesProcessConfig_TwoActivities_SecondaryDisplay() {
        final ActivityRecord activity = createActivityWithTask();
        final WindowProcessController wpc = activity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());

        final ActivityRecord secondActivityRecord =
                new ActivityBuilder(mAtm).setTask(activity.getTask()).setUseProcess(wpc).build();

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivityRecord.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testActivityOverridesProcessConfig_TwoActivities_DifferentTasks() {
        final ActivityRecord activity = createActivityWithTask();
        final WindowProcessController wpc = activity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());

        final ActivityRecord secondActivityRecord =
                createActivityOnDisplay(true /* defaultDisplay */, wpc);

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivityRecord.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testActivityOnCancelFixedRotationTransform() {
        final ActivityRecord activity = createActivityWithTask();
        final DisplayRotation displayRotation = activity.mDisplayContent.getDisplayRotation();
        spyOn(displayRotation);

        final DisplayContent display = activity.mDisplayContent;
        final int originalRotation = display.getRotation();

        // Make {@link DisplayContent#sendNewConfiguration} not apply rotation immediately.
        doReturn(true).when(displayRotation).isWaitingForRemoteRotation();
        doReturn((originalRotation + 1) % 4).when(displayRotation).rotationForOrientation(
                anyInt() /* orientation */, anyInt() /* lastRotation */);
        // Set to visible so the activity can freeze the screen.
        activity.setVisibility(true);

        display.rotateInDifferentOrientationIfNeeded(activity);
        display.setFixedRotationLaunchingAppUnchecked(activity);
        displayRotation.updateRotationUnchecked(true /* forceUpdate */);

        assertTrue(displayRotation.isRotatingSeamlessly());

        // The launching rotated app should not be cleared when waiting for remote rotation.
        display.continueUpdateOrientationForDiffOrienLaunchingApp();
        assertTrue(display.isFixedRotationLaunchingApp(activity));

        // Simulate the rotation has been updated to previous one, e.g. sensor updates before the
        // remote rotation is completed.
        doReturn(originalRotation).when(displayRotation).rotationForOrientation(
                anyInt() /* orientation */, anyInt() /* lastRotation */);
        display.updateOrientation();

        final DisplayInfo rotatedInfo = activity.getFixedRotationTransformDisplayInfo();
        activity.finishFixedRotationTransform();
        final ScreenRotationAnimation rotationAnim = display.getRotationAnimation();
        assertNotNull(rotationAnim);
        rotationAnim.setRotation(display.getPendingTransaction(), originalRotation);

        // Because the display doesn't rotate, the rotated activity needs to cancel the fixed
        // rotation. There should be a rotation animation to cover the change of activity.
        verify(activity).onCancelFixedRotationTransform(rotatedInfo.rotation);
        assertTrue(activity.isFreezingScreen());
        assertFalse(displayRotation.isRotatingSeamlessly());
        assertTrue(rotationAnim.isRotating());

        // Simulate the remote rotation has completed and the configuration doesn't change, then
        // the rotated activity should also be restored by clearing the transform.
        displayRotation.updateRotationUnchecked(true /* forceUpdate */);
        doReturn(false).when(displayRotation).isWaitingForRemoteRotation();
        clearInvocations(activity);
        display.setFixedRotationLaunchingAppUnchecked(activity);
        display.sendNewConfiguration();

        assertFalse(display.hasTopFixedRotationLaunchingApp());
        assertFalse(activity.hasFixedRotationTransform());

        // Simulate that the activity requests the same orientation as display.
        activity.setOrientation(display.getConfiguration().orientation);
        // Skip the real freezing.
        activity.mVisibleRequested = false;
        clearInvocations(activity);
        activity.onCancelFixedRotationTransform(originalRotation);
        // The implementation of cancellation must be executed.
        verify(activity).startFreezingScreen(originalRotation);
    }

    @Test
    public void testIsSnapshotCompatible() {
        final ActivityRecord activity = createActivityWithTask();
        final TaskSnapshot snapshot = new TaskSnapshotPersisterTestBase.TaskSnapshotBuilder()
                .setTopActivityComponent(activity.mActivityComponent)
                .setRotation(activity.getWindowConfiguration().getRotation())
                .build();

        assertTrue(activity.isSnapshotCompatible(snapshot));

        setRotatedScreenOrientationSilently(activity);

        assertFalse(activity.isSnapshotCompatible(snapshot));
    }

    /**
     * Test that the snapshot should be obsoleted if the top activity changed.
     */
    @Test
    public void testIsSnapshotCompatibleTopActivityChanged() {
        final ActivityRecord activity = createActivityWithTask();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(activity.getTask())
                .setOnTop(true)
                .build();
        final TaskSnapshot snapshot = new TaskSnapshotPersisterTestBase.TaskSnapshotBuilder()
                .setTopActivityComponent(secondActivity.mActivityComponent)
                .build();

        assertTrue(secondActivity.isSnapshotCompatible(snapshot));

        // Emulate the top activity changed.
        assertFalse(activity.isSnapshotCompatible(snapshot));
    }

    @Test
    public void testFixedRotationSnapshotStartingWindow() {
        final ActivityRecord activity = createActivityWithTask();
        // TaskSnapshotSurface requires a fullscreen opaque window.
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);
        params.width = params.height = WindowManager.LayoutParams.MATCH_PARENT;
        final TestWindowState w = new TestWindowState(
                mAtm.mWindowManager, mock(Session.class), new TestIWindow(), params, activity);
        activity.addWindow(w);

        // Assume the activity is launching in different rotation, and there was an available
        // snapshot accepted by {@link Activity#isSnapshotCompatible}.
        final TaskSnapshot snapshot = new TaskSnapshotPersisterTestBase.TaskSnapshotBuilder()
                .setRotation((activity.getWindowConfiguration().getRotation() + 1) % 4)
                .build();
        setRotatedScreenOrientationSilently(activity);
        activity.setVisible(false);

        final IWindowSession session = WindowManagerGlobal.getWindowSession();
        spyOn(session);
        try {
            // Return error to skip unnecessary operation.
            doReturn(WindowManagerGlobal.ADD_STARTING_NOT_NEEDED).when(session).addToDisplay(
                    any() /* window */,  any() /* attrs */,
                    anyInt() /* viewVisibility */, anyInt() /* displayId */,
                    any() /* requestedVisibility */, any() /* outInputChannel */,
                    any() /* outInsetsState */, any() /* outActiveControls */);
            mAtm.mWindowManager.mStartingSurfaceController
                    .createTaskSnapshotSurface(activity, snapshot);
        } catch (RemoteException ignored) {
        } finally {
            reset(session);
        }

        // Because the rotation of snapshot and the corresponding top activity are different, fixed
        // rotation should be applied when creating snapshot surface if the display rotation may be
        // changed according to the activity orientation.
        assertTrue(activity.hasFixedRotationTransform());
        assertTrue(activity.mDisplayContent.isFixedRotationLaunchingApp(activity));
    }

    /**
     * Sets orientation without notifying the parent to simulate that the display has not applied
     * the requested orientation yet.
     */
    static void setRotatedScreenOrientationSilently(ActivityRecord r) {
        final int rotatedOrentation = r.getConfiguration().orientation == ORIENTATION_PORTRAIT
                ? SCREEN_ORIENTATION_LANDSCAPE
                : SCREEN_ORIENTATION_PORTRAIT;
        doReturn(false).when(r).onDescendantOrientationChanged(any());
        r.setOrientation(rotatedOrentation);
    }

    @Test
    public void testActivityOnDifferentDisplayUpdatesProcessOverride() {
        final ActivityRecord secondaryDisplayActivity =
                createActivityOnDisplay(false /* defaultDisplay */, null /* process */);
        final WindowProcessController wpc = secondaryDisplayActivity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());

        final ActivityRecord secondActivityRecord =
                createActivityOnDisplay(true /* defaultDisplay */, wpc);

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivityRecord.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
        assertFalse(wpc.registeredForDisplayAreaConfigChanges());
    }

    @Test
    public void testActivityReparentChangesProcessOverride() {
        final ActivityRecord activity = createActivityWithTask();
        final WindowProcessController wpc = activity.app;
        final Task initialTask = activity.getTask();
        final Configuration initialConf =
                new Configuration(activity.getMergedOverrideConfiguration());
        assertEquals(0, activity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
        assertTrue(wpc.registeredForActivityConfigChanges());

        // Create a new task with custom config to reparent the activity to.
        final Task newTask =
                new TaskBuilder(mSupervisor).setParentTask(initialTask.getRootTask()).build();
        final Configuration newConfig = newTask.getConfiguration();
        newConfig.densityDpi += 100;
        newTask.onRequestedOverrideConfigurationChanged(newConfig);
        assertEquals(newTask.getConfiguration().densityDpi, newConfig.densityDpi);

        // Reparent the activity and verify that config override changed.
        activity.reparent(newTask, 0 /* top */, "test");
        assertEquals(activity.getConfiguration().densityDpi, newConfig.densityDpi);
        assertEquals(activity.getMergedOverrideConfiguration().densityDpi, newConfig.densityDpi);

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertNotEquals(initialConf, wpc.getRequestedOverrideConfiguration());
        assertEquals(0, activity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testActivityReparentDoesntClearProcessOverride_TwoActivities() {
        final ActivityRecord activity = createActivityWithTask();
        final WindowProcessController wpc = activity.app;
        final Configuration initialConf =
                new Configuration(activity.getMergedOverrideConfiguration());
        final Task initialTask = activity.getTask();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm).setTask(initialTask)
                .setUseProcess(wpc).build();

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));

        // Create a new task with custom config to reparent the second activity to.
        final Task newTask =
                new TaskBuilder(mSupervisor).setParentTask(initialTask.getRootTask()).build();
        final Configuration newConfig = newTask.getConfiguration();
        newConfig.densityDpi += 100;
        newTask.onRequestedOverrideConfigurationChanged(newConfig);

        // Reparent the activity and verify that config override changed.
        secondActivity.reparent(newTask, 0 /* top */, "test");

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
        assertNotEquals(initialConf, wpc.getRequestedOverrideConfiguration());

        // Reparent the first activity and verify that config override didn't change.
        activity.reparent(newTask, 1 /* top */, "test");
        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
        assertNotEquals(initialConf, wpc.getRequestedOverrideConfiguration());
    }

    @Test
    public void testActivityDestroyDoesntChangeProcessOverride() {
        final ActivityRecord firstActivity =
                createActivityOnDisplay(true /* defaultDisplay */, null /* process */);
        final WindowProcessController wpc = firstActivity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, firstActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));

        final ActivityRecord secondActivity =
                createActivityOnDisplay(false /* defaultDisplay */, wpc);
        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));

        final ActivityRecord thirdActivity =
                createActivityOnDisplay(false /* defaultDisplay */, wpc);
        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, thirdActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));

        secondActivity.destroyImmediately("");

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, thirdActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));

        firstActivity.destroyImmediately("");

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, thirdActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testFullscreenWindowCanTurnScreenOn() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        doReturn(true).when(activity).getTurnScreenOnFlag();

        assertTrue(activity.canTurnScreenOn());
    }

    @Test
    public void testFreeformWindowCanTurnScreenOn() {
        final ActivityRecord activity = createActivityWithTask();
        final Task task = activity.getTask();
        task.setWindowingMode(WINDOWING_MODE_FREEFORM);
        doReturn(true).when(activity).getTurnScreenOnFlag();

        assertTrue(activity.canTurnScreenOn());
    }

    @Test
    public void testGetLockTaskLaunchMode() {
        final ActivityRecord activity = createActivityWithTask();
        final ActivityOptions options = ActivityOptions.makeBasic().setLockTaskEnabled(true);
        activity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_DEFAULT;
        assertEquals(LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED,
                ActivityRecord.getLockTaskLaunchMode(activity.info, options));

        activity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_ALWAYS;
        assertEquals(LOCK_TASK_LAUNCH_MODE_DEFAULT,
                ActivityRecord.getLockTaskLaunchMode(activity.info, null /*options*/));

        activity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_NEVER;
        assertEquals(LOCK_TASK_LAUNCH_MODE_DEFAULT,
                ActivityRecord.getLockTaskLaunchMode(activity.info, null /*options*/));

        activity.info.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
        activity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_ALWAYS;
        assertEquals(LOCK_TASK_LAUNCH_MODE_ALWAYS,
                ActivityRecord.getLockTaskLaunchMode(activity.info, null /*options*/));

        activity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_NEVER;
        assertEquals(LOCK_TASK_LAUNCH_MODE_NEVER,
                ActivityRecord.getLockTaskLaunchMode(activity.info, null /*options*/));

    }

    @Test
    public void testProcessInfoUpdateWhenSetState() {
        final ActivityRecord activity = createActivityWithTask();
        activity.setState(INITIALIZING, "test");
        spyOn(activity.app);
        verifyProcessInfoUpdate(activity, RESUMED,
                true /* shouldUpdate */, true /* activityChange */);
        verifyProcessInfoUpdate(activity, PAUSED,
                false /* shouldUpdate */, false /* activityChange */);
        verifyProcessInfoUpdate(activity, STOPPED,
                false /* shouldUpdate */, false /* activityChange */);
        verifyProcessInfoUpdate(activity, STARTED,
                true /* shouldUpdate */, true /* activityChange */);

        activity.app.removeActivity(activity, true /* keepAssociation */);
        verifyProcessInfoUpdate(activity, DESTROYING,
                true /* shouldUpdate */, false /* activityChange */);
        verifyProcessInfoUpdate(activity, DESTROYED,
                true /* shouldUpdate */, false /* activityChange */);
    }

    @Test
    public void testSupportsSplitScreenWindowingMode() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setScreenOrientation(SCREEN_ORIENTATION_LANDSCAPE)
                .build();

        // Not allow non-resizable
        mAtm.mForceResizableActivities = false;
        mAtm.mSupportsNonResizableMultiWindow = -1;
        mAtm.mDevEnableNonResizableMultiWindow = false;
        assertFalse(activity.supportsSplitScreenWindowingMode());

        // Force resizable
        mAtm.mForceResizableActivities = true;
        mAtm.mSupportsNonResizableMultiWindow = -1;
        mAtm.mDevEnableNonResizableMultiWindow = false;
        assertTrue(activity.supportsSplitScreenWindowingMode());

        // Use development option to allow non-resizable
        mAtm.mForceResizableActivities = false;
        mAtm.mSupportsNonResizableMultiWindow = -1;
        mAtm.mDevEnableNonResizableMultiWindow = true;
        assertTrue(activity.supportsSplitScreenWindowingMode());

        // Always allow non-resizable
        mAtm.mForceResizableActivities = false;
        mAtm.mSupportsNonResizableMultiWindow = 1;
        mAtm.mDevEnableNonResizableMultiWindow = false;
        assertTrue(activity.supportsSplitScreenWindowingMode());
    }

    @Test
    public void testSupportsFreeform() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setScreenOrientation(SCREEN_ORIENTATION_LANDSCAPE)
                .build();

        // Not allow non-resizable
        mAtm.mForceResizableActivities = false;
        mAtm.mSupportsNonResizableMultiWindow = -1;
        mAtm.mDevEnableNonResizableMultiWindow = false;
        assertFalse(activity.supportsFreeform());

        // Force resizable
        mAtm.mForceResizableActivities = true;
        mAtm.mSupportsNonResizableMultiWindow = -1;
        mAtm.mDevEnableNonResizableMultiWindow = false;
        assertTrue(activity.supportsFreeform());

        // Use development option to allow non-resizable
        mAtm.mForceResizableActivities = false;
        mAtm.mSupportsNonResizableMultiWindow = -1;
        mAtm.mDevEnableNonResizableMultiWindow = true;
        assertTrue(activity.supportsFreeform());

        // Always allow non-resizable
        mAtm.mForceResizableActivities = false;
        mAtm.mSupportsNonResizableMultiWindow = 1;
        mAtm.mDevEnableNonResizableMultiWindow = false;
        assertTrue(activity.supportsFreeform());
    }

    @Test
    public void testSupportsPictureInPicture() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setActivityFlags(FLAG_SUPPORTS_PICTURE_IN_PICTURE)
                .build();

        // Device not supports PIP
        mAtm.mSupportsPictureInPicture = false;
        assertFalse(activity.supportsPictureInPicture());

        // Device and app support PIP
        mAtm.mSupportsPictureInPicture = true;
        assertTrue(activity.supportsPictureInPicture());

        // Activity not supports PIP
        activity.info.flags &= ~FLAG_SUPPORTS_PICTURE_IN_PICTURE;
        assertFalse(activity.supportsPictureInPicture());
    }

    private void verifyProcessInfoUpdate(ActivityRecord activity, ActivityState state,
            boolean shouldUpdate, boolean activityChange) {
        reset(activity.app);
        activity.setState(state, "test");
        verify(activity.app, times(shouldUpdate ? 1 : 0)).updateProcessInfo(anyBoolean(),
                eq(activityChange), anyBoolean(), anyBoolean());
    }

    private ActivityRecord createActivityWithTask() {
        return new ActivityBuilder(mAtm).setCreateTask(true).setOnTop(true).build();
    }

    private ActivityRecord createActivityWith2LevelTask() {
        final Task task = new TaskBuilder(mSupervisor)
                .setCreateParentTask(true).setCreateActivity(true).build();
        return task.getTopNonFinishingActivity();
    }

    /**
     * Creates an activity on display. For non-default display request it will also create a new
     * display with custom DisplayInfo.
     */
    private ActivityRecord createActivityOnDisplay(boolean defaultDisplay,
            WindowProcessController process) {
        final DisplayContent display;
        if (defaultDisplay) {
            display = mRootWindowContainer.getDefaultDisplay();
        } else {
            display = new TestDisplayContent.Builder(mAtm, 2000, 1000).setDensityDpi(300)
                    .setPosition(DisplayContent.POSITION_TOP).build();
        }
        final Task task = new TaskBuilder(mSupervisor).setDisplay(display).build();
        return new ActivityBuilder(mAtm).setTask(task).setUseProcess(process).build();
    }

    @Test
    @Presubmit
    public void testAddWindow_Order() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        assertEquals(0, activity.getChildCount());

        final WindowState win1 = createWindow(null, TYPE_APPLICATION, activity, "win1");
        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "startingWin");
        final WindowState baseWin = createWindow(null, TYPE_BASE_APPLICATION, activity, "baseWin");
        final WindowState win4 = createWindow(null, TYPE_APPLICATION, activity, "win4");

        // Should not contain the windows that were added above.
        assertEquals(4, activity.getChildCount());
        assertTrue(activity.mChildren.contains(win1));
        assertTrue(activity.mChildren.contains(startingWin));
        assertTrue(activity.mChildren.contains(baseWin));
        assertTrue(activity.mChildren.contains(win4));

        // The starting window should be on-top of all other windows.
        assertEquals(startingWin, activity.mChildren.peekLast());

        // The base application window should be below all other windows.
        assertEquals(baseWin, activity.mChildren.peekFirst());
        activity.removeImmediately();
    }

    @Test
    @Presubmit
    public void testFindMainWindow() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        assertNull(activity.findMainWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, activity, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, activity, "window12");
        assertEquals(window1, activity.findMainWindow());
        window1.mAnimatingExit = true;
        assertEquals(window1, activity.findMainWindow());
        final WindowState window2 = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "window2");
        assertEquals(window2, activity.findMainWindow());
        activity.removeImmediately();
    }

    @Test
    @Presubmit
    public void testGetTopFullscreenOpaqueWindow() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        assertNull(activity.getTopFullscreenOpaqueWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "window1");
        final WindowState window11 = createWindow(null, TYPE_APPLICATION, activity, "window11");
        final WindowState window12 = createWindow(null, TYPE_APPLICATION, activity, "window12");
        assertEquals(window12, activity.getTopFullscreenOpaqueWindow());
        window12.mAttrs.width = 500;
        assertEquals(window11, activity.getTopFullscreenOpaqueWindow());
        window11.mAttrs.width = 500;
        assertEquals(window1, activity.getTopFullscreenOpaqueWindow());
        window1.mAttrs.alpha = 0f;
        assertNull(activity.getTopFullscreenOpaqueWindow());
        activity.removeImmediately();
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testLandscapeSeascapeRotationByApp() {
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final TestWindowState appWindow = createWindowState(attrs, activity);
        activity.addWindow(appWindow);
        spyOn(appWindow);
        doNothing().when(appWindow).onStartFreezingScreen();

        // Set initial orientation and update.
        activity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        mDisplayContent.updateOrientation(
                mDisplayContent.getRequestedOverrideConfiguration(),
                null /* freezeThisOneIfNeeded */, false /* forceUpdate */);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mDisplayContent.getLastOrientation());
        appWindow.mResizeReported = false;

        // Update the orientation to perform 180 degree rotation and check that resize was reported.
        activity.setOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        mDisplayContent.updateOrientation(
                mDisplayContent.getRequestedOverrideConfiguration(),
                null /* freezeThisOneIfNeeded */, false /* forceUpdate */);
        // In this test, DC will not get config update. Set the waiting flag to false.
        mDisplayContent.mWaitingForConfig = false;
        mWm.mRoot.performSurfacePlacement();
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, mDisplayContent.getLastOrientation());
        assertTrue(appWindow.mResizeReported);
        appWindow.removeImmediately();
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testLandscapeSeascapeRotationByPolicy() {
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        // This instance has been spied in {@link TestDisplayContent}.
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("RotationByPolicy");
        final TestWindowState appWindow = createWindowState(attrs, activity);
        activity.addWindow(appWindow);
        spyOn(appWindow);
        doNothing().when(appWindow).onStartFreezingScreen();

        // Set initial orientation and update.
        performRotation(displayRotation, Surface.ROTATION_90);
        appWindow.mResizeReported = false;

        // Update the rotation to perform 180 degree rotation and check that resize was reported.
        performRotation(displayRotation, Surface.ROTATION_270);
        assertTrue(appWindow.mResizeReported);

        appWindow.removeImmediately();
    }

    private void performRotation(DisplayRotation spiedRotation, int rotationToReport) {
        doReturn(rotationToReport).when(spiedRotation).rotationForOrientation(anyInt(), anyInt());
        mWm.updateRotation(false, false);
    }

    @Test
    @Presubmit
    public void testGetOrientation() {
        // ActivityBuilder will resume top activities and cause the activity been added into
        // opening apps list. Since this test is focus on the effect of visible on getting
        // orientation, we skip app transition to avoid interference.
        doNothing().when(mDisplayContent).prepareAppTransition(anyInt());
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.setVisible(true);

        activity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        activity.setOccludesParent(false);
        // Can specify orientation if app doesn't occludes parent.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, activity.getOrientation());

        activity.setOccludesParent(true);
        activity.setVisible(false);
        activity.mVisibleRequested = false;
        // Can not specify orientation if app isn't visible even though it occludes parent.
        assertEquals(SCREEN_ORIENTATION_UNSET, activity.getOrientation());
        // Can specify orientation if the current orientation candidate is orientation behind.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE,
                activity.getOrientation(SCREEN_ORIENTATION_BEHIND));
    }

    @Test
    @Presubmit
    public void testKeyguardFlagsDuringRelaunch() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.flags |= FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD;
        attrs.setTitle("AppWindow");
        final TestWindowState appWindow = createWindowState(attrs, activity);

        // Add window with show when locked flag
        activity.addWindow(appWindow);
        assertTrue(activity.containsShowWhenLockedWindow()
                && activity.containsDismissKeyguardWindow());

        // Start relaunching
        activity.startRelaunching();
        assertTrue(activity.containsShowWhenLockedWindow()
                && activity.containsDismissKeyguardWindow());

        // Remove window and make sure that we still report back flag
        activity.removeChild(appWindow);
        assertTrue(activity.containsShowWhenLockedWindow()
                && activity.containsDismissKeyguardWindow());

        // Finish relaunching and ensure flag is now not reported
        activity.finishRelaunching();
        assertFalse(activity.containsShowWhenLockedWindow()
                || activity.containsDismissKeyguardWindow());
    }

    @Test
    public void testStuckExitingWindow() {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        closingWindow.mAnimatingExit = true;
        closingWindow.mRemoveOnExit = true;
        closingWindow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);

        // We pretended that we were running an exit animation, but that should have been cleared up
        // by changing visibility of ActivityRecord
        closingWindow.removeIfPossible();
        assertTrue(closingWindow.mRemoved);
    }

    @Test
    public void testSetOrientation() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.setVisible(true);

        // Assert orientation is unspecified to start.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, activity.getOrientation());

        activity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, activity.getOrientation());

        mDisplayContent.removeAppToken(activity.token);
        // Assert orientation is unset to after container is removed.
        assertEquals(SCREEN_ORIENTATION_UNSET, activity.getOrientation());

        // Reset display frozen state
        mWm.mDisplayFrozen = false;
    }

    @UseTestDisplay
    @Test
    public void testRespectTopFullscreenOrientation() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Configuration displayConfig = activity.mDisplayContent.getConfiguration();
        final Configuration activityConfig = activity.getConfiguration();
        activity.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        assertEquals(Configuration.ORIENTATION_PORTRAIT, displayConfig.orientation);
        assertEquals(Configuration.ORIENTATION_PORTRAIT, activityConfig.orientation);

        final ActivityRecord topActivity = createActivityRecord(activity.getTask());
        topActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertEquals(Configuration.ORIENTATION_LANDSCAPE, displayConfig.orientation);
        // Although the activity requested portrait, it is not the top activity that determines
        // the display orientation. So it should be able to inherit the orientation from parent.
        // Otherwise its configuration will be inconsistent that its orientation is portrait but
        // other screen configurations are in landscape, e.g. screenWidthDp, screenHeightDp, and
        // window configuration.
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, activityConfig.orientation);
    }

    @UseTestDisplay
    @Test
    public void testReportOrientationChange() {
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        activity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        mDisplayContent.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_ENABLED);
        reset(task);
        activity.reportDescendantOrientationChangeIfNeeded();
        verify(task, atLeast(1)).onConfigurationChanged(any(Configuration.class));
    }

    @Test
    public void testCreateRemoveStartingWindow() {
        registerTestStartingWindowOrganizer();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        assertHasStartingWindow(activity);
        activity.removeStartingWindow();
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity);
    }

    private void testLegacySplashScreen(int targetSdk, int verifyType) {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.mTargetSdk = targetSdk;
        activity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        assertHasStartingWindow(activity);
        assertEquals(activity.mStartingData.mTypeParams & TYPE_PARAMETER_LEGACY_SPLASH_SCREEN,
                verifyType);
        activity.removeStartingWindow();
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity);
    }

    @Test
    public void testCreateRemoveLegacySplashScreenWindow() {
        registerTestStartingWindowOrganizer();
        DeviceConfig.Properties properties = DeviceConfig.getProperties(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER);
        try {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                    "splash_screen_exception_list", DEFAULT_COMPONENT_PACKAGE_NAME, false);
            testLegacySplashScreen(Build.VERSION_CODES.R, TYPE_PARAMETER_LEGACY_SPLASH_SCREEN);
            testLegacySplashScreen(Build.VERSION_CODES.S, 0);
        } finally {
            try {
                DeviceConfig.setProperties(properties);
            } catch (DeviceConfig.BadConfigException e) {
                Assert.fail(e.getMessage());
            }
        }
    }

    @Test
    public void testTransferStartingWindow() {
        registerTestStartingWindowOrganizer();
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        activity2.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, activity1.appToken.asBinder(),
                true, true, false, true, false, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity1);
        assertHasStartingWindow(activity2);
    }

    @Test
    public void testTransferStartingWindowWhileCreating() {
        final TestStartingWindowOrganizer organizer = registerTestStartingWindowOrganizer();
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        organizer.setRunnableWhenAddingSplashScreen(
                () -> {
                    // Surprise, ...! Transfer window in the middle of the creation flow.
                    activity2.addStartingWindow(mPackageName,
                            android.R.style.Theme, null, "Test", 0, 0, 0, 0,
                            activity1.appToken.asBinder(), true, true, false,
                            true, false, false);
                });
        activity1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity1);
        assertHasStartingWindow(activity2);
    }

    @Test
    public void testTransferStartingWindowCanAnimate() {
        registerTestStartingWindowOrganizer();
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        activity2.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, activity1.appToken.asBinder(),
                true, true, false, true, false, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity1);
        assertHasStartingWindow(activity2);

        // Assert that bottom activity is allowed to do animation.
        ArrayList<WindowContainer> sources = new ArrayList<>();
        sources.add(activity2);
        doReturn(true).when(activity2).okToAnimate();
        doReturn(true).when(activity2).isAnimating();
        assertTrue(activity2.applyAnimation(null, TRANSIT_OLD_ACTIVITY_OPEN, true, false, sources));
    }
    @Test
    public void testTrackingStartingWindowThroughTrampoline() {
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm)
                .setCreateTask(true).setLaunchedFromUid(Process.SYSTEM_UID).build();
        sourceRecord.showStartingWindow(null /* prev */, true /* newTask */, false,
                true /* startActivity */, null);

        final ActivityRecord secondRecord = new ActivityBuilder(mAtm)
                .setTask(sourceRecord.getTask()).build();
        secondRecord.showStartingWindow(null /* prev */, true /* newTask */, false,
                true /* startActivity */, sourceRecord);
        assertFalse(secondRecord.mSplashScreenStyleEmpty);
        secondRecord.onStartingWindowDrawn();

        final ActivityRecord finalRecord = new ActivityBuilder(mAtm)
                .setTask(sourceRecord.getTask()).build();
        finalRecord.showStartingWindow(null /* prev */, true /* newTask */, false,
                true /* startActivity */, secondRecord);
        assertTrue(finalRecord.mSplashScreenStyleEmpty);
    }

    @Test
    public void testTransferStartingWindowFromFinishingActivity() {
        registerTestStartingWindowOrganizer();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = activity.getTask();
        activity.addStartingWindow(mPackageName, android.R.style.Theme, null /* compatInfo */,
                "Test", 0 /* labelRes */, 0 /* icon */, 0 /* logo */, 0 /* windowFlags */,
                null /* transferFrom */, true /* newTask */, true /* taskSwitch */,
                false /* processRunning */, false /* allowTaskSnapshot */,
                false /* activityCreate */, false /* suggestEmpty */);
        waitUntilHandlersIdle();
        assertHasStartingWindow(activity);
        activity.mStartingWindowState = ActivityRecord.STARTING_WINDOW_SHOWN;

        doCallRealMethod().when(task).startActivityLocked(
                any(), any(), anyBoolean(), anyBoolean(), any(), any());
        // In normal case, resumeFocusedTasksTopActivities() should be called after
        // startActivityLocked(). So skip resumeFocusedTasksTopActivities() in ActivityBuilder.
        doReturn(false).when(mRootWindowContainer).resumeFocusedTasksTopActivities();
        // Make mVisibleSetFromTransferredStartingWindow true.
        final ActivityRecord middle = new ActivityBuilder(mAtm).setTask(task).build();
        task.startActivityLocked(middle, null /* focusedTopActivity */,
                false /* newTask */, false /* keepCurTransition */, null /* options */,
                null /* sourceRecord */);
        middle.makeFinishingLocked();

        assertNull(activity.mStartingWindow);
        assertHasStartingWindow(middle);

        final ActivityRecord top = new ActivityBuilder(mAtm).setTask(task).build();
        // Expect the visibility should be updated to true when transferring starting window from
        // a visible activity.
        top.setVisible(false);
        // The finishing middle should be able to transfer starting window to top.
        task.startActivityLocked(top, null /* focusedTopActivity */,
                false /* newTask */, false /* keepCurTransition */, null /* options */,
                null /* sourceRecord */);

        assertNull(middle.mStartingWindow);
        assertHasStartingWindow(top);
        assertTrue(top.isVisible());
        // The activity was visible by mVisibleSetFromTransferredStartingWindow, so after its
        // starting window is transferred, it should restore to invisible.
        assertFalse(middle.isVisible());
    }

    @Test
    public void testTransferStartingWindowSetFixedRotation() {
        registerTestStartingWindowOrganizer();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = activity.getTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        topActivity.setVisible(false);
        task.positionChildAt(topActivity, POSITION_TOP);
        activity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();

        // Make activities to have different rotation from it display and set fixed rotation
        // transform to activity1.
        int rotation = (mDisplayContent.getRotation() + 1) % 4;
        mDisplayContent.setFixedRotationLaunchingApp(activity, rotation);
        doReturn(rotation).when(mDisplayContent)
                .rotationForActivityInDifferentOrientation(topActivity);

        // The transform will be finished because there is no running animation. Keep activity in
        // animating state to avoid the transform being finished.
        doReturn(true).when(activity).isAnimating(anyInt());
        // Make sure the fixed rotation transform linked to activity2 when adding starting window
        // on activity2.
        topActivity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, activity.appToken.asBinder(),
                false, false, false, true, false, false);
        waitUntilHandlersIdle();
        assertTrue(topActivity.hasFixedRotationTransform());
    }

    @Test
    public void testTryTransferStartingWindowFromHiddenAboveToken() {
        registerTestStartingWindowOrganizer();
        // Add two tasks on top of each other.
        final ActivityRecord activityTop = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activityBottom = new ActivityBuilder(mAtm).build();
        activityTop.getTask().addChild(activityBottom, 0);

        // Add a starting window.
        activityTop.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();

        // Make the top one invisible, and try transferring the starting window from the top to the
        // bottom one.
        activityTop.setVisibility(false, false);
        activityBottom.transferStartingWindowFromHiddenAboveTokenIfNeeded();
        waitUntilHandlersIdle();

        // Assert that the bottom window now has the starting window.
        assertNoStartingWindow(activityTop);
        assertHasStartingWindow(activityBottom);
    }

    @Test
    public void testTransitionAnimationBounds() {
        removeGlobalMinSizeRestriction();
        final Task task = new TaskBuilder(mSupervisor)
                .setCreateParentTask(true).setCreateActivity(true).build();
        final Task rootTask = task.getRootTask();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        final Rect stackBounds = new Rect(0, 0, 1000, 600);
        final Rect taskBounds = new Rect(100, 400, 600, 800);
        // Set the bounds and windowing mode to window configuration directly, otherwise the
        // testing setups may be discarded by configuration resolving.
        rootTask.getWindowConfiguration().setBounds(stackBounds);
        task.getWindowConfiguration().setBounds(taskBounds);
        activity.getWindowConfiguration().setBounds(taskBounds);

        // Check that anim bounds for freeform window match task bounds
        task.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(task.getBounds(), activity.getAnimationBounds(ROOT_TASK_CLIP_NONE));

        // ROOT_TASK_CLIP_AFTER_ANIM should use task bounds since they will be clipped by
        // bounds animation layer.
        task.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertEquals(task.getBounds(), activity.getAnimationBounds(ROOT_TASK_CLIP_AFTER_ANIM));

        // Even the activity is smaller than task and it is not aligned to the top-left corner of
        // task, the animation bounds the same as task and position should be zero because in real
        // case the letterbox will fill the remaining area in task.
        final Rect halfBounds = new Rect(taskBounds);
        halfBounds.scale(0.5f);
        activity.getWindowConfiguration().setBounds(halfBounds);
        final Point animationPosition = new Point();
        activity.getAnimationPosition(animationPosition);

        assertEquals(taskBounds, activity.getAnimationBounds(ROOT_TASK_CLIP_AFTER_ANIM));
        assertEquals(new Point(0, 0), animationPosition);

        // ROOT_TASK_CLIP_BEFORE_ANIM should use stack bounds since it won't be clipped later.
        task.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(rootTask.getBounds(), activity.getAnimationBounds(ROOT_TASK_CLIP_BEFORE_ANIM));
    }

    @Test
    public void testHasStartingWindow() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final WindowManager.LayoutParams attrs =
                new WindowManager.LayoutParams(TYPE_APPLICATION_STARTING);
        final TestWindowState startingWindow = createWindowState(attrs, activity);
        activity.startingDisplayed = true;
        activity.addWindow(startingWindow);
        assertTrue("Starting window should be present", activity.hasStartingWindow());
        activity.startingDisplayed = false;
        assertTrue("Starting window should be present", activity.hasStartingWindow());

        activity.removeChild(startingWindow);
        assertFalse("Starting window should not be present", activity.hasStartingWindow());
    }

    @Test
    public void testSetVisibility_visibleToVisible() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true).build();
        // By default, activity is visible.
        assertTrue(activity.isVisible());
        assertTrue(activity.mVisibleRequested);
        assertTrue(activity.mDisplayContent.mOpeningApps.contains(activity));
        assertFalse(activity.mDisplayContent.mClosingApps.contains(activity));

        // Request the activity to be visible. Although the activity is already visible, app
        // transition animation should be applied on this activity. This might be unnecessary, but
        // until we verify no logic relies on this behavior, we'll keep this as is.
        activity.setVisibility(true);
        assertTrue(activity.isVisible());
        assertTrue(activity.mVisibleRequested);
        assertTrue(activity.mDisplayContent.mOpeningApps.contains(activity));
        assertFalse(activity.mDisplayContent.mClosingApps.contains(activity));
    }

    @Test
    public void testSetVisibility_visibleToInvisible() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true).build();
        // By default, activity is visible.
        assertTrue(activity.isVisible());
        assertTrue(activity.mVisibleRequested);
        assertTrue(activity.mDisplayContent.mOpeningApps.contains(activity));
        assertFalse(activity.mDisplayContent.mClosingApps.contains(activity));

        // Request the activity to be invisible. Since the visibility changes, app transition
        // animation should be applied on this activity.
        activity.setVisibility(false);
        assertTrue(activity.isVisible());
        assertFalse(activity.mVisibleRequested);
        assertFalse(activity.mDisplayContent.mOpeningApps.contains(activity));
        assertTrue(activity.mDisplayContent.mClosingApps.contains(activity));
    }

    @Test
    public void testSetVisibility_invisibleToVisible() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true).setVisible(false).build();
        // Activiby is invisible. However ATMS requests it to become visible, since this is a top
        // activity.
        assertFalse(activity.isVisible());
        assertTrue(activity.mVisibleRequested);
        assertTrue(activity.mDisplayContent.mOpeningApps.contains(activity));
        assertFalse(activity.mDisplayContent.mClosingApps.contains(activity));

        // Request the activity to be visible. Since the visibility changes, app transition
        // animation should be applied on this activity.
        activity.setVisibility(true);
        assertFalse(activity.isVisible());
        assertTrue(activity.mVisibleRequested);
        assertTrue(activity.mDisplayContent.mOpeningApps.contains(activity));
        assertFalse(activity.mDisplayContent.mClosingApps.contains(activity));
    }

    @Test
    public void testSetVisibility_invisibleToInvisible() {
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true).setVisible(false).build();
        // Activiby is invisible. However ATMS requests it to become visible, since this is a top
        // activity.
        assertFalse(activity.isVisible());
        assertTrue(activity.mVisibleRequested);
        assertTrue(activity.mDisplayContent.mOpeningApps.contains(activity));
        assertFalse(activity.mDisplayContent.mClosingApps.contains(activity));

        // Request the activity to be invisible. Since the activity is already invisible, no app
        // transition should be applied on this activity.
        activity.setVisibility(false);
        assertFalse(activity.isVisible());
        assertFalse(activity.mVisibleRequested);
        assertFalse(activity.mDisplayContent.mOpeningApps.contains(activity));
        assertFalse(activity.mDisplayContent.mClosingApps.contains(activity));
    }

    private void assertHasStartingWindow(ActivityRecord atoken) {
        assertNotNull(atoken.mStartingSurface);
        assertNotNull(atoken.mStartingData);
        assertNotNull(atoken.mStartingWindow);
    }

    private void assertNoStartingWindow(ActivityRecord atoken) {
        assertNull(atoken.mStartingSurface);
        assertNull(atoken.mStartingWindow);
        assertNull(atoken.mStartingData);
        atoken.forAllWindows(windowState -> {
            assertFalse(windowState.getBaseType() == TYPE_APPLICATION_STARTING);
        }, true);
    }
}
