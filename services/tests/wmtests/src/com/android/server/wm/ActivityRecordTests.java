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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.ActivityStack.ActivityState.INITIALIZING;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_MOVING;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_INVISIBLE;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.PauseActivityItem;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.MergedConfiguration;
import android.util.MutableBoolean;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner.Stub;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;

import androidx.test.filters.MediumTest;

import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Before;
import org.junit.Test;
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
public class ActivityRecordTests extends ActivityTestsBase {
    private TestActivityStack mStack;
    private TaskRecord mTask;
    private ActivityRecord mActivity;

    @Before
    public void setUp() throws Exception {
        mStack = (TestActivityStack) new StackBuilder(mRootActivityContainer).build();
        mTask = mStack.getChildAt(0);
        mActivity = mTask.getTopActivity();

        doReturn(false).when(mService).isBooting();
        doReturn(true).when(mService).isBooted();
    }

    @Test
    public void testStackCleanupOnClearingTask() {
        mActivity.setTask(null);
        assertEquals(mStack.onActivityRemovedFromStackInvocationCount(), 1);
    }

    @Test
    public void testStackCleanupOnActivityRemoval() {
        mTask.removeActivity(mActivity);
        assertEquals(mStack.onActivityRemovedFromStackInvocationCount(),  1);
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
        assertEquals(mStack.onActivityRemovedFromStackInvocationCount(), 0);
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
        assertTrue(mActivity.isState(PAUSED));
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
        mActivity.haveState = false;
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
        newConfig.orientation = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;

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
        newConfig.orientation = Configuration.ORIENTATION_PORTRAIT;

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

        mActivity.info.configChanges &= ~ActivityInfo.CONFIG_ORIENTATION;
        final Configuration newConfig = new Configuration(mTask.getConfiguration());
        newConfig.orientation = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
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

        mActivity.info.configChanges &= ~ActivityInfo.CONFIG_ORIENTATION;
        final Configuration newConfig = new Configuration(mTask.getConfiguration());
        newConfig.orientation = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        mTask.onRequestedOverrideConfigurationChanged(newConfig);

        doReturn(true).when(mTask.getTask()).isDragResizing();

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
        mActivity.setState(ActivityStack.ActivityState.RESUMED, "Testing");

        mTask.onRequestedOverrideConfigurationChanged(mTask.getConfiguration());
        mActivity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                mActivity.getConfiguration()));

        mActivity.info.configChanges |= ActivityInfo.CONFIG_ORIENTATION;
        final Configuration newConfig = new Configuration(mActivity.getConfiguration());
        newConfig.orientation = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
                ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;

        // Mimic the behavior that display doesn't handle app's requested orientation.
        doAnswer(invocation -> {
            mTask.onConfigurationChanged(newConfig);
            return null;
        }).when(mActivity.mAppWindowToken).setOrientation(anyInt(), any(), any());

        final int requestedOrientation;
        switch (newConfig.orientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case Configuration.ORIENTATION_PORTRAIT:
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
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");

        final TestActivityStack stack = (TestActivityStack) new StackBuilder(mRootActivityContainer)
                .build();
        try {
            stack.setIsTranslucent(false);
            assertFalse(mStack.shouldBeVisible(null /* starting */));

            mTask.onRequestedOverrideConfigurationChanged(mTask.getConfiguration());
            mActivity.setLastReportedConfiguration(new MergedConfiguration(new Configuration(),
                    mActivity.getConfiguration()));

            mActivity.mLaunchTaskBehind = true;
            mActivity.info.configChanges |= ActivityInfo.CONFIG_ORIENTATION;
            final Configuration newConfig = new Configuration(mActivity.getConfiguration());
            newConfig.orientation = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
                    ? Configuration.ORIENTATION_LANDSCAPE
                    : Configuration.ORIENTATION_PORTRAIT;

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
        topActivity.changeWindowTranslucency(false);
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");
        mActivity.makeClientVisible();
        assertEquals(PAUSED, mActivity.getState());
    }

    @Test
    public void testSizeCompatMode_FixedAspectRatioBoundsWithDecor() {
        setupDisplayContentForCompatDisplayInsets();
        final int decorHeight = 200; // e.g. The device has cutout.
        final DisplayPolicy policy = setupDisplayAndParentSize(600, 800).getDisplayPolicy();
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
        mActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
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
        final ActivityDisplay newDisplay = addNewActivityDisplayAt(ActivityDisplay.POSITION_TOP);
        newDisplay.getWindowConfiguration().setAppBounds(new Rect(0, 0, 1000, 2000));
        newDisplay.getConfiguration().densityDpi = 300;

        mTask.getConfiguration().densityDpi = 200;
        prepareFixedAspectRatioUnresizableActivity();

        final Rect originalBounds = new Rect(mActivity.getBounds());
        final int originalDpi = mActivity.getConfiguration().densityDpi;

        // Move the non-resizable activity to the new display.
        mStack.reparent(newDisplay, true /* onTop */, false /* displayRemoved */);
        ensureActivityConfiguration();

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
        mTask.getConfiguration().orientation = Configuration.ORIENTATION_PORTRAIT;
        mActivity.info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        mActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
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
        mTask.getConfiguration().screenLayout = fixedScreenLayout
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
        doReturn(ActivityInfo.CONFIG_SCREEN_SIZE | ActivityInfo.CONFIG_ORIENTATION
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
        mService.mH.runWithScissors(() -> { }, TimeUnit.SECONDS.toMillis(3));
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

    /** Setup {@link #mActivity} as a size-compat-mode-able activity without fixed orientation. */
    private void prepareFixedAspectRatioUnresizableActivity() {
        setupDisplayContentForCompatDisplayInsets();
        when(mActivity.mAppWindowToken.getOrientationIgnoreVisibility()).thenReturn(
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        mActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
        mActivity.info.maxAspectRatio = 1.5f;
        ensureActivityConfiguration();
    }

    private void setupDisplayContentForCompatDisplayInsets() {
        final Rect displayBounds = mStack.getDisplay().getBounds();
        final DisplayContent displayContent = setupDisplayAndParentSize(
                displayBounds.width(), displayBounds.height());
        doReturn(mock(DisplayPolicy.class)).when(displayContent).getDisplayPolicy();
        doReturn(mock(WmDisplayCutout.class)).when(displayContent)
                .calculateDisplayCutoutForRotation(anyInt());
    }

    private DisplayContent setupDisplayAndParentSize(int width, int height) {
        // The DisplayContent is already a mocked object.
        final DisplayContent displayContent = mStack.getDisplay().mDisplayContent;
        displayContent.mBaseDisplayWidth = width;
        displayContent.mBaseDisplayHeight = height;
        mTask.getWindowConfiguration().setAppBounds(0, 0, width, height);
        mTask.getWindowConfiguration().setRotation(ROTATION_0);
        return displayContent;
    }
}
