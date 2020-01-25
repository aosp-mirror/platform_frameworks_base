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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityTaskManager;
import android.app.ActivityView;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.support.test.uiautomator.UiDevice;
import android.text.TextUtils;
import android.view.ViewGroup;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;

import com.android.internal.annotations.GuardedBy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *  atest WmTests:TaskStackChangedListenerTest
 */
@MediumTest
public class TaskStackChangedListenerTest {

    private IActivityManager mService;
    private ITaskStackListener mTaskStackListener;

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static boolean sTaskStackChangedCalled;
    private static boolean sActivityBResumed;

    @Before
    public void setUp() throws Exception {
        mService = ActivityManager.getService();
    }

    @After
    public void tearDown() throws Exception {
        ActivityTaskManager.getService().unregisterTaskStackListener(mTaskStackListener);
        mTaskStackListener = null;
    }

    @Test
    @Presubmit
    @FlakyTest(bugId = 130388819)
    public void testTaskStackChanged_afterFinish() throws Exception {
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onTaskStackChanged() throws RemoteException {
                synchronized (sLock) {
                    sTaskStackChangedCalled = true;
                }
            }
        });

        Context context = getInstrumentation().getContext();
        context.startActivity(
                new Intent(context, ActivityA.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UiDevice.getInstance(getInstrumentation()).waitForIdle();
        synchronized (sLock) {
            assertTrue(sTaskStackChangedCalled);
        }
        assertTrue(sActivityBResumed);
    }

    @Test
    @Presubmit
    public void testTaskDescriptionChanged() throws Exception {
        final Object[] params = new Object[2];
        final CountDownLatch latch = new CountDownLatch(1);
        registerTaskStackChangedListener(new TaskStackListener() {
            int mTaskId = -1;

            @Override
            public void onTaskCreated(int taskId, ComponentName componentName)
                    throws RemoteException {
                mTaskId = taskId;
            }
            @Override
            public void onTaskDescriptionChanged(int taskId, TaskDescription td)
                    throws RemoteException {
                if (mTaskId == taskId && !TextUtils.isEmpty(td.getLabel())) {
                    params[0] = taskId;
                    params[1] = td;
                    latch.countDown();
                }
            }
        });

        int taskId;
        synchronized (sLock) {
            taskId = startTestActivity(ActivityTaskDescriptionChange.class).getTaskId();
        }
        waitForCallback(latch);
        assertEquals(taskId, params[0]);
        assertEquals("Test Label", ((TaskDescription) params[1]).getLabel());
    }

    @Test
    @Presubmit
    public void testActivityRequestedOrientationChanged() throws Exception {
        final int[] params = new int[2];
        final CountDownLatch latch = new CountDownLatch(1);
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onActivityRequestedOrientationChanged(int taskId,
                    int requestedOrientation) {
                params[0] = taskId;
                params[1] = requestedOrientation;
                latch.countDown();
            }
        });
        int taskId;
        synchronized (sLock) {
            taskId = startTestActivity(ActivityRequestedOrientationChange.class).getTaskId();
        }
        waitForCallback(latch);
        assertEquals(taskId, params[0]);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, params[1]);
    }

    /**
     * Tests for onTaskCreated, onTaskMovedToFront, onTaskRemoved and onTaskRemovalStarted.
     */
    @Test
    @Presubmit
    @FlakyTest(bugId = 130388819)
    public void testTaskChangeCallBacks() throws Exception {
        final Object[] params = new Object[2];
        final CountDownLatch taskCreatedLaunchLatch = new CountDownLatch(1);
        final CountDownLatch taskMovedToFrontLatch = new CountDownLatch(1);
        final CountDownLatch taskRemovedLatch = new CountDownLatch(1);
        final CountDownLatch taskRemovalStartedLatch = new CountDownLatch(1);
        final CountDownLatch onDetachedFromWindowLatch = new CountDownLatch(1);
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onTaskCreated(int taskId, ComponentName componentName)
                    throws RemoteException {
                params[0] = taskId;
                params[1] = componentName;
                taskCreatedLaunchLatch.countDown();
            }

            @Override
            public void onTaskMovedToFront(int taskId) throws RemoteException {
                params[0] = taskId;
                taskMovedToFrontLatch.countDown();
            }

            @Override
            public void onTaskRemovalStarted(int taskId) {
                params[0] = taskId;
                taskRemovalStartedLatch.countDown();
            }

            @Override
            public void onTaskRemoved(int taskId) throws RemoteException {
                params[0] = taskId;
                taskRemovedLatch.countDown();
            }
        });

        final ActivityTaskChangeCallbacks activity =
                (ActivityTaskChangeCallbacks) startTestActivity(ActivityTaskChangeCallbacks.class);
        activity.setDetachedFromWindowLatch(onDetachedFromWindowLatch);
        final int id = activity.getTaskId();

        // Test for onTaskCreated.
        waitForCallback(taskCreatedLaunchLatch);
        assertEquals(id, params[0]);
        ComponentName componentName = (ComponentName) params[1];
        assertEquals(ActivityTaskChangeCallbacks.class.getName(), componentName.getClassName());

        // Test for onTaskMovedToFront.
        assertEquals(1, taskMovedToFrontLatch.getCount());
        mService.moveTaskToFront(null, getInstrumentation().getContext().getPackageName(), id, 0,
                null);
        waitForCallback(taskMovedToFrontLatch);
        assertEquals(activity.getTaskId(), params[0]);

        // Test for onTaskRemovalStarted.
        assertEquals(1, taskRemovalStartedLatch.getCount());
        assertEquals(1, taskRemovedLatch.getCount());
        activity.finishAndRemoveTask();
        waitForCallback(taskRemovalStartedLatch);
        // onTaskRemovalStarted happens before the activity's window is removed.
        assertFalse(activity.mOnDetachedFromWindowCalled);
        assertEquals(id, params[0]);

        // Test for onTaskRemoved.
        waitForCallback(taskRemovedLatch);
        assertEquals(id, params[0]);
        waitForCallback(onDetachedFromWindowLatch);
        assertTrue(activity.mOnDetachedFromWindowCalled);
    }

    @Test
    public void testTaskOnSingleTaskDisplayDrawn() throws Exception {
        final Instrumentation instrumentation = getInstrumentation();

        final CountDownLatch activityViewReadyLatch = new CountDownLatch(1);
        final CountDownLatch singleTaskDisplayDrawnLatch = new CountDownLatch(1);
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onSingleTaskDisplayDrawn(int displayId) throws RemoteException {
                singleTaskDisplayDrawnLatch.countDown();
            }
        });
        final ActivityViewTestActivity activity =
                (ActivityViewTestActivity) startTestActivity(ActivityViewTestActivity.class);
        final ActivityView activityView = activity.getActivityView();
        activityView.setCallback(new ActivityView.StateCallback() {
            @Override
            public void onActivityViewReady(ActivityView view) {
                activityViewReadyLatch.countDown();
            }

            @Override
            public void onActivityViewDestroyed(ActivityView view) {
            }
        });
        waitForCallback(activityViewReadyLatch);

        final Context context = instrumentation.getContext();
        Intent intent = new Intent(context, ActivityInActivityView.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        activityView.startActivity(intent);
        waitForCallback(singleTaskDisplayDrawnLatch);
    }

    @Test
    public void testSingleTaskDisplayEmpty() throws Exception {
        final Instrumentation instrumentation = getInstrumentation();

        final CountDownLatch activityViewReadyLatch = new CountDownLatch(1);
        final CountDownLatch activityViewDestroyedLatch = new CountDownLatch(1);
        final CountDownLatch singleTaskDisplayDrawnLatch = new CountDownLatch(1);
        final CountDownLatch singleTaskDisplayEmptyLatch = new CountDownLatch(1);

        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onSingleTaskDisplayDrawn(int displayId) throws RemoteException {
                singleTaskDisplayDrawnLatch.countDown();
            }
            @Override
            public void onSingleTaskDisplayEmpty(int displayId)
                    throws RemoteException {
                singleTaskDisplayEmptyLatch.countDown();
            }
        });
        final ActivityViewTestActivity activity =
                (ActivityViewTestActivity) startTestActivity(ActivityViewTestActivity.class);
        final ActivityView activityView = activity.getActivityView();
        activityView.setCallback(new ActivityView.StateCallback() {
            @Override
            public void onActivityViewReady(ActivityView view) {
                activityViewReadyLatch.countDown();
            }

            @Override
            public void onActivityViewDestroyed(ActivityView view) {
                activityViewDestroyedLatch.countDown();
            }
        });
        waitForCallback(activityViewReadyLatch);

        final Context context = instrumentation.getContext();
        Intent intent = new Intent(context, ActivityInActivityView.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        activityView.startActivity(intent);
        waitForCallback(singleTaskDisplayDrawnLatch);
        assertEquals(1, singleTaskDisplayEmptyLatch.getCount());

        activityView.release();
        waitForCallback(activityViewDestroyedLatch);
        waitForCallback(singleTaskDisplayEmptyLatch);
    }

    /**
     * Starts the provided activity and returns the started instance.
     */
    private TestActivity startTestActivity(Class<?> activityClass) throws InterruptedException {
        final ActivityMonitor monitor = new ActivityMonitor(activityClass.getName(), null, false);
        getInstrumentation().addMonitor(monitor);
        final Context context = getInstrumentation().getContext();
        context.startActivity(
                new Intent(context, activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final TestActivity activity = (TestActivity) monitor.waitForActivityWithTimeout(1000);
        if (activity == null) {
            throw new RuntimeException("Timed out waiting for Activity");
        }
        activity.waitForResumeStateChange(true);
        return activity;
    }

    private void registerTaskStackChangedListener(ITaskStackListener listener) throws Exception {
        mTaskStackListener = listener;
        ActivityTaskManager.getService().registerTaskStackListener(listener);
    }

    private void waitForCallback(CountDownLatch latch) {
        try {
            final boolean result = latch.await(4, TimeUnit.SECONDS);
            if (!result) {
                throw new RuntimeException("Timed out waiting for task stack change notification");
            }
        } catch (InterruptedException e) {
        }
    }

    public static class TestActivity extends Activity {
        boolean mIsResumed = false;

        @Override
        protected void onPostResume() {
            super.onPostResume();
            synchronized (this) {
                mIsResumed = true;
                notifyAll();
            }
        }

        @Override
        protected void onPause() {
            super.onPause();
            synchronized (this) {
                mIsResumed = false;
                notifyAll();
            }
        }

        /**
         * If isResumed is {@code true}, sleep the thread until the activity is resumed.
         * if {@code false}, sleep the thread until the activity is paused.
         */
        @SuppressWarnings("WaitNotInLoop")
        public void waitForResumeStateChange(boolean isResumed) throws InterruptedException {
            synchronized (this) {
                if (mIsResumed == isResumed) {
                    return;
                }
                wait(5000);
            }
            assertEquals("The activity resume state change timed out", isResumed, mIsResumed);
        }
    }

    public static class ActivityA extends TestActivity {

        private boolean mActivityBLaunched = false;

        @Override
        protected void onPostResume() {
            super.onPostResume();
            if (mActivityBLaunched) {
                return;
            }
            mActivityBLaunched = true;
            finish();
            startActivity(new Intent(this, ActivityB.class));
        }
    }

    public static class ActivityB extends TestActivity {

        @Override
        protected void onPostResume() {
            super.onPostResume();
            synchronized (sLock) {
                sTaskStackChangedCalled = false;
            }
            sActivityBResumed = true;
            finish();
        }
    }

    public static class ActivityRequestedOrientationChange extends TestActivity {
        @Override
        protected void onPostResume() {
            super.onPostResume();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            synchronized (sLock) {
                // Hold the lock to ensure no one is trying to access fields of this Activity in
                // this test.
                finish();
            }
        }
    }

    public static class ActivityTaskDescriptionChange extends TestActivity {
        @Override
        protected void onPostResume() {
            super.onPostResume();
            setTaskDescription(new TaskDescription("Test Label"));
            synchronized (sLock) {
                // Hold the lock to ensure no one is trying to access fields of this Activity in
                // this test.
                finish();
            }
        }
    }

    public static class ActivityTaskChangeCallbacks extends TestActivity {
        public boolean mOnDetachedFromWindowCalled = false;
        private CountDownLatch mOnDetachedFromWindowCountDownLatch;

        @Override
        public void onDetachedFromWindow() {
            mOnDetachedFromWindowCalled = true;
            mOnDetachedFromWindowCountDownLatch.countDown();
        }

        void setDetachedFromWindowLatch(CountDownLatch countDownLatch) {
            mOnDetachedFromWindowCountDownLatch = countDownLatch;
        }
    }

    public static class ActivityViewTestActivity extends TestActivity {
        private ActivityView mActivityView;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            mActivityView = new ActivityView(this, null /* attrs */, 0 /* defStyle */,
                    true /* singleTaskInstance */);
            setContentView(mActivityView);

            ViewGroup.LayoutParams layoutParams = mActivityView.getLayoutParams();
            layoutParams.width = MATCH_PARENT;
            layoutParams.height = MATCH_PARENT;
            mActivityView.requestLayout();
        }

        ActivityView getActivityView() {
            return mActivityView;
        }
    }

    // Activity that has {@link android.R.attr#resizeableActivity} attribute set to {@code true}
    public static class ActivityInActivityView extends TestActivity {}
}
