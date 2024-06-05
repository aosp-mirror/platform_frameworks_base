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

import static android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
import static android.app.Activity.RESULT_CANCELED;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.START_ABORTED;
import static android.app.ActivityManager.START_CANCELED;
import static android.app.ActivityManager.START_CLASS_NOT_FOUND;
import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
import static android.app.ActivityManager.START_INTENT_NOT_RESOLVED;
import static android.app.ActivityManager.START_NOT_VOICE_COMPATIBLE;
import static android.app.ActivityManager.START_PERMISSION_DENIED;
import static android.app.ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
import static android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.content.pm.ActivityInfo.FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;
import static android.content.pm.ActivityInfo.LAUNCH_MULTIPLE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.clearInvocations;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityStarter.canEmbedActivity;
import static com.android.server.wm.TaskFragment.EMBEDDING_ALLOWED;
import static com.android.server.wm.TaskFragment.EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION;
import static com.android.server.wm.TaskFragment.EMBEDDING_DISALLOWED_NEW_TASK;
import static com.android.server.wm.TaskFragment.EMBEDDING_DISALLOWED_UNTRUSTED_HOST;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowTestsBase.ActivityBuilder.DEFAULT_FAKE_UID;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;

import android.app.ActivityOptions;
import android.app.ActivityOptions.BackgroundActivityStartMode;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.IApplicationThread;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.WindowLayout;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SigningDetails;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.provider.DeviceConfig;
import android.service.voice.IVoiceInteractionSession;
import android.util.Pair;
import android.util.Size;
import android.view.Gravity;
import android.view.RemoteAnimationAdapter;
import android.window.TaskFragmentOrganizerToken;

import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.DeviceConfigStateHelper;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.am.PendingIntentRecord;
import com.android.server.pm.PackageArchiver;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.wm.BackgroundActivityStartController.BalVerdict;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;
import com.android.server.wm.utils.MockTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for the {@link ActivityStarter} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStarterTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityStarterTests extends WindowTestsBase {

    private static final int PRECONDITION_NO_CALLER_APP = 1;
    private static final int PRECONDITION_NO_INTENT_COMPONENT = 1 << 1;
    private static final int PRECONDITION_NO_ACTIVITY_INFO = 1 << 2;
    private static final int PRECONDITION_SOURCE_PRESENT = 1 << 3;
    private static final int PRECONDITION_REQUEST_CODE = 1 << 4;
    private static final int PRECONDITION_SOURCE_VOICE_SESSION = 1 << 5;
    private static final int PRECONDITION_NO_VOICE_SESSION_SUPPORT = 1 << 6;
    private static final int PRECONDITION_DIFFERENT_UID = 1 << 7;
    private static final int PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION = 1 << 8;
    private static final int PRECONDITION_CANNOT_START_ANY_ACTIVITY = 1 << 9;
    private static final int FAKE_CALLING_UID = 666;
    private static final int FAKE_REAL_CALLING_UID = 667;
    private static final String FAKE_CALLING_PACKAGE = "com.whatever.dude";
    private static final int UNIMPORTANT_UID = 12345;
    private static final int UNIMPORTANT_UID2 = 12346;
    private static final int CURRENT_IME_UID = 12347;

    protected final DeviceConfigStateHelper mDeviceConfig = new DeviceConfigStateHelper(
            DeviceConfig.NAMESPACE_WINDOW_MANAGER);

    private ActivityStartController mController;
    private ActivityMetricsLogger mActivityMetricsLogger;
    private PackageManagerInternal mMockPackageManager;
    private AppOpsManager mAppOpsManager;

    @Before
    public void setUp() throws Exception {
        mController = mock(ActivityStartController.class);
        BackgroundActivityStartController balController =
                new BackgroundActivityStartController(mAtm, mSupervisor);
        doReturn(balController).when(mAtm.mTaskSupervisor).getBackgroundActivityLaunchController();
        mActivityMetricsLogger = mock(ActivityMetricsLogger.class);
        clearInvocations(mActivityMetricsLogger);
        mAppOpsManager = mAtm.getAppOpsManager();
        doReturn(AppOpsManager.MODE_DEFAULT).when(mAppOpsManager).checkOpNoThrow(
                eq(AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION),
                anyInt(), any());
    }

    @After
    public void tearDown() throws Exception {
        mDeviceConfig.close();
    }

    @Test
    public void testStartActivityPreconditions() {
        verifyStartActivityPreconditions(PRECONDITION_NO_CALLER_APP, START_PERMISSION_DENIED);
        verifyStartActivityPreconditions(PRECONDITION_NO_INTENT_COMPONENT,
                START_INTENT_NOT_RESOLVED);
        verifyStartActivityPreconditions(PRECONDITION_NO_ACTIVITY_INFO, START_CLASS_NOT_FOUND);
        verifyStartActivityPreconditions(PRECONDITION_SOURCE_PRESENT | PRECONDITION_REQUEST_CODE,
                Intent.FLAG_ACTIVITY_FORWARD_RESULT, START_FORWARD_AND_REQUEST_CONFLICT);
        verifyStartActivityPreconditions(
                PRECONDITION_SOURCE_PRESENT | PRECONDITION_NO_VOICE_SESSION_SUPPORT
                        | PRECONDITION_SOURCE_VOICE_SESSION | PRECONDITION_DIFFERENT_UID,
                START_NOT_VOICE_COMPATIBLE);
        verifyStartActivityPreconditions(
                PRECONDITION_SOURCE_PRESENT | PRECONDITION_NO_VOICE_SESSION_SUPPORT
                        | PRECONDITION_SOURCE_VOICE_SESSION | PRECONDITION_DIFFERENT_UID
                        | PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION,
                START_NOT_VOICE_COMPATIBLE);
        verifyStartActivityPreconditions(PRECONDITION_CANNOT_START_ANY_ACTIVITY, START_ABORTED);
    }

    private static boolean containsConditions(int preconditions, int mask) {
        return (preconditions & mask) == mask;
    }

    private void verifyStartActivityPreconditions(int preconditions, int expectedResult) {
        verifyStartActivityPreconditions(preconditions, 0 /*launchFlags*/, expectedResult);
    }

    private void verifyStartActivityPreconditions(int preconditions, int launchFlags,
            int expectedResult) {
        // We track mocks created here because this is used in a single test
        // (testStartActivityPreconditions) as a specific case, and mocks created inside it won't be
        // used for other cases. To avoid extensive memory usage, we clean up all used mocks after
        // each case. This is necessary because usually we only clean up mocks after a test
        // finishes, but this test creates too many mocks that the intermediate memory usage can be
        // ~0.8 GiB and thus very susceptible to OutOfMemoryException.
        try (MockTracker tracker = new MockTracker()) {
            verifyStartActivityPreconditionsUntracked(preconditions, launchFlags, expectedResult);
        }
    }

    /**
     * Excercises how the {@link ActivityStarter} reacts to various preconditions. The caller
     * provides a bitmask of all the set conditions (such as {@link #PRECONDITION_NO_CALLER_APP})
     * and the launch flags specified in the intent. The method constructs a call to
     * {@link ActivityStarter#execute} based on these preconditions and ensures the result matches
     * the expected. It is important to note that the method also checks side effects of the start,
     * such as ensuring {@link ActivityOptions#abort()} is called in the relevant scenarios.
     *
     * @param preconditions A bitmask representing the preconditions for the launch
     * @param launchFlags The launch flags to be provided by the launch {@link Intent}.
     * @param expectedResult The expected result from the launch.
     */
    private void verifyStartActivityPreconditionsUntracked(int preconditions, int launchFlags,
            int expectedResult) {
        final ActivityTaskManagerService service = mAtm;
        final IPackageManager packageManager = mock(IPackageManager.class);

        final ActivityStarter starter =
                new ActivityStarter(
                        mController,
                        service,
                        service.mTaskSupervisor,
                        mock(ActivityStartInterceptor.class));
        prepareStarter(launchFlags);
        final IApplicationThread caller = mock(IApplicationThread.class);
        final WindowProcessListener listener = mock(WindowProcessListener.class);

        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = "com.android.test.package";
        final WindowProcessController wpc =
                containsConditions(preconditions, PRECONDITION_NO_CALLER_APP)
                        ? null
                        : new WindowProcessController(service, ai, null, 0, -1, null, listener);
        doReturn(wpc).when(service).getProcessController(any());

        final Intent intent = new Intent();
        intent.setFlags(launchFlags);

        final ActivityInfo aInfo = containsConditions(preconditions, PRECONDITION_NO_ACTIVITY_INFO)
                ?  null : new ActivityInfo();

        IVoiceInteractionSession voiceSession =
                containsConditions(preconditions, PRECONDITION_SOURCE_VOICE_SESSION)
                        ? mock(IVoiceInteractionSession.class) : null;

        // Create source token
        final ActivityBuilder builder = new ActivityBuilder(service).setTask(
                new TaskBuilder(service.mTaskSupervisor)
                        .setVoiceSession(voiceSession)
                        .setCreateParentTask(true)
                        .build());

        if (aInfo != null) {
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.packageName =
                    ActivityBuilder.getDefaultComponent().getPackageName();
        }

        // Offset uid by one from {@link ActivityInfo} to simulate different uids.
        if (containsConditions(preconditions, PRECONDITION_DIFFERENT_UID)) {
            builder.setUid(aInfo.applicationInfo.uid + 1);
        }

        final ActivityRecord source = builder.build();

        if (!containsConditions(preconditions, PRECONDITION_NO_INTENT_COMPONENT)) {
            intent.setComponent(source.mActivityComponent);
        }

        if (containsConditions(preconditions, PRECONDITION_CANNOT_START_ANY_ACTIVITY)) {
            doReturn(false).when(service.mTaskSupervisor).checkStartAnyActivityPermission(
                    any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any());
        }

        try {
            if (containsConditions(preconditions,
                    PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION)) {
                doAnswer((inv) -> {
                    throw new RemoteException();
                }).when(packageManager).activitySupportsIntentAsUser(
                        eq(source.mActivityComponent), eq(intent), any(), anyInt());
            } else {
                doReturn(!containsConditions(preconditions, PRECONDITION_NO_VOICE_SESSION_SUPPORT))
                        .when(packageManager).activitySupportsIntentAsUser(
                                eq(source.mActivityComponent), eq(intent), any(), anyInt());
            }
        } catch (RemoteException e) {
        }

        final IBinder resultTo = containsConditions(preconditions, PRECONDITION_SOURCE_PRESENT)
                || containsConditions(preconditions, PRECONDITION_SOURCE_VOICE_SESSION)
                ? source.token : null;

        final int requestCode = containsConditions(preconditions, PRECONDITION_REQUEST_CODE)
                ? 1 : 0;

        final int result = starter.setCaller(caller)
                .setIntent(intent)
                .setActivityInfo(aInfo)
                .setResultTo(resultTo)
                .setRequestCode(requestCode)
                .setReason("testLaunchActivityPermissionDenied")
                .execute();

        // In some cases the expected result internally is different than the published result. We
        // must use ActivityStarter#getExternalResult to translate.
        assertEquals(ActivityStarter.getExternalResult(expectedResult), result);

        // Ensure that {@link ActivityOptions} are aborted with unsuccessful result.
        if (expectedResult != START_SUCCESS) {
            final ActivityStarter optionStarter = new ActivityStarter(mController, mAtm,
                    mAtm.mTaskSupervisor, mock(ActivityStartInterceptor.class));
            final ActivityOptions options = spy(ActivityOptions.makeBasic());

            final int optionResult = optionStarter.setCaller(caller)
                    .setIntent(intent)
                    .setActivityInfo(aInfo)
                    .setResultTo(resultTo)
                    .setRequestCode(requestCode)
                    .setReason("testLaunchActivityPermissionDenied")
                    .setActivityOptions(new SafeActivityOptions(options))
                    .execute();
            verify(options, times(1)).abort();
        }
    }

    private ActivityStarter prepareStarter(@Intent.Flags int launchFlags) {
        return prepareStarter(launchFlags, true /* mockGetRootTask */, LAUNCH_MULTIPLE);
    }

    private ActivityStarter prepareStarter(@Intent.Flags int launchFlags,
            boolean mockGetRootTask) {
        return prepareStarter(launchFlags, mockGetRootTask, LAUNCH_MULTIPLE);
    }

    private void setupImeWindow() {
        final WindowState imeWindow = createWindow(null, W_INPUT_METHOD,
                "mImeWindow", CURRENT_IME_UID);
        mDisplayContent.mInputMethodWindow = imeWindow;
    }

    /**
     * Creates a {@link ActivityStarter} with default parameters and necessary mocks.
     *
     * @param launchFlags The intent flags to launch activity.
     * @param mockGetRootTask Whether to mock {@link RootWindowContainer#getOrCreateRootTask} for
     *                           always launching to the testing stack. Set to false when allowing
     *                           the activity can be launched to any stack that is decided by real
     *                           implementation.
     * @return A {@link ActivityStarter} with default setup.
     */
    private ActivityStarter prepareStarter(@Intent.Flags int launchFlags,
            boolean mockGetRootTask, int launchMode) {
        // always allow test to start activity.
        doReturn(true).when(mSupervisor).checkStartAnyActivityPermission(
                any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any());

        if (mockGetRootTask) {
            // Instrument the stack and task used.
            final Task stack = mRootWindowContainer.getDefaultTaskDisplayArea()
                    .createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                            true /* onTop */);

            // Direct starter to use spy stack.
            doReturn(stack).when(mRootWindowContainer)
                    .getOrCreateRootTask(any(), any(), any(), anyBoolean());
            doReturn(stack).when(mRootWindowContainer).getOrCreateRootTask(any(), any(), any(),
                    any(), anyBoolean(), any(), anyInt());
        }

        // Set up mock package manager internal and make sure no unmocked methods are called
        mMockPackageManager = mock(PackageManagerInternal.class,
                invocation -> {
                    throw new RuntimeException("Not stubbed");
                });
        doReturn(null).when(mMockPackageManager).getDefaultHomeActivity(anyInt());
        doReturn(mMockPackageManager).when(mAtm).getPackageManagerInternalLocked();
        doReturn("packageName").when(mMockPackageManager).getNameForUid(anyInt());
        doReturn(false).when(mMockPackageManager).isInstantAppInstallerComponent(any());
        doReturn(null).when(mMockPackageManager).resolveIntent(any(), any(), anyLong(), anyLong(),
                anyInt(), anyBoolean(), anyInt(), anyInt());
        doReturn(new ComponentName("", "")).when(mMockPackageManager).getSystemUiServiceComponent();

        // Never review permissions
        doReturn(false).when(mMockPackageManager).isPermissionsReviewRequired(any(), anyInt());
        doNothing().when(mMockPackageManager).grantImplicitAccess(
                anyInt(), any(), anyInt(), anyInt(), anyBoolean());
        doNothing().when(mMockPackageManager).notifyPackageUse(anyString(), anyInt());
        doReturn(mock(PackageArchiver.class)).when(mMockPackageManager).getPackageArchiver();

        final AndroidPackage mockPackage = mock(AndroidPackage.class);
        final SigningDetails signingDetails = mock(SigningDetails.class);
        doReturn(mockPackage).when(mMockPackageManager).getPackage(anyInt());
        doReturn(signingDetails).when(mockPackage).getSigningDetails();
        doReturn(false).when(signingDetails).hasAncestorOrSelfWithDigest(any());

        final Intent intent = new Intent();
        intent.addFlags(launchFlags);
        intent.setComponent(ActivityBuilder.getDefaultComponent());

        final ActivityInfo info = new ActivityInfo();

        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = ActivityBuilder.getDefaultComponent().getPackageName();
        info.launchMode = launchMode;

        return new ActivityStarter(mController, mAtm,
                mAtm.mTaskSupervisor, mock(ActivityStartInterceptor.class))
                .setIntent(intent)
                .setActivityInfo(info);
    }

    /**
     * Ensures that values specified at launch time are passed to {@link LaunchParamsModifier}
     * when we are laying out a new task.
     */
    @Test
    public void testCreateTaskLayout() {
        // modifier for validating passed values.
        final LaunchParamsModifier modifier = mock(LaunchParamsModifier.class);
        mAtm.mTaskSupervisor.getLaunchParamsController().registerModifier(modifier);

        // add custom values to activity info to make unique.
        final ActivityInfo info = new ActivityInfo();
        final Rect launchBounds = new Rect(0, 0, 20, 30);

        final WindowLayout windowLayout =
                new WindowLayout(10, .5f, 20, 1.0f, Gravity.NO_GRAVITY, 1, 1);

        info.windowLayout = windowLayout;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = ActivityBuilder.getDefaultComponent().getPackageName();

        // create starter.
        final ActivityStarter optionStarter = prepareStarter(0 /* launchFlags */);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchBounds(launchBounds);

        // run starter.
        optionStarter
                .setReason("testCreateTaskLayout")
                .setActivityInfo(info)
                .setActivityOptions(new SafeActivityOptions(options))
                .execute();

        // verify that values are passed to the modifier. Values are passed thrice -- two for
        // setting initial state, another when task is created.
        verify(modifier, times(3)).onCalculate(any(), eq(windowLayout), any(), any(), eq(options),
                any(), anyInt(), any(), any());
    }

    /**
     * This test ensures that if the intent is being delivered to a split-screen unfocused task
     * while it already on top, reports it as delivering to top.
     */
    @Test
    public void testSplitScreenDeliverToTop() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP,
                false /* mockGetRootTask */);
        final Pair<ActivityRecord, ActivityRecord> activities = createActivitiesInSplit();
        final ActivityRecord splitPrimaryFocusActivity = activities.first;
        final ActivityRecord splitSecondReusableActivity = activities.second;

        // Set focus back to primary.
        splitPrimaryFocusActivity.moveFocusableActivityToTop("testSplitScreenDeliverToTop");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(splitSecondReusableActivity.mActivityComponent);
        doReturn(splitSecondReusableActivity).when(mRootWindowContainer).findTask(any(), any());
        final int result = starter.setReason("testSplitScreenDeliverToTop").execute();

        // Ensure result is delivering intent to top.
        assertEquals(START_DELIVERED_TO_TOP, result);
    }

    /**
     * This test ensures that if the intent is being delivered to a split-screen unfocused task
     * reports it is brought to front instead of delivering to top.
     */
    @Test
    public void testSplitScreenTaskToFront() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP, false);
        final Pair<ActivityRecord, ActivityRecord> activities = createActivitiesInSplit();
        final ActivityRecord splitPrimaryFocusActivity = activities.first;
        final ActivityRecord splitSecondReusableActivity = activities.second;
        final ActivityRecord splitSecondTopActivity = new ActivityBuilder(mAtm).setCreateTask(true)
                .setParentTask(splitSecondReusableActivity.getRootTask()).build();
        assertTrue(splitSecondTopActivity.inMultiWindowMode());

        // Let primary stack has focus.
        splitPrimaryFocusActivity.moveFocusableActivityToTop("testSplitScreenTaskToFront");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(splitSecondReusableActivity.mActivityComponent);
        doReturn(splitSecondReusableActivity).when(mRootWindowContainer).findTask(any(), any());
        final int result = starter.setReason("testSplitScreenMoveToFront").execute();

        // Ensure result is moving task to front.
        assertEquals(START_TASK_TO_FRONT, result);
    }

    /** Returns 2 activities. The first is in primary and the second is in secondary. */
    private Pair<ActivityRecord, ActivityRecord> createActivitiesInSplit() {
        final TestSplitOrganizer splitOrg = new TestSplitOrganizer(mAtm);
        // The fullscreen windowing mode activity will be moved to split-secondary by
        // TestSplitOrganizer when a split-primary task appears.
        final ActivityRecord splitPrimaryActivity = new TaskBuilder(mSupervisor)
                .setParentTask(splitOrg.mPrimary)
                .setCreateActivity(true)
                .build()
                .getTopMostActivity();
        final ActivityRecord splitSecondActivity = new TaskBuilder(mSupervisor)
                .setParentTask(splitOrg.mSecondary)
                .setCreateActivity(true)
                .build()
                .getTopMostActivity();

        splitPrimaryActivity.setVisibleRequested(true);
        splitSecondActivity.setVisibleRequested(true);

        assertEquals(splitOrg.mPrimary, splitPrimaryActivity.getRootTask());
        assertEquals(splitOrg.mSecondary, splitSecondActivity.getRootTask());
        return Pair.create(splitPrimaryActivity, splitSecondActivity);
    }

    /**
     * This test ensures that if the intent is being delivered to a desktop mode unfocused task
     * while it is already on top, reports it as delivering to top.
     */
    @Test
    public void testDesktopModeDeliverToTop() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP,
                false /* mockGetRootTask */);
        final List<ActivityRecord> activities = createActivitiesInDesktopMode();

        // Set focus back to the first task.
        activities.get(0).moveFocusableActivityToTop("testDesktopModeDeliverToTop");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(activities.get(3).mActivityComponent);
        doReturn(activities.get(3)).when(mRootWindowContainer).findTask(any(), any());
        final int result = starter.setReason("testDesktopModeDeliverToTop").execute();

        // Ensure result is delivering intent to top.
        assertEquals(START_DELIVERED_TO_TOP, result);
    }

    /**
     * This test ensures that if the intent is being delivered to a desktop mode unfocused task
     * reports it is brought to front instead of delivering to top.
     */
    @Test
    public void testDesktopModeTaskToFront() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP, false);
        final List<ActivityRecord> activities = createActivitiesInDesktopMode();
        final ActivityRecord desktopModeFocusActivity = activities.get(0);
        final ActivityRecord desktopModeReusableActivity = activities.get(1);
        final ActivityRecord desktopModeTopActivity = new ActivityBuilder(mAtm).setCreateTask(true)
                .setParentTask(desktopModeReusableActivity.getRootTask()).build();
        assertTrue(desktopModeTopActivity.inMultiWindowMode());

        // Let first stack has focus.
        desktopModeFocusActivity.moveFocusableActivityToTop("testDesktopModeTaskToFront");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(desktopModeReusableActivity.mActivityComponent);
        doReturn(desktopModeReusableActivity).when(mRootWindowContainer).findTask(any(), any());
        final int result = starter.setReason("testDesktopModeMoveToFront").execute();

        // Ensure result is moving task to front.
        assertEquals(START_TASK_TO_FRONT, result);
    }

    /** Returns 4 activities. */
    private List<ActivityRecord> createActivitiesInDesktopMode() {
        final TestDesktopOrganizer desktopOrganizer = new TestDesktopOrganizer(mAtm);
        List<ActivityRecord> activityRecords = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            Rect bounds = new Rect(desktopOrganizer.getDefaultDesktopTaskBounds());
            bounds.offset(20 * i, 20 * i);
            desktopOrganizer.createTask(bounds);
        }

        for (int i = 0; i < 4; i++) {
            activityRecords.add(new TaskBuilder(mSupervisor)
                    .setParentTask(desktopOrganizer.mTasks.get(i))
                    .setCreateActivity(true)
                    .build()
                    .getTopMostActivity());
        }

        for (int i = 0; i < 4; i++) {
            activityRecords.get(i).setVisibleRequested(true);
        }

        for (int i = 0; i < 4; i++) {
            assertEquals(desktopOrganizer.mTasks.get(i), activityRecords.get(i).getRootTask());
        }

        return activityRecords;
    }

    @Test
    public void testMoveVisibleTaskToFront() {
        final ActivityRecord activity = new TaskBuilder(mSupervisor)
                .setCreateActivity(true).build().getTopMostActivity();
        final ActivityRecord translucentActivity = new TaskBuilder(mSupervisor)
                .setCreateActivity(true).build().getTopMostActivity();
        assertTrue(activity.isVisibleRequested());

        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);
        starter.getIntent().setComponent(activity.mActivityComponent);
        final int result = starter.setReason("testMoveVisibleTaskToFront").execute();

        assertEquals(START_TASK_TO_FRONT, result);
        assertEquals(1, activity.compareTo(translucentActivity));
    }

    /**
     * Tests activity is cleaned up properly in a task mode violation.
     */
    @Test
    public void testTaskModeViolation() {
        final DisplayContent display = mAtm.mRootWindowContainer.getDefaultDisplay();
        display.removeAllTasks();
        assertNoTasks(display);

        final ActivityStarter starter = prepareStarter(0);

        final LockTaskController lockTaskController = mAtm.getLockTaskController();
        doReturn(true).when(lockTaskController).isNewTaskLockTaskModeViolation(any());

        final int result = starter.setReason("testTaskModeViolation").execute();

        assertEquals(START_RETURN_LOCK_TASK_MODE_VIOLATION, result);
        assertNoTasks(display);
    }

    private void assertNoTasks(DisplayContent display) {
        display.forAllRootTasks(rootTask -> {
            assertFalse(rootTask.hasChild());
        });
    }

    /**
     * This test ensures that activity starts are not being logged when the logging is disabled.
     */
    @Test
    public void testActivityStartsLogging_noLoggingWhenDisabled() {
        doReturn(false).when(mAtm).isActivityStartsLoggingEnabled();
        doReturn(mActivityMetricsLogger).when(mAtm.mTaskSupervisor).getActivityMetricsLogger();

        ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK);
        starter.setReason("testActivityStartsLogging_noLoggingWhenDisabled").execute();

        // verify logging wasn't done
        verify(mActivityMetricsLogger, never()).logAbortedBgActivityStart(any(), any(), anyInt(),
                any(), anyInt(), anyBoolean(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    /**
     * This test ensures that activity starts are being logged when the logging is enabled.
     */
    @Test
    public void testActivityStartsLogging_logsWhenEnabled() {
        // note: conveniently this package doesn't have any activity visible
        doReturn(true).when(mAtm).isActivityStartsLoggingEnabled();
        doReturn(mActivityMetricsLogger).when(mAtm.mTaskSupervisor).getActivityMetricsLogger();

        ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK)
                .setCallingUid(FAKE_CALLING_UID)
                .setRealCallingUid(FAKE_REAL_CALLING_UID)
                .setCallingPackage(FAKE_CALLING_PACKAGE)
                .setOriginatingPendingIntent(null);

        starter.setReason("testActivityStartsLogging_logsWhenEnabled").execute();

        // verify the above activity start was logged
        verify(mActivityMetricsLogger, times(1)).logAbortedBgActivityStart(any(), any(),
                eq(FAKE_CALLING_UID), eq(FAKE_CALLING_PACKAGE), anyInt(), anyBoolean(),
                eq(FAKE_REAL_CALLING_UID), anyInt(), anyBoolean(), eq(false));
    }

    /**
     * This test ensures that unsupported usecases aren't aborted when background starts are
     * allowed.
     */
    @Test
    public void testBackgroundActivityStartsAllowed_noStartsAborted() {
        doReturn(true).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest("allowed_noStartsAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that unsupported usecases are aborted when background starts are
     * disallowed.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_unsupportedUsecaseAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_unsupportedUsecase_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that unsupported usecases are aborted when background starts are
     * disallowed.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_callingUidProcessStateTopAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callingUidProcessStateTop_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that unsupported usecases are aborted when background starts are
     * disallowed.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_realCallingUidProcessStateTopAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_realCallingUidProcessStateTop_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that unsupported usecases are aborted when background starts are
     * disallowed.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_hasForegroundActivitiesAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_hasForegroundActivities_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                true, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that unsupported usecases are aborted when background starts are
     * disallowed.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_pinnedSingleInstanceAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_pinned_singleinstance_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, true, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the calling process runs as ROOT_UID.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_rootUidNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest("disallowed_rootUid_notAborted", false,
                Process.ROOT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the calling process is running as SYSTEM_UID.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_systemUidNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest("disallowed_systemUid_notAborted", false,
                Process.SYSTEM_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the calling process is running as NFC_UID.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_nfcUidNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest("disallowed_nfcUid_notAborted", false,
                Process.NFC_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the calling process has a visible window.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_callingUidHasVisibleWindowNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callingUidHasVisibleWindow_notAborted", false,
                UNIMPORTANT_UID, true, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * The sending app has a visible window, but does not (by default) allow the pending intent to
     * start the background activity.
     */
    @Test
    @Ignore("b/266015587")
    public void testBackgroundActivityStartsDisallowed_realCallingUidHasVisibleWindowAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();

        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_realCallingUidHasVisibleWindow_abortedInU", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, true, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the caller is in the recent activity list.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_callerIsRecentsNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callerIsRecents_notAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, true, false, false, false, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the caller is temporarily (10s) allowed to start.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_callerIsAllowedNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callerIsAllowed_notAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, true, false, false, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the caller explicitly has background activity start privilege.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_callerIsInstrumentingWithBASPnotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callerIsInstrumentingWithBackgroundActivityStartPrivileges_notAborted",
                false,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, true, false, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the caller is a device owner.
     */
    @Test
    public void
            testBackgroundActivityStartsDisallowed_callingPackageNameIsDeviceOwnerNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callingPackageNameIsDeviceOwner_notAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, true, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the caller is a affiliated profile owner.
     */
    @Test
    public void
            testBackgroundActivityStartsDisallowed_isAffiliatedProfileOwnerNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callingUidIsAffiliatedProfileOwner_notAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, true, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the caller has the OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_callerHasSystemExemptAppOpNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callerHasSystemExemptAppOpNotAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, true);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed. Each scenarios tests one condition that makes them supported in isolation. In
     * this case the caller is an IME.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_callingPackageNameIsImeNotAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        setupImeWindow();
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callingPackageNameIsIme_notAborted", false,
                CURRENT_IME_UID, false, PROCESS_STATE_BOUND_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                false, false, false, false, false, false, false, false);
    }

    /**
     * This test ensures proper logging for BAL_ALLOW_PERMISSION.
     */
    @Test
    public void testBackgroundActivityStartsAllowed_logging() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        MockitoSession mockingSession = mockitoSession()
                .mockStatic(ActivityTaskManagerService.class)
                .mockStatic(FrameworkStatsLog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            doReturn(PERMISSION_GRANTED).when(() -> ActivityTaskManagerService.checkPermission(
                    eq(START_ACTIVITIES_FROM_BACKGROUND),
                    anyInt(), anyInt()));
            runAndVerifyBackgroundActivityStartsSubtest(
                    "allowed_notAborted", false,
                    UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                    UNIMPORTANT_UID2, false, PROCESS_STATE_BOUND_TOP,
                    false, true, false, false, false, false, false, false);
            verify(() -> FrameworkStatsLog.write(FrameworkStatsLog.BAL_ALLOWED,
                    "",  // activity name
                    BackgroundActivityStartController.BAL_ALLOW_PERMISSION,
                    UNIMPORTANT_UID,
                    UNIMPORTANT_UID2,
                    BackgroundActivityStartController.BAL_ALLOW_PERMISSION,
                    true, // opt in
                    false, // but no explicit opt in
                    BackgroundActivityStartController.BAL_BLOCK,
                    true, // opt in
                    false // but no explicit opt in
            ));
        } finally {
            mockingSession.finishMocking();
        }
    }

    /**
     * This test ensures proper logging for BAL_ALLOW_PENDING_INTENT, when the PendingIntent sender
     * is the only reason BAL is allowed.
     */
    @Test
    @RequiresFlagsDisabled(com.android.window.flags.Flags.FLAG_BAL_IMPROVED_METRICS)
    public void testBackgroundActivityStartsAllowed_loggingOnlyPendingIntentAllowed() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        MockitoSession mockingSession = mockitoSession()
                .mockStatic(ActivityTaskManagerService.class)
                .mockStatic(FrameworkStatsLog.class)
                .mockStatic(PendingIntentRecord.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            doReturn(PERMISSION_GRANTED).when(() -> ActivityTaskManagerService.checkPermission(
                    eq(START_ACTIVITIES_FROM_BACKGROUND),
                    anyInt(), anyInt()));
            doReturn(BackgroundStartPrivileges.allowBackgroundActivityStarts(null)).when(
                    () -> PendingIntentRecord.getBackgroundStartPrivilegesAllowedByCaller(
                            anyObject(), anyInt(), anyObject()));
            runAndVerifyBackgroundActivityStartsSubtest(
                    "allowed_notAborted", false,
                    UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                    Process.SYSTEM_UID, true, PROCESS_STATE_BOUND_TOP,
                    false, true, false, false, false, false, false, false,
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED);
            verify(() -> FrameworkStatsLog.write(FrameworkStatsLog.BAL_ALLOWED,
                    DEFAULT_COMPONENT_PACKAGE_NAME + "/" + DEFAULT_COMPONENT_PACKAGE_NAME,
                    BackgroundActivityStartController.BAL_ALLOW_PENDING_INTENT,
                    UNIMPORTANT_UID,
                    Process.SYSTEM_UID,
                    BackgroundActivityStartController.BAL_ALLOW_PERMISSION,
                    false, // opt in
                    true, // explicit opt out
                    BackgroundActivityStartController.BAL_ALLOW_VISIBLE_WINDOW,
                    true, // opt in
                    false // but no explicit opt in
            ));
        } finally {
            mockingSession.finishMocking();
        }
    }

    /**
     * This test ensures proper logging for BAL_ALLOW_PENDING_INTENT, when the PendingIntent sender
     * is not the primary reason to allow BAL (but the creator).
     */
    @Test
    @RequiresFlagsDisabled(com.android.window.flags.Flags.FLAG_BAL_IMPROVED_METRICS)
    public void testBackgroundActivityStartsAllowed_loggingPendingIntentAllowed() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        MockitoSession mockingSession = mockitoSession()
                .mockStatic(ActivityTaskManagerService.class)
                .mockStatic(FrameworkStatsLog.class)
                .mockStatic(PendingIntentRecord.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        try {
            doReturn(PERMISSION_GRANTED).when(() -> ActivityTaskManagerService.checkPermission(
                    eq(START_ACTIVITIES_FROM_BACKGROUND),
                    anyInt(), anyInt()));
            doReturn(BackgroundStartPrivileges.allowBackgroundActivityStarts(null)).when(
                    () -> PendingIntentRecord.getBackgroundStartPrivilegesAllowedByCaller(
                            anyObject(), anyInt(), anyObject()));
            runAndVerifyBackgroundActivityStartsSubtest(
                    "allowed_notAborted", false,
                    UNIMPORTANT_UID, false, PROCESS_STATE_BOUND_TOP,
                    Process.SYSTEM_UID, true, PROCESS_STATE_BOUND_TOP,
                    false, true, false, false, false, false, false, false,
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            verify(() -> FrameworkStatsLog.write(FrameworkStatsLog.BAL_ALLOWED,
                    "",
                    BackgroundActivityStartController.BAL_ALLOW_PERMISSION,
                    UNIMPORTANT_UID,
                    Process.SYSTEM_UID,
                    BackgroundActivityStartController.BAL_ALLOW_PERMISSION,
                    true, // opt in
                    true, // explicit opt in
                    BackgroundActivityStartController.BAL_ALLOW_VISIBLE_WINDOW,
                    true, // opt in
                    false // but no explicit opt in
            ));
        } finally {
            mockingSession.finishMocking();
        }
    }

    private void runAndVerifyBackgroundActivityStartsSubtest(String name, boolean shouldHaveAborted,
            int callingUid, boolean callingUidHasVisibleWindow, int callingUidProcState,
            int realCallingUid, boolean realCallingUidHasVisibleWindow, int realCallingUidProcState,
            boolean hasForegroundActivities, boolean callerIsRecents,
            boolean callerIsTempAllowed,
            boolean callerIsInstrumentingWithBackgroundActivityStartPrivileges,
            boolean isCallingUidDeviceOwner,
            boolean isCallingUidAffiliatedProfileOwner,
            boolean isPinnedSingleInstance,
            boolean hasSystemExemptAppOp) {
        runAndVerifyBackgroundActivityStartsSubtest(name, shouldHaveAborted, callingUid,
                callingUidHasVisibleWindow, callingUidProcState, realCallingUid,
                realCallingUidHasVisibleWindow,  realCallingUidProcState, hasForegroundActivities,
                callerIsRecents, callerIsTempAllowed,
                callerIsInstrumentingWithBackgroundActivityStartPrivileges,
                isCallingUidDeviceOwner, isCallingUidAffiliatedProfileOwner, isPinnedSingleInstance,
                hasSystemExemptAppOp,
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);
    }

    private void runAndVerifyBackgroundActivityStartsSubtest(String name, boolean shouldHaveAborted,
            int callingUid, boolean callingUidHasVisibleWindow, int callingUidProcState,
            int realCallingUid, boolean realCallingUidHasVisibleWindow, int realCallingUidProcState,
            boolean hasForegroundActivities, boolean callerIsRecents,
            boolean callerIsTempAllowed,
            boolean callerIsInstrumentingWithBackgroundActivityStartPrivileges,
            boolean isCallingUidDeviceOwner,
            boolean isCallingUidAffiliatedProfileOwner,
            boolean isPinnedSingleInstance,
            boolean hasSystemExemptAppOp,
            @BackgroundActivityStartMode int pendingIntentCreatorBackgroundActivityStartMode) {
        // window visibility
        doReturn(callingUidHasVisibleWindow).when(mAtm).hasActiveVisibleWindow(callingUid);
        doReturn(realCallingUidHasVisibleWindow).when(mAtm).hasActiveVisibleWindow(realCallingUid);
        // process importance
        mAtm.mActiveUids.onUidActive(callingUid, callingUidProcState);
        mAtm.mActiveUids.onUidActive(realCallingUid, realCallingUidProcState);
        // foreground activities
        final IApplicationThread caller = mock(IApplicationThread.class);
        final WindowProcessListener listener = mock(WindowProcessListener.class);
        final ApplicationInfo ai = new ApplicationInfo();
        ai.uid = callingUid;
        ai.packageName = "com.android.test.package";
        final WindowProcessController callerApp =
                spy(new WindowProcessController(mAtm, ai, null, callingUid, -1, null, listener));
        doReturn(hasForegroundActivities).when(callerApp).hasForegroundActivities();
        doReturn(callerApp).when(mAtm).getProcessController(caller);
        // caller is recents
        RecentTasks recentTasks = mock(RecentTasks.class);
        mAtm.mTaskSupervisor.setRecentTasks(recentTasks);
        doReturn(callerIsRecents).when(recentTasks).isCallerRecents(callingUid);
        // caller is temp allowed
        if (callerIsTempAllowed) {
            callerApp.addOrUpdateBackgroundStartPrivileges(new Binder(),
                    BackgroundStartPrivileges.ALLOW_BAL);
        }
        // caller is instrumenting with background activity starts privileges
        callerApp.setInstrumenting(callerIsInstrumentingWithBackgroundActivityStartPrivileges,
                callerIsInstrumentingWithBackgroundActivityStartPrivileges ? Process.SHELL_UID : -1,
                callerIsInstrumentingWithBackgroundActivityStartPrivileges);
        // callingUid is the device owner
        doReturn(isCallingUidDeviceOwner).when(mAtm).isDeviceOwner(callingUid);
        // callingUid is the affiliated profile owner
        doReturn(isCallingUidAffiliatedProfileOwner).when(mAtm)
            .isAffiliatedProfileOwner(callingUid);

        // caller has OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop
        doReturn(hasSystemExemptAppOp ? AppOpsManager.MODE_ALLOWED
                : AppOpsManager.MODE_DEFAULT).when(mAppOpsManager).checkOpNoThrow(
                eq(AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION),
                anyInt(), any());

        int launchMode = LAUNCH_MULTIPLE;
        if (isPinnedSingleInstance) {
            final ActivityRecord baseActivity =
                    new ActivityBuilder(mAtm).setCreateTask(true).build();
            baseActivity.getRootTask()
                    .setWindowingMode(WINDOWING_MODE_PINNED);
            doReturn(baseActivity).when(mRootWindowContainer).findTask(any(), any());
            launchMode = LAUNCH_SINGLE_INSTANCE;
        }

        ActivityOptions rawOptions = ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        pendingIntentCreatorBackgroundActivityStartMode);
        final ActivityOptions options = spy(rawOptions);
        ActivityRecord[] outActivity = new ActivityRecord[1];
        ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_NEW_TASK, true, launchMode)
                .setCallingPackage("com.whatever.dude")
                .setCaller(caller)
                .setCallingUid(callingUid)
                .setRealCallingUid(realCallingUid)
                .setActivityOptions(new SafeActivityOptions(options))
                .setOutActivity(outActivity);

        final int result = starter.setReason("testBackgroundActivityStarts_" + name).execute();

        assertEquals(ActivityStarter.getExternalResult(
                shouldHaveAborted ? START_ABORTED : START_SUCCESS), result);
        verify(options, times(shouldHaveAborted ? 1 : 0)).abort();

        final ActivityRecord startedActivity = outActivity[0];
        if (startedActivity != null && startedActivity.getTask() != null) {
            // Remove the activity so it doesn't interfere with with subsequent activity launch
            // tests from this method.
            startedActivity.getTask().removeChild(startedActivity);
        }
    }

    /**
     * This test ensures that {@link ActivityStarter#setTargetRootTaskIfNeeded} will task the
     * adjacent task of indicated launch target into account. So the existing task will be launched
     * into closer target.
     */
    @Test
    public void testAdjustLaunchTargetWithAdjacentTask() {
        // Create adjacent tasks and put one activity under it
        final Task parent = new TaskBuilder(mSupervisor).build();
        final Task adjacentParent = new TaskBuilder(mSupervisor).build();
        parent.setAdjacentTaskFragment(adjacentParent);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setParentTask(parent)
                .setCreateTask(true).build();

        // Launch the activity to its adjacent parent
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchRootTask(adjacentParent.mRemoteToken.toWindowContainerToken());
        prepareStarter(FLAG_ACTIVITY_NEW_TASK, false /* mockGetRootTask */)
                .setReason("testAdjustLaunchTargetWithAdjacentTask")
                .setIntent(activity.intent)
                .setActivityOptions(options.toBundle())
                .execute();

        // Verify the activity will be launched into the original parent
        assertTrue(activity.isDescendantOf(parent));
    }

    /**
     * This test ensures that {@link ActivityStarter#setTargetRootTaskIfNeeded} will
     * move the existing task to front if the current focused root task doesn't have running task.
     */
    @Test
    public void testBringTaskToFrontWhenFocusedTaskIsFinishing() {
        // Put 2 tasks in the same root task (simulate the behavior of home root task).
        final Task rootTask = new TaskBuilder(mSupervisor).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setParentTask(rootTask)
                .setCreateTask(true).build();
        new ActivityBuilder(mAtm)
                .setParentTask(activity.getRootTask())
                .setCreateTask(true).build();

        // Create a top finishing activity.
        final ActivityRecord finishingTopActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true).build();
        finishingTopActivity.getRootTask().moveToFront("finishingTopActivity");

        assertEquals(finishingTopActivity, mRootWindowContainer.topRunningActivity());
        finishingTopActivity.finishing = true;

        // Launch the bottom task of the target root task.
        prepareStarter(FLAG_ACTIVITY_NEW_TASK, false /* mockGetRootTask */)
                .setReason("testBringTaskToFrontWhenFocusedTaskIsFinishing")
                .setIntent(activity.intent.addFlags(
                        FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                .execute();
        verify(activity.getRootTask()).startActivityLocked(any(), any(), anyBoolean(),
                eq(true) /* isTaskSwitch */, any(), any());
        // The hierarchies of the activity should move to front.
        assertEquals(activity.getTask(), mRootWindowContainer.topRunningActivity().getTask());
    }

    /**
     * This test ensures that when starting an existing single task activity on secondary display
     * which is not the top focused display, it should deliver new intent to the activity and not
     * create a new stack.
     */
    @Test
    public void testDeliverIntentToTopActivityOfNonTopDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);

        // Create a secondary display at bottom.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setPosition(POSITION_BOTTOM).build();
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final Task stack = secondaryTaskContainer.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Create an activity record on the top of secondary display.
        final ActivityRecord topActivityOnSecondaryDisplay = createSingleTaskActivityOn(stack);

        // Put an activity on default display as the top focused activity.
        new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Start activity with the same intent as {@code topActivityOnSecondaryDisplay}
        // on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        final int result = starter.setReason("testDeliverIntentToTopActivityOfNonTopDisplay")
                .setIntent(topActivityOnSecondaryDisplay.intent)
                .setActivityOptions(options.toBundle())
                .execute();

        // Ensure result is delivering intent to top.
        assertEquals(START_DELIVERED_TO_TOP, result);

        // Ensure secondary display only creates one stack.
        verify(secondaryTaskContainer, times(1)).createRootTask(anyInt(), anyInt(), anyBoolean());
    }

    /**
     * This test ensures that when starting an existing non-top single task activity on secondary
     * display which is the top focused display, it should bring the task to front without creating
     * unused stack.
     */
    @Test
    public void testBringTaskToFrontOnSecondaryDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);

        // Create a secondary display with an activity.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500).build();
        mRootWindowContainer.positionChildAt(POSITION_TOP, secondaryDisplay,
                false /* includingParents */);
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final ActivityRecord singleTaskActivity = createSingleTaskActivityOn(
                secondaryTaskContainer.createRootTask(WINDOWING_MODE_FULLSCREEN,
                        ACTIVITY_TYPE_STANDARD, false /* onTop */));
        // Activity should start invisible since we are bringing it to front.
        singleTaskActivity.setVisible(false);
        singleTaskActivity.setVisibleRequested(false);

        // Create another activity on top of the secondary display.
        final Task topStack = secondaryTaskContainer.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task topTask = new TaskBuilder(mSupervisor).setParentTask(topStack).build();
        new ActivityBuilder(mAtm).setTask(topTask).build();

        doReturn(mActivityMetricsLogger).when(mSupervisor).getActivityMetricsLogger();
        // Start activity with the same intent as {@code singleTaskActivity} on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        final int result = starter.setReason("testBringTaskToFrontOnSecondaryDisplay")
                .setIntent(singleTaskActivity.intent)
                .setActivityOptions(options.toBundle())
                .execute();

        // Ensure result is moving existing task to front.
        assertEquals(START_TASK_TO_FRONT, result);

        // Ensure secondary display only creates two stacks.
        verify(secondaryTaskContainer, times(2)).createRootTask(anyInt(), anyInt(), anyBoolean());
        // The metrics logger should receive the same result and non-null options.
        verify(mActivityMetricsLogger).notifyActivityLaunched(any() /* launchingState */,
                eq(result), eq(false) /* newActivityCreated */, eq(singleTaskActivity),
                notNull() /* options */);
    }

    @Test
    public void testWasVisibleInRestartAttempt() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP, false);
        final ActivityRecord reusableActivity =
                new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord topActivity =
                new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Make sure topActivity is on top
        topActivity.getRootTask().moveToFront("testWasVisibleInRestartAttempt");
        reusableActivity.setVisible(false);

        final TaskChangeNotificationController taskChangeNotifier =
                mAtm.getTaskChangeNotificationController();
        spyOn(taskChangeNotifier);

        Task task = topActivity.getTask();
        starter.postStartActivityProcessing(
                task.getTopNonFinishingActivity(), START_DELIVERED_TO_TOP, task.getRootTask());

        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), eq(true));

        Task task2 = reusableActivity.getTask();
        starter.postStartActivityProcessing(
                task2.getTopNonFinishingActivity(), START_TASK_TO_FRONT, task.getRootTask());
        verify(taskChangeNotifier, times(2)).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), eq(false));
    }

    private ActivityRecord createSingleTaskActivityOn(Task task) {
        final ComponentName componentName = ComponentName.createRelative(
                DEFAULT_COMPONENT_PACKAGE_NAME,
                DEFAULT_COMPONENT_PACKAGE_NAME + ".SingleTaskActivity");
        return new ActivityBuilder(mAtm)
                .setComponent(componentName)
                .setLaunchMode(LAUNCH_SINGLE_TASK)
                .setTask(task)
                .build();
    }

    /**
     * This test ensures that a reused top activity in the top focused stack is able to be
     * reparented to another display.
     */
    @Test
    public void testReparentTopFocusedActivityToSecondaryDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);

        // Create a secondary display at bottom.
        final TestDisplayContent secondaryDisplay = addNewDisplayContentAt(POSITION_BOTTOM);
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        secondaryTaskContainer.createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        // Put an activity on default display as the top focused activity.
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setLaunchMode(LAUNCH_SINGLE_TASK)
                .build();

        // Start activity with the same intent as {@code topActivity} on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        starter.setReason("testReparentTopFocusedActivityToSecondaryDisplay")
                .setIntent(topActivity.intent)
                .setActivityOptions(options.toBundle())
                .execute();

        // Ensure the activity is moved to secondary display.
        assertEquals(secondaryDisplay, topActivity.mDisplayContent);
    }

    /**
     * This test ensures that starting an activity with the freeze-task-list activity option will
     * actually freeze the task list
     */
    @Test
    public void testFreezeTaskListActivityOption() {
        RecentTasks recentTasks = mock(RecentTasks.class);
        mAtm.mTaskSupervisor.setRecentTasks(recentTasks);
        doReturn(true).when(recentTasks).isCallerRecents(anyInt());

        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setFreezeRecentTasksReordering();

        starter.setReason("testFreezeTaskListActivityOption")
                .setActivityOptions(new SafeActivityOptions(options))
                .execute();

        verify(recentTasks, times(1)).setFreezeTaskListReordering();
        verify(recentTasks, times(0)).resetFreezeTaskListReorderingOnTimeout();
    }

    /**
     * This test ensures that if we froze the task list as a part of starting an activity that fails
     * to start, that we also reset the task list.
     */
    @Test
    public void testFreezeTaskListActivityOptionFailedStart_expectResetFreezeTaskList() {
        RecentTasks recentTasks = mock(RecentTasks.class);
        mAtm.mTaskSupervisor.setRecentTasks(recentTasks);
        doReturn(true).when(recentTasks).isCallerRecents(anyInt());

        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setFreezeRecentTasksReordering();

        starter.setReason("testFreezeTaskListActivityOptionFailedStart")
                .setActivityOptions(new SafeActivityOptions(options))
                .execute();

        // Simulate a failed start
        starter.postStartActivityProcessing(null, START_CANCELED, null);

        verify(recentTasks, times(1)).setFreezeTaskListReordering();
        verify(recentTasks, times(1)).resetFreezeTaskListReorderingOnTimeout();
    }

    @Test
    public void testNoActivityInfo() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        spyOn(starter.mRequest);

        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        starter.setReason("testNoActivityInfo").setIntent(intent)
                .setActivityInfo(null).execute();
        verify(starter.mRequest).resolveActivity(any());

        // Also verifies the value of Request#componentSpecified should be true even the
        // ActivityStarter#setComponentSpecified is not explicitly set.
        assertTrue(starter.mRequest.componentSpecified);
    }

    @Test
    public void testResolveEphemeralInstaller() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());

        doReturn(true).when(mMockPackageManager).isInstantAppInstallerComponent(any());
        starter.setIntent(intent).mRequest.resolveActivity(mAtm.mTaskSupervisor);

        // Make sure the client intent won't be modified.
        assertThat(intent.getComponent()).isNotNull();
        assertThat(starter.getIntent().getComponent()).isNull();
    }

    @Test
    public void testNotAllowIntentWithFd() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final Intent intent = spy(new Intent());
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        doReturn(true).when(intent).hasFileDescriptors();

        boolean exceptionCaught = false;
        try {
            starter.setIntent(intent).execute();
        } catch (IllegalArgumentException ex) {
            exceptionCaught = true;
        }
        assertThat(exceptionCaught).isTrue();
    }

    @Test
    public void testRecycleTaskFromAnotherUser() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        starter.mStartActivity = new ActivityBuilder(mAtm).build();
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor)
                .setParentTask(createTask(mDisplayContent, WINDOWING_MODE_FULLSCREEN,
                        ACTIVITY_TYPE_STANDARD))
                .setUserId(10)
                .build();

        final int result = starter.recycleTask(task, null, null, null,
                BalVerdict.ALLOW_PRIVILEGED);
        assertThat(result == START_SUCCESS).isTrue();
        assertThat(starter.mAddingToTask).isTrue();
    }

    @Test
    public void testRecycleTaskWakeUpWhenDreaming() {
        doNothing().when(mWm.mAtmService.mTaskSupervisor).wakeUp(anyString());
        doReturn(true).when(mWm.mAtmService).isDreaming();
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final ActivityRecord target = new ActivityBuilder(mAtm).setCreateTask(true).build();
        starter.mStartActivity = target;
        target.setVisibleRequested(false);
        target.setTurnScreenOn(true);
        // Assume the flag was consumed by relayout.
        target.setCurrentLaunchCanTurnScreenOn(false);
        startActivityInner(starter, target, null /* source */, null /* options */,
                null /* inTask */, null /* inTaskFragment */);
        // The flag should be set again when resuming (from recycleTask) the target as top.
        assertTrue(target.currentLaunchCanTurnScreenOn());
        // In real case, dream activity has a higher priority (TaskDisplayArea#getPriority) that
        // will be put at a higher z-order. So it relies on wakeUp() to be dismissed.
        verify(mWm.mAtmService.mTaskSupervisor).wakeUp(anyString());
    }

    @Test
    public void testTargetTaskInSplitScreen() {
        final ActivityStarter starter =
                prepareStarter(FLAG_ACTIVITY_LAUNCH_ADJACENT, false /* mockGetRootTask */);
        final ActivityRecord top = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityOptions options = ActivityOptions.makeBasic();
        final ActivityRecord[] outActivity = new ActivityRecord[1];

        // Activity must not land on split-screen task if currently not in split-screen mode.
        starter.setActivityOptions(options.toBundle())
                .setReason("testTargetTaskInSplitScreen")
                .setOutActivity(outActivity).execute();
        assertThat(outActivity[0].inMultiWindowMode()).isFalse();

        // Move activity to split-screen-primary task and make sure it has the focus.
        TestSplitOrganizer splitOrg = new TestSplitOrganizer(mAtm, top.getDisplayContent());
        top.getRootTask().reparent(splitOrg.mPrimary, POSITION_BOTTOM);
        top.getRootTask().moveToFront("testTargetTaskInSplitScreen");

        // Activity must land on split-screen-secondary when launch adjacent.
        startActivityInner(starter, outActivity[0], top, options, null /* inTask */,
                null /* taskFragment*/);
        assertThat(outActivity[0].inMultiWindowMode()).isTrue();
    }

    @Test
    public void testTransientLaunchWithKeyguard() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final ActivityRecord target = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord top = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        doReturn(true).when(keyguard).isKeyguardLocked(anyInt());
        doReturn(true).when(keyguard).isKeyguardOccluded(anyInt());
        registerTestTransitionPlayer();
        starter.setReason("testTransientLaunchWithKeyguard")
                .setActivityOptions(ActivityOptions.makeBasic().setTransientLaunch().toBundle())
                .setIntent(target.intent)
                .execute();
        final TransitionController controller = mRootWindowContainer.mTransitionController;
        final Transition transition = controller.getCollectingTransition();
        final Transition.ChangeInfo targetChangeInfo = transition.mChanges.get(target);

        assertThat(targetChangeInfo).isNotNull();
        assertThat(targetChangeInfo.hasChanged()).isTrue();
        assertThat(controller.isCollecting(top.getTask())).isTrue();
        assertThat(transition.isTransientLaunch(target)).isTrue();
        assertThat(transition.isInTransientHide(top.getTask())).isTrue();
    }

    /**
     * Tests ATMS#startActivityWithScreenshot should collect display content for creating snapshot.
     */
    @Test
    public void testActivityStartWithScreenshot() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        starter.setFreezeScreen(true);

        registerTestTransitionPlayer();

        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        starter.setReason("testActivityStartWithScreenshot")
                .setIntent(intent)
                .execute();

        final TransitionController controller = mRootWindowContainer.mTransitionController;
        final Transition transition = controller.getCollectingTransition();
        final Transition.ChangeInfo targetChangeInfo = transition.mChanges.get(mDisplayContent);
        assertThat(targetChangeInfo).isNotNull();
    }

    @Test
    public void testActivityStart_expectAddedToRecentTask() {
        RecentTasks recentTasks = mock(RecentTasks.class);
        mAtm.mTaskSupervisor.setRecentTasks(recentTasks);
        doReturn(true).when(recentTasks).isCallerRecents(anyInt());

        final ActivityStarter starter = prepareStarter(0 /* flags */);

        starter.setReason("testAddToTaskListOnActivityStart")
                .execute();

        verify(recentTasks, times(1)).add(any());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_failsByDefault() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertFalse(taskFragment.hasChild());
        assertNotNull("Target record must be started on Task.", targetRecord.getParent().asTask());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_allowedForSystemUid() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        taskFragment.setTaskFragmentOrganizer(mock(TaskFragmentOrganizerToken.class), SYSTEM_UID,
                "system_uid");

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertTrue(taskFragment.hasChild());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_allowedForSameUid() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        taskFragment.setTaskFragmentOrganizer(mock(TaskFragmentOrganizerToken.class),
                targetRecord.getUid(), "test_process_name");

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertTrue(taskFragment.hasChild());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_allowedTrustedCertUid() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        taskFragment.setTaskFragmentOrganizer(mock(TaskFragmentOrganizerToken.class),
                12345, "test_process_name");
        AndroidPackage androidPackage = mock(AndroidPackage.class);
        doReturn(androidPackage).when(mMockPackageManager).getPackage(eq(12345));

        Set<String> certs = new HashSet(Arrays.asList("test_cert1", "test_cert1"));
        targetRecord.info.setKnownActivityEmbeddingCerts(certs);
        SigningDetails signingDetails = mock(SigningDetails.class);
        doReturn(true).when(signingDetails).hasAncestorOrSelfWithDigest(any());
        doReturn(signingDetails).when(androidPackage).getSigningDetails();

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertTrue(taskFragment.hasChild());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_allowedForUntrustedEmbedding() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        targetRecord.info.flags |= ActivityInfo.FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertTrue(taskFragment.hasChild());
    }

    @Test
    public void testStartActivityInner_inTask() {
        final ActivityStarter starter = prepareStarter(0, false);
        // Simulate an app uses AppTask to create a non-attached task, and then it requests to
        // start activity in the task.
        final Task inTask = new TaskBuilder(mSupervisor).setTaskDisplayArea(null).setTaskId(123)
                .build();
        inTask.inRecents = true;
        assertFalse(inTask.isAttached());
        final ActivityRecord target = new ActivityBuilder(mAtm).build();
        startActivityInner(starter, target, null /* source */, null /* options */, inTask,
                null /* inTaskFragment */);

        assertTrue(inTask.isAttached());
        assertEquals(inTask, target.getTask());
    }

    @Test
    public void testLaunchCookie_newAndExistingTask() {
        final ActivityStarter starter = prepareStarter(0, false);

        // Put an activity on default display as the top focused activity.
        ActivityRecord r = new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Start an activity with a launch cookie
        final Binder cookie = new Binder();
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchCookie(cookie);
        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        starter.setReason("testLaunchCookie_newTask")
                .setIntent(intent)
                .setActivityOptions(options.toBundle())
                .execute();

        // Verify the cookie is set
        assertTrue(mRootWindowContainer.topRunningActivity().mLaunchCookie == cookie);

        // Relaunch the activity to bring the task forward
        final Binder newCookie = new Binder();
        final ActivityOptions newOptions = ActivityOptions.makeBasic();
        newOptions.setLaunchCookie(newCookie);
        starter.setReason("testLaunchCookie_existingTask")
                .setIntent(intent)
                .setActivityOptions(newOptions.toBundle())
                .execute();

        // Verify the cookie is updated
        assertTrue(mRootWindowContainer.topRunningActivity().mLaunchCookie == newCookie);
    }

    @Test
    public void testRemoteAnimation_appliesToExistingTask() {
        final ActivityStarter starter = prepareStarter(0, false);

        // Put an activity on default display as the top focused activity.
        ActivityRecord r = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        starter.setReason("testRemoteAnimation_newTask")
                .setIntent(intent)
                .execute();

        assertNull(mRootWindowContainer.topRunningActivity().mPendingRemoteAnimation);

        // Relaunch the activity with remote animation indicated in options.
        final RemoteAnimationAdapter adaptor = mock(RemoteAnimationAdapter.class);
        final ActivityOptions options = ActivityOptions.makeRemoteAnimation(adaptor);
        starter.setReason("testRemoteAnimation_existingTask")
                .setIntent(intent)
                .setActivityOptions(options.toBundle())
                .execute();

        // Verify the remote animation is updated.
        assertEquals(adaptor, mRootWindowContainer.topRunningActivity().mPendingRemoteAnimation);
    }

    @Test
    public void testStartLaunchIntoPipActivity() {
        final ActivityStarter starter = prepareStarter(0, false);

        // Create an activity from ActivityOptions#makeLaunchIntoPip
        final PictureInPictureParams params = new PictureInPictureParams.Builder()
                .build();
        final ActivityOptions opts = ActivityOptions.makeLaunchIntoPip(params);
        ActivityRecord targetRecord = new ActivityBuilder(mAtm)
                .setActivityOptions(opts)
                .build();

        // Start the target launch-into-pip activity from a source
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        startActivityInner(starter, targetRecord, sourceRecord, opts,
                null /* inTask */, null /* inTaskFragment */);

        // Verify the ActivityRecord#getLaunchIntoPipHostActivity points to sourceRecord.
        assertThat(targetRecord.getLaunchIntoPipHostActivity()).isNotNull();
        assertEquals(targetRecord.getLaunchIntoPipHostActivity(), sourceRecord);
    }

    @Test
    public void testResultCanceledWhenNotAllowedStartingActivity() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setTask(task).build();
        targetRecord.resultTo = sourceRecord;

        // Abort the activity start and ensure the sourceRecord gets the result (RESULT_CANCELED).
        spyOn(starter);
        doReturn(START_ABORTED).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, null /* inTaskFragment */);
        verify(sourceRecord).sendResult(anyInt(), any(), anyInt(), eq(RESULT_CANCELED), any(),
                any(), any());
    }

    @Test
    public void testCanEmbedActivity() {
        final Size minDimensions = new Size(1000, 1000);
        final WindowLayout windowLayout = new WindowLayout(0, 0, 0, 0, 0,
                minDimensions.getWidth(), minDimensions.getHeight());
        final ActivityRecord starting = new ActivityBuilder(mAtm)
                .setUid(UNIMPORTANT_UID)
                .setWindowLayout(windowLayout)
                .build();

        // Task fragment hasn't attached to a task yet. Start activity to a new task.
        TaskFragment taskFragment = new TaskFragmentBuilder(mAtm).build();
        final Task task = new TaskBuilder(mSupervisor).build();

        assertEquals(EMBEDDING_DISALLOWED_NEW_TASK,
                canEmbedActivity(taskFragment, starting, task));

        // Starting activity is going to be started on a task different from task fragment's parent
        // task. Start activity to a new task.
        task.addChild(taskFragment, POSITION_TOP);
        final Task newTask = new TaskBuilder(mSupervisor).build();

        assertEquals(EMBEDDING_DISALLOWED_NEW_TASK,
                canEmbedActivity(taskFragment, starting, newTask));

        // Make task fragment bounds exceed task bounds.
        final Rect taskBounds = task.getBounds();
        taskFragment.setBounds(taskBounds.left, taskBounds.top, taskBounds.right + 1,
                taskBounds.bottom + 1);

        assertEquals(EMBEDDING_DISALLOWED_UNTRUSTED_HOST,
                canEmbedActivity(taskFragment, starting, task));

        taskFragment.setBounds(taskBounds);
        starting.info.flags |= FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;

        assertEquals(EMBEDDING_ALLOWED, canEmbedActivity(taskFragment, starting, task));

        starting.info.flags &= ~FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;
        // Set task fragment's uid as the same as starting activity's uid.
        taskFragment.setTaskFragmentOrganizer(mock(TaskFragmentOrganizerToken.class),
                UNIMPORTANT_UID, "test");

        assertEquals(EMBEDDING_ALLOWED, canEmbedActivity(taskFragment, starting, task));

        // Make task fragment bounds smaller than starting activity's minimum dimensions
        taskFragment.setBounds(0, 0, minDimensions.getWidth() - 1, minDimensions.getHeight() - 1);

        assertEquals(EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION,
                canEmbedActivity(taskFragment, starting, task));
    }

    @Test
    public void testRecordActivityMovementBeforeDeliverToTop() {
        // Mock recents as task is only marked to be in recents
        mAtm.mTaskSupervisor.setRecentTasks(mock(RecentTasks.class));

        final Task task = new TaskBuilder(mAtm.mTaskSupervisor).build();
        final ActivityRecord activityBot = new ActivityBuilder(mAtm).setTask(task).build();
        final ActivityRecord activityTop = new ActivityBuilder(mAtm).setTask(task).build();

        activityBot.setVisible(false);
        activityBot.setVisibleRequested(false);

        assertTrue(activityTop.isVisible());
        assertTrue(activityTop.isVisibleRequested());

        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_REORDER_TO_FRONT
                        | FLAG_ACTIVITY_NEW_TASK, false /* mockGetRootTask */);
        starter.mStartActivity = activityBot;
        task.inRecents = true;
        starter.setInTask(task);
        starter.getIntent().setComponent(activityBot.mActivityComponent);
        final int result = starter.setReason("testRecordActivityMovement").execute();

        assertEquals(START_DELIVERED_TO_TOP, result);
        assertNotNull(starter.mMovedToTopActivity);

        final ActivityStarter starter2 = prepareStarter(FLAG_ACTIVITY_REORDER_TO_FRONT
                        | FLAG_ACTIVITY_NEW_TASK, false /* mockGetRootTask */);
        starter2.setInTask(task);
        starter2.getIntent().setComponent(activityBot.mActivityComponent);
        final int result2 = starter2.setReason("testRecordActivityMovement").execute();

        assertEquals(START_DELIVERED_TO_TOP, result2);
        assertNull(starter2.mMovedToTopActivity);
    }

    /**
     * Tests a task with specific display category exist in system and then launching another
     * activity with the same affinity but without define the display category. Make sure the
     * lunching activity is placed on the different task.
     */
    @Test
    public void testLaunchActivityWithoutDisplayCategory() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.taskAffinity = ActivityRecord.computeTaskAffinity("test", DEFAULT_FAKE_UID);
        info.requiredDisplayCategory = "automotive";
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).setActivityInfo(info)
                .build();

        final ActivityRecord target = new ActivityBuilder(mAtm).setAffinity(info.taskAffinity)
                .build();
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, target, null /* source */, null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertNotEquals(task, target.getTask());
    }

    /**
     * Tests a task with a specific display category exist in the system and then launches another
     * activity with the different display category. Make sure the launching activity is not placed
     * on the sourceRecord's task.
     */
    @Test
    public void testLaunchActivityWithDifferentDisplayCategory() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.taskAffinity = ActivityRecord.computeTaskAffinity("test", DEFAULT_FAKE_UID);
        info.requiredDisplayCategory = "automotive";
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).setActivityInfo(info)
                .build();

        final ActivityRecord target = new ActivityBuilder(mAtm).setRequiredDisplayCategory("auto")
                .setAffinity(info.taskAffinity).build();
        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, target,  task.getBottomMostActivity(), null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertNotEquals(task, target.getTask());
    }

    /**
     * Tests a task with specific display category exist in system and then launching another
     * activity with the same display category. Make sure the launching activity is placed on the
     * same task.
     */
    @Test
    public void testLaunchActivityWithSameDisplayCategory() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.taskAffinity = ActivityRecord.computeTaskAffinity("test", DEFAULT_FAKE_UID);
        info.requiredDisplayCategory = "automotive";
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).setActivityInfo(info)
                .build();

        final ActivityRecord target = new ActivityBuilder(mAtm)
                .setRequiredDisplayCategory(info.requiredDisplayCategory)
                .setAffinity(info.taskAffinity).build();

        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, target,  task.getBottomMostActivity(), null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertEquals(task, target.getTask());
    }

    /**
     * Tests a task with specific display category exist in system and launching activity into the
     * specific task within inTask attribute. Make sure the activity is not placed on the task since
     * the display category is different.
     */
    @Test
    public void testLaunchActivityInTaskWithDisplayCategory() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.requiredDisplayCategory = "automotive";
        final Task inTask = new TaskBuilder(mSupervisor).setActivityInfo(info).build();
        inTask.inRecents = true;

        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        final ActivityRecord target = new ActivityBuilder(mAtm).build();
        startActivityInner(starter, target, null /* source */, null /* options */, inTask,
                null /* inTaskFragment */);

        assertNotEquals(inTask, target.getTask());
    }

    /**
     * Tests a task without a specific display category exist in the system and launches activity
     * with display category into the task within the inTask attribute. Make sure the activity is
     * not placed on the task since the display category is different.
     */
    @Test
    public void testLaunchDisplayCategoryActivityInTask() {
        final Task inTask = new TaskBuilder(mSupervisor).build();
        inTask.inRecents = true;

        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        final ActivityRecord target = new ActivityBuilder(mAtm).setRequiredDisplayCategory("auto")
                .build();
        startActivityInner(starter, target, null /* source */, null /* options */, inTask,
                null /* inTaskFragment */);

        assertNotEquals(inTask, target.getTask());
    }

    private static void startActivityInner(ActivityStarter starter, ActivityRecord target,
            ActivityRecord source, ActivityOptions options, Task inTask,
            TaskFragment inTaskFragment) {
        starter.startActivityInner(target, source, null /* voiceSession */,
                null /* voiceInteractor */, 0 /* startFlags */,
                options, inTask, inTaskFragment,
                BalVerdict.ALLOW_PRIVILEGED,
                null /* intentGrants */, -1 /* realCallingUid */);
    }
}
