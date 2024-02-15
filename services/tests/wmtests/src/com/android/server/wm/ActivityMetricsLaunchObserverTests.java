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

import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.content.ComponentName.createRelative;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;

import android.app.ActivityOptions;
import android.app.ActivityOptions.SourceInfo;
import android.app.WaitResult;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.Log;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * Tests for the {@link ActivityMetricsLaunchObserver} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityMetricsLaunchObserverTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityMetricsLaunchObserverTests extends WindowTestsBase {
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
    private ActivityMetricsLogger mActivityMetricsLogger;
    private ActivityMetricsLogger.LaunchingState mLaunchingState;
    private ActivityMetricsLaunchObserver mLaunchObserver;

    private ActivityRecord mTrampolineActivity;
    private ActivityRecord mTopActivity;
    private ActivityOptions mActivityOptions;
    private Transition mTransition;
    private boolean mLaunchTopByTrampoline;
    private boolean mNewActivityCreated = true;
    private long mExpectedStartedId;
    private final ArrayMap<ComponentName, Long> mLastLaunchedIds = new ArrayMap<>();

    @Before
    public void setUpAMLO() {
        mLaunchObserver = mock(ActivityMetricsLaunchObserver.class);

        // ActivityTaskSupervisor always creates its own instance of ActivityMetricsLogger.
        mActivityMetricsLogger = mSupervisor.getActivityMetricsLogger();
        mActivityMetricsLogger.getLaunchObserverRegistry().registerLaunchObserver(mLaunchObserver);

        // Sometimes we need an ActivityRecord for ActivityMetricsLogger to do anything useful.
        // This seems to be the easiest way to create an ActivityRecord.
        mTrampolineActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setComponent(createRelative(DEFAULT_COMPONENT_PACKAGE_NAME, "TrampolineActivity"))
                .build();
        mTopActivity = new ActivityBuilder(mAtm)
                .setTask(mTrampolineActivity.getTask())
                .setComponent(createRelative(DEFAULT_COMPONENT_PACKAGE_NAME, "TopActivity"))
                .build();
        mTopActivity.mDisplayContent.mOpeningApps.add(mTopActivity);
        mTransition = new Transition(TRANSIT_OPEN, 0 /* flags */,
                mTopActivity.mTransitionController, createTestBLASTSyncEngine());
        mTransition.mParticipants.add(mTopActivity);
        mTopActivity.mTransitionController.moveToCollecting(mTransition);
        // becomes invisible when covered by mTopActivity
        mTrampolineActivity.setVisibleRequested(false);
    }

    private <T> T verifyAsync(T mock) {
        // With WindowTestRunner, all test methods are inside WM lock, so we have to unblock any
        // messages that are waiting for the lock.
        waitHandlerIdle(mAtm.mH);
        // AMLO callbacks happen on a separate thread than AML calls, so we need to use a timeout.
        return verify(mock, timeout(TIMEOUT_MS));
    }

    private void verifyOnActivityLaunched(ActivityRecord activity) {
        final ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verifyAsync(mLaunchObserver).onActivityLaunched(idCaptor.capture(),
                eq(activity.mActivityComponent), anyInt(), anyInt());
        final long id = idCaptor.getValue();
        setExpectedStartedId(id, activity);
        mLastLaunchedIds.put(activity.mActivityComponent, id);
    }

    private void verifyOnActivityLaunchFinished(ActivityRecord activity) {
        verifyAsync(mLaunchObserver).onActivityLaunchFinished(eq(mExpectedStartedId),
                eq(activity.mActivityComponent), anyLong(), anyInt());
    }

    private void setExpectedStartedId(long id, Object reason) {
        mExpectedStartedId = id;
        Log.i("AMLTest", "setExpectedStartedId=" + id + " from " + reason);
    }

    private void setLastExpectedStartedId(ActivityRecord r) {
        setExpectedStartedId(getLastStartedId(r), r);
    }

    private long getLastStartedId(ActivityRecord r) {
        final Long id = mLastLaunchedIds.get(r.mActivityComponent);
        return id != null ? id : -1;
    }

    private long eqLastStartedId(ActivityRecord r) {
        return eq(getLastStartedId(r));
    }

    private long onIntentStarted(Intent intent) {
        notifyActivityLaunching(intent);

        long timestamp = -1;
        // If it is launching top activity from trampoline activity, the observer shouldn't receive
        // onActivityLaunched because the activities should belong to the same transition.
        if (!mLaunchTopByTrampoline) {
            final ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
            verifyAsync(mLaunchObserver).onIntentStarted(eq(intent), captor.capture());
            timestamp = captor.getValue();
        }
        verifyNoMoreInteractions(mLaunchObserver);
        return timestamp;
    }

    @Test
    public void testOnIntentFailed() {
        final long id = onIntentStarted(new Intent("testOnIntentFailed"));

        // Bringing an intent that's already running 'to front' is not considered
        // as an ACTIVITY_LAUNCHED state transition.
        notifyActivityLaunched(START_TASK_TO_FRONT, null /* launchedActivity */);

        verifyAsync(mLaunchObserver).onIntentFailed(eq(id));
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testLaunchState() {
        final ToIntFunction<Runnable> launchTemplate = action -> {
            clearInvocations(mLaunchObserver);
            onActivityLaunched(mTopActivity);
            notifyTransitionStarting(mTopActivity);
            if (action != null) {
                action.run();
            }
            final ActivityMetricsLogger.TransitionInfoSnapshot info =
                    notifyWindowsDrawn(mTopActivity);
            verifyOnActivityLaunchFinished(mTopActivity);
            return info.getLaunchState();
        };

        final WindowProcessController app = mTopActivity.app;
        // Assume that the process is started (ActivityBuilder has mocked the returned value of
        // ATMS#getProcessController) but the activity has not attached process.
        mTopActivity.app = null;
        assertWithMessage("Warm launch").that(launchTemplate.applyAsInt(null))
                .isEqualTo(WaitResult.LAUNCH_STATE_WARM);

        mTopActivity.app = app;
        mNewActivityCreated = false;
        assertWithMessage("Hot launch").that(launchTemplate.applyAsInt(null))
                .isEqualTo(WaitResult.LAUNCH_STATE_HOT);

        assertWithMessage("Relaunch").that(launchTemplate.applyAsInt(
                () -> mActivityMetricsLogger.notifyActivityRelaunched(mTopActivity)))
                .isEqualTo(WaitResult.LAUNCH_STATE_RELAUNCH);

        assertWithMessage("Cold launch by restart").that(launchTemplate.applyAsInt(
                () -> mActivityMetricsLogger.notifyBindApplication(
                        mTopActivity.info.applicationInfo)))
                .isEqualTo(WaitResult.LAUNCH_STATE_COLD);

        mTopActivity.app = null;
        mNewActivityCreated = true;
        doReturn(null).when(mAtm).getProcessController(app.mName, app.mUid);
        assertWithMessage("Cold launch").that(launchTemplate.applyAsInt(null))
                .isEqualTo(WaitResult.LAUNCH_STATE_COLD);
    }

    private void onActivityLaunched(ActivityRecord activity) {
        onIntentStarted(activity.intent);
        notifyAndVerifyActivityLaunched(activity);

        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchFinished() {
        onActivityLaunched(mTopActivity);

        notifyTransitionStarting(mTopActivity);
        notifyWindowsDrawn(mTopActivity);

        verifyOnActivityLaunchFinished(mTopActivity);
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelled_hasDrawn() {
        onActivityLaunched(mTopActivity);

        mTopActivity.setVisibleRequested(true);
        doReturn(true).when(mTopActivity).isReportedDrawn();

        // Cannot time already-visible activities.
        notifyActivityLaunched(START_TASK_TO_FRONT, mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqLastStartedId(mTopActivity));
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelled_finishedBeforeDrawn() {
        doReturn(true).when(mTopActivity).isReportedDrawn();

        // Create an activity with different process that meets process switch.
        final ActivityRecord noDrawnActivity = new ActivityBuilder(mAtm)
                .setTask(mTopActivity.getTask())
                .setProcessName("other")
                .build();

        notifyActivityLaunching(noDrawnActivity.intent);
        notifyAndVerifyActivityLaunched(noDrawnActivity);

        noDrawnActivity.setVisibleRequested(false);
        mActivityMetricsLogger.notifyVisibilityChanged(noDrawnActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqLastStartedId(noDrawnActivity));

        // If an activity is removed immediately before visibility update, it should cancel too.
        final ActivityRecord removedImm = new ActivityBuilder(mAtm).setCreateTask(true).build();
        clearInvocations(mLaunchObserver);
        onActivityLaunched(removedImm);
        removedImm.removeImmediately();
        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqLastStartedId(removedImm));
    }

    @Test
    public void testOnActivityLaunchWhileSleeping() {
        notifyActivityLaunching(mTrampolineActivity.intent);
        notifyAndVerifyActivityLaunched(mTrampolineActivity);
        doReturn(true).when(mTrampolineActivity.mDisplayContent).isSleeping();
        mTrampolineActivity.setState(ActivityRecord.State.RESUMED, "test");
        mTrampolineActivity.setVisibility(false);
        waitHandlerIdle(mAtm.mH);
        // Not cancel immediately because in one of real cases, the keyguard may be going away or
        // occluded later, then the activity can be drawn.
        verify(mLaunchObserver, never()).onActivityLaunchCancelled(
                eqLastStartedId(mTrampolineActivity));

        clearInvocations(mLaunchObserver);
        mLaunchTopByTrampoline = true;
        mTopActivity.setVisibleRequested(false);
        notifyActivityLaunching(mTopActivity.intent);
        // It should schedule a message with UNKNOWN_VISIBILITY_CHECK_DELAY_MS to check whether
        // the launch event is still valid.
        notifyActivityLaunched(START_SUCCESS, mTopActivity);

        // The posted message will acquire wm lock, so the test needs to release the lock to verify.
        final Throwable error = awaitInWmLock(() -> {
            try {
                verify(mLaunchObserver, timeout(TIMEOUT_MS)).onActivityLaunchCancelled(
                        mExpectedStartedId);
            } catch (Throwable e) {
                // Catch any errors including assertion because this runs in another thread.
                return e;
            }
            return null;
        });
        // The launch event must be cancelled because the activity keeps invisible.
        if (error != null) {
            throw new AssertionError(error);
        }
    }

    @Test
    public void testOnReportFullyDrawn() {
        // Create an invisible event that should be cancelled after the next event starts.
        final ActivityRecord prev = new ActivityBuilder(mAtm).setCreateTask(true).build();
        onActivityLaunched(prev);
        prev.setVisibleRequested(false);

        mActivityOptions = ActivityOptions.makeBasic();
        mActivityOptions.setSourceInfo(SourceInfo.TYPE_LAUNCHER, SystemClock.uptimeMillis() - 10);
        onIntentStarted(mTopActivity.intent);
        notifyAndVerifyActivityLaunched(mTopActivity);
        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eq(getLastStartedId(prev)));

        // The activity reports fully drawn before windows drawn, then the fully drawn event will
        // be pending (see {@link WindowingModeTransitionInfo#pendingFullyDrawn}).
        mActivityMetricsLogger.notifyFullyDrawn(mTopActivity, false /* restoredFromBundle */);
        notifyTransitionStarting(mTopActivity);
        // The pending fully drawn event should send when the actual windows drawn event occurs.
        final ActivityMetricsLogger.TransitionInfoSnapshot info = notifyWindowsDrawn(mTopActivity);
        assertWithMessage("Record start source").that(info.sourceType)
                .isEqualTo(SourceInfo.TYPE_LAUNCHER);
        assertWithMessage("Record event time").that(info.sourceEventDelayMs).isAtLeast(10);

        verifyAsync(mLaunchObserver).onReportFullyDrawn(eq(mExpectedStartedId), anyLong());
        verifyOnActivityLaunchFinished(mTopActivity);
        verifyNoMoreInteractions(mLaunchObserver);

        final ActivityMetricsLogger.TransitionInfoSnapshot fullyDrawnInfo = mActivityMetricsLogger
                .notifyFullyDrawn(mTopActivity, false /* restoredFromBundle */);
        assertWithMessage("Invisible event must be dropped").that(fullyDrawnInfo).isNull();
    }

    private void onActivityLaunchedTrampoline() {
        onIntentStarted(mTrampolineActivity.intent);
        notifyAndVerifyActivityLaunched(mTrampolineActivity);

        // A second, distinct, activity launch is coalesced into the current app launch sequence.
        mLaunchTopByTrampoline = true;
        onIntentStarted(mTopActivity.intent);
        notifyActivityLaunched(START_SUCCESS, mTopActivity);

        // The observer shouldn't receive onActivityLaunched for an existing transition.
        verifyNoMoreInteractions(mLaunchObserver);
    }

    private void notifyActivityLaunching(Intent intent) {
        final ActivityMetricsLogger.LaunchingState previousState = mLaunchingState;
        mLaunchingState = mActivityMetricsLogger.notifyActivityLaunching(intent,
                mLaunchTopByTrampoline ? mTrampolineActivity : null /* caller */,
                mLaunchTopByTrampoline ? mTrampolineActivity.getUid() : 0);
        if (mLaunchTopByTrampoline) {
            // The transition of TrampolineActivity has not been completed, so when the next
            // activity is starting from it, the same launching state should be returned.
            assertWithMessage("Use existing launching state for a caller in active transition")
                    .that(previousState).isEqualTo(mLaunchingState);
        }
    }

    private void notifyActivityLaunched(int resultCode, ActivityRecord activity) {
        mActivityMetricsLogger.notifyActivityLaunched(mLaunchingState, resultCode,
                mNewActivityCreated, activity, mActivityOptions);
    }

    private void notifyAndVerifyActivityLaunched(ActivityRecord activity) {
        notifyActivityLaunched(START_SUCCESS, activity);
        verifyOnActivityLaunched(activity);
    }

    private void notifyTransitionStarting(ActivityRecord activity) {
        final ArrayMap<WindowContainer, Integer> reasons = new ArrayMap<>();
        reasons.put(activity, ActivityTaskManagerInternal.APP_TRANSITION_SPLASH_SCREEN);
        mActivityMetricsLogger.notifyTransitionStarting(reasons);
    }

    private ActivityMetricsLogger.TransitionInfoSnapshot notifyWindowsDrawn(ActivityRecord r) {
        return mActivityMetricsLogger.notifyWindowsDrawn(r);
    }

    @Test
    public void testInTaskActivityStart() {
        mTrampolineActivity.setVisible(true);
        doReturn(true).when(mTrampolineActivity).isReportedDrawn();
        spyOn(mActivityMetricsLogger);

        onActivityLaunched(mTopActivity);
        transitToDrawnAndVerifyOnLaunchFinished(mTopActivity);

        verify(mActivityMetricsLogger, timeout(TIMEOUT_MS)).logInTaskActivityStart(
                any(), anyBoolean(), anyInt());
    }

    @Test
    public void testOnActivityLaunchFinishedTrampoline() {
        onActivityLaunchedTrampoline();

        notifyTransitionStarting(mTopActivity);
        notifyWindowsDrawn(mTrampolineActivity);

        assertWithMessage("Trampoline activity is drawn but the top activity is not yet")
                .that(mLaunchingState.allDrawn()).isFalse();

        notifyWindowsDrawn(mTopActivity);

        verifyOnActivityLaunchFinished(mTopActivity);
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testDoNotCountInvisibleActivityToBeDrawn() {
        onActivityLaunchedTrampoline();
        mTrampolineActivity.setVisibility(false);
        notifyWindowsDrawn(mTopActivity);

        assertWithMessage("Trampoline activity is invisible so there should be no undrawn windows")
                .that(mLaunchingState.allDrawn()).isTrue();

        // Since the activity is drawn, the launch event should be reported.
        notifyTransitionStarting(mTopActivity);
        verifyOnActivityLaunchFinished(mTopActivity);
        mLaunchTopByTrampoline = false;
        clearInvocations(mLaunchObserver);

        // Another round without setting visibility of the trampoline activity.
        onActivityLaunchedTrampoline();
        mTrampolineActivity.setState(ActivityRecord.State.PAUSING, "test");
        notifyWindowsDrawn(mTopActivity);
        // If the transition can start, the invisible activities should be discarded and the launch
        // event be reported successfully.
        notifyTransitionStarting(mTopActivity);
        verifyOnActivityLaunchFinished(mTopActivity);
    }

    @Test
    public void testOnActivityLaunchCancelledTrampoline() {
        onActivityLaunchedTrampoline();

        doReturn(true).when(mTopActivity).isReportedDrawn();

        // Cannot time already-visible activities.
        notifyActivityLaunched(START_TASK_TO_FRONT, mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(mExpectedStartedId);
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testActivityDrawnBeforeTransition() {
        mTopActivity.setVisible(false);
        onIntentStarted(mTopActivity.intent);
        // Assume the activity is launched the second time consecutively. The drawn event is from
        // the first time (omitted in test) launch that is earlier than transition.
        doReturn(true).when(mTopActivity).isReportedDrawn();
        notifyWindowsDrawn(mTopActivity);
        verifyNoMoreInteractions(mLaunchObserver);

        notifyActivityLaunched(START_SUCCESS, mTopActivity);
        // If the launching activity was drawn when starting transition, the launch event should
        // be reported successfully.
        notifyTransitionStarting(mTopActivity);

        verifyOnActivityLaunched(mTopActivity);
        verifyOnActivityLaunchFinished(mTopActivity);
    }

    @Test
    public void testActivityDrawnWithoutTransition() {
        mTopActivity.mDisplayContent.mOpeningApps.remove(mTopActivity);
        mTransition.mParticipants.remove(mTopActivity);
        onIntentStarted(mTopActivity.intent);
        notifyAndVerifyActivityLaunched(mTopActivity);
        notifyWindowsDrawn(mTopActivity);
        // Even if there is no notifyTransitionStarting, the launch event can still be reported
        // because the drawn activity is not involved in transition.
        verifyOnActivityLaunchFinished(mTopActivity);
    }

    @Test
    public void testConcurrentLaunches() {
        onActivityLaunched(mTopActivity);
        clearInvocations(mLaunchObserver);
        final ActivityMetricsLogger.LaunchingState previousState = mLaunchingState;

        final ActivityRecord otherActivity = new ActivityBuilder(mAtm)
                .setComponent(createRelative(DEFAULT_COMPONENT_PACKAGE_NAME, "OtherActivity"))
                .setCreateTask(true)
                .build();
        // Assume the calling uid is different from the uid of TopActivity, so a new launching
        // state should be created here.
        onActivityLaunched(otherActivity);

        assertWithMessage("Different callers should get 2 independent launching states")
                .that(previousState).isNotEqualTo(mLaunchingState);
        setLastExpectedStartedId(otherActivity);
        transitToDrawnAndVerifyOnLaunchFinished(otherActivity);

        // The first transition should still be valid.
        setLastExpectedStartedId(mTopActivity);
        transitToDrawnAndVerifyOnLaunchFinished(mTopActivity);
    }

    @Test
    public void testConsecutiveLaunch() {
        onActivityLaunched(mTrampolineActivity);
        mActivityMetricsLogger.notifyActivityLaunching(mTopActivity.intent,
                mTrampolineActivity /* caller */, mTrampolineActivity.getUid());

        // Simulate a corner case that the trampoline activity is removed by CLEAR_TASK.
        // The 2 launch events can still be coalesced to one by matching the uid.
        mTrampolineActivity.takeFromHistory();
        assertNull(mTrampolineActivity.getTask());

        notifyActivityLaunched(START_SUCCESS, mTopActivity);
        transitToDrawnAndVerifyOnLaunchFinished(mTopActivity);
    }

    @Test
    public void testConsecutiveLaunchNewTask() {
        final IBinder launchCookie = mock(IBinder.class);
        final WindowContainerToken launchRootTask = mock(WindowContainerToken.class);
        mTrampolineActivity.noDisplay = true;
        mTrampolineActivity.mLaunchCookie = launchCookie;
        mTrampolineActivity.mLaunchRootTask = launchRootTask;
        onActivityLaunched(mTrampolineActivity);
        final ActivityRecord activityOnNewTask = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .build();
        mActivityMetricsLogger.notifyActivityLaunching(activityOnNewTask.intent,
                mTrampolineActivity /* caller */, mTrampolineActivity.getUid());
        notifyActivityLaunched(START_SUCCESS, activityOnNewTask);

        transitToDrawnAndVerifyOnLaunchFinished(activityOnNewTask);
        assertWithMessage("Trampoline's cookie must be transferred").that(
                mTrampolineActivity.mLaunchCookie).isNull();
        assertWithMessage("The last launch task has the transferred cookie").that(
                activityOnNewTask.mLaunchCookie).isEqualTo(launchCookie);
        assertWithMessage("Trampoline's launch root task must be transferred").that(
                mTrampolineActivity.mLaunchRootTask).isNull();
        assertWithMessage("The last launch task has the transferred launch root task").that(
                activityOnNewTask.mLaunchRootTask).isEqualTo(launchRootTask);
    }

    @Test
    public void testConsecutiveLaunchOnDifferentDisplay() {
        onActivityLaunched(mTopActivity);

        final Task stack = new TaskBuilder(mSupervisor)
                .setDisplay(addNewDisplayContentAt(DisplayContent.POSITION_BOTTOM))
                .build();
        final ActivityRecord activityOnNewDisplay = new ActivityBuilder(mAtm)
                .setTask(stack)
                .setProcessName("new")
                .build();

        // Before TopActivity is drawn, it launches another activity on a different display.
        mActivityMetricsLogger.notifyActivityLaunching(activityOnNewDisplay.intent,
                mTopActivity /* caller */, mTopActivity.getUid());
        notifyAndVerifyActivityLaunched(activityOnNewDisplay);

        // There should be 2 events instead of coalescing as one event.
        setLastExpectedStartedId(mTopActivity);
        transitToDrawnAndVerifyOnLaunchFinished(mTopActivity);
        setLastExpectedStartedId(activityOnNewDisplay);
        transitToDrawnAndVerifyOnLaunchFinished(activityOnNewDisplay);

        assertWithMessage("The launching state must not include the separated launch")
                .that(mLaunchingState.contains(activityOnNewDisplay)).isFalse();
    }

    @Test
    public void testConsecutiveLaunchWithDifferentWindowingMode() {
        mTopActivity.setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
        mTrampolineActivity.setVisibleRequested(true);
        onActivityLaunched(mTrampolineActivity);
        mActivityMetricsLogger.notifyActivityLaunching(mTopActivity.intent,
                mTrampolineActivity /* caller */, mTrampolineActivity.getUid());
        notifyAndVerifyActivityLaunched(mTopActivity);
        // Different windowing modes should be independent launch events.
        setLastExpectedStartedId(mTrampolineActivity);
        transitToDrawnAndVerifyOnLaunchFinished(mTrampolineActivity);
        setLastExpectedStartedId(mTopActivity);
        transitToDrawnAndVerifyOnLaunchFinished(mTopActivity);
    }

    private void transitToDrawnAndVerifyOnLaunchFinished(ActivityRecord activity) {
        notifyTransitionStarting(activity);
        notifyWindowsDrawn(activity);

        verifyOnActivityLaunchFinished(activity);
    }
}
