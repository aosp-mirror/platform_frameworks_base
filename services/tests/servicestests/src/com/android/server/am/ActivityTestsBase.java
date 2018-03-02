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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.Display.DEFAULT_DISPLAY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.android.server.wm.DisplayWindowController;
import org.mockito.invocation.InvocationOnMock;

import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.voice.IVoiceInteractionSession;
import android.support.test.InstrumentationRegistry;
import com.android.server.AttributeCache;
import com.android.server.wm.AppWindowContainerController;
import com.android.server.wm.PinnedStackWindowController;
import com.android.server.wm.StackWindowController;
import com.android.server.wm.TaskWindowContainerController;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowTestUtils;
import org.junit.After;
import org.junit.Before;
import org.mockito.MockitoAnnotations;

/**
 * A base class to handle common operations in activity related unit tests.
 */
public class ActivityTestsBase {
    private static boolean sOneTimeSetupDone = false;

    private final Context mContext = InstrumentationRegistry.getContext();
    private HandlerThread mHandlerThread;

    @Before
    public void setUp() throws Exception {
        if (!sOneTimeSetupDone) {
            sOneTimeSetupDone = true;

            // Allows to mock package local classes and methods
            System.setProperty("dexmaker.share_classloader", "true");
            MockitoAnnotations.initMocks(this);
        }
        mHandlerThread = new HandlerThread("ActivityTestsBaseThread");
        mHandlerThread.start();
    }

    @After
    public void tearDown() {
        mHandlerThread.quitSafely();
    }

    protected ActivityManagerService createActivityManagerService() {
        final ActivityManagerService service =
                setupActivityManagerService(new TestActivityManagerService(mContext));
        AttributeCache.init(mContext);
        return service;
    }

    protected ActivityManagerService setupActivityManagerService(ActivityManagerService service) {
        service = spy(service);
        doReturn(mock(IPackageManager.class)).when(service).getPackageManager();
        service.mWindowManager = prepareMockWindowManager();
        return service;
    }

    /**
     * Builder for creating new activities.
     */
    protected static class ActivityBuilder {
        // An id appended to the end of the component name to make it unique
        private static int sCurrentActivityId = 0;

        // Default package name
        static final String DEFAULT_PACKAGE = "com.foo";

        // Default base activity name
        private static final String DEFAULT_BASE_ACTIVITY_NAME = ".BarActivity";

        private final ActivityManagerService mService;

        private ComponentName mComponent;
        private TaskRecord mTaskRecord;
        private int mUid;
        private boolean mCreateTask;
        private ActivityStack mStack;

        ActivityBuilder(ActivityManagerService service) {
            mService = service;
        }

        ActivityBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        ActivityBuilder setTask(TaskRecord task) {
            mTaskRecord = task;
            return this;
        }

        ActivityBuilder setStack(ActivityStack stack) {
            mStack = stack;
            return this;
        }

        ActivityBuilder setCreateTask(boolean createTask) {
            mCreateTask = createTask;
            return this;
        }

        ActivityBuilder setUid(int uid) {
            mUid = uid;
            return this;
        }

        ActivityRecord build() {
            if (mComponent == null) {
                final int id = sCurrentActivityId++;
                mComponent = ComponentName.createRelative(DEFAULT_PACKAGE,
                        DEFAULT_BASE_ACTIVITY_NAME + id);
            }

            if (mCreateTask) {
                mTaskRecord = new TaskBuilder(mService.mStackSupervisor)
                        .setComponent(mComponent)
                        .setStack(mStack).build();
            }

            Intent intent = new Intent();
            intent.setComponent(mComponent);
            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.packageName = mComponent.getPackageName();
            aInfo.applicationInfo.uid = mUid;
            final ActivityRecord activity = new ActivityRecord(mService, null /* caller */,
                    0 /* launchedFromPid */, 0, null, intent, null,
                    aInfo /*aInfo*/, new Configuration(), null /* resultTo */, null /* resultWho */,
                    0 /* reqCode */, false /*componentSpecified*/, false /* rootVoiceInteraction */,
                    mService.mStackSupervisor, null /* options */, null /* sourceRecord */);
            activity.mWindowContainerController = mock(AppWindowContainerController.class);

            if (mTaskRecord != null) {
                mTaskRecord.addActivityToTop(activity);
            }

            activity.setProcess(new ProcessRecord(null, mService.mContext.getApplicationInfo(),
                    "name", 12345));
            activity.app.thread = mock(IApplicationThread.class);

            return activity;
        }
    }

    /**
     * Builder for creating new tasks.
     */
    protected static class TaskBuilder {
        private final ActivityStackSupervisor mSupervisor;

        private ComponentName mComponent;
        private String mPackage;
        private int mFlags = 0;
        private int mTaskId = 0;
        private int mUserId = 0;
        private IVoiceInteractionSession mVoiceSession;

        private ActivityStack mStack;

        TaskBuilder(ActivityStackSupervisor supervisor) {
            mSupervisor = supervisor;
        }

        TaskBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        TaskBuilder setPackage(String packageName) {
            mPackage = packageName;
            return this;
        }

        TaskBuilder setVoiceSession(IVoiceInteractionSession session) {
            mVoiceSession = session;
            return this;
        }

        TaskBuilder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        TaskBuilder setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        TaskBuilder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        TaskBuilder setStack(ActivityStack stack) {
            mStack = stack;
            return this;
        }

        TaskRecord build() {
            if (mStack == null) {
                mStack = mSupervisor.getDefaultDisplay().createStack(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
            }

            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.packageName = mPackage;

            Intent intent = new Intent();
            intent.setComponent(mComponent);
            intent.setFlags(mFlags);

            final TaskRecord task = new TaskRecord(mSupervisor.mService, mTaskId, aInfo,
                    intent /*intent*/, mVoiceSession, null /*_voiceInteractor*/);
            task.userId = mUserId;
            mSupervisor.setFocusStackUnchecked("test", mStack);
            mStack.addTask(task, true, "creating test task");
            task.setStack(mStack);
            task.setWindowContainerController(mock(TaskWindowContainerController.class));
            task.touchActiveTime();

            return task;
        }
    }

    /**
     * An {@link ActivityManagerService} subclass which provides a test
     * {@link ActivityStackSupervisor}.
     */
    protected static class TestActivityManagerService extends ActivityManagerService {
        private ClientLifecycleManager mLifecycleManager;

        TestActivityManagerService(Context context) {
            super(context);
            mSupportsMultiWindow = true;
            mSupportsMultiDisplay = true;
            mSupportsSplitScreenMultiWindow = true;
            mSupportsFreeformWindowManagement = true;
            mSupportsPictureInPicture = true;
            mWindowManager = WindowTestUtils.getMockWindowManagerService();
        }

        @Override
        public ClientLifecycleManager getLifecycleManager() {
            if (mLifecycleManager == null) {
                return super.getLifecycleManager();
            }
            return mLifecycleManager;
        }

        void setLifecycleManager(ClientLifecycleManager manager) {
            mLifecycleManager = manager;
        }

        @Override
        final protected ActivityStackSupervisor createStackSupervisor() {
            final ActivityStackSupervisor supervisor = spy(createTestSupervisor());

            // No home stack is set.
            doNothing().when(supervisor).moveHomeStackToFront(any());
            doReturn(true).when(supervisor).moveHomeStackTaskToTop(any());
            // Invoked during {@link ActivityStack} creation.
            doNothing().when(supervisor).updateUIDsPresentOnDisplay();
            // Always keep things awake.
            doReturn(true).when(supervisor).hasAwakeDisplay();
            // Called when moving activity to pinned stack.
            doNothing().when(supervisor).ensureActivitiesVisibleLocked(any(), anyInt(), anyBoolean());
            // Do not schedule idle timeouts
            doNothing().when(supervisor).scheduleIdleTimeoutLocked(any());

            supervisor.initialize();

            return supervisor;
        }

        protected ActivityStackSupervisor createTestSupervisor() {
            return new TestActivityStackSupervisor(this, mHandlerThread.getLooper());
        }

        @Override
        void updateUsageStats(ActivityRecord component, boolean resumed) {
        }
    }

    /**
     * An {@link ActivityStackSupervisor} which stubs out certain methods that depend on
     * setup not available in the test environment. Also specifies an injector for
     */
    protected static class TestActivityStackSupervisor extends ActivityStackSupervisor {
        private ActivityDisplay mDisplay;

        public TestActivityStackSupervisor(ActivityManagerService service, Looper looper) {
            super(service, looper);
            mDisplayManager =
                    (DisplayManager) mService.mContext.getSystemService(Context.DISPLAY_SERVICE);
            mWindowManager = prepareMockWindowManager();
        }

        @Override
        public void initialize() {
            super.initialize();
            mDisplay = new TestActivityDisplay(this, DEFAULT_DISPLAY);
            attachDisplay(mDisplay);
        }

        @Override
        ActivityDisplay getDefaultDisplay() {
            return mDisplay;
        }

        // Just return the current front task. This is called internally so we cannot use spy to mock this out.
        @Override
        ActivityStack getNextFocusableStackLocked(ActivityStack currentFocus) {
            return mFocusedStack;
        }
    }

    protected static class TestActivityDisplay extends ActivityDisplay {

        private final ActivityStackSupervisor mSupervisor;
        TestActivityDisplay(ActivityStackSupervisor supervisor, int displayId) {
            super(supervisor, displayId);
            mSupervisor = supervisor;
        }

        @Override
        <T extends ActivityStack> T createStackUnchecked(int windowingMode, int activityType,
                int stackId, boolean onTop) {
            if (windowingMode == WINDOWING_MODE_PINNED) {
                return (T) new PinnedActivityStack(this, stackId, mSupervisor, onTop) {
                    @Override
                    Rect getDefaultPictureInPictureBounds(float aspectRatio) {
                        return new Rect(50, 50, 100, 100);
                    }

                    @Override
                    PinnedStackWindowController createStackWindowController(int displayId,
                            boolean onTop, Rect outBounds) {
                        return mock(PinnedStackWindowController.class);
                    }
                };
            } else {
                return (T) new TestActivityStack(
                        this, stackId, mSupervisor, windowingMode, activityType, onTop);
            }
        }

        @Override
        protected DisplayWindowController createWindowContainerController() {
            return mock(DisplayWindowController.class);
        }
    }

    private static WindowManagerService prepareMockWindowManager() {
        final WindowManagerService service = WindowTestUtils.getMockWindowManagerService();

        doAnswer((InvocationOnMock invocationOnMock) -> {
            final Runnable runnable = invocationOnMock.<Runnable>getArgument(0);
            if (runnable != null) {
                runnable.run();
            }
            return null;
        }).when(service).inSurfaceTransaction(any());

        return service;
    }

    /**
     * Overrided of {@link ActivityStack} that tracks test metrics, such as the number of times a
     * method is called. Note that its functionality depends on the implementations of the
     * construction arguments.
     */
    protected static class TestActivityStack<T extends StackWindowController>
            extends ActivityStack<T> {
        private int mOnActivityRemovedFromStackCount = 0;
        private T mContainerController;

        static final int IS_TRANSLUCENT_UNSET = 0;
        static final int IS_TRANSLUCENT_FALSE = 1;
        static final int IS_TRANSLUCENT_TRUE = 2;
        private int mIsTranslucent = IS_TRANSLUCENT_UNSET;

        static final int SUPPORTS_SPLIT_SCREEN_UNSET = 0;
        static final int SUPPORTS_SPLIT_SCREEN_FALSE = 1;
        static final int SUPPORTS_SPLIT_SCREEN_TRUE = 2;
        private int mSupportsSplitScreen = SUPPORTS_SPLIT_SCREEN_UNSET;

        TestActivityStack(ActivityDisplay display, int stackId, ActivityStackSupervisor supervisor,
                int windowingMode, int activityType, boolean onTop) {
            super(display, stackId, supervisor, windowingMode, activityType, onTop);
        }

        @Override
        void onActivityRemovedFromStack(ActivityRecord r) {
            mOnActivityRemovedFromStackCount++;
            super.onActivityRemovedFromStack(r);
        }

        // Returns the number of times {@link #onActivityRemovedFromStack} has been called
        int onActivityRemovedFromStackInvocationCount() {
            return mOnActivityRemovedFromStackCount;
        }

        @Override
        protected T createStackWindowController(int displayId, boolean onTop, Rect outBounds) {
            mContainerController = (T) WindowTestUtils.createMockStackWindowContainerController();

            // Primary pinned stacks require a non-empty out bounds to be set or else all tasks
            // will be moved to the full screen stack.
            if (getWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                outBounds.set(0, 0, 100, 100);
            }
            return mContainerController;
        }

        @Override
        T getWindowContainerController() {
            return mContainerController;
        }

        void setIsTranslucent(boolean isTranslucent) {
            mIsTranslucent = isTranslucent ? IS_TRANSLUCENT_TRUE : IS_TRANSLUCENT_FALSE;
        }

        @Override
        boolean isStackTranslucent(ActivityRecord starting) {
            switch (mIsTranslucent) {
                case IS_TRANSLUCENT_TRUE:
                    return true;
                case IS_TRANSLUCENT_FALSE:
                    return false;
                case IS_TRANSLUCENT_UNSET:
                default:
                    return super.isStackTranslucent(starting);
            }
        }

        void setSupportsSplitScreen(boolean supportsSplitScreen) {
            mSupportsSplitScreen = supportsSplitScreen
                    ? SUPPORTS_SPLIT_SCREEN_TRUE : SUPPORTS_SPLIT_SCREEN_FALSE;
        }

        @Override
        public boolean supportsSplitScreenWindowingMode() {
            switch (mSupportsSplitScreen) {
                case SUPPORTS_SPLIT_SCREEN_TRUE:
                    return true;
                case SUPPORTS_SPLIT_SCREEN_FALSE:
                    return false;
                case SUPPORTS_SPLIT_SCREEN_UNSET:
                default:
                    return super.supportsSplitScreenWindowingMode();
            }
        }
    }
}
