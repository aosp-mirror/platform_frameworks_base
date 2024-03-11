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

import static android.app.ActivityManager.PROCESS_STATE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.platform.test.annotations.Presubmit;
import android.view.IRecentsAnimationRunner;

import androidx.test.filters.MediumTest;

import com.android.server.wm.RecentsAnimationController.RecentsAnimationCallbacks;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:RecentsAnimationTest
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RecentsAnimationTest extends WindowTestsBase {

    private static final int TEST_USER_ID = 100;

    private final ComponentName mRecentsComponent =
            new ComponentName(mContext.getPackageName(), "RecentsActivity");
    private RecentsAnimationController mRecentsAnimationController;

    @Before
    public void setUp() throws Exception {
        mRecentsAnimationController = mock(RecentsAnimationController.class);
        mAtm.mWindowManager.setRecentsAnimationController(mRecentsAnimationController);
        doNothing().when(mAtm.mWindowManager).initializeRecentsAnimation(
                anyInt(), any(), any(), anyInt(), any(), any());

        final RecentTasks recentTasks = mAtm.getRecentTasks();
        spyOn(recentTasks);
        doReturn(mRecentsComponent).when(recentTasks).getRecentsComponent();
    }

    @Test
    public void testRecentsActivityVisiblility() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task recentsStack = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        final WindowProcessController wpc = mSystemServicesTestRule.addProcess(
                mRecentsComponent.getPackageName(), mRecentsComponent.getPackageName(),
                // Use real pid/uid of the test so the corresponding process can be mapped by
                // Binder.getCallingPid/Uid.
                WindowManagerService.MY_PID, WindowManagerService.MY_UID);
        ActivityRecord recentActivity = new ActivityBuilder(mAtm)
                .setComponent(mRecentsComponent)
                .setTask(recentsStack)
                .setUseProcess(wpc)
                .build();
        ActivityRecord topActivity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        topActivity.getRootTask().moveToFront("testRecentsActivityVisiblility");

        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyBoolean() /* notifyClients */);

        RecentsAnimationCallbacks recentsAnimation = startRecentsActivity(
                mRecentsComponent, true /* getRecentsAnimation */);
        // The launch-behind state should make the recents activity visible.
        assertTrue(recentActivity.isVisibleRequested());
        assertEquals(ActivityTaskManagerService.DEMOTE_TOP_REASON_ANIMATING_RECENTS,
                mAtm.mDemoteTopAppReasons);
        assertFalse(mAtm.mInternal.useTopSchedGroupForTopProcess());

        // Simulate the animation is cancelled without changing the stack order.
        recentsAnimation.onAnimationFinished(REORDER_KEEP_IN_PLACE, false /* sendUserLeaveHint */);
        // The non-top recents activity should be invisible by the restored launch-behind state.
        assertFalse(recentActivity.isVisibleRequested());
        assertEquals(0, mAtm.mDemoteTopAppReasons);
    }

    @Test
    public void testPreloadRecentsActivity() {
        TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task homeStack =
                defaultTaskDisplayArea.getRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        defaultTaskDisplayArea.positionChildAt(POSITION_TOP, homeStack,
                false /* includingParents */);
        ActivityRecord topRunningHomeActivity = homeStack.topRunningActivity();
        if (topRunningHomeActivity == null) {
            topRunningHomeActivity = new ActivityBuilder(mAtm)
                    .setParentTask(homeStack)
                    .setCreateTask(true)
                    .build();
        }

        ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.uid = 10001;
        aInfo.applicationInfo.targetSdkVersion = mContext.getApplicationInfo().targetSdkVersion;
        aInfo.packageName = aInfo.applicationInfo.packageName = mRecentsComponent.getPackageName();
        aInfo.processName = "recents";
        doReturn(aInfo).when(mSupervisor).resolveActivity(any() /* intent */, any() /* rInfo */,
                anyInt() /* startFlags */, any() /* profilerInfo */);

        // Assume its process is alive because the caller should be the recents service.
        final WindowProcessController proc = mSystemServicesTestRule.addProcess(aInfo.packageName,
                aInfo.processName, 12345 /* pid */, aInfo.applicationInfo.uid);
        proc.setCurrentProcState(PROCESS_STATE_HOME);

        Intent recentsIntent = new Intent().setComponent(mRecentsComponent);
        // Null animation indicates to preload.
        mAtm.startRecentsActivity(recentsIntent, 0 /* eventTime */,
                null /* recentsAnimationRunner */);

        Task recentsStack = defaultTaskDisplayArea.getRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS);
        assertThat(recentsStack).isNotNull();

        ActivityRecord recentsActivity = recentsStack.getTopNonFinishingActivity();
        // The activity is started in background so it should be invisible and will be stopped.
        assertThat(recentsActivity).isNotNull();
        assertThat(recentsActivity.getState()).isEqualTo(STOPPING);
        assertFalse(recentsActivity.isVisibleRequested());

        // Assume it is stopped to test next use case.
        recentsActivity.activityStopped(null /* newIcicle */, null /* newPersistentState */,
                null /* description */);

        spyOn(recentsActivity);
        // Start when the recents activity exists. It should ensure the configuration.
        mAtm.startRecentsActivity(recentsIntent, 0 /* eventTime */,
                null /* recentsAnimationRunner */);

        verify(recentsActivity).ensureActivityConfiguration(eq(true) /* ignoreVisibility */);
    }

    @Test
    public void testRestartRecentsActivity() throws Exception {
        // Have a recents activity that is not attached to its process (ActivityRecord.app = null).
        TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task recentsStack = defaultTaskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        ActivityRecord recentActivity = new ActivityBuilder(mAtm).setComponent(
                mRecentsComponent).setCreateTask(true).setParentTask(recentsStack).build();
        WindowProcessController app = recentActivity.app;
        recentActivity.app = null;

        // Start an activity on top.
        new ActivityBuilder(mAtm).setCreateTask(true).build().getRootTask().moveToFront(
                "testRestartRecentsActivity");

        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyBoolean() /* notifyClients */);
        doReturn(app).when(mAtm).getProcessController(eq(recentActivity.processName), anyInt());
        doNothing().when(mClientLifecycleManager).scheduleTransaction(any());

        startRecentsActivity();

        // Recents activity must be restarted, but not be resumed while running recents animation.
        verify(mRootWindowContainer.mTaskSupervisor).startSpecificActivity(
                eq(recentActivity), eq(false), anyBoolean());
        assertThat(recentActivity.getState()).isEqualTo(PAUSED);
    }

    @Test
    public void testSetLaunchTaskBehindOfTargetActivity() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task homeStack = taskDisplayArea.getRootHomeTask();
        // Assume the home activity support recents.
        ActivityRecord targetActivity = homeStack.getTopNonFinishingActivity();
        if (targetActivity == null) {
            targetActivity = new ActivityBuilder(mAtm)
                    .setCreateTask(true)
                    .setParentTask(homeStack)
                    .build();
        }

        // Put another home activity in home stack.
        ActivityRecord anotherHomeActivity = new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(mContext.getPackageName(), "Home2"))
                .setCreateTask(true)
                .setParentTask(homeStack)
                .build();
        // Start an activity on top so the recents activity can be started.
        new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .build()
                .getRootTask()
                .moveToFront("Activity start");

        // Start the recents animation.
        RecentsAnimationCallbacks recentsAnimation = startRecentsActivity(
                targetActivity.getTask().getBaseIntent().getComponent(),
                true /* getRecentsAnimation */);
        // Ensure launch-behind is set for being visible.
        assertTrue(targetActivity.mLaunchTaskBehind);

        anotherHomeActivity.moveFocusableActivityToTop("launchAnotherHome");

        // The test uses mocked RecentsAnimationController so we have to invoke the callback
        // manually to simulate the flow.
        recentsAnimation.onAnimationFinished(REORDER_KEEP_IN_PLACE, false /* sendUserLeaveHint */);
        // We should restore the launch-behind of the original target activity.
        assertFalse(targetActivity.mLaunchTaskBehind);
    }

    @Test
    public void testCancelAnimationOnVisibleStackOrderChange() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task fullscreenStack = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setParentTask(fullscreenStack)
                .build();
        Task recentsStack = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        new ActivityBuilder(mAtm)
                .setComponent(mRecentsComponent)
                .setCreateTask(true)
                .setParentTask(recentsStack)
                .build();
        Task fullscreenStack2 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(mContext.getPackageName(), "App2"))
                .setCreateTask(true)
                .setParentTask(fullscreenStack2)
                .build();

        // Start the recents animation
        startRecentsActivity();

        fullscreenStack.moveToFront("Activity start");

        // Assume recents animation already started, set a state that cancel recents animation
        // with screenshot.
        doReturn(true).when(mRecentsAnimationController).shouldDeferCancelUntilNextTransition();
        doReturn(true).when(mRecentsAnimationController).shouldDeferCancelWithScreenshot();
        // Start another fullscreen activity.
        fullscreenStack2.moveToFront("Activity start");

        // Ensure that the recents animation was canceled by setCancelOnNextTransitionStart().
        verify(mRecentsAnimationController, times(1)).setCancelOnNextTransitionStart();
    }

    @Test
    public void testKeepAnimationOnHiddenStackOrderChange() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task fullscreenStack = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setParentTask(fullscreenStack)
                .build();
        Task recentsStack = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        new ActivityBuilder(mAtm)
                .setComponent(mRecentsComponent)
                .setCreateTask(true)
                .setParentTask(recentsStack)
                .build();
        Task fullscreenStack2 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(mContext.getPackageName(), "App2"))
                .setCreateTask(true)
                .setParentTask(fullscreenStack2)
                .build();

        // Start the recents animation
        startRecentsActivity();

        fullscreenStack.removeIfPossible();

        // Ensure that the recents animation was NOT canceled
        verify(mAtm.mWindowManager, times(0)).cancelRecentsAnimation(
                eq(REORDER_KEEP_IN_PLACE), any());
        verify(mRecentsAnimationController, times(0)).setCancelOnNextTransitionStart();
    }

    @Test
    public void testMultipleUserHomeActivity_findUserHomeTask() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultDisplay()
                .getDefaultTaskDisplayArea();
        Task homeStack = taskDisplayArea.getRootTask(WINDOWING_MODE_UNDEFINED,
                ACTIVITY_TYPE_HOME);
        ActivityRecord otherUserHomeActivity = new ActivityBuilder(mAtm)
                .setParentTask(homeStack)
                .setCreateTask(true)
                .setComponent(new ComponentName(mContext.getPackageName(), "Home2"))
                .build();
        otherUserHomeActivity.getTask().mUserId = TEST_USER_ID;

        Task fullscreenStack = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setParentTask(fullscreenStack)
                .build();

        doReturn(TEST_USER_ID).when(mAtm).getCurrentUserId();
        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyBoolean() /* notifyClients */);

        startRecentsActivity(otherUserHomeActivity.getTask().getBaseIntent().getComponent(),
                true);

        // Ensure we find the task for the right user and it is made visible
        assertTrue(otherUserHomeActivity.isVisibleRequested());
    }

    private void startRecentsActivity() {
        startRecentsActivity(mRecentsComponent, false /* getRecentsAnimation */);
    }

    /**
     * @return non-null {@link RecentsAnimationCallbacks} if the given {@code getRecentsAnimation}
     *         is {@code true}.
     */
    private RecentsAnimationCallbacks startRecentsActivity(ComponentName recentsComponent,
            boolean getRecentsAnimation) {
        RecentsAnimationCallbacks[] recentsAnimation = { null };
        if (getRecentsAnimation) {
            doAnswer(invocation -> {
                // The callback is actually RecentsAnimation.
                recentsAnimation[0] = invocation.getArgument(2);
                return null;
            }).when(mAtm.mWindowManager).initializeRecentsAnimation(
                    anyInt() /* targetActivityType */, any() /* recentsAnimationRunner */,
                    any() /* callbacks */, anyInt() /* displayId */, any() /* recentTaskIds */,
                    any() /* targetActivity */);
        }

        Intent recentsIntent = new Intent();
        recentsIntent.setComponent(recentsComponent);
        mAtm.startRecentsActivity(recentsIntent, 0 /* eventTime */,
                mock(IRecentsAnimationRunner.class));
        return recentsAnimation[0];
    }
}
