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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyInt;
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

    private TestActivityStack mStack;
    private TaskRecord mTask;
    private ActivityRecord mActivityRecord;
    private ActivityRecord mActivityRecordTrampoline;

    @Before
    public void setUpAMLO() throws Exception {
        mLaunchObserver = mock(ActivityMetricsLaunchObserver.class);

        // ActivityStackSupervisor always creates its own instance of ActivityMetricsLogger.
        mActivityMetricsLogger = mSupervisor.getActivityMetricsLogger();

        mLaunchObserverRegistry = mActivityMetricsLogger.getLaunchObserverRegistry();
        mLaunchObserverRegistry.registerLaunchObserver(mLaunchObserver);

        // Sometimes we need an ActivityRecord for ActivityMetricsLogger to do anything useful.
        // This seems to be the easiest way to create an ActivityRecord.
        mStack = mRootActivityContainer.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mTask = new TaskBuilder(mSupervisor).setStack(mStack).build();
        mActivityRecord = new ActivityBuilder(mService).setTask(mTask).build();
        mActivityRecordTrampoline = new ActivityBuilder(mService).setTask(mTask).build();
    }

    @After
    public void tearDownAMLO() throws Exception {
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
        return verify(mock, timeout(100));
    }

    @Test
    public void testOnIntentStarted() throws Exception {
        Intent intent = new Intent("action 1");

        mActivityMetricsLogger.notifyActivityLaunching(intent);

        verifyAsync(mLaunchObserver).onIntentStarted(eq(intent));
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnIntentFailed() throws Exception {
        testOnIntentStarted();

        ActivityRecord activityRecord = null;

        // Bringing an intent that's already running 'to front' is not considered
        // as an ACTIVITY_LAUNCHED state transition.
        mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT,
                activityRecord);

        verifyAsync(mLaunchObserver).onIntentFailed();
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunched() throws Exception {
        testOnIntentStarted();

        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS,
                mActivityRecord);

        verifyAsync(mLaunchObserver).onActivityLaunched(eqProto(mActivityRecord), anyInt());
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchFinished() throws Exception {
       testOnActivityLaunched();

       mActivityMetricsLogger.notifyTransitionStarting(new SparseIntArray(),
               SystemClock.uptimeMillis());

       mActivityMetricsLogger.notifyWindowsDrawn(mActivityRecord.getWindowingMode(),
               SystemClock.uptimeMillis());

       verifyAsync(mLaunchObserver).onActivityLaunchFinished(eqProto(mActivityRecord));
       verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelled() throws Exception {
       testOnActivityLaunched();

       mActivityRecord.mDrawn = true;

       // Cannot time already-visible activities.
       mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT, mActivityRecord);

       verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(mActivityRecord));
       verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchedTrampoline() throws Exception {
        testOnIntentStarted();

        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS,
                mActivityRecord);

        verifyAsync(mLaunchObserver).onActivityLaunched(eqProto(mActivityRecord), anyInt());

        // A second, distinct, activity launch is coalesced into the the current app launch sequence
        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS,
                mActivityRecordTrampoline);

        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchFinishedTrampoline() throws Exception {
       testOnActivityLaunchedTrampoline();

       mActivityMetricsLogger.notifyTransitionStarting(new SparseIntArray(),
               SystemClock.uptimeMillis());

       mActivityMetricsLogger.notifyWindowsDrawn(mActivityRecordTrampoline.getWindowingMode(),
               SystemClock.uptimeMillis());

       verifyAsync(mLaunchObserver).onActivityLaunchFinished(eqProto(mActivityRecordTrampoline));
       verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelledTrampoline() throws Exception {
       testOnActivityLaunchedTrampoline();

       mActivityRecordTrampoline.mDrawn = true;

       // Cannot time already-visible activities.
       mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT,
               mActivityRecordTrampoline);

       verifyAsync(mLaunchObserver).onActivityLaunchCancelled(eqProto(mActivityRecordTrampoline));
       verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testActivityRecordProtoIsNotTooBig() throws Exception {
        // The ActivityRecordProto must not be too big, otherwise converting it at runtime
        // will become prohibitively expensive.
        assertWithMessage("mActivityRecord: %s", mActivityRecord).
                that(activityRecordToProto(mActivityRecord).length).
                isAtMost(ActivityMetricsLogger.LAUNCH_OBSERVER_ACTIVITY_RECORD_PROTO_CHUNK_SIZE);

        assertWithMessage("mActivityRecordTrampoline: %s", mActivityRecordTrampoline).
                that(activityRecordToProto(mActivityRecordTrampoline).length).
                isAtMost(ActivityMetricsLogger.LAUNCH_OBSERVER_ACTIVITY_RECORD_PROTO_CHUNK_SIZE);
    }
}
