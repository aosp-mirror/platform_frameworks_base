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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.testing.DexmakerShareClassLoaderRule;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.app.IVoiceInteractor;
import com.android.server.AppOpsService;
import com.android.server.AttributeCache;
import com.android.server.ServiceThread;
import com.android.server.am.ActivityManagerService;
import com.android.server.uri.UriGrantsManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.mockito.invocation.InvocationOnMock;

import java.io.File;
import java.util.List;

/**
 * A base class to handle common operations in activity related unit tests.
 */
class ActivityTestsBase {
    private static int sNextDisplayId = DEFAULT_DISPLAY + 1;

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    final Context mContext = getInstrumentation().getTargetContext();
    final TestInjector mTestInjector = new TestInjector();

    ActivityTaskManagerService mService;
    ActivityStackSupervisor mSupervisor;

    // Default package name
    static final String DEFAULT_COMPONENT_PACKAGE_NAME = "com.foo";

    // Default base activity name
    private static final String DEFAULT_COMPONENT_CLASS_NAME = ".BarActivity";

    @BeforeClass
    public static void setUpOnceBase() {
        AttributeCache.init(getInstrumentation().getTargetContext());
    }

    @Before
    public void setUpBase() {
        mTestInjector.setUp();
    }

    @After
    public void tearDownBase() {
        mTestInjector.tearDown();
    }

    ActivityTaskManagerService createActivityTaskManagerService() {
        final TestActivityTaskManagerService atm =
                spy(new TestActivityTaskManagerService(mContext));
        setupActivityManagerService(atm);
        return atm;
    }

    void setupActivityTaskManagerService() {
        mService = createActivityTaskManagerService();
        mSupervisor = mService.mStackSupervisor;
    }

    ActivityManagerService createActivityManagerService() {
        final TestActivityTaskManagerService atm =
                spy(new TestActivityTaskManagerService(mContext));
        return setupActivityManagerService(atm);
    }

    ActivityManagerService setupActivityManagerService(TestActivityTaskManagerService atm) {
        final TestActivityManagerService am = spy(new TestActivityManagerService(mTestInjector));
        setupActivityManagerService(am, atm);
        return am;
    }

    /** Creates a {@link TestActivityDisplay}. */
    TestActivityDisplay createNewActivityDisplay() {
        return TestActivityDisplay.create(mSupervisor, sNextDisplayId++);
    }

    TestActivityDisplay createNewActivityDisplay(DisplayInfo info) {
        return TestActivityDisplay.create(mSupervisor, sNextDisplayId++, info);
    }

    /** Creates and adds a {@link TestActivityDisplay} to supervisor at the given position. */
    TestActivityDisplay addNewActivityDisplayAt(int position) {
        final TestActivityDisplay display = createNewActivityDisplay();
        mSupervisor.addChild(display, position);
        return display;
    }

    void setupActivityManagerService(
            TestActivityManagerService am, TestActivityTaskManagerService atm) {
        atm.setActivityManagerService(am.mIntentFirewall, am.mPendingIntentController);
        atm.mAmInternal = am.getLocalService();
        am.mAtmInternal = atm.getLocalService();
        // Makes sure the supervisor is using with the spy object.
        atm.mStackSupervisor.setService(atm);
        doReturn(mock(IPackageManager.class)).when(am).getPackageManager();
        doReturn(mock(IPackageManager.class)).when(atm).getPackageManager();
        PackageManagerInternal mockPackageManager = mock(PackageManagerInternal.class);
        doReturn(mockPackageManager).when(am).getPackageManagerInternalLocked();
        doReturn(null).when(mockPackageManager).getDefaultHomeActivity(anyInt());
        doNothing().when(am).grantEphemeralAccessLocked(anyInt(), any(), anyInt(), anyInt());
        am.mActivityTaskManager = atm;
        am.mWindowManager = prepareMockWindowManager();
        atm.setWindowManager(am.mWindowManager);

        // Put a home stack on the default display, so that we'll always have something focusable.
        final TestActivityStackSupervisor supervisor =
                (TestActivityStackSupervisor) atm.mStackSupervisor;
        supervisor.mDisplay.createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
        final TaskRecord task = new TaskBuilder(atm.mStackSupervisor)
                .setStack(supervisor.getDefaultDisplay().getHomeStack()).build();
        new ActivityBuilder(atm).setTask(task).build();
    }

    /**
     * Builder for creating new activities.
     */
    protected static class ActivityBuilder {
        // An id appended to the end of the component name to make it unique
        private static int sCurrentActivityId = 0;

        private final ActivityTaskManagerService mService;

        private ComponentName mComponent;
        private TaskRecord mTaskRecord;
        private int mUid;
        private boolean mCreateTask;
        private ActivityStack mStack;
        private int mActivityFlags;
        private int mLaunchMode;

        ActivityBuilder(ActivityTaskManagerService service) {
            mService = service;
        }

        ActivityBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        static ComponentName getDefaultComponent() {
            return ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                    DEFAULT_COMPONENT_PACKAGE_NAME);
        }

        ActivityBuilder setTask(TaskRecord task) {
            mTaskRecord = task;
            return this;
        }

        ActivityBuilder setActivityFlags(int flags) {
            mActivityFlags = flags;
            return this;
        }

        ActivityBuilder setLaunchMode(int launchMode) {
            mLaunchMode = launchMode;
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
                mComponent = ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                        DEFAULT_COMPONENT_CLASS_NAME + id);
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
            aInfo.flags |= mActivityFlags;
            aInfo.launchMode = mLaunchMode;

            final ActivityRecord activity = new ActivityRecord(mService, null /* caller */,
                    0 /* launchedFromPid */, 0, null, intent, null,
                    aInfo /*aInfo*/, new Configuration(), null /* resultTo */, null /* resultWho */,
                    0 /* reqCode */, false /*componentSpecified*/, false /* rootVoiceInteraction */,
                    mService.mStackSupervisor, null /* options */, null /* sourceRecord */);
            activity.mWindowContainerController = mock(AppWindowContainerController.class);

            if (mTaskRecord != null) {
                mTaskRecord.addActivityToTop(activity);
            }

            final WindowProcessController wpc = new WindowProcessController(mService,
                    mService.mContext.getApplicationInfo(), "name", 12345,
                    UserHandle.getUserId(12345), mock(Object.class),
                    mock(WindowProcessListener.class));
            wpc.setThread(mock(IApplicationThread.class));
            activity.setProcess(wpc);
            return activity;
        }
    }

    /**
     * Builder for creating new tasks.
     */
    protected static class TaskBuilder {
        // Default package name
        static final String DEFAULT_PACKAGE = "com.bar";

        private final ActivityStackSupervisor mSupervisor;

        private ComponentName mComponent;
        private String mPackage;
        private int mFlags = 0;
        // Task id 0 is reserved in ARC for the home app.
        private int mTaskId = 1;
        private int mUserId = 0;
        private IVoiceInteractionSession mVoiceSession;
        private boolean mCreateStack = true;

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

        /**
         * Set to {@code true} by default, set to {@code false} to prevent the task from
         * automatically creating a parent stack.
         */
        TaskBuilder setCreateStack(boolean createStack) {
            mCreateStack = createStack;
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
            if (mStack == null && mCreateStack) {
                mStack = mSupervisor.getDefaultDisplay().createStack(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
            }

            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.packageName = mPackage;

            Intent intent = new Intent();
            if (mComponent == null) {
                mComponent = ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                        DEFAULT_COMPONENT_CLASS_NAME);
            }

            intent.setComponent(mComponent);
            intent.setFlags(mFlags);

            final TestTaskRecord task = new TestTaskRecord(mSupervisor.mService, mTaskId, aInfo,
                    intent /*intent*/, mVoiceSession, null /*_voiceInteractor*/);
            task.userId = mUserId;

            if (mStack != null) {
                mStack.moveToFront("test");
                mStack.addTask(task, true, "creating test task");
                task.setStack(mStack);
                task.setWindowContainerController();
            }

            task.touchActiveTime();

            return task;
        }

        private static class TestTaskRecord extends TaskRecord {
            TestTaskRecord(ActivityTaskManagerService service, int taskId, ActivityInfo info,
                       Intent intent, IVoiceInteractionSession voiceSession,
                       IVoiceInteractor voiceInteractor) {
                super(service, taskId, info, intent, voiceSession, voiceInteractor);
            }

            @Override
            void createWindowContainer(boolean onTop, boolean showForAllUsers) {
                setWindowContainerController();
            }

            private void setWindowContainerController() {
                setWindowContainerController(mock(TaskWindowContainerController.class));
            }
        }
    }

    protected static class TestActivityTaskManagerService extends ActivityTaskManagerService {
        private LockTaskController mLockTaskController;
        private ActivityTaskManagerInternal mInternal;
        private PackageManagerInternal mPmInternal;

        // ActivityStackSupervisor may be created more than once while setting up AMS and ATMS.
        // We keep the reference in order to prevent creating it twice.
        private ActivityStackSupervisor mTestStackSupervisor;

        TestActivityTaskManagerService(Context context) {
            super(context);
            mSupportsMultiWindow = true;
            mSupportsMultiDisplay = true;
            mSupportsSplitScreenMultiWindow = true;
            mSupportsFreeformWindowManagement = true;
            mSupportsPictureInPicture = true;
            mUgmInternal = mock(UriGrantsManagerInternal.class);
        }

        @Override
        int handleIncomingUser(int callingPid, int callingUid, int userId, String name) {
            return userId;
        }

        @Override
        public LockTaskController getLockTaskController() {
            if (mLockTaskController == null) {
                mLockTaskController = spy(super.getLockTaskController());
            }

            return mLockTaskController;
        }

        @Override
        void updateUsageStats(ActivityRecord component, boolean resumed) {
        }

        @Override
        protected final ActivityStackSupervisor createStackSupervisor() {
            if (mTestStackSupervisor == null) {
                final ActivityStackSupervisor supervisor = spy(createTestSupervisor());
                final KeyguardController keyguardController = mock(KeyguardController.class);

                // Invoked during {@link ActivityStack} creation.
                doNothing().when(supervisor).updateUIDsPresentOnDisplay();
                // Always keep things awake.
                doReturn(true).when(supervisor).hasAwakeDisplay();
                // Called when moving activity to pinned stack.
                doNothing().when(supervisor).ensureActivitiesVisibleLocked(any(), anyInt(),
                        anyBoolean());
                // Do not schedule idle timeouts
                doNothing().when(supervisor).scheduleIdleTimeoutLocked(any());
                // unit test version does not handle launch wake lock
                doNothing().when(supervisor).acquireLaunchWakelock();
                doReturn(keyguardController).when(supervisor).getKeyguardController();

                supervisor.initialize();
                mTestStackSupervisor = supervisor;
            }
            return mTestStackSupervisor;
        }

        protected ActivityStackSupervisor createTestSupervisor() {
            return new TestActivityStackSupervisor(this, mH.getLooper());
        }

        ActivityTaskManagerInternal getLocalService() {
            if (mInternal == null) {
                mInternal = new ActivityTaskManagerService.LocalService();
            }
            return mInternal;
        }

        @Override
        PackageManagerInternal getPackageManagerInternalLocked() {
            if (mPmInternal == null) {
                mPmInternal = mock(PackageManagerInternal.class);
                doReturn(false)
                        .when(mPmInternal)
                        .isPermissionsReviewRequired(anyString(), anyInt());
            }
            return mPmInternal;
        }
    }

    private static class TestInjector extends ActivityManagerService.Injector {
        private ServiceThread mHandlerThread;

        @Override
        public Context getContext() {
            return getInstrumentation().getTargetContext();
        }

        @Override
        public AppOpsService getAppOpsService(File file, Handler handler) {
            return null;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandlerThread.getThreadHandler();
        }

        @Override
        public boolean isNetworkRestrictedForUid(int uid) {
            return false;
        }

        void setUp() {
            mHandlerThread = new ServiceThread("ActivityTestsThread",
                    Process.THREAD_PRIORITY_DEFAULT, true /* allowIo */);
            mHandlerThread.start();
        }

        void tearDown() {
            mHandlerThread.quitSafely();
        }
    }

    /**
     * An {@link ActivityManagerService} subclass which provides a test
     * {@link ActivityStackSupervisor}.
     */
    static class TestActivityManagerService extends ActivityManagerService {

        private ActivityManagerInternal mInternal;

        TestActivityManagerService(TestInjector testInjector) {
            super(testInjector, testInjector.mHandlerThread);
            mUgmInternal = mock(UriGrantsManagerInternal.class);
        }

        ActivityManagerInternal getLocalService() {
            if (mInternal == null) {
                mInternal = new LocalService();
            }
            return mInternal;
        }
    }

    /**
     * An {@link ActivityStackSupervisor} which stubs out certain methods that depend on
     * setup not available in the test environment. Also specifies an injector for
     */
    protected static class TestActivityStackSupervisor extends ActivityStackSupervisor {
        private ActivityDisplay mDisplay;
        private KeyguardController mKeyguardController;

        public TestActivityStackSupervisor(ActivityTaskManagerService service, Looper looper) {
            super(service, looper);
            mDisplayManager =
                    (DisplayManager) mService.mContext.getSystemService(Context.DISPLAY_SERVICE);
            mWindowManager = prepareMockWindowManager();
            mKeyguardController = mock(KeyguardController.class);
            setWindowContainerController(mock(RootWindowContainerController.class));
        }

        @Override
        public void initialize() {
            super.initialize();
            mDisplay = spy(TestActivityDisplay.create(this, DEFAULT_DISPLAY));
            addChild(mDisplay, ActivityDisplay.POSITION_TOP);
        }

        @Override
        public KeyguardController getKeyguardController() {
            return mKeyguardController;
        }

        @Override
        ActivityDisplay getDefaultDisplay() {
            return mDisplay;
        }

        @Override
        void setWindowManager(WindowManagerService wm) {
            mWindowManager = wm;
        }
    }

    protected static class TestActivityDisplay extends ActivityDisplay {
        private final ActivityStackSupervisor mSupervisor;

        static TestActivityDisplay create(ActivityStackSupervisor supervisor, int displayId) {
            return create(supervisor, displayId, new DisplayInfo());
        }

        static TestActivityDisplay create(ActivityStackSupervisor supervisor, int displayId,
                DisplayInfo info) {
            if (displayId == DEFAULT_DISPLAY) {
                return new TestActivityDisplay(supervisor,
                        supervisor.mDisplayManager.getDisplay(displayId));
            }
            final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                    info, DEFAULT_DISPLAY_ADJUSTMENTS);
            return new TestActivityDisplay(supervisor, display);
        }

        TestActivityDisplay(ActivityStackSupervisor supervisor, Display display) {
            super(supervisor, display);
            // Normally this comes from display-properties as exposed by WM. Without that, just
            // hard-code to FULLSCREEN for tests.
            setWindowingMode(WINDOWING_MODE_FULLSCREEN);
            mSupervisor = supervisor;
        }

        @SuppressWarnings("TypeParameterUnusedInFormals")
        @Override
        <T extends ActivityStack> T createStackUnchecked(int windowingMode, int activityType,
                int stackId, boolean onTop) {
            return new StackBuilder(mSupervisor).setDisplay(this)
                    .setWindowingMode(windowingMode).setActivityType(activityType)
                    .setStackId(stackId).setOnTop(onTop).setCreateActivity(false).build();
        }

        @Override
        protected DisplayWindowController createWindowContainerController() {
            return mock(DisplayWindowController.class);
        }

        void removeAllTasks() {
            for (int i = 0; i < getChildCount(); i++) {
                final ActivityStack stack = getChildAt(i);
                for (TaskRecord task : (List<TaskRecord>) stack.getAllTasks()) {
                    stack.removeTask(task, "removeAllTasks", REMOVE_TASK_MODE_DESTROYING);
                }
            }
        }
    }

    private static WindowManagerService prepareMockWindowManager() {
        final WindowManagerService service = mock(WindowManagerService.class);

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
     * Overridden {@link ActivityStack} that tracks test metrics, such as the number of times a
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
                int windowingMode, int activityType, boolean onTop, boolean createActivity) {
            super(display, stackId, supervisor, windowingMode, activityType, onTop);
            if (createActivity) {
                new ActivityBuilder(mService).setCreateTask(true).setStack(this).build();
                if (onTop) {
                    // We move the task to front again in order to regain focus after activity
                    // added to the stack. Or {@link ActivityDisplay#mPreferredTopFocusableStack}
                    // could be other stacks (e.g. home stack).
                    moveToFront("createActivityStack");
                } else {
                    moveToBack("createActivityStack", null);
                }
            }
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

        @Override
        void startActivityLocked(ActivityRecord r, ActivityRecord focusedTopActivity,
                                 boolean newTask, boolean keepCurTransition,
                                 ActivityOptions options) {
        }
    }

    protected static class StackBuilder {
        private final ActivityStackSupervisor mSupervisor;
        private ActivityDisplay mDisplay;
        private int mStackId = -1;
        private int mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        private int mActivityType = ACTIVITY_TYPE_STANDARD;
        private boolean mOnTop = true;
        private boolean mCreateActivity = true;

        StackBuilder(ActivityStackSupervisor supervisor) {
            mSupervisor = supervisor;
            mDisplay = mSupervisor.getDefaultDisplay();
        }

        StackBuilder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        StackBuilder setActivityType(int activityType) {
            mActivityType = activityType;
            return this;
        }

        StackBuilder setStackId(int stackId) {
            mStackId = stackId;
            return this;
        }

        StackBuilder setDisplay(ActivityDisplay display) {
            mDisplay = display;
            return this;
        }

        StackBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        StackBuilder setCreateActivity(boolean createActivity) {
            mCreateActivity = createActivity;
            return this;
        }

        @SuppressWarnings("TypeParameterUnusedInFormals")
        <T extends ActivityStack> T build() {
            final int stackId = mStackId >= 0 ? mStackId : mDisplay.getNextStackId();
            if (mWindowingMode == WINDOWING_MODE_PINNED) {
                return (T) new PinnedActivityStack(mDisplay, stackId, mSupervisor, mOnTop) {
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
                return (T) new TestActivityStack(mDisplay, stackId, mSupervisor, mWindowingMode,
                        mActivityType, mOnTop, mCreateActivity);
            }
        }

    }
}
