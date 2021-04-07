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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;

import android.app.WaitResult;
import android.content.Intent;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;

import com.android.server.wm.ActivityMetricsLaunchObserver.ActivityRecordProto;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Tests for the {@link ActivityMetricsLaunchObserver} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityMetricsLaunchObserverTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityMetricsLaunchObserverTests extends ActivityTestsBase {
    private ActivityMetricsLogger mActivityMetricsLogger;
    private ActivityMetricsLogger.LaunchingState mLaunchingState;
    private ActivityMetricsLaunchObserver mLaunchObserver;
    private ActivityMetricsLaunchObserverRegistry mLaunchObserverRegistry;

    private ActivityRecord mTrampolineActivity;
    private ActivityRecord mTopActivity;
    private boolean mLaunchTopByTrampoline;

    @Before
    public void setUpAMLO() {
        mLaunchObserver = mock(ActivityMetricsLaunchObserver.class);

        // ActivityStackSupervisor always creates its own instance of ActivityMetricsLogger.
        mActivityMetricsLogger = mSupervisor.getActivityMetricsLogger();

        mLaunchObserverRegistry = mActivityMetricsLogger.getLaunchObserverRegistry();
        mLaunchObserverRegistry.registerLaunchObserver(mLaunchObserver);

        // Sometimes we need an ActivityRecord for ActivityMetricsLogger to do anything useful.
        // This seems to be the easiest way to create an ActivityRecord.
        mTrampolineActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .setComponent(createRelative(DEFAULT_COMPONENT_PACKAGE_NAME, "TrampolineActivity"))
                .build();
        mTopActivity = new ActivityBuilder(mService)
                .setTask(mTrampolineActivity.getTask())
                .setComponent(createRelative(DEFAULT_COMPONENT_PACKAGE_NAME, "TopActivity"))
                .build();
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
        waitHandlerIdle(mService.mH);
        // AMLO callbacks happen on a separate thread than AML calls, so we need to use a timeout.
        return verify(mock, timeout(TimeUnit.SECONDS.toMillis(5)));
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

    private void onActivityLaunched(ActivityRecord activity) {
        onIntentStarted(activity.intent);
        notifyActivityLaunched(START_SUCCESS, activity);

        verifyAsync(mLaunchObserver).onActivityLaunched(eqProto(activity), anyInt());
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchFinished() {
        // Assume that the process is started (ActivityBuilder has mocked the returned value of
        // ATMS#getProcessController) but the activity has not attached process.
        mTopActivity.app = null;
        onActivityLaunched(mTopActivity);

        notifyTransitionStarting(mTopActivity);
        final ActivityMetricsLogger.TransitionInfoSnapshot info = notifyWindowsDrawn(mTopActivity);
        assertWithMessage("Warm launch").that(info.getLaunchState())
                .isEqualTo(WaitResult.LAUNCH_STATE_WARM);

        verifyOnActivityLaunchFinished(mTopActivity);
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelled_hasDrawn() {
        onActivityLaunched(mTopActivity);

        mTopActivity.mVisibleRequested = mTopActivity.mDrawn = true;

        // Cannot time already-visible activities.
        notifyActivityLaunched(START_TASK_TO_FRONT, mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(mTopActivity));
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelled_finishedBeforeDrawn() {
        mTopActivity.mVisibleRequested = mTopActivity.mDrawn = true;

        // Suppress resume when creating the record because we want to notify logger manually.
        mSupervisor.beginDeferResume();
        // Create an activity with different process that meets process switch.
        final ActivityRecord noDrawnActivity = new ActivityBuilder(mService)
                .setTask(mTopActivity.getTask())
                .setProcessName("other")
                .build();
        mSupervisor.readyToResume();

        notifyActivityLaunching(noDrawnActivity.intent);
        notifyActivityLaunched(START_SUCCESS, noDrawnActivity);

        noDrawnActivity.mVisibleRequested = false;
        mActivityMetricsLogger.notifyVisibilityChanged(noDrawnActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(noDrawnActivity));
    }

    @Test
    public void testOnReportFullyDrawn() {
        onActivityLaunched(mTopActivity);

        // The activity reports fully drawn before windows drawn, then the fully drawn event will
        // be pending (see {@link WindowingModeTransitionInfo#pendingFullyDrawn}).
        mActivityMetricsLogger.logAppTransitionReportedDrawn(mTopActivity, false);
        notifyTransitionStarting(mTopActivity);
        // The pending fully drawn event should send when the actual windows drawn event occurs.
        final ActivityMetricsLogger.TransitionInfoSnapshot info = notifyWindowsDrawn(mTopActivity);
        assertWithMessage("Hot launch").that(info.getLaunchState())
                .isEqualTo(WaitResult.LAUNCH_STATE_HOT);

        verifyAsync(mLaunchObserver).onReportFullyDrawn(eqProto(mTopActivity), anyLong());
        verifyOnActivityLaunchFinished(mTopActivity);
        verifyNoMoreInteractions(mLaunchObserver);
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
                mLaunchTopByTrampoline ? mTrampolineActivity : null /* caller */);
        if (mLaunchTopByTrampoline) {
            // The transition of TrampolineActivity has not been completed, so when the next
            // activity is starting from it, the same launching state should be returned.
            assertWithMessage("Use existing launching state for a caller in active transition")
                    .that(previousState).isEqualTo(mLaunchingState);
        }
    }

    private void notifyActivityLaunched(int resultCode, ActivityRecord activity) {
        mActivityMetricsLogger.notifyActivityLaunched(mLaunchingState, resultCode, activity);
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

        assertWithMessage("Trampoline activity is invisble so there should be no undrawn windows")
                .that(mLaunchingState.allDrawn()).isTrue();
    }

    @Test
    public void testOnActivityLaunchCancelledTrampoline() {
        onActivityLaunchedTrampoline();

        mTopActivity.mDrawn = true;

        // Cannot time already-visible activities.
        notifyActivityLaunched(START_TASK_TO_FRONT, mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(mTopActivity));
        verifyNoMoreInteractions(mLaunchObserver);
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

        final ActivityRecord otherActivity = new ActivityBuilder(mService)
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
    public void testConsecutiveLaunchOnDifferentDisplay() {
        onActivityLaunched(mTopActivity);

        final ActivityStack stack = new StackBuilder(mRootWindowContainer)
                .setDisplay(addNewDisplayContentAt(DisplayContent.POSITION_BOTTOM))
                .setCreateActivity(false)
                .build();
        final ActivityRecord activityOnNewDisplay = new ActivityBuilder(mService)
                .setStack(stack)
                .setCreateTask(true)
                .setProcessName("new")
                .build();

        // Before TopActivity is drawn, it launches another activity on a different display.
        mActivityMetricsLogger.notifyActivityLaunching(activityOnNewDisplay.intent,
                mTopActivity /* caller */);
        notifyActivityLaunched(START_SUCCESS, activityOnNewDisplay);

        // There should be 2 events instead of coalescing as one event.
        transitToDrawnAndVerifyOnLaunchFinished(mTopActivity);
        transitToDrawnAndVerifyOnLaunchFinished(activityOnNewDisplay);
    }

    private void transitToDrawnAndVerifyOnLaunchFinished(ActivityRecord activity) {
        notifyTransitionStarting(activity);
        notifyWindowsDrawn(activity);

        verifyOnActivityLaunchFinished(activity);
    }
}
