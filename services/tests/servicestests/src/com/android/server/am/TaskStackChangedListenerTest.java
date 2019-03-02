/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.am;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskDescription;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.app.Instrumentation.ActivityMonitor;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.RemoteException;
import android.support.test.uiautomator.UiDevice;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.annotations.GuardedBy;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@MediumTest
@RunWith(AndroidJUnit4.class)
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
        mService.unregisterTaskStackListener(mTaskStackListener);
        mTaskStackListener = null;
    }

    @Test
    public void testTaskStackChanged_afterFinish() throws Exception {
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onTaskStackChanged() throws RemoteException {
                synchronized (sLock) {
                    sTaskStackChangedCalled = true;
                }
            }
        });

        Context ctx = InstrumentationRegistry.getContext();
        ctx.startActivity(new Intent(ctx, ActivityA.class));
        UiDevice.getInstance(getInstrumentation()).waitForIdle();
        synchronized (sLock) {
            Assert.assertTrue(sTaskStackChangedCalled);
        }
        Assert.assertTrue(sActivityBResumed);
    }

    @Test
    public void testTaskDescriptionChanged() throws Exception {
        final Object[] params = new Object[2];
        final CountDownLatch latch = new CountDownLatch(1);
        registerTaskStackChangedListener(new TaskStackListener() {
            int taskId = -1;

            @Override
            public void onTaskCreated(int taskId, ComponentName componentName)
                    throws RemoteException {
                this.taskId = taskId;
            }
            @Override
            public void onTaskDescriptionChanged(int taskId, TaskDescription td)
                    throws RemoteException {
                if (this.taskId == taskId && !TextUtils.isEmpty(td.getLabel())) {
                    params[0] = taskId;
                    params[1] = td;
                    latch.countDown();
                }
            }
        });
        final Activity activity = startTestActivity(ActivityTaskDescriptionChange.class);
        waitForCallback(latch);
        assertEquals(activity.getTaskId(), params[0]);
        assertEquals("Test Label", ((TaskDescription) params[1]).getLabel());
    }

    @Test
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
        final Activity activity = startTestActivity(ActivityRequestedOrientationChange.class);
        waitForCallback(latch);
        assertEquals(activity.getTaskId(), params[0]);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, params[1]);
    }

    @Test
    /**
     * Tests for onTaskCreated, onTaskMovedToFront, onTaskRemoved and onTaskRemovalStarted.
     */
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
        final int id = activity.getTaskId();

        // Test for onTaskCreated.
        waitForCallback(taskCreatedLaunchLatch);
        assertEquals(id, params[0]);
        ComponentName componentName = (ComponentName) params[1];
        assertEquals(ActivityTaskChangeCallbacks.class.getName(), componentName.getClassName());

        // Test for onTaskMovedToFront.
        assertEquals(1, taskMovedToFrontLatch.getCount());
        mService.moveTaskToFront(id, 0, null);
        waitForCallback(taskMovedToFrontLatch);
        assertEquals(activity.getTaskId(), params[0]);

        // Test for onTaskRemovalStarted.
        assertEquals(1, taskRemovalStartedLatch.getCount());
        activity.finishAndRemoveTask();
        waitForCallback(taskRemovalStartedLatch);
        // onTaskRemovalStarted happens before the activity's window is removed.
        assertFalse(activity.onDetachedFromWindowCalled);
        assertEquals(id, params[0]);

        // Test for onTaskRemoved.
        assertEquals(1, taskRemovedLatch.getCount());
        waitForCallback(taskRemovedLatch);
        assertEquals(id, params[0]);
        assertTrue(activity.onDetachedFromWindowCalled);
    }

    /**
     * Starts the provided activity and returns the started instance.
     */
    private Activity startTestActivity(Class<?> activityClass) {
        final Context context = InstrumentationRegistry.getContext();
        final ActivityMonitor monitor =
                new ActivityMonitor(activityClass.getName(), null, false);
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor);
        context.startActivity(new Intent(context, activityClass));
        final Activity activity = monitor.waitForActivityWithTimeout(1000);
        if (activity == null) {
            throw new RuntimeException("Timed out waiting for Activity");
        }
        return activity;
    }

    private void registerTaskStackChangedListener(ITaskStackListener listener) throws Exception {
        mTaskStackListener = listener;
        mService.registerTaskStackListener(listener);
    }

    private void waitForCallback(CountDownLatch latch) {
        try {
        final boolean result = latch.await(2, TimeUnit.SECONDS);
            if (!result) {
                throw new RuntimeException("Timed out waiting for task stack change notification");
            }
        }catch (InterruptedException e) {}
    }

    public static class ActivityA extends Activity {

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

    public static class ActivityB extends Activity {

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

    public static class ActivityRequestedOrientationChange extends Activity {
        @Override
        protected void onPostResume() {
            super.onPostResume();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            finish();
        }
    }

    public static class ActivityTaskDescriptionChange extends Activity {
        @Override
        protected void onPostResume() {
            super.onPostResume();
            setTaskDescription(new TaskDescription("Test Label"));
            finish();
        }
    }

    public static class ActivityTaskChangeCallbacks extends Activity {
        public boolean onDetachedFromWindowCalled = false;;

        @Override
        public void onDetachedFromWindow() {
            onDetachedFromWindowCalled = true;
        }
    }
}
