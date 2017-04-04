/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.mockito.Mockito.mock;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import com.android.server.AttributeCache;
import com.android.server.wm.AppWindowContainerController;
import com.android.server.wm.StackWindowController;

import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowTestUtils;
import org.junit.Before;
import org.mockito.MockitoAnnotations;

/**
 * A base class to handle common operations in activity related unit tests.
 */
public class ActivityTestsBase {
    private final Context mContext = InstrumentationRegistry.getContext();
    private static boolean sLooperPrepared;
    private Handler mHandler;

    // Grabbing an instance of {@link WindowManagerService} creates it if not present so this must
    // be called at before any tests.
    private final WindowManagerService mWms = WindowTestUtils.getWindowManagerService(mContext);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        if (!sLooperPrepared) {
            sLooperPrepared = true;
            Looper.prepare();
        }
    }

    protected ActivityManagerService createActivityManagerService() {
        return new TestActivityManagerService(mContext);
    }

    protected static TestActivityStack createActivityStack(ActivityManagerService service,
            int stackId, int displayId, boolean onTop) {
        if (service.mStackSupervisor instanceof TestActivityStackSupervisor) {
            final TestActivityStack stack = ((TestActivityStackSupervisor) service.mStackSupervisor)
                    .createTestStack(stackId, onTop);
            return stack;
        }

        return null;
    }

    protected static ActivityRecord createActivity(ActivityManagerService service,
            ComponentName component, TaskRecord task) {
        Intent intent = new Intent();
        intent.setComponent(component);
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.packageName = component.getPackageName();
        AttributeCache.init(service.mContext);
        final ActivityRecord activity = new ActivityRecord(service, null /* caller */,
                0 /* launchedFromPid */, 0, null, intent, null,
                aInfo /*aInfo*/, new Configuration(), null /* resultTo */, null /* resultWho */,
                0 /* reqCode */, false /*componentSpecified*/, false /* rootVoiceInteraction */,
                service.mStackSupervisor, null /* container */, null /* options */,
                null /* sourceRecord */);
        activity.mWindowContainerController = mock(AppWindowContainerController.class);

        if (task != null) {
            task.addActivityToTop(activity);
        }

        return activity;
    }

    protected static TaskRecord createTask(ActivityManagerService service,
            ComponentName component, ActivityStack stack) {
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.packageName = component.getPackageName();

        Intent intent = new Intent();
        intent.setComponent(component);

        final TaskRecord task = new TaskRecord(service, 0, aInfo, intent /*intent*/,
                null /*_taskDescription*/, null /*thumbnailInfo*/);
        stack.addTask(task, true, "creating test task");
        task.setStack(stack);
        task.createWindowContainer(true, true);

        return task;
    }

    /**
     * An {@link ActivityManagerService} subclass which provides a test
     * {@link ActivityStackSupervisor}.
     */
    protected static class TestActivityManagerService extends ActivityManagerService {
        public TestActivityManagerService(Context context) {
            super(context);
        }

        @Override
        protected ActivityStackSupervisor createStackSupervisor() {
            return new TestActivityStackSupervisor(this, new Handler().getLooper());
        }
    }

    /**
     * An {@link ActivityStackSupervisor} which stubs out certain methods that depend on
     * setup not available in the test environment. Also specifies an injector for
     */
    protected static class TestActivityStackSupervisor extends ActivityStackSupervisor {
        public TestActivityStackSupervisor(ActivityManagerService service, Looper looper) {
            super(service, looper);
        }

        // Invoked during {@link ActivityStack} creation.
        @Override
        void updateUIDsPresentOnDisplay() {
        }

        public TestActivityStack createTestStack(int stackId, boolean onTop) {
            final ActivityDisplay display = new ActivityDisplay();
            final TestActivityContainer container =
                    new TestActivityContainer(stackId, display, onTop);
            return container.getStack();
        }

        private class TestActivityContainer extends ActivityContainer {
            private TestActivityStack mStack;
            TestActivityContainer(int stackId, ActivityDisplay activityDisplay, boolean onTop) {
                super(stackId, activityDisplay, onTop);
            }

            @Override
            protected void createStack(int stackId, boolean onTop) {
                mStack = new TestActivityStack(this, null /*recentTasks*/, onTop);
            }

            public TestActivityStack getStack() {
                return mStack;
            }
        }
    }

    /**
     * Override of {@link ActivityStack} that tracks test metrics, such as the number of times a
     * method is called. Note that its functionality depends on the implementations of the
     * construction arguments.
     */
    protected static class TestActivityStack<T extends StackWindowController>
            extends ActivityStack<T> {
        private int mOnActivityRemovedFromStackCount = 0;
        private T mContainerController;
        TestActivityStack(ActivityStackSupervisor.ActivityContainer activityContainer,
                RecentTasks recentTasks, boolean onTop) {
            super(activityContainer, recentTasks, onTop);
        }

        @Override
        void onActivityRemovedFromStack(ActivityRecord r) {
            mOnActivityRemovedFromStackCount++;
            super.onActivityRemovedFromStack(r);
        }

        // Returns the number of times {@link #onActivityRemovedFromStack} has been called
        public int onActivityRemovedFromStackInvocationCount() {
            return mOnActivityRemovedFromStackCount;
        }

        @Override
        protected T createStackWindowController(int displayId, boolean onTop,
                Rect outBounds) {
            mContainerController = (T) WindowTestUtils.createMockStackWindowContainerController();
            return mContainerController;
        }

        @Override
        T getWindowContainerController() {
            return mContainerController;
        }
    }

    protected static class ActivityStackBuilder {
        private boolean mOnTop = true;
        private int mStackId = 0;
        private int mDisplayId = 1;

        private final ActivityManagerService mService;

        public ActivityStackBuilder(ActivityManagerService ams) {
            mService = ams;
        }

        public ActivityStackBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        public ActivityStackBuilder setStackId(int id) {
            mStackId = id;
            return this;
        }

        public ActivityStackBuilder setDisplayId(int id) {
            mDisplayId = id;
            return this;
        }

        public TestActivityStack build() {
            return createActivityStack(mService, mStackId, mDisplayId, mOnTop);
        }
    }
}
