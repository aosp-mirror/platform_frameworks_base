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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.Intent;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.SparseIntArray;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link ActivityMetricsLaunchObserver} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityMetricsLaunchObserverTests
 */
@SmallTest
@Presubmit
@FlakyTest(detail="promote once confirmed non-flaky")
public class ActivityMetricsLaunchObserverTests extends ActivityTestsBase {
    private ActivityMetricsLogger mActivityMetricsLogger;
    private ActivityMetricsLaunchObserver mLaunchObserver;

    private TestActivityStack mStack;
    private TaskRecord mTask;
    private ActivityRecord mActivityRecord;
    private ActivityRecord mActivityRecordTrampoline;

    @Before
    public void setUpAMLO() throws Exception {
        setupActivityTaskManagerService();

        mActivityMetricsLogger =
                new ActivityMetricsLogger(mSupervisor, mService.mContext, mService.mH.getLooper());

        mLaunchObserver = mock(ActivityMetricsLaunchObserver.class);

        // TODO: Use ActivityMetricsLaunchObserverRegistry .
        java.lang.reflect.Field f =
                mActivityMetricsLogger.getClass().getDeclaredField("mLaunchObserver");
        f.setAccessible(true);
        f.set(mActivityMetricsLogger, mLaunchObserver);

        // Sometimes we need an ActivityRecord for ActivityMetricsLogger to do anything useful.
        // This seems to be the easiest way to create an ActivityRecord.
        mStack = mSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        mTask = new TaskBuilder(mSupervisor).setStack(mStack).build();
        mActivityRecord = new ActivityBuilder(mService).setTask(mTask).build();
        mActivityRecordTrampoline = new ActivityBuilder(mService).setTask(mTask).build();
    }

    @Test
    public void testOnIntentStarted() throws Exception {
        Intent intent = new Intent("action 1");

        mActivityMetricsLogger.notifyActivityLaunching(intent);

        verify(mLaunchObserver).onIntentStarted(eq(intent));
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

        verify(mLaunchObserver).onIntentFailed();
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunched() throws Exception {
        testOnIntentStarted();

        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS,
                mActivityRecord);

        verify(mLaunchObserver).onActivityLaunched(eq(mActivityRecord), anyInt());
        verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchFinished() throws Exception {
       testOnActivityLaunched();

       mActivityMetricsLogger.notifyTransitionStarting(new SparseIntArray(),
               SystemClock.uptimeMillis());

       mActivityMetricsLogger.notifyWindowsDrawn(mActivityRecord.getWindowingMode(),
               SystemClock.uptimeMillis());

       verify(mLaunchObserver).onActivityLaunchFinished(eq(mActivityRecord));
       verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelled() throws Exception {
       testOnActivityLaunched();

       mActivityRecord.nowVisible = true;

       // Cannot time already-visible activities.
       mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT, mActivityRecord);

       verify(mLaunchObserver).onActivityLaunchCancelled(eq(mActivityRecord));
       verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchedTrampoline() throws Exception {
        testOnIntentStarted();

        mActivityMetricsLogger.notifyActivityLaunched(START_SUCCESS,
                mActivityRecord);

        verify(mLaunchObserver).onActivityLaunched(eq(mActivityRecord), anyInt());

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

       verify(mLaunchObserver).onActivityLaunchFinished(eq(mActivityRecordTrampoline));
       verifyNoMoreInteractions(mLaunchObserver);
    }

    @Test
    public void testOnActivityLaunchCancelledTrampoline() throws Exception {
       testOnActivityLaunchedTrampoline();

       mActivityRecordTrampoline.nowVisible = true;

       // Cannot time already-visible activities.
       mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT,
               mActivityRecordTrampoline);

       verify(mLaunchObserver).onActivityLaunchCancelled(eq(mActivityRecordTrampoline));
       verifyNoMoreInteractions(mLaunchObserver);
    }
}
