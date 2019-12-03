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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;

import static org.mockito.ArgumentMatchers.eq;

import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
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
import android.os.PowerManager;
import android.os.Process;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.testing.DexmakerShareClassLoaderRule;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.app.IVoiceInteractor;
import com.android.server.AttributeCache;
import com.android.server.ServiceThread;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.PendingIntentController;
import com.android.server.appop.AppOpsService;
import com.android.server.firewall.IntentFirewall;
import com.android.server.pm.UserManagerService;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.TaskRecord.TaskRecordFactory;
import com.android.server.wm.utils.MockTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.mockito.invocation.InvocationOnMock;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * A base class to handle common operations in activity related unit tests.
 */
class ActivityTestsBase {
    private static int sNextDisplayId = DEFAULT_DISPLAY + 1;
    private static final int[] TEST_USER_PROFILE_IDS = {};

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    final Context mContext = getInstrumentation().getTargetContext();
    final TestInjector mTestInjector = new TestInjector();

    ActivityTaskManagerService mService;
    RootActivityContainer mRootActivityContainer;
    ActivityStackSupervisor mSupervisor;

    private MockTracker mMockTracker;

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
        mMockTracker = new MockTracker();

        mTestInjector.setUp();

        mService = new TestActivityTaskManagerService(mContext);
        mSupervisor = mService.mStackSupervisor;
        mRootActivityContainer = mService.mRootActivityContainer;
    }

    @After
    public void tearDownBase() {
        mTestInjector.tearDown();
        if (mService != null) {
            mService.setWindowManager(null);
            mService = null;
        }
        if (sMockWindowManagerService != null) {
            reset(sMockWindowManagerService);
        }

        mMockTracker.close();
        mMockTracker = null;
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
        mRootActivityContainer.addChild(display, position);
        return display;
    }

    /** Creates and adds a {@link TestActivityDisplay} to supervisor at the given position. */
    TestActivityDisplay addNewActivityDisplayAt(DisplayInfo info, int position) {
        final TestActivityDisplay display = createNewActivityDisplay(info);
        mRootActivityContainer.addChild(display, position);
        return display;
    }

    /**
     * Delegates task creation to {@link #TaskBuilder} to avoid the dependency of window hierarchy
     * when starting activity in unit tests.
     */
    void mockTaskRecordFactory(Consumer<TaskBuilder> taskBuilderSetup) {
        final TaskBuilder taskBuilder = new TaskBuilder(mSupervisor).setCreateStack(false);
        if (taskBuilderSetup != null) {
            taskBuilderSetup.accept(taskBuilder);
        }
        final TaskRecord task = taskBuilder.build();
        final TaskRecordFactory factory = mock(TaskRecordFactory.class);
        TaskRecord.setTaskRecordFactory(factory);
        doReturn(task).when(factory).create(any() /* service */, anyInt() /* taskId */,
                any() /* info */, any() /* intent */, any() /* voiceSession */,
                any() /* voiceInteractor */);
    }

    void mockTaskRecordFactory() {
        mockTaskRecordFactory(null /* taskBuilderSetup */);
    }

    /**
     * Builder for creating new activities.
     */
    protected static class ActivityBuilder {
        // An id appended to the end of the component name to make it unique
        private static int sCurrentActivityId = 0;

        private final ActivityTaskManagerService mService;

        private ComponentName mComponent;
        private String mTargetActivity;
        private TaskRecord mTaskRecord;
        private int mUid;
        private boolean mCreateTask;
        private ActivityStack mStack;
        private int mActivityFlags;
        private int mLaunchMode;
        private int mLaunchedFromPid;
        private int mLaunchedFromUid;

        ActivityBuilder(ActivityTaskManagerService service) {
            mService = service;
        }

        ActivityBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        ActivityBuilder setTargetActivity(String targetActivity) {
            mTargetActivity = targetActivity;
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

        ActivityBuilder setLaunchedFromPid(int pid) {
            mLaunchedFromPid = pid;
            return this;
        }

        ActivityBuilder setLaunchedFromUid(int uid) {
            mLaunchedFromUid = uid;
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
            aInfo.packageName = mComponent.getPackageName();
            if (mTargetActivity != null) {
                aInfo.targetActivity = mTargetActivity;
            }
            aInfo.flags |= mActivityFlags;
            aInfo.launchMode = mLaunchMode;

            final ActivityRecord activity = new ActivityRecord(mService, null /* caller */,
                    mLaunchedFromPid /* launchedFromPid */, mLaunchedFromUid /* launchedFromUid */,
                    null, intent, null, aInfo /*aInfo*/, new Configuration(), null /* resultTo */,
                    null /* resultWho */, 0 /* reqCode */, false /*componentSpecified*/,
                    false /* rootVoiceInteraction */, mService.mStackSupervisor,
                    null /* options */, null /* sourceRecord */);
            spyOn(activity);
            activity.mAppWindowToken = mock(AppWindowToken.class);
            doCallRealMethod().when(activity.mAppWindowToken).getOrientationIgnoreVisibility();
            doCallRealMethod().when(activity.mAppWindowToken)
                    .setOrientation(anyInt(), any(), any());
            doCallRealMethod().when(activity.mAppWindowToken).setOrientation(anyInt());
            doNothing().when(activity).removeWindowContainer();
            doReturn(mock(Configuration.class)).when(activity.mAppWindowToken)
                    .getRequestedOverrideConfiguration();

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
                mStack = mSupervisor.mRootActivityContainer.getDefaultDisplay().createStack(
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
                task.setTask();
                mStack.getTaskStack().addChild(task.mTask, 0);
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
            void createTask(boolean onTop, boolean showForAllUsers) {
                setTask();
            }

            void setTask() {
                Task mockTask = mock(Task.class);
                mockTask.mTaskRecord = this;
                doCallRealMethod().when(mockTask).onDescendantOrientationChanged(any(), any());
                setTask(mock(Task.class));
            }
        }
    }

    protected class TestActivityTaskManagerService extends ActivityTaskManagerService {
        private PackageManagerInternal mPmInternal;
        private PermissionPolicyInternal mPermissionPolicyInternal;

        // ActivityStackSupervisor may be created more than once while setting up AMS and ATMS.
        // We keep the reference in order to prevent creating it twice.
        ActivityStackSupervisor mTestStackSupervisor;

        ActivityDisplay mDefaultDisplay;
        AppOpsService mAppOpsService;

        TestActivityTaskManagerService(Context context) {
            super(context);
            spyOn(this);

            mUgmInternal = mock(UriGrantsManagerInternal.class);
            mAppOpsService = mock(AppOpsService.class);

            // Make sure permission checks aren't overridden.
            doReturn(AppOpsManager.MODE_DEFAULT)
                    .when(mAppOpsService).noteOperation(anyInt(), anyInt(), anyString());

            mSupportsMultiWindow = true;
            mSupportsMultiDisplay = true;
            mSupportsSplitScreenMultiWindow = true;
            mSupportsFreeformWindowManagement = true;
            mSupportsPictureInPicture = true;

            final TestActivityManagerService am =
                    new TestActivityManagerService(mTestInjector, this);

            spyOn(getLifecycleManager());
            spyOn(getLockTaskController());
            doReturn(mock(IPackageManager.class)).when(this).getPackageManager();
            // allow background activity starts by default
            doReturn(true).when(this).isBackgroundActivityStartsEnabled();
            doNothing().when(this).updateCpuStats();

            // UserManager
            final UserManagerService ums = mock(UserManagerService.class);
            doReturn(ums).when(this).getUserManager();
            doReturn(TEST_USER_PROFILE_IDS).when(ums).getProfileIds(anyInt(), eq(true));
        }

        void setup(IntentFirewall intentFirewall, PendingIntentController intentController,
                ActivityManagerInternal amInternal, WindowManagerService wm, Looper looper) {
            mAmInternal = amInternal;
            initialize(intentFirewall, intentController, looper);
            initRootActivityContainerMocks(wm);
            setWindowManager(wm);
            createDefaultDisplay();
        }

        void initRootActivityContainerMocks(WindowManagerService wm) {
            spyOn(mRootActivityContainer);
            mRootActivityContainer.setWindowContainer(mock(RootWindowContainer.class));
            mRootActivityContainer.mWindowManager = wm;
            mRootActivityContainer.mDisplayManager =
                    (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
            doNothing().when(mRootActivityContainer).setWindowManager(any());
            // Invoked during {@link ActivityStack} creation.
            doNothing().when(mRootActivityContainer).updateUIDsPresentOnDisplay();
            // Always keep things awake.
            doReturn(true).when(mRootActivityContainer).hasAwakeDisplay();
            // Called when moving activity to pinned stack.
            doNothing().when(mRootActivityContainer).ensureActivitiesVisible(any(), anyInt(),
                    anyBoolean());
        }

        void createDefaultDisplay() {
            // Create a default display and put a home stack on it so that we'll always have
            // something focusable.
            mDefaultDisplay = TestActivityDisplay.create(mStackSupervisor, DEFAULT_DISPLAY);
            spyOn(mDefaultDisplay);
            mRootActivityContainer.addChild(mDefaultDisplay, ActivityDisplay.POSITION_TOP);
            mDefaultDisplay.createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
            final TaskRecord task = new TaskBuilder(mStackSupervisor)
                    .setStack(mDefaultDisplay.getHomeStack()).build();
            new ActivityBuilder(this).setTask(task).build();

            doReturn(mDefaultDisplay).when(mRootActivityContainer).getDefaultDisplay();
        }

        @Override
        int handleIncomingUser(int callingPid, int callingUid, int userId, String name) {
            return userId;
        }

        @Override
        AppOpsService getAppOpsService() {
            return mAppOpsService;
        }

        @Override
        void updateCpuStats() {
        }

        @Override
        void updateBatteryStats(ActivityRecord component, boolean resumed) {
        }

        @Override
        void updateActivityUsageStats(ActivityRecord activity, int event) {
        }

        @Override
        protected ActivityStackSupervisor createStackSupervisor() {
            if (mTestStackSupervisor == null) {
                mTestStackSupervisor = new TestActivityStackSupervisor(this, mH.getLooper());
            }
            return mTestStackSupervisor;
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

        @Override
        PermissionPolicyInternal getPermissionPolicyInternal() {
            if (mPermissionPolicyInternal == null) {
                mPermissionPolicyInternal = mock(PermissionPolicyInternal.class);
                doReturn(true).when(mPermissionPolicyInternal).checkStartActivity(any(), anyInt(),
                        any());
            }
            return mPermissionPolicyInternal;
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
            // Make sure there are no running messages and then quit the thread so the next test
            // won't be affected.
            mHandlerThread.getThreadHandler().runWithScissors(mHandlerThread::quit,
                    0 /* timeout */);
        }
    }

    // TODO: Replace this with a mock object since we are no longer in AMS package.
    /**
     * An {@link ActivityManagerService} subclass which provides a test
     * {@link ActivityStackSupervisor}.
     */
    class TestActivityManagerService extends ActivityManagerService {

        TestActivityManagerService(TestInjector testInjector, TestActivityTaskManagerService atm) {
            super(testInjector, testInjector.mHandlerThread);
            spyOn(this);

            mWindowManager = prepareMockWindowManager();
            mUgmInternal = mock(UriGrantsManagerInternal.class);

            atm.setup(mIntentFirewall, mPendingIntentController, new LocalService(), mWindowManager,
                    testInjector.mHandlerThread.getLooper());

            mActivityTaskManager = atm;
            mAtmInternal = atm.mInternal;

            doReturn(mock(IPackageManager.class)).when(this).getPackageManager();
            PackageManagerInternal mockPackageManager = mock(PackageManagerInternal.class);
            doReturn(mockPackageManager).when(this).getPackageManagerInternalLocked();
            doReturn(null).when(mockPackageManager).getDefaultHomeActivity(anyInt());
            doNothing().when(this).grantEphemeralAccessLocked(anyInt(), any(), anyInt(), anyInt());
        }
    }

    /**
     * An {@link ActivityStackSupervisor} which stubs out certain methods that depend on
     * setup not available in the test environment. Also specifies an injector for
     */
    protected class TestActivityStackSupervisor extends ActivityStackSupervisor {
        private KeyguardController mKeyguardController;

        TestActivityStackSupervisor(ActivityTaskManagerService service, Looper looper) {
            super(service, looper);
            spyOn(this);
            mWindowManager = prepareMockWindowManager();
            mKeyguardController = mock(KeyguardController.class);

            // Do not schedule idle that may touch methods outside the scope of the test.
            doNothing().when(this).scheduleIdleLocked();
            doNothing().when(this).scheduleIdleTimeoutLocked(any());
            // unit test version does not handle launch wake lock
            doNothing().when(this).acquireLaunchWakelock();
            doReturn(mKeyguardController).when(this).getKeyguardController();

            mLaunchingActivityWakeLock = mock(PowerManager.WakeLock.class);

            initialize();
        }

        @Override
        public KeyguardController getKeyguardController() {
            return mKeyguardController;
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
                        supervisor.mRootActivityContainer.mDisplayManager.getDisplay(displayId));
            }
            final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                    info, DEFAULT_DISPLAY_ADJUSTMENTS);
            return new TestActivityDisplay(supervisor, display);
        }

        TestActivityDisplay(ActivityStackSupervisor supervisor, Display display) {
            super(supervisor.mService.mRootActivityContainer, display);
            // Normally this comes from display-properties as exposed by WM. Without that, just
            // hard-code to FULLSCREEN for tests.
            setWindowingMode(WINDOWING_MODE_FULLSCREEN);
            mSupervisor = supervisor;
        }

        @SuppressWarnings("TypeParameterUnusedInFormals")
        @Override
        ActivityStack createStackUnchecked(int windowingMode, int activityType,
                int stackId, boolean onTop) {
            return new StackBuilder(mSupervisor.mRootActivityContainer).setDisplay(this)
                    .setWindowingMode(windowingMode).setActivityType(activityType)
                    .setStackId(stackId).setOnTop(onTop).setCreateActivity(false).build();
        }

        @Override
        protected DisplayContent createDisplayContent() {
            final DisplayContent displayContent = mock(DisplayContent.class);
            DockedStackDividerController divider = mock(DockedStackDividerController.class);
            doReturn(divider).when(displayContent).getDockedDividerController();
            return displayContent;
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

    private static WindowManagerService sMockWindowManagerService;

    private static WindowManagerService prepareMockWindowManager() {
        if (sMockWindowManagerService == null) {
            sMockWindowManagerService = mock(WindowManagerService.class);
        }

        sMockWindowManagerService.mRoot = mock(RootWindowContainer.class);

        doAnswer((InvocationOnMock invocationOnMock) -> {
            final Runnable runnable = invocationOnMock.<Runnable>getArgument(0);
            if (runnable != null) {
                runnable.run();
            }
            return null;
        }).when(sMockWindowManagerService).inSurfaceTransaction(any());

        return sMockWindowManagerService;
    }

    /**
     * Overridden {@link ActivityStack} that tracks test metrics, such as the number of times a
     * method is called. Note that its functionality depends on the implementations of the
     * construction arguments.
     */
    protected static class TestActivityStack
            extends ActivityStack {
        private int mOnActivityRemovedFromStackCount = 0;

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
        protected void createTaskStack(int displayId, boolean onTop, Rect outBounds) {
            mTaskStack = mock(TaskStack.class);

            // Primary pinned stacks require a non-empty out bounds to be set or else all tasks
            // will be moved to the full screen stack.
            if (getWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                outBounds.set(0, 0, 100, 100);
            }
        }

        @Override
        TaskStack getTaskStack() {
            return mTaskStack;
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

    static class StackBuilder {
        private final RootActivityContainer mRootActivityContainer;
        private ActivityDisplay mDisplay;
        private int mStackId = -1;
        private int mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        private int mActivityType = ACTIVITY_TYPE_STANDARD;
        private boolean mOnTop = true;
        private boolean mCreateActivity = true;

        StackBuilder(RootActivityContainer root) {
            mRootActivityContainer = root;
            mDisplay = mRootActivityContainer.getDefaultDisplay();
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
        ActivityStack build() {
            final int stackId = mStackId >= 0 ? mStackId : mDisplay.getNextStackId();
            if (mWindowingMode == WINDOWING_MODE_PINNED) {
                return new ActivityStack(mDisplay, stackId, mRootActivityContainer.mStackSupervisor,
                        mWindowingMode, ACTIVITY_TYPE_STANDARD, mOnTop) {
                    @Override
                    Rect getDefaultPictureInPictureBounds(float aspectRatio) {
                        return new Rect(50, 50, 100, 100);
                    }

                    @Override
                    void createTaskStack(int displayId, boolean onTop, Rect outBounds) {
                        mTaskStack = mock(TaskStack.class);
                    }
                };
            } else {
                return new TestActivityStack(mDisplay, stackId,
                        mRootActivityContainer.mStackSupervisor, mWindowingMode,
                        mActivityType, mOnTop, mCreateActivity);
            }
        }

    }
}
