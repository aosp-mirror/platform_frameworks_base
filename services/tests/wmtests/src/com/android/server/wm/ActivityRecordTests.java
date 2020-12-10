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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_ALWAYS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_NEVER;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.os.Process.NOBODY_UID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.atLeast;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;

import android.app.ActivityManager.TaskSnapshot;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.PauseActivityItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.MergedConfiguration;
import android.util.MutableBoolean;
import android.view.DisplayInfo;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner.Stub;
import android.view.IWindowSession;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import androidx.test.filters.MediumTest;

import com.android.internal.R;
import com.android.server.wm.ActivityStack.ActivityState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;

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
    private Task mTask;
    private ActivityRecord mActivity;

    @Before
    public void setUp() throws Exception {
        mStack = new StackBuilder(mRootWindowContainer).build();
        mTask = mStack.getBottomMostTask();
        mActivity = mTask.getTopNonFinishingActivity();

        setBooted(mService);
    }

    @Test
    public void testStackCleanupOnClearingTask() {
        mActivity.onParentChanged(null /*newParent*/, mActivity.getTask());
        verify(mStack, times(1)).cleanUpActivityReferences(any());
    }

    @Test
    public void testStackCleanupOnActivityRemoval() {
        mTask.removeChild(mActivity);
        verify(mStack, times(1)).cleanUpActivityReferences(any());
    }

    @Test
    public void testStackCleanupOnTaskRemoval() {
        mStack.removeChild(mTask, null /*reason*/);
        // Stack should be gone on task removal.
        assertNull(mService.mRootWindowContainer.getStack(mStack.mTaskId));
    }

    @Test
    public void testRemoveChildWithOverlayActivity() {
        final ActivityRecord overlayActivity =
                new ActivityBuilder(mService).setTask(mTask).build();
        overlayActivity.setTaskOverlay(true);
        final ActivityRecord overlayActivity2 =
                new ActivityBuilder(mService).setTask(mTask).build();
        overlayActivity2.setTaskOverlay(true);

        mTask.removeChild(overlayActivity2, "test");
        verify(mSupervisor, never()).removeTask(any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testNoCleanupMovingActivityInSameStack() {
        final Task newTask = new TaskBuilder(mService.mStackSupervisor).setStack(mStack).build();
        mActivity.reparent(newTask, 0, null /*reason*/);
        verify(mStack, times(0)).cleanUpActivityReferences(any());
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
        assertFalse(activity.canBeLaunchedOnDisplay(Integer.MAX_VALUE));
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
        Task task2 = new TaskBuilder(mService.mStackSupervisor).setStack(mStack).build();
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

        doReturn(true).when(mTask).isDragResizing();

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
        final DisplayContent dc = mTask.getDisplayContent();
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
    public void ignoreRequestedOrientationInFreeformWindows() {
        mStack.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        final Rect stableRect = new Rect();
        mStack.getDisplay().mDisplayContent.getStableRect(stableRect);

        // Carve out non-decor insets from stableRect
        final Rect insets = new Rect();
        final DisplayInfo displayInfo = mStack.getDisplay().getDisplayInfo();
        final DisplayPolicy policy = mStack.getDisplay().getDisplayPolicy();
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
        mTask.setBounds(bounds);

        // Requests orientation that's different from its bounds.
        mActivity.setRequestedOrientation(
                isScreenPortrait ? SCREEN_ORIENTATION_PORTRAIT : SCREEN_ORIENTATION_LANDSCAPE);

        // Asserts it has orientation derived from bounds.
        assertEquals(isScreenPortrait ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT,
                mActivity.getConfiguration().orientation);
    }

    @Test
    public void ignoreRequestedOrientationInSplitWindows() {
        mStack.setWindowingMode(WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final Rect stableRect = new Rect();
        mStack.getDisplay().getStableRect(stableRect);

        // Carve out non-decor insets from stableRect
        final Rect insets = new Rect();
        final DisplayInfo displayInfo = mStack.getDisplay().getDisplayInfo();
        final DisplayPolicy policy = mStack.getDisplay().getDisplayPolicy();
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
        mTask.setBounds(bounds);

        // Requests orientation that's different from its bounds.
        mActivity.setRequestedOrientation(
                isScreenPortrait ? SCREEN_ORIENTATION_PORTRAIT : SCREEN_ORIENTATION_LANDSCAPE);

        // Asserts it has orientation derived from bounds.
        assertEquals(isScreenPortrait ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT,
                mActivity.getConfiguration().orientation);
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
    public void testShouldMakeActive_nonTopVisible() {
        ActivityRecord finishingActivity = new ActivityBuilder(mService).setTask(mTask).build();
        finishingActivity.finishing = true;
        ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");

        assertEquals(false, mActivity.shouldMakeActive(null /* activeActivity */));
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
    public void testShouldResumeOrPauseWithResults() {
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");
        spyOn(mStack);

        ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        mActivity.addResultLocked(topActivity, "resultWho", 0, 0, new Intent());
        topActivity.finishing = true;

        doReturn(STACK_VISIBILITY_VISIBLE).when(mStack).getVisibility(null);
        assertEquals(true, mActivity.shouldResumeActivity(null /* activeActivity */));
        assertEquals(false, mActivity.shouldPauseActivity(null /*activeActivity */));
    }

    @Test
    public void testPushConfigurationWhenLaunchTaskBehind() throws Exception {
        mActivity = new ActivityBuilder(mService)
                .setTask(mTask)
                .setLaunchTaskBehind(true)
                .setConfigChanges(CONFIG_ORIENTATION | CONFIG_SCREEN_LAYOUT)
                .build();
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");

        final ActivityStack stack = new StackBuilder(mRootWindowContainer).build();
        try {
            doReturn(false).when(stack).isTranslucent(any());
            assertTrue(mStack.shouldBeVisible(null /* starting */));

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
            stack.getDisplayArea().removeChild(stack);
        }
    }

    @Test
    public void testShouldStartWhenMakeClientActive() {
        ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.setOccludesParent(false);
        mActivity.setState(ActivityStack.ActivityState.STOPPED, "Testing");
        mActivity.setVisibility(true);
        mActivity.makeActiveIfNeeded(null /* activeActivity */);
        assertEquals(STARTED, mActivity.getState());
    }

    @Test
    public void testTakeOptions() {
        ActivityOptions opts = ActivityOptions.makeRemoteAnimation(
                new RemoteAnimationAdapter(new Stub() {

                    @Override
                    public void onAnimationStart(RemoteAnimationTarget[] apps,
                            RemoteAnimationTarget[] wallpapers,
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
        mActivity.activityStopped(savedState, persistentSavedState, "desc");
        assertTrue(mActivity.hasSavedState());
        assertEquals(savedState, mActivity.getSavedState());
        assertEquals(persistentSavedState, mActivity.getPersistentSavedState());

        // Sending 'null' for saved state can only happen due to timeout, so previously stored saved
        // states should not be overridden.
        mActivity.setState(STOPPING, "test");
        mActivity.activityStopped(null /* savedState */, null /* persistentSavedState */, "desc");
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
        mActivity.onParentChanged(null /*newParent*/, mActivity.getTask());
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
        mActivity.mVisibleRequested = false;
        mActivity.nowVisible = false;
        // Set process to 'null' to allow immediate removal, but don't call mActivity.setProcess() -
        // this will cause NPE when updating task's process.
        mActivity.app = null;

        // Put a visible activity on top, so the finishing activity doesn't have to wait until the
        // next activity reports idle to destroy it.
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.setState(RESUMED, "test");

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
        final ActivityStack stack1 = new StackBuilder(mRootWindowContainer).build();
        mStack.moveToFront("test");
        // The stack2 is needed here for moving back to simulate the
        // {@link DisplayContent#mPreferredTopFocusableStack} is cleared, so
        // {@link DisplayContent#getFocusedStack} will rely on the order of focusable-and-visible
        // stacks. Then when mActivity is finishing, its stack will be invisible (no running
        // activities in the stack) that is the key condition to verify.
        final ActivityStack stack2 = new StackBuilder(mRootWindowContainer).build();
        stack2.moveToBack("test", stack2.getBottomMostTask());

        assertTrue(mStack.isTopStackInDisplayArea());

        mActivity.setState(RESUMED, "test");
        mActivity.finishIfPossible(0 /* resultCode */, null /* resultData */,
                null /* resultGrants */, "test", false /* oomAdj */);

        assertTrue(stack1.isTopStackInDisplayArea());
    }

    /**
     * Verify that when finishing the top focused activity while root task was created by organizer,
     * the stack order will be changed by adjusting focus.
     */
    @Test
    public void testFinishActivityIfPossible_adjustStackOrderOrganizedRoot() {
        // Make mStack be a the root task that created by task organizer
        mStack.mCreatedByOrganizer = true;

        // Have two tasks (topRootableTask and mTask) as the children of mStack.
        ActivityRecord topActivity = new ActivityBuilder(mActivity.mAtmService)
                .setCreateTask(true)
                .setStack(mStack)
                .build();
        ActivityStack topRootableTask = (ActivityStack) topActivity.getTask();
        topRootableTask.moveToFront("test");
        assertTrue(mStack.isTopStackInDisplayArea());

        // Finish top activity and verify the next focusable rootable task has adjusted to top.
        topActivity.setState(RESUMED, "test");
        topActivity.finishIfPossible(0 /* resultCode */, null /* resultData */,
                null /* resultGrants */, "test", false /* oomAdj */);
        assertEquals(mTask, mStack.getTopMostTask());
    }

    /**
     * Verify that when top focused activity is on secondary display, when finishing the top focused
     * activity on default display, the preferred top stack on default display should be changed by
     * adjusting focus.
     */
    @Test
    public void testFinishActivityIfPossible_PreferredTopStackChanged() {
        final ActivityRecord topActivityOnNonTopDisplay =
                createActivityOnDisplay(true /* defaultDisplay */, null /* process */);
        ActivityStack topRootableTask = topActivityOnNonTopDisplay.getRootTask();
        topRootableTask.moveToFront("test");
        assertTrue(topRootableTask.isTopStackInDisplayArea());
        assertEquals(topRootableTask, topActivityOnNonTopDisplay.getDisplayArea()
                .mPreferredTopFocusableStack);

        final ActivityRecord secondaryDisplayActivity =
                createActivityOnDisplay(false /* defaultDisplay */, null /* process */);
        topRootableTask = secondaryDisplayActivity.getRootTask();
        topRootableTask.moveToFront("test");
        assertTrue(topRootableTask.isTopStackInDisplayArea());
        assertEquals(topRootableTask,
                secondaryDisplayActivity.getDisplayArea().mPreferredTopFocusableStack);

        // The global top focus activity is on secondary display now.
        // Finish top activity on default display and verify the next preferred top focusable stack
        // on default display has changed.
        topActivityOnNonTopDisplay.setState(RESUMED, "test");
        topActivityOnNonTopDisplay.finishIfPossible(0 /* resultCode */, null /* resultData */,
                null /* resultGrants */, "test", false /* oomAdj */);
        assertEquals(mTask, mStack.getTopMostTask());
        assertEquals(mStack, mActivity.getDisplayArea().mPreferredTopFocusableStack);
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
        mActivity.mVisibleRequested = true;
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
        mActivity.mVisibleRequested = true;
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
        mActivity.mVisibleRequested = false;
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
     * Verify that finish request won't change the state of next top activity if the current
     * finishing activity doesn't need to be destroyed immediately. The case is usually like
     * from {@link ActivityStack#completePauseLocked(boolean, ActivityRecord)} to
     * {@link ActivityRecord#completeFinishing(String)}, so the complete-pause should take the
     * responsibility to resume the next activity with updating the state.
     */
    @Test
    public void testCompleteFinishing_keepStateOfNextInvisible() {
        final ActivityRecord currentTop = mActivity;
        currentTop.mVisibleRequested = currentTop.nowVisible = true;

        // Simulates that {@code currentTop} starts an existing activity from background (so its
        // state is stopped) and the starting flow just goes to place it at top.
        final ActivityStack nextStack = new StackBuilder(mRootWindowContainer).build();
        final ActivityRecord nextTop = nextStack.getTopNonFinishingActivity();
        nextTop.setState(STOPPED, "test");

        mStack.mPausingActivity = currentTop;
        currentTop.finishing = true;
        currentTop.setState(PAUSED, "test");
        currentTop.completeFinishing("completePauseLocked");

        // Current top becomes stopping because it is visible and the next is invisible.
        assertEquals(STOPPING, currentTop.getState());
        // The state of next activity shouldn't be changed.
        assertEquals(STOPPED, nextTop.getState());
    }

    /**
     * Verify that complete finish request for visible activity must be delayed before the next one
     * becomes visible.
     */
    @Test
    public void testCompleteFinishing_waitForNextVisible() {
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as not visible, so that we will wait for it before removing
        // the top one.
        mActivity.mVisibleRequested = false;
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
        topActivity.mVisibleRequested = false;
        topActivity.nowVisible = false;
        topActivity.finishing = true;
        topActivity.setState(STOPPED, "true");
        // Mark the bottom activity as not visible, so that we would wait for it before removing
        // the top one.
        mActivity.mVisibleRequested = false;
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
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        mActivity.mVisibleRequested = true;
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
        topActivity.mVisibleRequested = false;
        topActivity.nowVisible = false;
        topActivity.finishing = true;
        topActivity.setState(STOPPED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        mActivity.mVisibleRequested = true;
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
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.finishing = true;
        topActivity.setState(PAUSED, "true");
        // Mark the bottom activity as already visible, so that there is no need to wait for it.
        mActivity.mVisibleRequested = true;
        mActivity.nowVisible = true;
        mActivity.setState(RESUMED, "test");

        // Add another stack to become focused and make the activity there visible. This way it
        // simulates finishing in non-focused stack in split-screen.
        final ActivityStack stack = new StackBuilder(mRootWindowContainer).build();
        final ActivityRecord focusedActivity = stack.getTopMostActivity();
        focusedActivity.nowVisible = true;
        focusedActivity.mVisibleRequested = true;
        focusedActivity.setState(RESUMED, "test");
        stack.mResumedActivity = focusedActivity;

        topActivity.completeFinishing("test");

        verify(topActivity).destroyIfPossible(anyString());
    }

    /**
     * Verify the visibility of a show-when-locked and dismiss keyguard activity on sleeping
     * display.
     */
    @Test
    public void testDisplaySleeping_activityInvisible() {
        final KeyguardController keyguardController =
                mActivity.mStackSupervisor.getKeyguardController();
        doReturn(true).when(keyguardController).isKeyguardLocked();
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.setState(RESUMED, "test" /*reason*/);
        doReturn(true).when(topActivity).containsDismissKeyguardWindow();
        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyInt() /* configChanges */,
                anyBoolean() /* preserveWindows */, anyBoolean() /* notifyClients */);
        topActivity.setShowWhenLocked(true);

        // Verify the top activity is occluded keyguard.
        assertEquals(topActivity, mStack.topRunningActivity());
        assertTrue(mStack.topActivityOccludesKeyguard());

        final DisplayContent display = mActivity.mDisplayContent;
        doReturn(true).when(display).isSleeping();
        assertFalse(topActivity.shouldBeVisible());
    }

    /**
     * Verify that complete finish request for a show-when-locked activity must ensure the
     * keyguard occluded state being updated.
     */
    @Test
    public void testCompleteFinishing_showWhenLocked() {
        // Make keyguard locked and set the top activity show-when-locked.
        KeyguardController keyguardController = mActivity.mStackSupervisor.getKeyguardController();
        doReturn(true).when(keyguardController).isKeyguardLocked();
        final ActivityRecord topActivity = new ActivityBuilder(mService).setTask(mTask).build();
        topActivity.mVisibleRequested = true;
        topActivity.nowVisible = true;
        topActivity.setState(RESUMED, "true");
        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyInt() /* configChanges */,
                anyBoolean() /* preserveWindows */, anyBoolean() /* notifyClients */);
        topActivity.setShowWhenLocked(true);

        // Verify the stack-top activity is occluded keyguard.
        assertEquals(topActivity, mStack.topRunningActivity());
        assertTrue(mStack.topActivityOccludesKeyguard());

        // Finish the top activity
        topActivity.setState(PAUSED, "true");
        topActivity.finishing = true;
        topActivity.completeFinishing("test");

        // Verify new top activity does not occlude keyguard.
        assertEquals(mActivity, mStack.topRunningActivity());
        assertFalse(mStack.topActivityOccludesKeyguard());
    }

    /**
     * Verify that complete finish request for an activity which the resume activity is translucent
     * must ensure the visibilities of activities being updated.
     */
    @Test
    public void testCompleteFinishing_ensureActivitiesVisible() {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setTask(mTask).build();
        firstActivity.mVisibleRequested = false;
        firstActivity.nowVisible = false;
        firstActivity.setState(STOPPED, "true");

        final ActivityRecord secondActivity = new ActivityBuilder(mService).setTask(mTask).build();
        secondActivity.mVisibleRequested = true;
        secondActivity.nowVisible = true;
        secondActivity.setState(PAUSED, "true");

        final ActivityRecord translucentActivity =
                new ActivityBuilder(mService).setTask(mTask).build();
        translucentActivity.mVisibleRequested = true;
        translucentActivity.nowVisible = true;
        translucentActivity.setState(RESUMED, "true");

        doReturn(false).when(translucentActivity).occludesParent();

        // Finish the second activity
        secondActivity.finishing = true;
        secondActivity.completeFinishing("test");
        verify(secondActivity.getDisplay()).ensureActivitiesVisible(null /* starting */,
                0 /* configChanges */ , false /* preserveWindows */,
                true /* notifyClients */);

        // Finish the first activity
        firstActivity.finishing = true;
        firstActivity.mVisibleRequested = true;
        firstActivity.completeFinishing("test");
        verify(firstActivity.getDisplay(), times(2)).ensureActivitiesVisible(null /* starting */,
                0 /* configChanges */ , false /* preserveWindows */,
                true /* notifyClients */);
    }

    /**
     * Verify destroy activity request completes successfully.
     */
    @Test
    public void testDestroyIfPossible() {
        doReturn(false).when(mRootWindowContainer).resumeFocusedStacksTopActivities();
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
        final ActivityStack homeStack = mActivity.getDisplayArea().getRootHomeTask();
        homeStack.forAllLeafTasks((t) -> {
            homeStack.removeChild(t, "test");
        }, true /* traverseTopToBottom */);
        mActivity.finishing = true;
        doReturn(false).when(mRootWindowContainer).resumeFocusedStacksTopActivities();
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
     * Verify that complete finish request for visible activity must resume next home stack before
     * destroying it immediately if it is the last running activity on a display with a home stack.
     * We must wait for home activity to come up to avoid a black flash in this case.
     */
    @Test
    public void testCompleteFinishing_lastActivityAboveEmptyHomeStack() {
        // Empty the home stack.
        final ActivityStack homeStack = mActivity.getDisplayArea().getRootHomeTask();
        homeStack.forAllLeafTasks((t) -> {
            homeStack.removeChild(t, "test");
        }, true /* traverseTopToBottom */);
        mActivity.finishing = true;
        spyOn(mStack);

        // Try to finish the last activity above the home stack.
        mActivity.completeFinishing("test");

        // Verify that the activity is not destroyed immediately, but waits for next one to come up.
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
        final Task task = mActivity.getTask();

        mActivity.destroyImmediately(false /* removeFromApp */, "test");

        assertEquals(DESTROYED, mActivity.getState());
        assertNull(mActivity.getTask());
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
        final Task task = mActivity.getTask();

        mActivity.destroyImmediately(false /* removeFromApp */, "test");

        assertEquals(DESTROYED, mActivity.getState());
        assertEquals(task, mActivity.getTask());
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
        final ActivityStack stack = mActivity.getRootTask();
        final Task task = mActivity.getTask();

        mActivity.removeFromHistory("test");

        assertEquals(DESTROYED, mActivity.getState());
        assertNull(mActivity.app);
        assertNull(mActivity.getTask());
        assertEquals(0, task.getChildCount());
        assertEquals(task.getStack(), task);
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

    @Test
    public void testActivityOverridesProcessConfig() {
        final WindowProcessController wpc = mActivity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());
        assertFalse(wpc.registeredForDisplayConfigChanges());

        final ActivityRecord secondaryDisplayActivity =
                createActivityOnDisplay(false /* defaultDisplay */, null /* process */);

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, mActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
        assertNotEquals(mActivity.getConfiguration(),
                secondaryDisplayActivity.getConfiguration());
    }

    @Test
    public void testActivityOverridesProcessConfig_TwoActivities() {
        final WindowProcessController wpc = mActivity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());

        final Task firstTaskRecord = mActivity.getTask();
        final ActivityRecord secondActivityRecord =
                new ActivityBuilder(mService).setTask(firstTaskRecord).setUseProcess(wpc).build();

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivityRecord.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testActivityOverridesProcessConfig_TwoActivities_SecondaryDisplay() {
        final WindowProcessController wpc = mActivity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());

        final ActivityRecord secondActivityRecord =
                new ActivityBuilder(mService).setTask(mTask).setUseProcess(wpc).build();

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivityRecord.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testActivityOverridesProcessConfig_TwoActivities_DifferentTasks() {
        final WindowProcessController wpc = mActivity.app;
        assertTrue(wpc.registeredForActivityConfigChanges());

        final ActivityRecord secondActivityRecord =
                createActivityOnDisplay(true /* defaultDisplay */, wpc);

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivityRecord.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testActivityOnCancelFixedRotationTransform() {
        final DisplayRotation displayRotation = mActivity.mDisplayContent.getDisplayRotation();
        spyOn(displayRotation);

        final DisplayContent display = mActivity.mDisplayContent;
        final int originalRotation = display.getRotation();

        // Make {@link DisplayContent#sendNewConfiguration} not apply rotation immediately.
        doReturn(true).when(displayRotation).isWaitingForRemoteRotation();
        doReturn((originalRotation + 1) % 4).when(displayRotation).rotationForOrientation(
                anyInt() /* orientation */, anyInt() /* lastRotation */);
        // Set to visible so the activity can freeze the screen.
        mActivity.setVisibility(true);

        display.rotateInDifferentOrientationIfNeeded(mActivity);
        display.setFixedRotationLaunchingAppUnchecked(mActivity);
        displayRotation.updateRotationUnchecked(true /* forceUpdate */);

        assertTrue(displayRotation.isRotatingSeamlessly());

        // The launching rotated app should not be cleared when waiting for remote rotation.
        display.continueUpdateOrientationForDiffOrienLaunchingApp();
        assertTrue(display.isFixedRotationLaunchingApp(mActivity));

        // Simulate the rotation has been updated to previous one, e.g. sensor updates before the
        // remote rotation is completed.
        doReturn(originalRotation).when(displayRotation).rotationForOrientation(
                anyInt() /* orientation */, anyInt() /* lastRotation */);
        display.updateOrientation();

        final DisplayInfo rotatedInfo = mActivity.getFixedRotationTransformDisplayInfo();
        mActivity.finishFixedRotationTransform();
        final ScreenRotationAnimation rotationAnim = display.getRotationAnimation();
        assertNotNull(rotationAnim);
        rotationAnim.setRotation(display.getPendingTransaction(), originalRotation);

        // Because the display doesn't rotate, the rotated activity needs to cancel the fixed
        // rotation. There should be a rotation animation to cover the change of activity.
        verify(mActivity).onCancelFixedRotationTransform(rotatedInfo.rotation);
        assertTrue(mActivity.isFreezingScreen());
        assertFalse(displayRotation.isRotatingSeamlessly());
        assertTrue(rotationAnim.isRotating());

        // Simulate the remote rotation has completed and the configuration doesn't change, then
        // the rotated activity should also be restored by clearing the transform.
        displayRotation.updateRotationUnchecked(true /* forceUpdate */);
        doReturn(false).when(displayRotation).isWaitingForRemoteRotation();
        clearInvocations(mActivity);
        display.setFixedRotationLaunchingAppUnchecked(mActivity);
        display.sendNewConfiguration();

        assertFalse(display.hasTopFixedRotationLaunchingApp());
        assertFalse(mActivity.hasFixedRotationTransform());
    }

    @Test
    public void testIsSnapshotCompatible() {
        final TaskSnapshot snapshot = new TaskSnapshotPersisterTestBase.TaskSnapshotBuilder()
                .setRotation(mActivity.getWindowConfiguration().getRotation())
                .build();

        assertTrue(mActivity.isSnapshotCompatible(snapshot));

        setRotatedScreenOrientationSilently(mActivity);

        assertFalse(mActivity.isSnapshotCompatible(snapshot));
    }

    @Test
    public void testFixedRotationSnapshotStartingWindow() {
        // TaskSnapshotSurface requires a fullscreen opaque window.
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);
        params.width = params.height = WindowManager.LayoutParams.MATCH_PARENT;
        final WindowTestUtils.TestWindowState w = new WindowTestUtils.TestWindowState(
                mService.mWindowManager, mock(Session.class), new TestIWindow(), params, mActivity);
        mActivity.addWindow(w);

        // Assume the activity is launching in different rotation, and there was an available
        // snapshot accepted by {@link Activity#isSnapshotCompatible}.
        final TaskSnapshot snapshot = new TaskSnapshotPersisterTestBase.TaskSnapshotBuilder()
                .setRotation((mActivity.getWindowConfiguration().getRotation() + 1) % 4)
                .build();
        setRotatedScreenOrientationSilently(mActivity);
        mActivity.setVisible(false);

        final IWindowSession session = WindowManagerGlobal.getWindowSession();
        spyOn(session);
        try {
            // Return error to skip unnecessary operation.
            doReturn(WindowManagerGlobal.ADD_STARTING_NOT_NEEDED).when(session).addToDisplay(
                    any() /* window */, anyInt() /* seq */, any() /* attrs */,
                    anyInt() /* viewVisibility */, anyInt() /* displayId */, any() /* outFrame */,
                    any() /* outContentInsets */, any() /* outStableInsets */,
                    any() /* outDisplayCutout */, any() /* outInputChannel */,
                    any() /* outInsetsState */, any() /* outActiveControls */);
            TaskSnapshotSurface.create(mService.mWindowManager, mActivity, snapshot);
        } catch (RemoteException ignored) {
        } finally {
            reset(session);
        }

        // Because the rotation of snapshot and the corresponding top activity are different, fixed
        // rotation should be applied when creating snapshot surface if the display rotation may be
        // changed according to the activity orientation.
        assertTrue(mActivity.hasFixedRotationTransform());
        assertTrue(mActivity.mDisplayContent.isFixedRotationLaunchingApp(mActivity));
    }

    /**
     * Sets orientation without notifying the parent to simulate that the display has not applied
     * the requested orientation yet.
     */
    static void setRotatedScreenOrientationSilently(ActivityRecord r) {
        final int rotatedOrentation = r.getConfiguration().orientation == ORIENTATION_PORTRAIT
                ? SCREEN_ORIENTATION_LANDSCAPE
                : SCREEN_ORIENTATION_PORTRAIT;
        doReturn(false).when(r).onDescendantOrientationChanged(any(), any());
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
        assertFalse(wpc.registeredForDisplayConfigChanges());
    }

    @Test
    public void testActivityReparentChangesProcessOverride() {
        final WindowProcessController wpc = mActivity.app;
        final Task initialTask = mActivity.getTask();
        final Configuration initialConf =
                new Configuration(mActivity.getMergedOverrideConfiguration());
        assertEquals(0, mActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
        assertTrue(wpc.registeredForActivityConfigChanges());

        // Create a new task with custom config to reparent the activity to.
        final Task newTask =
                new TaskBuilder(mSupervisor).setStack(initialTask.getStack()).build();
        final Configuration newConfig = newTask.getConfiguration();
        newConfig.densityDpi += 100;
        newTask.onRequestedOverrideConfigurationChanged(newConfig);
        assertEquals(newTask.getConfiguration().densityDpi, newConfig.densityDpi);

        // Reparent the activity and verify that config override changed.
        mActivity.reparent(newTask, 0 /* top */, "test");
        assertEquals(mActivity.getConfiguration().densityDpi, newConfig.densityDpi);
        assertEquals(mActivity.getMergedOverrideConfiguration().densityDpi, newConfig.densityDpi);

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertNotEquals(initialConf, wpc.getRequestedOverrideConfiguration());
        assertEquals(0, mActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testActivityReparentDoesntClearProcessOverride_TwoActivities() {
        final WindowProcessController wpc = mActivity.app;
        final Configuration initialConf =
                new Configuration(mActivity.getMergedOverrideConfiguration());
        final Task initialTask = mActivity.getTask();
        final ActivityRecord secondActivity = new ActivityBuilder(mService).setTask(initialTask)
                .setUseProcess(wpc).build();

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, secondActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));

        // Create a new task with custom config to reparent the second activity to.
        final Task newTask =
                new TaskBuilder(mSupervisor).setStack(initialTask.getStack()).build();
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
        mActivity.reparent(newTask, 1 /* top */, "test");
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

        secondActivity.destroyImmediately(true, "");

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, thirdActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));

        firstActivity.destroyImmediately(true, "");

        assertTrue(wpc.registeredForActivityConfigChanges());
        assertEquals(0, thirdActivity.getMergedOverrideConfiguration()
                .diff(wpc.getRequestedOverrideConfiguration()));
    }

    @Test
    public void testGetLockTaskLaunchMode() {
        final ActivityOptions options = ActivityOptions.makeBasic().setLockTaskEnabled(true);
        mActivity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_DEFAULT;
        assertEquals(LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED,
                ActivityRecord.getLockTaskLaunchMode(mActivity.info, options));

        mActivity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_ALWAYS;
        assertEquals(LOCK_TASK_LAUNCH_MODE_DEFAULT,
                ActivityRecord.getLockTaskLaunchMode(mActivity.info, null /*options*/));

        mActivity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_NEVER;
        assertEquals(LOCK_TASK_LAUNCH_MODE_DEFAULT,
                ActivityRecord.getLockTaskLaunchMode(mActivity.info, null /*options*/));

        mActivity.info.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
        mActivity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_ALWAYS;
        assertEquals(LOCK_TASK_LAUNCH_MODE_ALWAYS,
                ActivityRecord.getLockTaskLaunchMode(mActivity.info, null /*options*/));

        mActivity.info.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_NEVER;
        assertEquals(LOCK_TASK_LAUNCH_MODE_NEVER,
                ActivityRecord.getLockTaskLaunchMode(mActivity.info, null /*options*/));

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
            display = new TestDisplayContent.Builder(mService, 2000, 1000).setDensityDpi(300)
                    .setPosition(DisplayContent.POSITION_TOP).build();
        }
        final ActivityStack stack = display.getDefaultTaskDisplayArea()
                .createStack(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task task = new TaskBuilder(mSupervisor).setStack(stack).build();
        return new ActivityBuilder(mService).setTask(task).setUseProcess(process).build();
    }
}
