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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;

import android.content.Intent;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.SparseIntArray;

import androidx.test.filters.SmallTest;

import com.android.server.wm.ActivityMetricsLaunchObserver.ActivityRecordProto;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
public class ActivityMetricsLaunchObserverTests extends ActivityTestsBase {
    private ActivityMetricsLogger mActivityMetricsLogger;
    private ActivityMetricsLaunchObserver mLaunchObserver;
    private ActivityMetricsLaunchObserverRegistry mLaunchObserverRegistry;

    private ActivityRecord mTrampolineActivity;
    private ActivityRecord mTopActivity;

    @Before
    public void setUpAMLO() {
        mLaunchObserver = mock(ActivityMetricsLaunchObserver.class);

        // ActivityStackSupervisor always creates its own instance of ActivityMetricsLogger.
        mActivityMetricsLogger = mSupervisor.getActivityMetricsLogger();

        mLaunchObserverRegistry = mActivityMetricsLogger.getLaunchObserverRegistry();
        mLaunchObserverRegistry.registerLaunchObserver(mLaunchObserver);

        // Sometimes we need an ActivityRecord for ActivityMetricsLogger to do anything useful.
        // This seems to be the easiest way to create an ActivityRecord.
        mTrampolineActivity = new ActivityBuilder(mService).setCreateTask(true).build();
        mTopActivity = new ActivityBuilder(mService)
                .setTask(mTrampolineActivity.getTaskRecord())
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

    static <T> T verifyAsync(T mock) {
        // AMLO callbacks happen on a separate thread than AML calls, so we need to use a timeout.
        return verify(mock, timeout(TimeUnit.SECONDS.toMillis(5)));
    }

    private void onIntentStarted() {
        Intent intent = new Intent("action 1");

        mActivityMetricsLogger.notifyActivityLaunching(intent);

        verifyAsync(mLaunchObserver).onIntentStarted(eq(intent), anyLong());
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnIntentFailed() {
        onIntentStarted();

        // Bringing an intent that's already running 'to front' is not considered
        // as an ACTIVITY_LAUNCHED state transition.
        mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT,
                null /* launchedActivity */);

        verifyAsync(mLaunchObserver).onIntentFailed();
        verifyNoMoreInteractions(mLaunchObserver);
    }

    private void onActivityLaunched() {
        onIntentStarted();

        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS, mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunched(eqProto(mTopActivity), anyInt());
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchFinished() {
        onActivityLaunched();

        notifyTransitionStarting();
        notifyWindowsDrawn(mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchFinished(eqProto(mTopActivity), anyLong());
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelled_hasDrawn() {
        onActivityLaunched();

        mTopActivity.visible = mTopActivity.mDrawn = true;

        // Cannot time already-visible activities.
        mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT, mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(mTopActivity));
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelled_finishedBeforeDrawn() {
        mTopActivity.visible = mTopActivity.mDrawn = true;

        // Suppress resume when creating the record because we want to notify logger manually.
        mSupervisor.beginDeferResume();
        // Create an activity with different process that meets process switch.
        final ActivityRecord noDrawnActivity = new ActivityBuilder(mService)
                .setTask(mTopActivity.getTaskRecord())
                .setProcessName("other")
                .build();
        mSupervisor.readyToResume();

        mActivityMetricsLogger.notifyActivityLaunching(noDrawnActivity.intent);
        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS, noDrawnActivity);

        noDrawnActivity.destroyIfPossible("test");
        mActivityMetricsLogger.notifyVisibilityChanged(noDrawnActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(noDrawnActivity));
    }

    @Test
    public void testOnReportFullyDrawn() {
        onActivityLaunched();

        mActivityMetricsLogger.logAppTransitionReportedDrawn(mTopActivity, false);

        verifyAsync(mLaunchObserver).onReportFullyDrawn(eqProto(mTopActivity), anyLong());
        verifyNoMoreInteractions(mLaunchObserver);
    }

    private void onActivityLaunchedTrampoline() {
        onIntentStarted();

        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS, mTrampolineActivity);

        verifyAsync(mLaunchObserver).onActivityLaunched(eqProto(mTrampolineActivity), anyInt());

        // A second, distinct, activity launch is coalesced into the current app launch sequence.
        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS, mTopActivity);

        verifyNoMoreInteractions(mLaunchObserver);
    }

    private void notifyTransitionStarting() {
        mActivityMetricsLogger.notifyTransitionStarting(new SparseIntArray(),
                SystemClock.elapsedRealtimeNanos());
    }

    private void notifyWindowsDrawn(ActivityRecord r) {
        mActivityMetricsLogger.notifyWindowsDrawn(r.getWindowingMode(),
                SystemClock.elapsedRealtimeNanos());
    }

    @Test
    public void testOnActivityLaunchFinishedTrampoline() {
        onActivityLaunchedTrampoline();

        notifyTransitionStarting();
        notifyWindowsDrawn(mTrampolineActivity);

        notifyWindowsDrawn(mTopActivity);

        verifyAsync(mLaunchObserver).onActivityLaunchFinished(eqProto(mTopActivity), anyLong());
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelledTrampoline() {
        onActivityLaunchedTrampoline();

        mTopActivity.mDrawn = true;

        // Cannot time already-visible activities.
        mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT, mTopActivity);

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
}
