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
import static android.app.ActivityManager.START_SWITCHES_CANCELED;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.clearInvocations;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.WindowLayout;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManagerInternal;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.service.voice.IVoiceInteractionSession;
import android.view.Gravity;

import androidx.test.filters.SmallTest;

import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;
import com.android.server.wm.utils.MockTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ActivityStarter} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStarterTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityStarterTests extends ActivityTestsBase {
    private ActivityStartController mController;
    private ActivityMetricsLogger mActivityMetricsLogger;
    private PackageManagerInternal mMockPackageManager;

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
    private static final int PRECONDITION_DISALLOW_APP_SWITCHING = 1 << 10;

    private static final int FAKE_CALLING_UID = 666;
    private static final int FAKE_REAL_CALLING_UID = 667;
    private static final String FAKE_CALLING_PACKAGE = "com.whatever.dude";
    private static final int UNIMPORTANT_UID = 12345;
    private static final int UNIMPORTANT_UID2 = 12346;

    @Before
    public void setUp() throws Exception {
        mController = mock(ActivityStartController.class);
        mActivityMetricsLogger = mock(ActivityMetricsLogger.class);
        clearInvocations(mActivityMetricsLogger);
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
        verifyStartActivityPreconditions(PRECONDITION_DISALLOW_APP_SWITCHING,
                START_SWITCHES_CANCELED);
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
        final ActivityTaskManagerService service = mService;
        final IPackageManager packageManager = mock(IPackageManager.class);
        final ActivityStartController controller = mock(ActivityStartController.class);

        final ActivityStarter starter = new ActivityStarter(controller, service,
                service.mStackSupervisor, mock(ActivityStartInterceptor.class));
        prepareStarter(launchFlags);
        final IApplicationThread caller = mock(IApplicationThread.class);
        final WindowProcessListener listener = mock(WindowProcessListener.class);

        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = "com.android.test.package";
        final WindowProcessController wpc =
                containsConditions(preconditions, PRECONDITION_NO_CALLER_APP)
                ? null : new WindowProcessController(service, ai, null, 0, -1, null, listener);
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
                new TaskBuilder(service.mStackSupervisor).setVoiceSession(voiceSession).build());

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

        if (containsConditions(preconditions, PRECONDITION_DISALLOW_APP_SWITCHING)) {
            doReturn(false).when(service).checkAppSwitchAllowedLocked(
                    anyInt(), anyInt(), anyInt(), anyInt(), any());
        }

        if (containsConditions(preconditions, PRECONDITION_CANNOT_START_ANY_ACTIVITY)) {
            doReturn(false).when(service.mStackSupervisor).checkStartAnyActivityPermission(
                    any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any());
        }

        try {
            if (containsConditions(preconditions,
                    PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION)) {
                doAnswer((inv) -> {
                    throw new RemoteException();
                }).when(packageManager).activitySupportsIntent(
                        eq(source.mActivityComponent), eq(intent), any());
            } else {
                doReturn(!containsConditions(preconditions, PRECONDITION_NO_VOICE_SESSION_SUPPORT))
                        .when(packageManager).activitySupportsIntent(eq(source.mActivityComponent),
                        eq(intent), any());
            }
        } catch (RemoteException e) {
        }

        final IBinder resultTo = containsConditions(preconditions, PRECONDITION_SOURCE_PRESENT)
                || containsConditions(preconditions, PRECONDITION_SOURCE_VOICE_SESSION)
                ? source.appToken : null;

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
            final ActivityStarter optionStarter = new ActivityStarter(mController, mService,
                    mService.mStackSupervisor, mock(ActivityStartInterceptor.class));
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
        return prepareStarter(launchFlags, true /* mockGetLaunchStack */);
    }

    /**
     * Creates a {@link ActivityStarter} with default parameters and necessary mocks.
     *
     * @param launchFlags The intent flags to launch activity.
     * @param mockGetLaunchStack Whether to mock {@link RootWindowContainer#getLaunchStack} for
     *                           always launching to the testing stack. Set to false when allowing
     *                           the activity can be launched to any stack that is decided by real
     *                           implementation.
     * @return A {@link ActivityStarter} with default setup.
     */
    private ActivityStarter prepareStarter(@Intent.Flags int launchFlags,
            boolean mockGetLaunchStack) {
        // always allow test to start activity.
        doReturn(true).when(mSupervisor).checkStartAnyActivityPermission(
                any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any());

        if (mockGetLaunchStack) {
            // Instrument the stack and task used.
            final ActivityStack stack = mRootWindowContainer.getDefaultTaskDisplayArea()
                    .createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                            true /* onTop */);

            // Direct starter to use spy stack.
            doReturn(stack).when(mRootWindowContainer)
                    .getLaunchStack(any(), any(), any(), anyBoolean());
            doReturn(stack).when(mRootWindowContainer)
                    .getLaunchStack(any(), any(), any(), anyBoolean(), any(), anyInt(), anyInt());
        }

        // Set up mock package manager internal and make sure no unmocked methods are called
        mMockPackageManager = mock(PackageManagerInternal.class,
                invocation -> {
                    throw new RuntimeException("Not stubbed");
                });
        doReturn(mMockPackageManager).when(mService).getPackageManagerInternalLocked();
        doReturn(false).when(mMockPackageManager).isInstantAppInstallerComponent(any());
        doReturn(null).when(mMockPackageManager).resolveIntent(any(), any(), anyInt(), anyInt(),
                anyInt(), anyBoolean(), anyInt());
        doReturn(new ComponentName("", "")).when(mMockPackageManager).getSystemUiServiceComponent();

        // Never review permissions
        doReturn(false).when(mMockPackageManager).isPermissionsReviewRequired(any(), anyInt());
        doNothing().when(mMockPackageManager).grantImplicitAccess(
                anyInt(), any(), anyInt(), anyInt(), anyBoolean());
        doNothing().when(mMockPackageManager).notifyPackageUse(anyString(), anyInt());

        final Intent intent = new Intent();
        intent.addFlags(launchFlags);
        intent.setComponent(ActivityBuilder.getDefaultComponent());

        final ActivityInfo info = new ActivityInfo();

        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = ActivityBuilder.getDefaultComponent().getPackageName();

        return new ActivityStarter(mController, mService,
                mService.mStackSupervisor, mock(ActivityStartInterceptor.class))
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
        mService.mStackSupervisor.getLaunchParamsController().registerModifier(modifier);

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
                anyInt(), any(), any());
    }

    /**
     * This test ensures that if the intent is being delivered to a split-screen unfocused task
     * while it already on top, reports it as delivering to top.
     */
    @Test
    public void testSplitScreenDeliverToTop() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP, false);
        final ActivityRecord splitPrimaryFocusActivity =
                new ActivityBuilder(mService).setCreateTask(true).build();
        final ActivityRecord splitSecondReusableActivity =
                new ActivityBuilder(mService).setCreateTask(true).build();
        splitPrimaryFocusActivity.getRootTask()
                .setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        splitSecondReusableActivity.getRootTask()
                .setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);

        // Set focus back to primary.
        splitPrimaryFocusActivity.getRootTask().moveToFront("testSplitScreenDeliverToTop");

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
        final ActivityRecord splitSecondReusableActivity =
                new ActivityBuilder(mService).setCreateTask(true).build();
        final ActivityRecord splitSecondTopActivity =
                new ActivityBuilder(mService).setCreateTask(true).build();
        final ActivityRecord splitPrimaryFocusActivity =
                new ActivityBuilder(mService).setCreateTask(true).build();
        splitPrimaryFocusActivity.getRootTask()
                .setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        splitSecondReusableActivity.getRootTask()
                .setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        splitSecondTopActivity.getRootTask()
                .setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);

        // Make it on top of split-screen-secondary.
        splitSecondTopActivity.getRootTask().moveToFront("testSplitScreenTaskToFront");

        // Let primary stack has focus.
        splitPrimaryFocusActivity.getRootTask().moveToFront("testSplitScreenTaskToFront");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(splitSecondReusableActivity.mActivityComponent);
        doReturn(splitSecondReusableActivity).when(mRootWindowContainer).findTask(any(), any());
        final int result = starter.setReason("testSplitScreenMoveToFront").execute();

        // Ensure result is moving task to front.
        assertEquals(START_TASK_TO_FRONT, result);
    }

    /**
     * Tests activity is cleaned up properly in a task mode violation.
     */
    @Test
    public void testTaskModeViolation() {
        final DisplayContent display = mService.mRootWindowContainer.getDefaultDisplay();
        display.removeAllTasks();
        assertNoTasks(display);

        final ActivityStarter starter = prepareStarter(0);

        final LockTaskController lockTaskController = mService.getLockTaskController();
        doReturn(true).when(lockTaskController).isLockTaskModeViolation(any());

        final int result = starter.setReason("testTaskModeViolation").execute();

        assertEquals(START_RETURN_LOCK_TASK_MODE_VIOLATION, result);
        assertNoTasks(display);
    }

    private void assertNoTasks(DisplayContent display) {
        for (int tdaNdx = display.getTaskDisplayAreaCount() - 1; tdaNdx >= 0; --tdaNdx) {
            final TaskDisplayArea taskDisplayArea = display.getTaskDisplayAreaAt(tdaNdx);
            for (int sNdx = taskDisplayArea.getStackCount() - 1; sNdx >= 0; --sNdx) {
                final ActivityStack stack = taskDisplayArea.getStackAt(sNdx);
                assertFalse(stack.hasChild());
            }
        }
    }

    /**
     * This test ensures that activity starts are not being logged when the logging is disabled.
     */
    @Test
    public void testActivityStartsLogging_noLoggingWhenDisabled() {
        doReturn(false).when(mService).isActivityStartsLoggingEnabled();
        doReturn(mActivityMetricsLogger).when(mService.mStackSupervisor).getActivityMetricsLogger();

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
        doReturn(true).when(mService).isActivityStartsLoggingEnabled();
        doReturn(mActivityMetricsLogger).when(mService.mStackSupervisor).getActivityMetricsLogger();

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
        doReturn(true).when(mService).isBackgroundActivityStartsEnabled();

        runAndVerifyBackgroundActivityStartsSubtest("allowed_noStartsAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, false, false);
    }

    /**
     * This test ensures that unsupported usecases are aborted when background starts are
     * disallowed.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_unsupportedStartsAborted() {
        doReturn(false).when(mService).isBackgroundActivityStartsEnabled();

        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_unsupportedUsecase_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callingUidProcessStateTop_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_realCallingUidProcessStateTop_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP,
                false, false, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_hasForegroundActivities_aborted", true,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                true, false, false, false, false);
    }

    /**
     * This test ensures that supported usecases aren't aborted when background starts are
     * disallowed.
     * The scenarios each have only one condition that makes them supported.
     */
    @Test
    public void testBackgroundActivityStartsDisallowed_supportedStartsNotAborted() {
        doReturn(false).when(mService).isBackgroundActivityStartsEnabled();

        runAndVerifyBackgroundActivityStartsSubtest("disallowed_rootUid_notAborted", false,
                Process.ROOT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest("disallowed_systemUid_notAborted", false,
                Process.SYSTEM_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest("disallowed_nfcUid_notAborted", false,
                Process.NFC_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callingUidHasVisibleWindow_notAborted", false,
                UNIMPORTANT_UID, true, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_realCallingUidHasVisibleWindow_notAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, true, PROCESS_STATE_TOP + 1,
                false, false, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callerIsRecents_notAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, true, false, false, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callerIsWhitelisted_notAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, true, false, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callerIsInstrumentingWithBackgroundActivityStartPrivileges_notAborted",
                false,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, true, false);
        runAndVerifyBackgroundActivityStartsSubtest(
                "disallowed_callingPackageNameIsDeviceOwner_notAborted", false,
                UNIMPORTANT_UID, false, PROCESS_STATE_TOP + 1,
                UNIMPORTANT_UID2, false, PROCESS_STATE_TOP + 1,
                false, false, false, false, true);
    }

    private void runAndVerifyBackgroundActivityStartsSubtest(String name, boolean shouldHaveAborted,
            int callingUid, boolean callingUidHasVisibleWindow, int callingUidProcState,
            int realCallingUid, boolean realCallingUidHasVisibleWindow, int realCallingUidProcState,
            boolean hasForegroundActivities, boolean callerIsRecents,
            boolean callerIsTempWhitelisted,
            boolean callerIsInstrumentingWithBackgroundActivityStartPrivileges,
            boolean isCallingUidDeviceOwner) {
        // window visibility
        doReturn(callingUidHasVisibleWindow).when(mService.mWindowManager.mRoot)
                .isAnyNonToastWindowVisibleForUid(callingUid);
        doReturn(realCallingUidHasVisibleWindow).when(mService.mWindowManager.mRoot)
                .isAnyNonToastWindowVisibleForUid(realCallingUid);
        // process importance
        doReturn(callingUidProcState).when(mService).getUidState(callingUid);
        doReturn(realCallingUidProcState).when(mService).getUidState(realCallingUid);
        // foreground activities
        final IApplicationThread caller = mock(IApplicationThread.class);
        final WindowProcessListener listener = mock(WindowProcessListener.class);
        final ApplicationInfo ai = new ApplicationInfo();
        ai.uid = callingUid;
        ai.packageName = "com.android.test.package";
        final WindowProcessController callerApp =
                new WindowProcessController(mService, ai, null, callingUid, -1, null, listener);
        callerApp.setHasForegroundActivities(hasForegroundActivities);
        doReturn(callerApp).when(mService).getProcessController(caller);
        // caller is recents
        RecentTasks recentTasks = mock(RecentTasks.class);
        mService.mStackSupervisor.setRecentTasks(recentTasks);
        doReturn(callerIsRecents).when(recentTasks).isCallerRecents(callingUid);
        // caller is temp whitelisted
        callerApp.setAllowBackgroundActivityStarts(callerIsTempWhitelisted);
        // caller is instrumenting with background activity starts privileges
        callerApp.setInstrumenting(callerIsInstrumentingWithBackgroundActivityStartPrivileges,
                callerIsInstrumentingWithBackgroundActivityStartPrivileges);
        // callingUid is the device owner
        doReturn(isCallingUidDeviceOwner).when(mService).isDeviceOwner(callingUid);

        final ActivityOptions options = spy(ActivityOptions.makeBasic());
        ActivityRecord[] outActivity = new ActivityRecord[1];
        ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK)
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
     * This test ensures that {@link ActivityStarter#setTargetStackAndMoveToFrontIfNeeded} will
     * move the existing task to front if the current focused stack doesn't have running task.
     */
    @Test
    public void testBringTaskToFrontWhenFocusedStackIsFinising() {
        // Put 2 tasks in the same stack (simulate the behavior of home stack).
        final ActivityRecord activity = new ActivityBuilder(mService)
                .setCreateTask(true).build();
        new ActivityBuilder(mService)
                .setStack(activity.getRootTask())
                .setCreateTask(true).build();

        // Create a top finishing activity.
        final ActivityRecord finishingTopActivity = new ActivityBuilder(mService)
                .setCreateTask(true).build();
        finishingTopActivity.getRootTask().moveToFront("finishingTopActivity");

        assertEquals(finishingTopActivity, mRootWindowContainer.topRunningActivity());
        finishingTopActivity.finishing = true;

        // Launch the bottom task of the target stack.
        prepareStarter(FLAG_ACTIVITY_NEW_TASK, false /* mockGetLaunchStack */)
                .setReason("testBringTaskToFrontWhenTopStackIsFinising")
                .setIntent(activity.intent)
                .execute();
        // The hierarchies of the activity should move to front.
        assertEquals(activity, mRootWindowContainer.topRunningActivity());
    }

    /**
     * This test ensures that when starting an existing single task activity on secondary display
     * which is not the top focused display, it should deliver new intent to the activity and not
     * create a new stack.
     */
    @Test
    public void testDeliverIntentToTopActivityOfNonTopDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetLaunchStack */);

        // Create a secondary display at bottom.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mService, 1000, 1500)
                        .setPosition(POSITION_BOTTOM).build();
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final ActivityStack stack = secondaryTaskContainer.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Create an activity record on the top of secondary display.
        final ActivityRecord topActivityOnSecondaryDisplay = createSingleTaskActivityOn(stack);

        // Put an activity on default display as the top focused activity.
        new ActivityBuilder(mService).setCreateTask(true).build();

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
        verify(secondaryTaskContainer, times(1)).createStack(anyInt(), anyInt(), anyBoolean());
    }

    /**
     * This test ensures that when starting an existing non-top single task activity on secondary
     * display which is the top focused display, it should bring the task to front without creating
     * unused stack.
     */
    @Test
    public void testBringTaskToFrontOnSecondaryDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetLaunchStack */);

        // Create a secondary display with an activity.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mService, 1000, 1500).build();
        mRootWindowContainer.positionChildAt(POSITION_TOP, secondaryDisplay,
                false /* includingParents */);
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final ActivityRecord singleTaskActivity = createSingleTaskActivityOn(
                secondaryTaskContainer.createStack(WINDOWING_MODE_FULLSCREEN,
                        ACTIVITY_TYPE_STANDARD, false /* onTop */));

        // Create another activity on top of the secondary display.
        final ActivityStack topStack = secondaryTaskContainer.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task topTask = new TaskBuilder(mSupervisor).setStack(topStack).build();
        new ActivityBuilder(mService).setTask(topTask).build();

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
        verify(secondaryTaskContainer, times(2)).createStack(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void testWasVisibleInRestartAttempt() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP, false);
        final ActivityRecord reusableActivity =
                new ActivityBuilder(mService).setCreateTask(true).build();
        final ActivityRecord topActivity =
                new ActivityBuilder(mService).setCreateTask(true).build();

        // Make sure topActivity is on top
        topActivity.getRootTask().moveToFront("testWasVisibleInRestartAttempt");
        reusableActivity.setVisible(false);

        final TaskChangeNotificationController taskChangeNotifier =
                mService.getTaskChangeNotificationController();
        spyOn(taskChangeNotifier);

        Task task = topActivity.getTask();
        starter.postStartActivityProcessing(
                task.getTopNonFinishingActivity(), START_DELIVERED_TO_TOP, task.getStack());

        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), eq(true));

        Task task2 = reusableActivity.getTask();
        starter.postStartActivityProcessing(
                task2.getTopNonFinishingActivity(), START_TASK_TO_FRONT, task.getStack());
        verify(taskChangeNotifier, times(2)).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), eq(false));
    }

    private ActivityRecord createSingleTaskActivityOn(ActivityStack stack) {
        final ComponentName componentName = ComponentName.createRelative(
                DEFAULT_COMPONENT_PACKAGE_NAME,
                DEFAULT_COMPONENT_PACKAGE_NAME + ".SingleTaskActivity");
        final Task task = new TaskBuilder(mSupervisor)
                .setComponent(componentName)
                .setStack(stack)
                .build();
        return new ActivityBuilder(mService)
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
                false /* mockGetLaunchStack */);

        // Create a secondary display at bottom.
        final TestDisplayContent secondaryDisplay = addNewDisplayContentAt(POSITION_BOTTOM);
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        secondaryTaskContainer.createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        // Put an activity on default display as the top focused activity.
        final ActivityRecord topActivity = new ActivityBuilder(mService)
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
        assertEquals(secondaryDisplay, topActivity.getDisplay());
    }

    /**
     * This test ensures that starting an activity with the freeze-task-list activity option will
     * actually freeze the task list
     */
    @Test
    public void testFreezeTaskListActivityOption() {
        RecentTasks recentTasks = mock(RecentTasks.class);
        mService.mStackSupervisor.setRecentTasks(recentTasks);
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
        mService.mStackSupervisor.setRecentTasks(recentTasks);
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
    }

    @Test
    public void testResolveEphemeralInstaller() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());

        doReturn(true).when(mMockPackageManager).isInstantAppInstallerComponent(any());
        starter.setIntent(intent).mRequest.resolveActivity(mService.mStackSupervisor);

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
        starter.mStartActivity = new ActivityBuilder(mService).build();
        final Task task = new TaskBuilder(mService.mStackSupervisor)
                .setStack(mService.mRootWindowContainer.getDefaultTaskDisplayArea().createStack(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */))
                .setUserId(10)
                .build();

        final int result = starter.recycleTask(task, null, null);
        assertThat(result == START_SUCCESS).isTrue();
        assertThat(starter.mAddingToTask).isTrue();
    }

    @Test
    public void testTargetStackInSplitScreen() {
        final ActivityStarter starter =
                prepareStarter(FLAG_ACTIVITY_LAUNCH_ADJACENT, false /* mockGetLaunchStack */);
        final ActivityRecord top = new ActivityBuilder(mService).setCreateTask(true).build();
        final ActivityOptions options = ActivityOptions.makeBasic();
        final ActivityRecord[] outActivity = new ActivityRecord[1];

        // Activity must not land on split-screen stack if currently not in split-screen mode.
        starter.setActivityOptions(options.toBundle())
                .setReason("testWindowingModeOptionsLaunchAdjacent")
                .setOutActivity(outActivity).execute();
        assertThat(outActivity[0].inSplitScreenWindowingMode()).isFalse();

        // Move activity to split-screen-primary stack and make sure it has the focus.
        TestSplitOrganizer splitOrg = new TestSplitOrganizer(mService, top.getDisplayId());
        top.getRootTask().reparent(splitOrg.mPrimary, POSITION_BOTTOM);
        top.getRootTask().moveToFront("testWindowingModeOptionsLaunchAdjacent");

        // Activity must landed on split-screen-secondary when launch adjacent.
        starter.setActivityOptions(options.toBundle())
                .setReason("testWindowingModeOptionsLaunchAdjacent")
                .setOutActivity(outActivity).execute();
        assertThat(outActivity[0].inSplitScreenSecondaryWindowingMode()).isTrue();
    }

    @Test
    public void testActivityStart_expectAddedToRecentTask() {
        RecentTasks recentTasks = mock(RecentTasks.class);
        mService.mStackSupervisor.setRecentTasks(recentTasks);
        doReturn(true).when(recentTasks).isCallerRecents(anyInt());

        final ActivityStarter starter = prepareStarter(0 /* flags */);

        starter.setReason("testAddToTaskListOnActivityStart")
                .execute();

        verify(recentTasks, times(1)).add(any());
    }

    @Test
    public void testStartActivityInner_allSplitScreenPrimaryActivitiesVisible() {
        // Given
        final ActivityStarter starter = prepareStarter(0, false);

        starter.setReason("testAllSplitScreenPrimaryActivitiesAreResumed");

        final ActivityRecord targetRecord = new ActivityBuilder(mService).build();
        targetRecord.setFocusable(false);
        targetRecord.setVisibility(false);
        final ActivityRecord sourceRecord = new ActivityBuilder(mService).build();

        final ActivityStack stack = spy(
                mRootWindowContainer.getDefaultTaskDisplayArea()
                        .createStack(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD,
                                /* onTop */true));

        stack.addChild(targetRecord);

        doReturn(stack).when(mRootWindowContainer)
                .getLaunchStack(any(), any(), any(), anyBoolean(), any(), anyInt(), anyInt());

        starter.mStartActivity = new ActivityBuilder(mService).build();

        // When
        starter.startActivityInner(
                /* r */targetRecord,
                /* sourceRecord */ sourceRecord,
                /* voiceSession */null,
                /* voiceInteractor */ null,
                /* startFlags */ 0,
                /* doResume */true,
                /* options */null,
                /* inTask */null,
                /* restrictedBgActivity */false);

        // Then
        verify(stack).ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        verify(targetRecord).makeVisibleIfNeeded(null, true);
        assertTrue(targetRecord.mVisibleRequested);
    }
}
