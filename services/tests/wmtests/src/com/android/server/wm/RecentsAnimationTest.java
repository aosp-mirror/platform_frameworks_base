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
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;

import android.app.IApplicationThread;
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
public class RecentsAnimationTest extends ActivityTestsBase {

    private static final int TEST_USER_ID = 100;

    private final ComponentName mRecentsComponent =
            new ComponentName(mContext.getPackageName(), "RecentsActivity");
    private RecentsAnimationController mRecentsAnimationController;

    @Before
    public void setUp() throws Exception {
        mRecentsAnimationController = mock(RecentsAnimationController.class);
        mService.mWindowManager.setRecentsAnimationController(mRecentsAnimationController);
        doNothing().when(mService.mWindowManager).initializeRecentsAnimation(
                anyInt(), any(), any(), anyInt(), any(), any());
        doReturn(true).when(mService.mWindowManager).canStartRecentsAnimation();

        final RecentTasks recentTasks = mService.getRecentTasks();
        spyOn(recentTasks);
        doReturn(mRecentsComponent).when(recentTasks).getRecentsComponent();
    }

    @Test
    public void testRecentsActivityVisiblility() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        ActivityStack recentsStack = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        ActivityRecord recentActivity = new ActivityBuilder(mService)
                .setComponent(mRecentsComponent)
                .setCreateTask(true)
                .setStack(recentsStack)
                .build();
        ActivityRecord topActivity = new ActivityBuilder(mService).setCreateTask(true).build();
        topActivity.getRootTask().moveToFront("testRecentsActivityVisiblility");

        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyInt() /* configChanges */,
                anyBoolean() /* preserveWindows */, anyBoolean() /* notifyClients */);

        RecentsAnimationCallbacks recentsAnimation = startRecentsActivity(
                mRecentsComponent, true /* getRecentsAnimation */);
        // The launch-behind state should make the recents activity visible.
        assertTrue(recentActivity.mVisibleRequested);

        // Simulate the animation is cancelled without changing the stack order.
        recentsAnimation.onAnimationFinished(REORDER_KEEP_IN_PLACE, false /* sendUserLeaveHint */);
        // The non-top recents activity should be invisible by the restored launch-behind state.
        assertFalse(recentActivity.mVisibleRequested);
    }

    @Test
    public void testPreloadRecentsActivity() {
        TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final ActivityStack homeStack =
                defaultTaskDisplayArea.getStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        defaultTaskDisplayArea.positionStackAtTop(homeStack, false /* includingParents */);
        ActivityRecord topRunningHomeActivity = homeStack.topRunningActivity();
        if (topRunningHomeActivity == null) {
            topRunningHomeActivity = new ActivityBuilder(mService)
                    .setStack(homeStack)
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
        WindowProcessController wpc = new WindowProcessController(mService, aInfo.applicationInfo,
                aInfo.processName, aInfo.applicationInfo.uid, 0 /* userId */,
                mock(Object.class) /* owner */, mock(WindowProcessListener.class));
        wpc.setThread(mock(IApplicationThread.class));
        doReturn(wpc).when(mService).getProcessController(eq(wpc.mName), eq(wpc.mUid));

        Intent recentsIntent = new Intent().setComponent(mRecentsComponent);
        // Null animation indicates to preload.
        mService.startRecentsActivity(recentsIntent, null /* assistDataReceiver */,
                null /* recentsAnimationRunner */);

        ActivityStack recentsStack = defaultTaskDisplayArea.getStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS);
        assertThat(recentsStack).isNotNull();

        ActivityRecord recentsActivity = recentsStack.getTopNonFinishingActivity();
        // The activity is started in background so it should be invisible and will be stopped.
        assertThat(recentsActivity).isNotNull();
        assertThat(mSupervisor.mStoppingActivities).contains(recentsActivity);
        assertFalse(recentsActivity.mVisibleRequested);

        // Assume it is stopped to test next use case.
        recentsActivity.activityStopped(null /* newIcicle */, null /* newPersistentState */,
                null /* description */);
        mSupervisor.mStoppingActivities.remove(recentsActivity);

        spyOn(recentsActivity);
        // Start when the recents activity exists. It should ensure the configuration.
        mService.startRecentsActivity(recentsIntent, null /* assistDataReceiver */,
                null /* recentsAnimationRunner */);

        verify(recentsActivity).ensureActivityConfiguration(anyInt() /* globalChanges */,
                anyBoolean() /* preserveWindow */, eq(true) /* ignoreVisibility */);
        assertThat(mSupervisor.mStoppingActivities).contains(recentsActivity);
    }

    @Test
    public void testRestartRecentsActivity() throws Exception {
        // Have a recents activity that is not attached to its process (ActivityRecord.app = null).
        TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        ActivityStack recentsStack = defaultTaskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        ActivityRecord recentActivity = new ActivityBuilder(mService).setComponent(
                mRecentsComponent).setCreateTask(true).setStack(recentsStack).build();
        WindowProcessController app = recentActivity.app;
        recentActivity.app = null;

        // Start an activity on top.
        new ActivityBuilder(mService).setCreateTask(true).build().getRootTask().moveToFront(
                "testRestartRecentsActivity");

        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyInt() /* configChanges */,
                anyBoolean() /* preserveWindows */, anyBoolean() /* notifyClients */);
        doReturn(app).when(mService).getProcessController(eq(recentActivity.processName), anyInt());
        ClientLifecycleManager lifecycleManager = mService.getLifecycleManager();
        doNothing().when(lifecycleManager).scheduleTransaction(any());

        startRecentsActivity();

        // Recents activity must be restarted, but not be resumed while running recents animation.
        verify(mRootWindowContainer.mStackSupervisor).startSpecificActivity(
                eq(recentActivity), eq(false), anyBoolean());
        assertThat(recentActivity.getState()).isEqualTo(PAUSED);
    }

    @Test
    public void testSetLaunchTaskBehindOfTargetActivity() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        ActivityStack homeStack = taskDisplayArea.getRootHomeTask();
        // Assume the home activity support recents.
        ActivityRecord targetActivity = homeStack.getTopNonFinishingActivity();
        if (targetActivity == null) {
            targetActivity = new ActivityBuilder(mService)
                    .setCreateTask(true)
                    .setStack(homeStack)
                    .build();
        }

        // Put another home activity in home stack.
        ActivityRecord anotherHomeActivity = new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "Home2"))
                .setCreateTask(true)
                .setStack(homeStack)
                .build();
        // Start an activity on top so the recents activity can be started.
        new ActivityBuilder(mService)
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
        // The current top activity is not the recents so the animation should be canceled.
        verify(mService.mWindowManager, times(1)).cancelRecentsAnimation(
                eq(REORDER_KEEP_IN_PLACE), any() /* reason */);

        // The test uses mocked RecentsAnimationController so we have to invoke the callback
        // manually to simulate the flow.
        recentsAnimation.onAnimationFinished(REORDER_KEEP_IN_PLACE, false /* sendUserLeaveHint */);
        // We should restore the launch-behind of the original target activity.
        assertFalse(targetActivity.mLaunchTaskBehind);
    }

    @Test
    public void testCancelAnimationOnVisibleStackOrderChange() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        ActivityStack fullscreenStack = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setStack(fullscreenStack)
                .build();
        ActivityStack recentsStack = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(mRecentsComponent)
                .setCreateTask(true)
                .setStack(recentsStack)
                .build();
        ActivityStack fullscreenStack2 = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App2"))
                .setCreateTask(true)
                .setStack(fullscreenStack2)
                .build();

        // Start the recents animation
        startRecentsActivity();

        fullscreenStack.moveToFront("Activity start");

        // Ensure that the recents animation was canceled by cancelAnimationSynchronously().
        verify(mService.mWindowManager, times(1)).cancelRecentsAnimation(
                eq(REORDER_KEEP_IN_PLACE), any());

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
        ActivityStack fullscreenStack = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setStack(fullscreenStack)
                .build();
        ActivityStack recentsStack = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(mRecentsComponent)
                .setCreateTask(true)
                .setStack(recentsStack)
                .build();
        ActivityStack fullscreenStack2 = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App2"))
                .setCreateTask(true)
                .setStack(fullscreenStack2)
                .build();

        // Start the recents animation
        startRecentsActivity();

        fullscreenStack.removeIfPossible();

        // Ensure that the recents animation was NOT canceled
        verify(mService.mWindowManager, times(0)).cancelRecentsAnimation(
                eq(REORDER_KEEP_IN_PLACE), any());
        verify(mRecentsAnimationController, times(0)).setCancelOnNextTransitionStart();
    }

    @Test
    public void testMultipleUserHomeActivity_findUserHomeTask() {
        TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultDisplay()
                .getDefaultTaskDisplayArea();
        ActivityStack homeStack = taskDisplayArea.getStack(WINDOWING_MODE_UNDEFINED,
                ACTIVITY_TYPE_HOME);
        ActivityRecord otherUserHomeActivity = new ActivityBuilder(mService)
                .setStack(homeStack)
                .setCreateTask(true)
                .setComponent(new ComponentName(mContext.getPackageName(), "Home2"))
                .build();
        otherUserHomeActivity.getTask().mUserId = TEST_USER_ID;

        ActivityStack fullscreenStack = taskDisplayArea.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setStack(fullscreenStack)
                .build();

        doReturn(TEST_USER_ID).when(mService).getCurrentUserId();
        doCallRealMethod().when(mRootWindowContainer).ensureActivitiesVisible(
                any() /* starting */, anyInt() /* configChanges */,
                anyBoolean() /* preserveWindows */, anyBoolean() /* notifyClients */);

        startRecentsActivity(otherUserHomeActivity.getTask().getBaseIntent().getComponent(),
                true);

        // Ensure we find the task for the right user and it is made visible
        assertTrue(otherUserHomeActivity.mVisibleRequested);
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
            }).when(mService.mWindowManager).initializeRecentsAnimation(
                    anyInt() /* targetActivityType */, any() /* recentsAnimationRunner */,
                    any() /* callbacks */, anyInt() /* displayId */, any() /* recentTaskIds */,
                    any() /* targetActivity */);
        }

        Intent recentsIntent = new Intent();
        recentsIntent.setComponent(recentsComponent);
        mService.startRecentsActivity(recentsIntent, null /* assistDataReceiver */,
                mock(IRecentsAnimationRunner.class));
        return recentsAnimation[0];
    }
}
