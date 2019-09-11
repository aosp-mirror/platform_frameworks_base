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

import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.os.Process.NOBODY_UID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.atLeast;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.ActivityRecord.FINISH_RESULT_CANCELLED;
import static com.android.server.wm.ActivityRecord.FINISH_RESULT_REMOVED;
import static com.android.server.wm.ActivityRecord.FINISH_RESULT_REQUESTED;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYED;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYING;
import static com.android.server.wm.ActivityStack.ActivityState.FINISHING;
import static com.android.server.wm.ActivityStack.ActivityState.INITIALIZING;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STARTED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_MOVING;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_INVISIBLE;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.PauseActivityItem;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.util.MergedConfiguration;
import android.util.MutableBoolean;
import android.view.DisplayInfo;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner.Stub;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;

import androidx.test.filters.MediumTest;

import com.android.internal.R;
import com.android.server.wm.ActivityStack.ActivityState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;

import java.util.concurrent.TimeUnit;

/**
 * Tests for the {@link ActivityRecord} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityRecordTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityRecordTests extends ActivityTestsBase {
    private ActivityStack mStack;
    private TaskRecord mTask;
    private ActivityRecord mActivity;

    @Before
    public void setUp() throws Exception {
        mStack = new StackBuilder(mRootActivityContainer).build();
        mTask = mStack.getChildAt(0);
        mActivity = mTask.getTopActivity();

        doReturn(false).when(mService).isBooting();
        doReturn(true).when(mService).isBooted();
    }

    @Test
    public void testStackCleanupOnClearingTask() {
        mActivity.setTask(null);
        verify(mStack, times(1)).onActivityRemovedFromStack(any());
    }

    @Test
    public void testStackCleanupOnActivityRemoval() {
        mTask.removeActivity(mActivity);
        verify(mStack, times(1)).onActivityRemovedFromStack(any());
    }

    @Test
    public void testStackCleanupOnTaskRemoval() {
        mStack.removeTask(mTask, null /*reason*/, REMOVE_TASK_MODE_MOVING);
        // Stack should be gone on task removal.
        assertNull(mService.mRootActivityContainer.getStack(mStack.mStackId));
    }

    @Test
    public void testNoCleanupMovingActivityInSameStack() {
        final TaskRecord newTask = new TaskBuilder(mService.mStackSupervisor).setStack(mStack)
                .build();
        mActivity.reparent(newTask, 0, null /*reason*/);
        verify(mStack, times(0)).onActivityRemovedFromStack(any());
    }

    @Test
    public void testPausingWhenVisibleFromStopped() throws Exception {
        final MutableBoolean pauseFound = new MutableBoolean(false);
        doAnswer((InvocationOnMock invocationOnMock) -> {
            final ClientTransaction transaction = invocationOnMock.getArgument(0);
            if (transaction.getLifecycleStateRequest() instanceof PauseActivityItem) {
                pauseFound.value = true;
            }
            return null;
        }).when(mActivity.app.getThread()).scheduleTransaction(any());

        mActivity.setState(STOPPED, "testPausingWhenVisibleFromStopped");

        // The activity is in the focused stack so it should be resumed.
        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(mActivity.isState(RESUMED));
        assertFalse(pauseFound.value);

        // Make the activity non focusable
        mActivity.setState(STOPPED, "testPausingWhenVisibleFromStopped");
        doReturn(false).when(mActivity).isFocusable();

        // If the activity is not focusable, it should move to paused.
        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(mActivity.isState(PAUSING));
        assertTrue(pauseFound.value);

        // Make sure that the state does not change for current non-stopping states.
        mActivity.setState(INITIALIZING, "testPausingWhenVisibleFromStopped");
        doReturn(true).when(mActivity).isFocusable();

        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);

        assertTrue(mActivity.isState(INITIALIZING));

        // Make sure the state does not change if we are not the current top activity.
        mActivity.setState(STOPPED, "testPausingWhenVisibleFromStopped behind");

        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        mStack.mTranslucentActivityWaiting = topActivity;
        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(mActivity.isState(STARTED));

        mStack.mTranslucentActivityWaiting = null;
        topActivity.setOccludesParent(false);
        mActivity.setState(STOPPED, "testPausingWhenVisibleFromStopped behind non-opaque");
        mActivity.makeVisibleIfNeeded(null /* starting */, true /* reportToClient */);
        assertTrue(mActivity.isState(STARTED));
    }

    private void ensureActivityConfiguration() {
        mActivity.ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
    }

    @Test
    public void testCanBeLaunchedOnDisplay() {
        mService.mSupportsMultiWindow = true;
        final ActivityRecord activity = new ActivityBuilder(mService).build();

        // An activity can be launched on default display.
        assertTrue(activity.canBeLaunchedOnDisplay(DEFAULT_DISPLAY));
        // An activity cannot be launched on a non-existent display.
        assertFalse(activity.canBeLaunchedOnDisplay(DEFAULT_DISPLAY + 1));
    }

    @Test
    public void testRestartProcessIfVisible() {
        doNothing().when(mSupervisor).scheduleRestartTimeout(mActivity);
        mActivity.visible = true;
        mActivity.setSavedState(null /* savedState */);
        mActivity.setState(ActivityStack.ActivityState.RESUMED, "testRestart");
        prepareFixedAspectRatioUnresizableActivity();

        final Rect originalOverrideBounds = new Rect(mActivity.getBounds());
        setupDisplayAndParentSize(600, 1200);
        // The visible activity should recompute configuration according to the last parent bounds.
        mService.restartActivityProcessIfVisible(mActivity.appToken);

        assertEquals(ActivityStack.ActivityState.RESTARTING_PROCESS, mActivity.getState());
        assertNotEquals(originalOverrideBounds, mActivity.getBounds());
    }

    @Test
    public void testsApplyOptionsLocked() {
        ActivityOptions activityOptions = ActivityOptions.makeBasic();

        // Set and apply options for ActivityRecord. Pending options should be cleared
        mActivity.updateOptionsLocked(activityOptions);
        mActivity.applyOptionsLocked();
        assertNull(mActivity.pendingOptions);

        // Set options for two ActivityRecords in same Task. Apply one ActivityRecord options.
        // Pending options should be cleared for both ActivityRecords
        ActivityRecord activity2 = new ActivityBuilder(mService).setTask(mTask).build();
        activity2.updateOptionsLocked(activityOptions);
        mActivity.updateOptionsLocked(activityOptions);
        mActivity.applyOptionsLocked();
        assertNull(mActivity.pendingOptions);
        assertNull(activity2.pendingOptions);

        // Set options for two ActivityRecords in separate Tasks. Apply one ActivityRecord options.
        // Pending options should be cleared for only ActivityRecord that was applied
        TaskRecord task2 = new TaskBuilder(mService.mStackSupervisor).setStack(mStack).build();
        activity2 = new ActivityBuilder(mService).setTask(task2).build();
        activity2.updateOptionsLocked(activityOptions);
        mActivity.updateOptionsLocked(activityOptions);
        mActivity.applyOptionsLocked();
        assertNull(mActivity.pendingOptions);
        assertNotNull(activity2.pendingOptions);
    }

    @Test
    public void testNewOverrideConfigurationIncrementsSeq() {
        final Configuration newConfig = new Configuration();

        final int prevSeq = mActivity.getMergedOverrideConfiguration().seq;
        mActivity.onRequestedOverrideConfigurationChanged(newConfig);
        assertEquals(prevSeq + 1, mActivity.getMergedOverrideConfiguration().seq);
    }

    @Test
    public void testNewParentConfigurationIncrementsSeq() {
        final Configuration newConfig = new Configuration(
                mTask.getRequestedOverrideConfiguration());
        newConfig.orientation = newConfig.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;

        final int prevSeq = mActivity.getMergedOverrideConfiguration().seq;
        mTask.onRequestedOverrideConfigurationChanged(newConfig);
        assertEquals(prevSeq + 1, mActivity.getMergedOverrideConfiguration().seq);
    }

    @Test
    public void testNotifiesSeqIncrementToAppToken() {
        final Configuration appWindowTokenRequestedOrientation = mock(Configuration.class);
        mActivity.mAppWindowToken = mock(AppWindowToken.class);
        doReturn(appWindowTokenRequestedOrientation).when(mActivity.mAppWindowToken)
                .getRequestedOverrideConfiguration();

        final Configuration newConfig = new Configuration();
        newConfig.orientation = ORIENTATION_PORTRAIT;

        final int prevSeq = mActivity.getMergedOverrideConfiguration().seq;
        mActivity.onRequestedOverrideConfigurationChanged(newConfig);
        assertEquals(prevSeq + 1, appWindowTokenRequestedOrientation.seq);
        verify(mActivity.mAppWindowToken).onMergedOverrideConfigurationChanged();
    }

    @Test
    public void testSetsRelaunchReason_NotDragResizing() {
        mActivity.setState(ActivityStack.ActivityState.RESUMED, "Testing");

        mTask.onRequestedOverrideConfigurationChanged(mTask.getConfiguration());
        mActivity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                mActivity.getConfiguration()));

        mActivity.info.configChanges &= ~CONFIG_ORIENTATION;
        final Configuration newConfig = new Configuration(mTask.getConfiguration());
        newConfig.orientation = newConfig.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE
                : ORIENTATION_PORTRAIT;
        mTask.onRequestedOverrideConfigurationChanged(newConfig);

        mActivity.mRelaunchReason = ActivityTaskManagerService.RELAUNCH_REASON_NONE;

        ensureActivityConfiguration();

        assertEquals(ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE,
                mActivity.mRelaunchReason);
    }

    @Test
    public void testSetsRelaunchReason_DragResizing() {
        mActivity.setState(ActivityStack.ActivityState.RESUMED, "Testing");

        mTask.onRequestedOverrideConfigurationChanged(mTask.getConfiguration());
        mActivity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                mActivity.getConfiguration()));

        mActivity.info.configChanges &= ~CONFIG_ORIENTATION;
        final Configuration newConfig = new Configuration(mTask.getConfiguration());
        newConfig.orientation = newConfig.orientation == ORIENTATION_PORTRAIT
                ? ORIENTATION_LANDSCAPE
                : ORIENTATION_PORTRAIT;
        mTask.onRequestedOverrideConfigurationChanged(newConfig);

        doReturn(true).when(mTask.mTask).isDragResizing();

        mActivity.mRelaunchReason = ActivityTaskManagerService.RELAUNCH_REASON_NONE;

        ensureActivityConfiguration();

        assertEquals(ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE,
                mActivity.mRelaunchReason);
    }

    @Test
    public void testSetsRelaunchReason_NonResizeConfigChanges() {
        mActivity.setState(ActivityStack.ActivityState.RESUMED, "Testing");

        mTask.onRequestedOverrideConfigurationChanged(mTask.getConfiguration());
        mActivity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                mActivity.getConfiguration()));

        mActivity.info.configChanges &= ~ActivityInfo.CONFIG_FONT_SCALE;
        final Configuration newConfig = new Configuration(mTask.getConfiguration());
        newConfig.fontScale = 5;
        mTask.onRequestedOverrideConfigurationChanged(newConfig);

        mActivity.mRelaunchReason =
                ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;

        ensureActivityConfiguration();

        assertEquals(ActivityTaskManagerService.RELAUNCH_REASON_NONE,
                mActivity.mRelaunchReason);
    }

    @Test
    public void testSetRequestedOrientationUpdatesConfiguration() throws Exception {
        mActivity = new ActivityBuilder(mService)
                .setTask(mTask)
                .setConfigChanges(CONFIG_ORIENTATION | CONFIG_SCREEN_LAYOUT)
                .build();
        mActivity.setState(ActivityStack.ActivityState.RESUMED, "Testing");

        mActivity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                mActivity.getConfiguration()));

        final Configuration newConfig = new Configuration(mActivity.getConfiguration());
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
        final DisplayContent dc = mTask.mTask.getDisplayContent();
        doReturn(false).when(dc).onDescendantOrientationChanged(any(), any());
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
        mActivity.setRequestedOrientation(requestedOrientation);

        final ActivityConfigurationChangeItem expected =
                ActivityConfigurationChangeItem.obtain(newConfig);
        verify(mService.getLifecycleManager()).scheduleTransaction(eq(mActivity.app.getThread()),
                eq(mActivity.appToken), eq(expected));
    }

    @Test
    public void testShouldMakeActive_deferredResume() {
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");

        mSupervisor.beginDeferResume();
        assertEquals(false, mActivity.shouldMakeActive(null /* activeActivity */));

        mSupervisor.endDeferResume();
        assertEquals(true, mActivity.shouldMakeActive(null /* activeActivity */));
    }

    @Test
    public void testShouldResume_stackVisibility() {
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");
        spyOn(mStack);

        doReturn(STACK_VISIBILITY_VISIBLE).when(mStack).getVisibility(null);
        assertEquals(true, mActivity.shouldResumeActivity(null /* activeActivity */));

        doReturn(STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT).when(mStack).getVisibility(null);
        assertEquals(false, mActivity.shouldResumeActivity(null /* activeActivity */));

        doReturn(STACK_VISIBILITY_INVISIBLE).when(mStack).getVisibility(null);
        assertEquals(false, mActivity.shouldResumeActivity(null /* activeActivity */));
    }

    @Test
    public void testPushConfigurationWhenLaunchTaskBehind() throws Exception {
        mActivity = new ActivityBuilder(mService)
                .setTask(mTask)
                .setLaunchTaskBehind(true)
                .setConfigChanges(CONFIG_ORIENTATION)
                .build();
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");

        final ActivityStack stack = new StackBuilder(mRootActivityContainer).build();
        try {
            doReturn(false).when(stack).isStackTranslucent(any());
            assertFalse(mStack.shouldBeVisible(null /* starting */));

            mActivity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                    mActivity.getConfiguration()));

            final Configuration newConfig = new Configuration(mActivity.getConfiguration());
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

            mTask.onConfigurationChanged(newConfig);

            mActivity.ensureActivityConfiguration(0 /* globalChanges */,
                    false /* preserveWindow */, true /* ignoreStopState */);

            final ActivityConfigurationChangeItem expected =
                    ActivityConfigurationChangeItem.obtain(newConfig);
            verify(mService.getLifecycleManager()).scheduleTransaction(
                    eq(mActivity.app.getThread()), eq(mActivity.appToken), eq(expected));
        } finally {
            stack.getDisplay().removeChild(stack);
        }
    }

    @Test
    public void testShouldPauseWhenMakeClientVisible() {
        ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.setOccludesParent(false);
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");
        mActivity.makeClientVisible();
        assertEquals(STARTED, mActivity.getState());
    }

    @Test
    public void testSizeCompatMode_FixedAspectRatioBoundsWithDecor() {
        setupDisplayContentForCompatDisplayInsets();
        final int decorHeight = 200; // e.g. The device has cutout.
        final DisplayPolicy policy = setupDisplayAndParentSize(600, 800).getDisplayPolicy();
        spyOn(policy);
        doAnswer(invocationOnMock -> {
            final int rotation = invocationOnMock.<Integer>getArgument(0);
            final Rect insets = invocationOnMock.<Rect>getArgument(4);
            if (rotation == ROTATION_0) {
                insets.top = decorHeight;
            } else if (rotation == ROTATION_90) {
                insets.left = decorHeight;
            }
            return null;
        }).when(policy).getNonDecorInsetsLw(anyInt() /* rotation */, anyInt() /* width */,
                anyInt() /* height */, any() /* displayCutout */, any() /* outInsets */);

        doReturn(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                .when(mActivity.mAppWindowToken).getOrientationIgnoreVisibility();
        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        mActivity.info.minAspectRatio = mActivity.info.maxAspectRatio = 1;
        ensureActivityConfiguration();
        // The parent configuration doesn't change since the first resolved configuration, so the
        // activity shouldn't be in the size compatibility mode.
        assertFalse(mActivity.inSizeCompatMode());

        final Rect appBounds = mActivity.getWindowConfiguration().getAppBounds();
        // Ensure the app bounds keep the declared aspect ratio.
        assertEquals(appBounds.width(), appBounds.height());
        // The decor height should be a part of the effective bounds.
        assertEquals(mActivity.getBounds().height(), appBounds.height() + decorHeight);

        mTask.getConfiguration().windowConfiguration.setRotation(ROTATION_90);
        mActivity.onConfigurationChanged(mTask.getConfiguration());
        // After changing orientation, the aspect ratio should be the same.
        assertEquals(appBounds.width(), appBounds.height());
        // The decor height will be included in width.
        assertEquals(mActivity.getBounds().width(), appBounds.width() + decorHeight);
    }

    @Test
    public void testSizeCompatMode_FixedScreenConfigurationWhenMovingToDisplay() {
        // Initialize different bounds on a new display.
        final Rect newDisplayBounds = new Rect(0, 0, 1000, 2000);
        DisplayInfo info = new DisplayInfo();
        mService.mContext.getDisplay().getDisplayInfo(info);
        info.logicalWidth = newDisplayBounds.width();
        info.logicalHeight = newDisplayBounds.height();
        info.logicalDensityDpi = 300;

        final ActivityDisplay newDisplay =
                addNewActivityDisplayAt(info, ActivityDisplay.POSITION_TOP);

        final Configuration c =
                new Configuration(mStack.getDisplay().getRequestedOverrideConfiguration());
        c.densityDpi = 200;
        mStack.getDisplay().onRequestedOverrideConfigurationChanged(c);
        mActivity = new ActivityBuilder(mService)
                .setTask(mTask)
                .setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                .setMaxAspectRatio(1.5f)
                .build();
        mActivity.visible = true;

        final Rect originalBounds = new Rect(mActivity.getBounds());
        final int originalDpi = mActivity.getConfiguration().densityDpi;

        // Move the non-resizable activity to the new display.
        mStack.reparent(newDisplay, true /* onTop */, false /* displayRemoved */);

        assertEquals(originalBounds, mActivity.getBounds());
        assertEquals(originalDpi, mActivity.getConfiguration().densityDpi);
        assertTrue(mActivity.inSizeCompatMode());
    }

    @Test
    public void testSizeCompatMode_FixedScreenBoundsWhenDisplaySizeChanged() {
        setupDisplayContentForCompatDisplayInsets();
        when(mActivity.mAppWindowToken.getOrientationIgnoreVisibility()).thenReturn(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        mTask.getWindowConfiguration().setAppBounds(mStack.getDisplay().getBounds());
        mTask.getConfiguration().orientation = ORIENTATION_PORTRAIT;
        mActivity.info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        ensureActivityConfiguration();
        final Rect originalBounds = new Rect(mActivity.getBounds());

        // Change the size of current display.
        setupDisplayAndParentSize(1000, 2000);
        ensureActivityConfiguration();

        assertEquals(originalBounds, mActivity.getBounds());
        assertTrue(mActivity.inSizeCompatMode());
    }

    @Test
    public void testSizeCompatMode_FixedScreenLayoutSizeBits() {
        final int fixedScreenLayout = Configuration.SCREENLAYOUT_LONG_NO
                | Configuration.SCREENLAYOUT_SIZE_NORMAL;
        mTask.getRequestedOverrideConfiguration().screenLayout = fixedScreenLayout
                | Configuration.SCREENLAYOUT_LAYOUTDIR_LTR;
        prepareFixedAspectRatioUnresizableActivity();

        // The initial configuration should inherit from parent.
        assertEquals(mTask.getConfiguration().screenLayout,
                mActivity.getConfiguration().screenLayout);

        mTask.getConfiguration().screenLayout = Configuration.SCREENLAYOUT_LAYOUTDIR_RTL
                | Configuration.SCREENLAYOUT_LONG_YES | Configuration.SCREENLAYOUT_SIZE_LARGE;
        mActivity.onConfigurationChanged(mTask.getConfiguration());

        // The size and aspect ratio bits don't change, but the layout direction should be updated.
        assertEquals(fixedScreenLayout | Configuration.SCREENLAYOUT_LAYOUTDIR_RTL,
                mActivity.getConfiguration().screenLayout);
    }

    @Test
    public void testSizeCompatMode_ResetNonVisibleActivity() {
        final ActivityDisplay display = mStack.getDisplay();
        spyOn(display);

        prepareFixedAspectRatioUnresizableActivity();
        mActivity.setState(STOPPED, "testSizeCompatMode");
        mActivity.visible = false;
        mActivity.app.setReportedProcState(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);
        // Make the parent bounds to be different so the activity is in size compatibility mode.
        mTask.getWindowConfiguration().setAppBounds(new Rect(0, 0, 600, 1200));

        // Simulate the display changes orientation.
        doReturn(ActivityInfo.CONFIG_SCREEN_SIZE | CONFIG_ORIENTATION
                | ActivityInfo.CONFIG_WINDOW_CONFIGURATION)
                        .when(display).getLastOverrideConfigurationChanges();
        mActivity.onConfigurationChanged(mTask.getConfiguration());
        // The override configuration should not change so it is still in size compatibility mode.
        assertTrue(mActivity.inSizeCompatMode());

        // Simulate the display changes density.
        doReturn(ActivityInfo.CONFIG_DENSITY).when(display).getLastOverrideConfigurationChanges();
        mService.mAmInternal = mock(ActivityManagerInternal.class);
        mActivity.onConfigurationChanged(mTask.getConfiguration());
        // The override configuration should be reset and the activity's process will be killed.
        assertFalse(mActivity.inSizeCompatMode());
        verify(mActivity).restartProcessIfVisible();
        mLockRule.runWithScissors(mService.mH, () -> { }, TimeUnit.SECONDS.toMillis(3));
        verify(mService.mAmInternal).killProcess(
                eq(mActivity.app.mName), eq(mActivity.app.mUid), anyString());
    }

    @Test
    public void testTakeOptions() {
        ActivityOptions opts = ActivityOptions.makeRemoteAnimation(
                new RemoteAnimationAdapter(new Stub() {

                    @Override
                    public void onAnimationStart(RemoteAnimationTarget[] apps,
                            IRemoteAnimationFinishedCallback finishedCallback) {

                    }

                    @Override
                    public void onAnimationCancelled() {

                    }
                }, 0, 0));
        mActivity.updateOptionsLocked(opts);
        assertNotNull(mActivity.takeOptionsLocked(true /* fromClient */));
        assertNotNull(mActivity.pendingOptions);

        mActivity.updateOptionsLocked(ActivityOptions.makeBasic());
        assertNotNull(mActivity.takeOptionsLocked(false /* fromClient */));
        assertNull(mActivity.pendingOptions);
    }

    @Test
    public void testCanLaunchHomeActivityFromChooser() {
        ComponentName chooserComponent = ComponentName.unflattenFromString(
                Resources.getSystem().getString(R.string.config_chooserActivity));
        ActivityRecord chooserActivity = new ActivityBuilder(mService).setComponent(
                chooserComponent).build();
        assertThat(mActivity.canLaunchHomeActivity(NOBODY_UID, chooserActivity)).isTrue();
    }

    /**
     * Verify that an {@link ActivityRecord} reports that it has saved state after creation, and
     * that it is cleared after activity is resumed.
     */
    @Test
    public void testHasSavedState() {
        assertTrue(mActivity.hasSavedState());

        ActivityRecord.activityResumedLocked(mActivity.appToken);
        assertFalse(mActivity.hasSavedState());
        assertNull(mActivity.getSavedState());
    }

    /** Verify the behavior of {@link ActivityRecord#setSavedState(Bundle)}. */
    @Test
    public void testUpdateSavedState() {
        mActivity.setSavedState(null /* savedState */);
        assertFalse(mActivity.hasSavedState());
        assertNull(mActivity.getSavedState());

        final Bundle savedState = new Bundle();
        savedState.putString("test", "string");
        mActivity.setSavedState(savedState);
        assertTrue(mActivity.hasSavedState());
        assertEquals(savedState, mActivity.getSavedState());
    }

    /** Verify the correct updates of saved state when activity client reports stop. */
    @Test
    public void testUpdateSavedState_activityStopped() {
        final Bundle savedState = new Bundle();
        savedState.putString("test", "string");
        final PersistableBundle persistentSavedState = new PersistableBundle();
        persistentSavedState.putString("persist", "string");

        // Set state to STOPPING, or ActivityRecord#activityStoppedLocked() call will be ignored.
        mActivity.setState(STOPPING, "test");
        mActivity.activityStoppedLocked(savedState, persistentSavedState, "desc");
        assertTrue(mActivity.hasSavedState());
        assertEquals(savedState, mActivity.getSavedState());
        assertEquals(persistentSavedState, mActivity.getPersistentSavedState());

        // Sending 'null' for saved state can only happen due to timeout, so previously stored saved
        // states should not be overridden.
        mActivity.setState(STOPPING, "test");
        mActivity.activityStoppedLocked(null /* savedState */, null /* persistentSavedState */,
                "desc");
        assertTrue(mActivity.hasSavedState());
        assertEquals(savedState, mActivity.getSavedState());
        assertEquals(persistentSavedState, mActivity.getPersistentSavedState());
    }

    /**
     * Verify that activity finish request is not performed if activity is finishing or is in
     * incorrect state.
     */
    @Test
    public void testFinishActivityIfPossible_cancelled() {
        // Mark activity as finishing
        mActivity.finishing = true;
        assertEquals("Duplicate finish request must be ignored", FINISH_RESULT_CANCELLED,
                mActivity.finishIfPossible("test", false /* oomAdj */));
        assertTrue(mActivity.finishing);
        assertTrue(mActivity.isInStackLocked());

        // Remove activity from task
        mActivity.finishing = false;
        mActivity.setTask(null);
        assertEquals("Activity outside of task/stack cannot be finished", FINISH_RESULT_CANCELLED,
                mActivity.finishIfPossible("test", false /* oomAdj */));
        assertFalse(mActivity.finishing);
    }

    /**
     * Verify that activity finish request is placed, but not executed immediately if activity is
     * not ready yet.
     */
    @Test
    public void testFinishActivityIfPossible_requested() {
        mActivity.finishing = false;
        assertEquals("Currently resumed activity must be prepared removal", FINISH_RESULT_REQUESTED,
                mActivity.finishIfPossible("test", false /* oomAdj */));
        assertTrue(mActivity.finishing);
        assertTrue(mActivity.isInStackLocked());

        // First request to finish activity must schedule a "destroy" request to the client.
        // Activity must be removed from history after the client reports back or after timeout.
        mActivity.finishing = false;
        mActivity.setState(STOPPED, "test");
        assertEquals("Activity outside of task/stack cannot be finished", FINISH_RESULT_REQUESTED,
                mActivity.finishIfPossible("test", false /* oomAdj */));
        assertTrue(mActivity.finishing);
        assertTrue(mActivity.isInStackLocked());
    }

    /**
     * Verify that activity finish request removes activity immediately if it's ready.
     */
    @Test
    public void testFinishActivityIfPossible_removed() {
        // Prepare the activity record to be ready for immediate removal. It should be invisible and
        // have no process. Otherwise, request to finish it will send a message to client first.
        mActivity.setState(STOPPED, "test");
        mActivity.visible = false;
        mActivity.nowVisible = false;
        // Set process to 'null' to allow immediate removal, but don't call mActivity.setProcess() -
        // this will cause NPE when updating task's process.
        mActivity.app = null;
        assertEquals("Activity outside of task/stack cannot be finished", FINISH_RESULT_REMOVED,
                mActivity.finishIfPossible("test", false /* oomAdj */));
        assertTrue(mActivity.finishing);
        assertFalse(mActivity.isInStackLocked());
    }

    /**
     * Verify that when finishing the top focused activity on top display, the stack order will be
     * changed by adjusting focus.
     */
    @Test
    public void testFinishActivityIfPossible_adjustStackOrder() {
        // Prepare the stacks with order (top to bottom): mStack, stack1, stack2.
        final ActivityStack stack1 = new StackBuilder(mRootActivityContainer).build();
        mStack.moveToFront("test");
        // The stack2 is needed here for moving back to simulate the
        // {@link ActivityDisplay#mPreferredTopFocusableStack} is cleared, so
        // {@link ActivityDisplay#getFocusedStack} will rely on the order of focusable-and-visible
        // stacks. Then when mActivity is finishing, its stack will be invisible (no running
        // activities in the stack) that is the key condition to verify.
        final ActivityStack stack2 = new StackBuilder(mRootActivityContainer).build();
        stack2.moveToBack("test", stack2.getChildAt(0));

        assertTrue(mStack.isTopStackOnDisplay());

        mActivity.setState(RESUMED, "test");
        mActivity.finishIfPossible(0 /* resultCode */, null /* resultData */, "test",
                false /* oomAdj */);

        assertTrue(stack1.isTopStackOnDisplay());
    }

    /**
     * Verify that resumed activity is paused due to finish request.
     */
    @Test
    public void testFinishActivityIfPossible_resumedStartsPausing() {
        mActivity.finishing = false;
        mActivity.setState(RESUMED, "test");
        assertEquals("Currently resumed activity must be paused before removal",
                FINISH_RESULT_REQUESTED, mActivity.finishIfPossible("test", false /* oomAdj */));
        assertEquals(PAUSING, mActivity.getState());
        verify(mActivity).setVisibility(eq(false));
        verify(mActivity.getDisplay().mDisplayContent)
                .prepareAppTransition(eq(TRANSIT_TASK_CLOSE), eq(false) /* alwaysKeepCurrent */);
    }

    /**
     * Verify that finish request will be completed immediately for non-resumed activity.
     */
    @Test
    public void testFinishActivityIfPossible_nonResumedFinishCompletesImmediately() {
        final ActivityState[] states = {INITIALIZING, STARTED, PAUSED, STOPPING, STOPPED};
        for (ActivityState state : states) {
            mActivity.finishing = false;
            mActivity.setState(state, "test");
            reset(mActivity);
            assertEquals("Finish must be requested", FINISH_RESULT_REQUESTED,
                    mActivity.finishIfPossible("test", false /* oomAdj */));
            verify(mActivity).completeFinishing(anyString());
        }
    }

    /**
     * Verify that finishing will not be completed in PAUSING state.
     */
    @Test
    public void testFinishActivityIfPossible_pausing() {
        mActivity.finishing = false;
        mActivity.setState(PAUSING, "test");
        assertEquals("Finish must be requested", FINISH_RESULT_REQUESTED,
                mActivity.finishIfPossible("test", false /* oomAdj */));
        verify(mActivity, never()).completeFinishing(anyString());
    }

    /**
     * Verify that finish request for resumed activity will prepare an app transition but not
     * execute it immediately.
     */
    @Test
    public void testFinishActivityIfPossible_visibleResumedPreparesAppTransition() {
        mActivity.finishing = false;
        mActivity.visible = true;
        mActivity.setState(RESUMED, "test");
        mActivity.finishIfPossible("test", false /* oomAdj */);

        verify(mActivity).setVisibility(eq(false));
        verify(mActivity.getDisplay().mDisplayContent)
                .prepareAppTransition(eq(TRANSIT_TASK_CLOSE), eq(false) /* alwaysKeepCurrent */);
        verify(mActivity.getDisplay().mDisplayContent, never()).executeAppTransition();
    }

    /**
     * Verify that finish request for paused activity will prepare and execute an app transition.
     */
    @Test
    public void testFinishActivityIfPossible_visibleNotResumedExecutesAppTransition() {
        mActivity.finishing = false;
        mActivity.visible = true;
        mActivity.setState(PAUSED, "test");
        mActivity.finishIfPossible("test", false /* oomAdj */);

        verify(mActivity, atLeast(1)).setVisibility(eq(false));
        verify(mActivity.getDisplay().mDisplayContent)
                .prepareAppTransition(eq(TRANSIT_TASK_CLOSE), eq(false) /* alwaysKeepCurrent */);
        verify(mActivity.getDisplay().mDisplayContent).executeAppTransition();
    }

    /**
     * Verify that finish request for non-visible activity will not prepare any transitions.
     */
    @Test
    public void testFinishActivityIfPossible_nonVisibleNoAppTransition() {
        // Put an activity on top of test activity to make it invisible and prevent us from
        // accidentally resuming the topmost one again.
        new ActivityBuilder(mService).build();
        mActivity.visible = false;
        mActivity.setState(STOPPED, "test");

        mActivity.finishIfPossible("test", false /* oomAdj */);

        verify(mActivity.getDisplay().mDisplayContent, never())
                .prepareAppTransition(eq(TRANSIT_TASK_CLOSE), eq(false) /* alwaysKeepCurrent */);
    }

    /**
     * Verify that complete finish request for non-finishing activity is invalid.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCompleteFinishing_failNotFinishing() {
        mActivity.finishing = false;
        mActivity.completeFinishing("test");
    }

    /**
     * Verify that complete finish request for resumed activity is invalid.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCompleteFinishing_failResumed() {
        mActivity.setState(RESUMED, "test");
        mActivity.completeFinishing("test");
    }

    /**
     * Verify that finish request for pausing activity must be a no-op - activity will finish
     * once it completes pausing.
     */
    @Test
    public void testCompleteFinishing_pausing() {
        mActivity.setState(PAUSING, "test");
        mActivity.finishing = true;

        assertEquals("Activity must not be removed immediately - waiting for paused",
                mActivity, mActivity.completeFinishing("test"));
        assertEquals(PAUSING, mActivity.getState());
        verify(mActivity, never()).destroyIfPossible(anyString());
    }

    /**
     * Verify that complete finish request for visible activity must be delayed before the next one
     * becomes visible.
     */
    @Test
    public void testCompleteFinishing_waitForNextVisible() {
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.visible = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as not visible, so that we will wait for it before removing
        // the top one.
        mActivity.visible = false;
        mActivity.nowVisible = false;
        mActivity.setState(STOPPED, "test");

        assertEquals("Activity must not be removed immediately - waiting for next visible",
                topActivity, topActivity.completeFinishing("test"));
        assertEquals("Activity must be stopped to make next one visible", STOPPING,
                topActivity.getState());
        assertTrue("Activity must be stopped to make next one visible",
                topActivity.mStackSupervisor.mStoppingActivities.contains(topActivity));
        verify(topActivity, never()).destroyIfPossible(anyString());
    }

    /**
     * Verify that complete finish request for invisible activity must not be delayed.
     */
    @Test
    public void testCompleteFinishing_noWaitForNextVisible_alreadyInvisible() {
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.visible = false;
        topActivity.nowVisible = false;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as not visible, so that we would wait for it before removing
        // the top one.
        mActivity.visible = false;
        mActivity.nowVisible = false;
        mActivity.setState(STOPPED, "test");

        topActivity.completeFinishing("test");

        verify(topActivity).destroyIfPossible(anyString());
    }

    /**
     * Verify that paused finishing activity will be added to finishing list and wait for next one
     * to idle.
     */
    @Test
    public void testCompleteFinishing_waitForIdle() {
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.visible = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        mActivity.visible = true;
        mActivity.nowVisible = true;
        mActivity.setState(RESUMED, "test");

        topActivity.completeFinishing("test");

        verify(topActivity).addToFinishingAndWaitForIdle();
    }

    /**
     * Verify that complete finish request for visible activity must not be delayed if the next one
     * is already visible and it's not the focused stack.
     */
    @Test
    public void testCompleteFinishing_noWaitForNextVisible_stopped() {
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.visible = false;
        topActivity.nowVisible = false;
        topActivity.finishing = true;
        topActivity.setState(STOPPED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        mActivity.visible = true;
        mActivity.nowVisible = true;
        mActivity.setState(RESUMED, "test");

        topActivity.completeFinishing("test");

        verify(topActivity).destroyIfPossible(anyString());
    }

    /**
     * Verify that complete finish request for visible activity must not be delayed if the next one
     * is already visible and it's not the focused stack.
     */
    @Test
    public void testCompleteFinishing_noWaitForNextVisible_nonFocusedStack() {
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.visible = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        mActivity.visible = true;
        mActivity.nowVisible = true;
        mActivity.setState(RESUMED, "test");

        // Add another stack to become focused and make the activity there visible. This way it
        // simulates finishing in non-focused stack in split-screen.
        final ActivityStack stack = new StackBuilder(mRootActivityContainer).build();
        stack.getChildAt(0).getChildAt(0).nowVisible = true;
        stack.getChildAt(0).getChildAt(0).visible = true;

        topActivity.completeFinishing("test");

        verify(topActivity).destroyIfPossible(anyString());
    }

    /**
     * Verify destroy activity request completes successfully.
     */
    @Test
    public void testDestroyIfPossible() {
        doReturn(false).when(mRootActivityContainer).resumeFocusedStacksTopActivities();
        spyOn(mStack);
        mActivity.destroyIfPossible("test");

        assertEquals(DESTROYING, mActivity.getState());
        assertTrue(mActivity.finishing);
        verify(mActivity).destroyImmediately(eq(true) /* removeFromApp */, anyString());
    }

    /**
     * Verify that complete finish request for visible activity must not destroy it immediately if
     * it is the last running activity on a display with a home stack. We must wait for home
     * activity to come up to avoid a black flash in this case.
     */
    @Test
    public void testDestroyIfPossible_lastActivityAboveEmptyHomeStack() {
        // Empty the home stack.
        final ActivityStack homeStack = mActivity.getDisplay().getHomeStack();
        for (TaskRecord t : homeStack.getAllTasks()) {
            homeStack.removeTask(t, "test", REMOVE_TASK_MODE_DESTROYING);
        }
        mActivity.finishing = true;
        doReturn(false).when(mRootActivityContainer).resumeFocusedStacksTopActivities();
        spyOn(mStack);

        // Try to destroy the last activity above the home stack.
        mActivity.destroyIfPossible("test");

        // Verify that the activity was not actually destroyed, but waits for next one to come up
        // instead.
        verify(mActivity, never()).destroyImmediately(eq(true) /* removeFromApp */, anyString());
        assertEquals(FINISHING, mActivity.getState());
        assertTrue(mActivity.mStackSupervisor.mFinishingActivities.contains(mActivity));
    }

    /**
     * Test that the activity will be moved to destroying state and the message to destroy will be
     * sent to the client.
     */
    @Test
    public void testDestroyImmediately_hadApp_finishing() {
        mActivity.finishing = true;
        mActivity.destroyImmediately(false /* removeFromApp */, "test");

        assertEquals(DESTROYING, mActivity.getState());
    }

    /**
     * Test that the activity will be moved to destroyed state immediately if it was not marked as
     * finishing before {@link ActivityRecord#destroyImmediately(boolean, String)}.
     */
    @Test
    public void testDestroyImmediately_hadApp_notFinishing() {
        mActivity.finishing = false;
        mActivity.destroyImmediately(false /* removeFromApp */, "test");

        assertEquals(DESTROYED, mActivity.getState());
    }

    /**
     * Test that an activity with no process attached and that is marked as finishing will be
     * removed from task when {@link ActivityRecord#destroyImmediately(boolean, String)} is called.
     */
    @Test
    public void testDestroyImmediately_noApp_finishing() {
        mActivity.app = null;
        mActivity.finishing = true;
        final TaskRecord task = mActivity.getTaskRecord();

        mActivity.destroyImmediately(false /* removeFromApp */, "test");

        assertEquals(DESTROYED, mActivity.getState());
        assertNull(mActivity.getTaskRecord());
        assertEquals(0, task.getChildCount());
    }

    /**
     * Test that an activity with no process attached and that is not marked as finishing will be
     * marked as DESTROYED but not removed from task.
     */
    @Test
    public void testDestroyImmediately_noApp_notFinishing() {
        mActivity.app = null;
        mActivity.finishing = false;
        final TaskRecord task = mActivity.getTaskRecord();

        mActivity.destroyImmediately(false /* removeFromApp */, "test");

        assertEquals(DESTROYED, mActivity.getState());
        assertEquals(task, mActivity.getTaskRecord());
        assertEquals(1, task.getChildCount());
    }

    /**
     * Test that an activity will not be destroyed if it is marked as non-destroyable.
     */
    @Test
    public void testSafelyDestroy_nonDestroyable() {
        doReturn(false).when(mActivity).isDestroyable();

        mActivity.safelyDestroy("test");

        verify(mActivity, never()).destroyImmediately(eq(true) /* removeFromApp */, anyString());
    }

    /**
     * Test that an activity will not be destroyed if it is marked as non-destroyable.
     */
    @Test
    public void testSafelyDestroy_destroyable() {
        doReturn(true).when(mActivity).isDestroyable();

        mActivity.safelyDestroy("test");

        verify(mActivity).destroyImmediately(eq(true) /* removeFromApp */, anyString());
    }

    @Test
    public void testRemoveFromHistory() {
        final ActivityStack stack = mActivity.getActivityStack();
        final TaskRecord task = mActivity.getTaskRecord();

        mActivity.removeFromHistory("test");

        assertEquals(DESTROYED, mActivity.getState());
        assertNull(mActivity.app);
        assertNull(mActivity.getTaskRecord());
        assertEquals(0, task.getChildCount());
        assertNull(task.getStack());
        assertEquals(0, stack.getChildCount());
    }

    /**
     * Test that it's not allowed to call {@link ActivityRecord#destroyed(String)} if activity is
     * not in destroying or destroyed state.
     */
    @Test(expected = IllegalStateException.class)
    public void testDestroyed_notDestroying() {
        mActivity.setState(STOPPED, "test");
        mActivity.destroyed("test");
    }

    /**
     * Test that {@link ActivityRecord#destroyed(String)} can be called if an activity is destroying
     */
    @Test
    public void testDestroyed_destroying() {
        mActivity.setState(DESTROYING, "test");
        mActivity.destroyed("test");

        verify(mActivity).removeFromHistory(anyString());
    }

    /**
     * Test that {@link ActivityRecord#destroyed(String)} can be called if an activity is destroyed.
     */
    @Test
    public void testDestroyed_destroyed() {
        mActivity.setState(DESTROYED, "test");
        mActivity.destroyed("test");

        verify(mActivity).removeFromHistory(anyString());
    }

    /** Setup {@link #mActivity} as a size-compat-mode-able activity without fixed orientation. */
    private void prepareFixedAspectRatioUnresizableActivity() {
        setupDisplayContentForCompatDisplayInsets();
        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        mActivity.info.maxAspectRatio = 1.5f;
        mActivity.visible = true;
        ensureActivityConfiguration();
    }

    private void setupDisplayContentForCompatDisplayInsets() {
        final Rect displayBounds = mStack.getDisplay().getBounds();
        setupDisplayAndParentSize(displayBounds.width(), displayBounds.height());
    }

    private DisplayContent setupDisplayAndParentSize(int width, int height) {
        final DisplayContent displayContent = mStack.getDisplay().mDisplayContent;
        displayContent.mBaseDisplayWidth = width;
        displayContent.mBaseDisplayHeight = height;
        final Configuration c =
                new Configuration(mStack.getDisplay().getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(new Rect(0, 0, width, height));
        c.windowConfiguration.setAppBounds(0, 0, width, height);
        c.windowConfiguration.setRotation(ROTATION_0);
        mStack.getDisplay().onRequestedOverrideConfigurationChanged(c);
        return displayContent;
    }
}
