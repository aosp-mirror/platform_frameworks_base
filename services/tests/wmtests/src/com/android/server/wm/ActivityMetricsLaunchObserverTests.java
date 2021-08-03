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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;

import android.app.ActivityOptions;
import android.app.ActivityOptions.SourceInfo;
import android.app.WaitResult;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;

import com.android.server.wm.ActivityMetricsLaunchObserver.ActivityRecordProto;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

import java.util.Arrays;
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
    private ActivityMetricsLaunchObserverRegistry mLaunchObserverRegistry;

    private ActivityRecord mTrampolineActivity;
    private ActivityRecord mTopActivity;
    private ActivityOptions mActivityOptions;
    private boolean mLaunchTopByTrampoline;
    private boolean mNewActivityCreated = true;

    @Before
    public void setUpAMLO() {
        mLaunchObserver = mock(ActivityMetricsLaunchObserver.class);

        // ActivityTaskSupervisor always creates its own instance of ActivityMetricsLogger.
        mActivityMetricsLogger = mSupervisor.getActivityMetricsLogger();

        mLaunchObserverRegistry = mActivityMetricsLogger.getLaunchObserverRegistry();
        mLaunchObserverRegistry.registerLaunchObserver(mLaunchObserver);

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
        // becomes invisible when covered by mTopActivity
        mTrampolineActivity.mVisibleRequested = false;
    }

    @After
    public void tearDownAMLO() {
        if (mLaunchObserverRegistry != null) {  // Don't NPE if setUp failed.
            mLaunchObserverRegistry.unregisterLaunchObserver(mLaunchObserver);
        }
    }

    static class ActivityRecordMatcher implements ArgumentMatcher</*@ActivityRecordProto*/ byte[]> {
        private final @ActivityRecordProto byte[] mExpected;

        public ActivityRecordMatcher(ActivityRecord activityRecord) {
            mExpected = activityRecordToProto(activityRecord);
        }

        public boolean matches(@ActivityRecordProto byte[] actual) {
            return Arrays.equals(mExpected, actual);
        }
    }

    static @ActivityRecordProto byte[] activityRecordToProto(ActivityRecord record) {
        return ActivityMetricsLogger.convertActivityRecordToProto(record);
    }

    static @ActivityRecordProto byte[] eqProto(ActivityRecord record) {
        return argThat(new ActivityRecordMatcher(record));
    }

    private <T> T verifyAsync(T mock) {
        // With WindowTestRunner, all test methods are inside WM lock, so we have to unblock any
        // messages that are waiting for the lock.
        waitHandlerIdle(mAtm.mH);
        // AMLO callbacks happen on a separate thread than AML calls, so we need to use a timeout.
        return verify(mock, timeout(TIMEOUT_MS));
    }

    private void verifyOnActivityLaunchFinished(ActivityRecord activity) {
        verifyAsync(mLaunchObserver).onActivityLaunchFinished(eqProto(activity), anyLong());
    }

    private void onIntentStarted(Intent intent) {
        notifyActivityLaunching(intent);

        // If it is launching top activity from trampoline activity, the observer shouldn't receive
        // onActivityLaunched because the activities should belong to the same transition.
        if (!mLaunchTopByTrampoline) {
            verifyAsync(mLaunchObserver).onIntentStarted(eq(intent), anyLong());
        }
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnIntentFailed() {
        onIntentStarted(new Intent("testOnIntentFailed"));

        // Bringing an intent that's already running 'to front' is not considered
        // as an ACTIVITY_LAUNCHED state transition.
        notifyActivityLaunched(START_TASK_TO_FRONT, null /* launchedActivity */);

        verifyAsync(mLaunchObserver).onIntentFailed();
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testLaunchState() {
        final ToIntFunction<Boolean> launchTemplate = doRelaunch -> {
            clearInvocations(mLaunchObserver);
            onActivityLaunched(mTopActivity);
            notifyTransitionStarting(mTopActivity);
            if (doRelaunch) {
                mActivityMetricsLogger.notifyActivityRelaunched(mTopActivity);
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
        assertWithMessage("Warm launch").that(launchTemplate.applyAsInt(false /* doRelaunch */))
                .isEqualTo(WaitResult.LAUNCH_STATE_WARM);

        mTopActivity.app = app;
        mNewActivityCreated = false;
        assertWithMessage("Hot launch").that(launchTemplate.applyAsInt(false /* doRelaunch */))
                .isEqualTo(WaitResult.LAUNCH_STATE_HOT);

        assertWithMessage("Relaunch").that(launchTemplate.applyAsInt(true /* doRelaunch */))
                .isEqualTo(WaitResult.LAUNCH_STATE_RELAUNCH);

        mTopActivity.app = null;
        mNewActivityCreated = true;
        doReturn(null).when(mAtm).getProcessController(app.mName, app.mUid);
        assertWithMessage("Cold launch").that(launchTemplate.applyAsInt(false /* doRelaunch */))
                .isEqualTo(WaitResult.LAUNCH_STATE_COLD);
    }

    private void onActivityLaunched(ActivityRecord activity) {
        onIntentStarted(activity.intent);
        notifyActivityLaunched(START_SUCCESS, activity);

        verifyAsync(mLaunchObserver).onActivityLaunched(eqProto(activity), anyInt());
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

        mTopActivity.mVisibleRequested = true;
        doReturn(true).when(mTopActivity).isReportedDrawn();

        // Cannot time already-visible activities.
        notifyActivityLaunched(START_TASK_TO_FRONT, mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(mTopActivity));
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
        notifyActivityLaunched(START_SUCCESS, noDrawnActivity);

        noDrawnActivity.mVisibleRequested = false;
        mActivityMetricsLogger.notifyVisibilityChanged(noDrawnActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(noDrawnActivity));
    }

    @Test
    public void testOnActivityLaunchWhileSleeping() {
        notifyActivityLaunching(mTrampolineActivity.intent);
        notifyActivityLaunched(START_SUCCESS, mTrampolineActivity);
        doReturn(true).when(mTrampolineActivity.mDisplayContent).isSleeping();
        mTrampolineActivity.setState(ActivityRecord.State.RESUMED, "test");
        mTrampolineActivity.setVisibility(false);
        waitHandlerIdle(mAtm.mH);
        // Not cancel immediately because in one of real cases, the keyguard may be going away or
        // occluded later, then the activity can be drawn.
        verify(mLaunchObserver, never()).onActivityLaunchCancelled(eqProto(mTrampolineActivity));

        clearInvocations(mLaunchObserver);
        mLaunchTopByTrampoline = true;
        mTopActivity.mVisibleRequested = false;
        notifyActivityLaunching(mTopActivity.intent);
        // It should schedule a message with UNKNOWN_VISIBILITY_CHECK_DELAY_MS to check whether
        // the launch event is still valid.
        notifyActivityLaunched(START_SUCCESS, mTopActivity);

        // The posted message will acquire wm lock, so the test needs to release the lock to verify.
        final Throwable error = awaitInWmLock(() -> {
            try {
                // Though the aborting target should be eqProto(mTopActivity), use any() to avoid
                // any changes in proto that may cause failure by different arguments.
                verify(mLaunchObserver, timeout(TIMEOUT_MS)).onActivityLaunchCancelled(any());
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
        onActivityLaunched(mTrampolineActivity);
        mTrampolineActivity.mVisibleRequested = false;

        mActivityOptions = ActivityOptions.makeBasic();
        mActivityOptions.setSourceInfo(SourceInfo.TYPE_LAUNCHER, SystemClock.uptimeMillis() - 10);
        onIntentStarted(mTopActivity.intent);
        notifyActivityLaunched(START_SUCCESS, mTopActivity);
        verifyAsync(mLaunchObserver).onActivityLaunched(eqProto(mTopActivity), anyInt());
        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(mTrampolineActivity));

        // The activity reports fully drawn before windows drawn, then the fully drawn event will
        // be pending (see {@link WindowingModeTransitionInfo#pendingFullyDrawn}).
        mActivityMetricsLogger.logAppTransitionReportedDrawn(mTopActivity, false);
        notifyTransitionStarting(mTopActivity);
        // The pending fully drawn event should send when the actual windows drawn event occurs.
        final ActivityMetricsLogger.TransitionInfoSnapshot info = notifyWindowsDrawn(mTopActivity);
        assertWithMessage("Record start source").that(info.sourceType)
                .isEqualTo(SourceInfo.TYPE_LAUNCHER);
        assertWithMessage("Record event time").that(info.sourceEventDelayMs).isAtLeast(10);

        verifyAsync(mLaunchObserver).onReportFullyDrawn(eqProto(mTopActivity), anyLong());
        verifyOnActivityLaunchFinished(mTopActivity);
        verifyNoMoreInteractions(mLaunchObserver);

        final ActivityMetricsLogger.TransitionInfoSnapshot fullyDrawnInfo = mActivityMetricsLogger
                .logAppTransitionReportedDrawn(mTopActivity, false /* restoredFromBundle */);
        assertWithMessage("Invisible event must be dropped").that(fullyDrawnInfo).isNull();
    }

    private void onActivityLaunchedTrampoline() {
        onIntentStarted(mTrampolineActivity.intent);
        notifyActivityLaunched(START_SUCCESS, mTrampolineActivity);

        verifyAsync(mLaunchObserver).onActivityLaunched(eqProto(mTrampolineActivity), anyInt());

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

    private void notifyTransitionStarting(ActivityRecord activity) {
        final ArrayMap<WindowContainer, Integer> reasons = new ArrayMap<>();
        reasons.put(activity, ActivityTaskManagerInternal.APP_TRANSITION_SPLASH_SCREEN);
        mActivityMetricsLogger.notifyTransitionStarting(reasons);
    }

    private ActivityMetricsLogger.TransitionInfoSnapshot notifyWindowsDrawn(ActivityRecord r) {
        return mActivityMetricsLogger.notifyWindowsDrawn(r, SystemClock.elapsedRealtimeNanos());
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

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(mTopActivity));
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testActivityDrawnBeforeTransition() {
        mTopActivity.setVisible(false);
        notifyActivityLaunching(mTopActivity.intent);
        // Assume the activity is launched the second time consecutively. The drawn event is from
        // the first time (omitted in test) launch that is earlier than transition.
        doReturn(true).when(mTopActivity).isReportedDrawn();
        notifyWindowsDrawn(mTopActivity);
        notifyActivityLaunched(START_SUCCESS, mTopActivity);
        // If the launching activity was drawn when starting transition, the launch event should
        // be reported successfully.
        notifyTransitionStarting(mTopActivity);

        verifyOnActivityLaunchFinished(mTopActivity);
    }

    @Test
    public void testActivityRecordProtoIsNotTooBig() {
        // The ActivityRecordProto must not be too big, otherwise converting it at runtime
        // will become prohibitively expensive.
        assertWithMessage("mTopActivity: %s", mTopActivity)
                .that(activityRecordToProto(mTopActivity).length)
                .isAtMost(ActivityMetricsLogger.LAUNCH_OBSERVER_ACTIVITY_RECORD_PROTO_CHUNK_SIZE);

        assertWithMessage("mTrampolineActivity: %s", mTrampolineActivity)
                .that(activityRecordToProto(mTrampolineActivity).length)
                .isAtMost(ActivityMetricsLogger.LAUNCH_OBSERVER_ACTIVITY_RECORD_PROTO_CHUNK_SIZE);
    }

    @Test
    public void testConcurrentLaunches() {
        onActivityLaunched(mTopActivity);
        final ActivityMetricsLogger.LaunchingState previousState = mLaunchingState;

        final ActivityRecord otherActivity = new ActivityBuilder(mAtm)
                .setComponent(createRelative(DEFAULT_COMPONENT_PACKAGE_NAME, "OtherActivity"))
                .setCreateTask(true)
                .build();
        // Assume the calling uid is different from the uid of TopActivity, so a new launching
        // state should be created here.
        onActivityLaunched(otherActivity);

        assertWithMessage("Different callers should get 2 indepedent launching states")
                .that(previousState).isNotEqualTo(mLaunchingState);
        transitToDrawnAndVerifyOnLaunchFinished(otherActivity);

        // The first transition should still be valid.
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
        notifyActivityLaunched(START_SUCCESS, activityOnNewDisplay);

        // There should be 2 events instead of coalescing as one event.
        transitToDrawnAndVerifyOnLaunchFinished(mTopActivity);
        transitToDrawnAndVerifyOnLaunchFinished(activityOnNewDisplay);
    }

    @Test
    public void testConsecutiveLaunchWithDifferentWindowingMode() {
        mTopActivity.setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
        mTrampolineActivity.mVisibleRequested = true;
        onActivityLaunched(mTrampolineActivity);
        mActivityMetricsLogger.notifyActivityLaunching(mTopActivity.intent,
                mTrampolineActivity /* caller */, mTrampolineActivity.getUid());
        notifyActivityLaunched(START_SUCCESS, mTopActivity);
        // Different windowing modes should be independent launch events.
        transitToDrawnAndVerifyOnLaunchFinished(mTrampolineActivity);
        transitToDrawnAndVerifyOnLaunchFinished(mTopActivity);
    }

    private void transitToDrawnAndVerifyOnLaunchFinished(ActivityRecord activity) {
        notifyTransitionStarting(activity);
        notifyWindowsDrawn(activity);

        verifyOnActivityLaunchFinished(activity);
    }
}
