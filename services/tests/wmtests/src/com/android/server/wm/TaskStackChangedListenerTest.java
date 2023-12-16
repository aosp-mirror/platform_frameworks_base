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

import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.os.Build.HW_TIMEOUT_MULTIPLIER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.wm.utils.CommonUtils.runWithShellPermissionIdentity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ITaskStackListener;
import android.app.Instrumentation.ActivityMonitor;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.text.TextUtils;
import android.view.Display;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.filters.MediumTest;

import com.android.server.wm.utils.CommonUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Build/Install/Run:
 *  atest WmTests:TaskStackChangedListenerTest
 */
@MediumTest
public class TaskStackChangedListenerTest {

    private ITaskStackListener mTaskStackListener;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private final ArrayList<Activity> mStartedActivities = new ArrayList<>();

    private static final int WAIT_TIMEOUT_MS = 5000 * HW_TIMEOUT_MULTIPLIER;
    private static final Object sLock = new Object();

    @Before
    public void setUp() {
        CommonUtils.dismissKeyguard();
    }

    @After
    public void tearDown() throws Exception {
        if (mTaskStackListener != null) {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskStackListener);
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mImageReader.close();
        }
        // Finish from bottom to top.
        final int size = mStartedActivities.size();
        for (int i = 0; i < size; i++) {
            final Activity activity = mStartedActivities.get(i);
            if (!activity.isFinishing()) {
                activity.finish();
            }
        }
        // Wait for the last launched activity to be removed.
        if (size > 0) {
            CommonUtils.waitUntilActivityRemoved(mStartedActivities.get(size - 1));
        }
        mStartedActivities.clear();
    }

    private VirtualDisplay createVirtualDisplay() {
        final int width = 800;
        final int height = 600;
        final int density = 160;
        final DisplayManager displayManager = getInstrumentation().getContext().getSystemService(
                DisplayManager.class);
        mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
                2 /* maxImages */);
        final int flags = VIRTUAL_DISPLAY_FLAG_PRESENTATION | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | VIRTUAL_DISPLAY_FLAG_PUBLIC;
        final String name = getClass().getSimpleName() + "_VirtualDisplay";
        mVirtualDisplay = displayManager.createVirtualDisplay(name, width, height, density,
                mImageReader.getSurface(), flags);
        mVirtualDisplay.setSurface(mImageReader.getSurface());
        assertNotNull("display must be registered",
                Arrays.stream(displayManager.getDisplays()).filter(
                        d -> d.getName().equals(name)).findAny());
        return mVirtualDisplay;
    }

    @Test
    @Presubmit
    public void testTaskStackChanged_afterFinish() throws Exception {
        final TestActivity activity = startTestActivity(ActivityA.class);
        final CountDownLatch latch = new CountDownLatch(1);
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onTaskStackChanged() throws RemoteException {
                latch.countDown();
            }
        });

        activity.finish();
        waitForCallback(latch);
    }

    @Test
    @Presubmit
    public void testTaskStackChanged_resumeWhilePausing() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onTaskStackChanged() throws RemoteException {
                latch.countDown();
            }
        });

        startTestActivity(ResumeWhilePausingActivity.class);
        waitForCallback(latch);
    }

    @Test
    @Presubmit
    public void testTaskDescriptionChanged() throws Exception {
        final Object[] params = new Object[2];
        final CountDownLatch latch = new CountDownLatch(2);
        registerTaskStackChangedListener(new TaskStackListener() {
            int mTaskId = -1;

            @Override
            public void onTaskCreated(int taskId, ComponentName componentName)
                    throws RemoteException {
                mTaskId = taskId;
            }
            @Override
            public void onTaskDescriptionChanged(RunningTaskInfo info) {
                if (mTaskId == info.taskId && !TextUtils.isEmpty(info.taskDescription.getLabel())) {
                    params[0] = info.taskId;
                    params[1] = info.taskDescription;
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
    public void testTaskChangeCallBacks() throws Exception {
        final CountDownLatch taskCreatedLaunchLatch = new CountDownLatch(1);
        final CountDownLatch taskMovedToFrontLatch = new CountDownLatch(1);
        final CountDownLatch taskRemovedLatch = new CountDownLatch(1);
        final CountDownLatch taskRemovalStartedLatch = new CountDownLatch(1);
        final int[] expectedTaskId = { -1 };
        final int[] receivedTaskId = { -1 };
        final ComponentName expectedName = new ComponentName(getInstrumentation().getContext(),
                ActivityTaskChangeCallbacks.class);
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onTaskCreated(int taskId, ComponentName componentName) {
                receivedTaskId[0] = taskId;
                if (expectedName.equals(componentName)) {
                    taskCreatedLaunchLatch.countDown();
                }
            }

            @Override
            public void onTaskMovedToFront(RunningTaskInfo info) {
                receivedTaskId[0] = info.taskId;
                taskMovedToFrontLatch.countDown();
            }

            @Override
            public void onTaskRemovalStarted(RunningTaskInfo info) {
                if (expectedTaskId[0] == info.taskId) {
                    taskRemovalStartedLatch.countDown();
                }
            }

            @Override
            public void onTaskRemoved(int taskId) {
                if (expectedTaskId[0] == taskId) {
                    taskRemovedLatch.countDown();
                }
            }
        });

        final ActivityTaskChangeCallbacks activity =
                (ActivityTaskChangeCallbacks) startTestActivity(ActivityTaskChangeCallbacks.class);
        expectedTaskId[0] = activity.getTaskId();

        // Test for onTaskCreated and onTaskMovedToFront
        waitForCallback(taskMovedToFrontLatch);
        assertEquals(0, taskCreatedLaunchLatch.getCount());
        assertEquals(expectedTaskId[0], receivedTaskId[0]);

        // Ensure that the window is attached before removal so there will be a detached callback.
        waitForCallback(activity.mOnAttachedToWindowCountDownLatch);
        // Test for onTaskRemovalStarted.
        assertEquals(1, taskRemovalStartedLatch.getCount());
        assertEquals(1, taskRemovedLatch.getCount());
        activity.finishAndRemoveTask();
        waitForCallback(taskRemovalStartedLatch);
        // onTaskRemovalStarted happens before the activity's window is removed.
        assertEquals(1, activity.mOnDetachedFromWindowCountDownLatch.getCount());

        // Test for onTaskRemoved.
        waitForCallback(taskRemovedLatch);
        waitForCallback(activity.mOnDetachedFromWindowCountDownLatch);
    }

    @Test
    public void testTaskDisplayChanged() throws Exception {
        final int virtualDisplayId = createVirtualDisplay().getDisplay().getDisplayId();

        // Launch a Activity inside VirtualDisplay
        CountDownLatch displayChangedLatch1 = new CountDownLatch(1);
        final Object[] params1 = new Object[1];
        registerTaskStackChangedListener(new TaskDisplayChangedListener(
                virtualDisplayId, params1, displayChangedLatch1));
        ActivityOptions options1 = ActivityOptions.makeBasic().setLaunchDisplayId(virtualDisplayId);
        int taskId1;
        synchronized (sLock) {
            taskId1 = startTestActivity(ActivityInVirtualDisplay.class, options1).getTaskId();
        }
        waitForCallback(displayChangedLatch1);

        assertEquals(taskId1, params1[0]);

        // Launch the Activity in the default display, expects that reparenting happens.
        final Object[] params2 = new Object[1];
        final CountDownLatch displayChangedLatch2 = new CountDownLatch(1);
        registerTaskStackChangedListener(
                new TaskDisplayChangedListener(
                        Display.DEFAULT_DISPLAY, params2, displayChangedLatch2));
        int taskId2;
        ActivityOptions options2 = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        synchronized (sLock) {
            taskId2 = startTestActivity(ActivityInVirtualDisplay.class, options2).getTaskId();
        }
        waitForCallback(displayChangedLatch2);

        assertEquals(taskId2, params2[0]);
        assertEquals(taskId1, taskId2);  // TaskId should be same since reparenting happens.
    }

    private static class TaskDisplayChangedListener extends TaskStackListener {
        private int mDisplayId;
        private final Object[] mParams;
        private final CountDownLatch mDisplayChangedLatch;
        TaskDisplayChangedListener(
                int displayId, Object[] params, CountDownLatch displayChangedLatch) {
            mDisplayId = displayId;
            mParams = params;
            mDisplayChangedLatch = displayChangedLatch;
        }
        @Override
        public void onTaskDisplayChanged(int taskId, int displayId) throws RemoteException {
            // Filter out the events for the uninterested displays.
            // if (displayId != mDisplayId) return;
            mParams[0] = taskId;
            mDisplayChangedLatch.countDown();
        }
    };

    @Presubmit
    @Test
    public void testNotifyTaskRequestedOrientationChanged() throws Exception {
        final ArrayBlockingQueue<int[]> taskIdAndOrientationQueue = new ArrayBlockingQueue<>(10);
        registerTaskStackChangedListener(new TaskStackListener() {
            @Override
            public void onTaskRequestedOrientationChanged(int taskId, int requestedOrientation) {
                int[] taskIdAndOrientation = new int[2];
                taskIdAndOrientation[0] = taskId;
                taskIdAndOrientation[1] = requestedOrientation;
                taskIdAndOrientationQueue.offer(taskIdAndOrientation);
            }
        });

        final boolean isIgnoringOrientationRequest =
                CommonUtils.getIgnoreOrientationRequest(Display.DEFAULT_DISPLAY);
        if (isIgnoringOrientationRequest) {
            CommonUtils.setIgnoreOrientationRequest(Display.DEFAULT_DISPLAY, false);
        }

        try {
            final LandscapeActivity activity =
                    (LandscapeActivity) startTestActivity(LandscapeActivity.class);

            int[] taskIdAndOrientation = waitForResult(taskIdAndOrientationQueue,
                    candidate -> candidate[0] == activity.getTaskId());
            assertNotNull(taskIdAndOrientation);
            assertEquals(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, taskIdAndOrientation[1]);

            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            taskIdAndOrientation = waitForResult(taskIdAndOrientationQueue,
                    candidate -> candidate[0] == activity.getTaskId());
            assertNotNull(taskIdAndOrientation);
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, taskIdAndOrientation[1]);

            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            taskIdAndOrientation = waitForResult(taskIdAndOrientationQueue,
                    candidate -> candidate[0] == activity.getTaskId());
            assertNotNull(taskIdAndOrientation);
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, taskIdAndOrientation[1]);
        } finally {
            CommonUtils.setIgnoreOrientationRequest(
                    Display.DEFAULT_DISPLAY, isIgnoringOrientationRequest);
        }
    }

    /**
     * Starts the provided activity and returns the started instance.
     */
    private TestActivity startTestActivity(Class<?> activityClass) throws InterruptedException {
        return startTestActivity(activityClass, ActivityOptions.makeBasic());
    }

    private TestActivity startTestActivity(Class<?> activityClass, ActivityOptions options)
            throws InterruptedException {
        final ActivityMonitor monitor = new ActivityMonitor(activityClass.getName(), null, false);
        getInstrumentation().addMonitor(monitor);
        final Context context = getInstrumentation().getContext();
        runWithShellPermissionIdentity(() -> context.startActivity(
                new Intent(context, activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                options.toBundle()));
        final TestActivity activity =
                (TestActivity) monitor.waitForActivityWithTimeout(WAIT_TIMEOUT_MS);
        if (activity == null) {
            throw new RuntimeException("Timed out waiting for Activity");
        }
        activity.waitForResumeStateChange(true);
        mStartedActivities.add(activity);
        return activity;
    }

    private void registerTaskStackChangedListener(ITaskStackListener listener) throws Exception {
        if (mTaskStackListener != null) {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskStackListener);
        }
        mTaskStackListener = listener;
        ActivityTaskManager.getService().registerTaskStackListener(listener);
    }

    private void waitForCallback(CountDownLatch latch) {
        try {
            final boolean result = latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!result) {
                throw new AssertionError("Timed out waiting for task stack change notification");
            }
        } catch (InterruptedException e) {
        }
    }

    private <T> T waitForResult(ArrayBlockingQueue<T> queue, Predicate<T> predicate) {
        try {
            final long timeout = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(15);
            T result;
            do {
                result = queue.poll(timeout - SystemClock.uptimeMillis(), TimeUnit.MILLISECONDS);
            } while (result != null && !predicate.test(result));
            return result;
        } catch (InterruptedException e) {
            return null;
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
                wait(WAIT_TIMEOUT_MS);
            }
            assertEquals("The activity resume state change timed out", isResumed, mIsResumed);
        }
    }

    public static class ActivityA extends TestActivity {}

    public static class ActivityB extends TestActivity {

        @Override
        protected void onPostResume() {
            super.onPostResume();
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
            // Sets the color of the status-bar should update the TaskDescription again.
            getWindow().setStatusBarColor(Color.RED);
            synchronized (sLock) {
                // Hold the lock to ensure no one is trying to access fields of this Activity in
                // this test.
                finish();
            }
        }
    }

    public static class ActivityTaskChangeCallbacks extends TestActivity {
        final CountDownLatch mOnAttachedToWindowCountDownLatch = new CountDownLatch(1);
        final CountDownLatch mOnDetachedFromWindowCountDownLatch = new CountDownLatch(1);

        @Override
        public void onAttachedToWindow() {
            mOnAttachedToWindowCountDownLatch.countDown();
        }

        @Override
        public void onDetachedFromWindow() {
            mOnDetachedFromWindowCountDownLatch.countDown();
        }
    }

    public static class ActivityInVirtualDisplay extends TestActivity {

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            LinearLayout layout = new LinearLayout(this);
            layout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(layout);
        }
    }

    public static class ResumeWhilePausingActivity extends TestActivity {}

    public static class LandscapeActivity extends TestActivity {}
}
