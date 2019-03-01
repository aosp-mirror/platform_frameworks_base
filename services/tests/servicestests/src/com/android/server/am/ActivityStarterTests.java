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

import static android.app.ActivityManager.START_ABORTED;
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
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;

import static com.android.server.am.ActivityManagerService.ANIMATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.WindowLayout;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.service.voice.IVoiceInteractionSession;
import android.view.Gravity;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsImpl;
import com.android.server.am.LaunchParamsController.LaunchParamsModifier;
import com.android.server.am.TaskRecord.TaskRecordFactory;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ActivityStarter} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ActivityStarterTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStarterTests extends ActivityTestsBase {
    private ActivityManagerService mService;
    private ActivityStarter mStarter;
    private ActivityStartController mController;
    private ActivityMetricsLogger mActivityMetricsLogger;

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

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mService = createActivityManagerService();
        mController = mock(ActivityStartController.class);
        mActivityMetricsLogger = mock(ActivityMetricsLogger.class);
        clearInvocations(mActivityMetricsLogger);
        mStarter = new ActivityStarter(mController, mService, mService.mStackSupervisor,
                mock(ActivityStartInterceptor.class));
    }

    @Test
    public void testUpdateLaunchBounds() throws Exception {
        // When in a non-resizeable stack, the task bounds should be updated.
        final TaskRecord task = new TaskBuilder(mService.mStackSupervisor)
                .setStack(mService.mStackSupervisor.getDefaultDisplay().createStack(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */))
                .build();
        final Rect bounds = new Rect(10, 10, 100, 100);

        mStarter.updateBounds(task, bounds);
        assertEquals(task.getOverrideBounds(), bounds);
        assertEquals(new Rect(), task.getStack().getOverrideBounds());

        // When in a resizeable stack, the stack bounds should be updated as well.
        final TaskRecord task2 = new TaskBuilder(mService.mStackSupervisor)
                .setStack(mService.mStackSupervisor.getDefaultDisplay().createStack(
                        WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */))
                .build();
        assertTrue(task2.getStack() instanceof PinnedActivityStack);
        mStarter.updateBounds(task2, bounds);

        verify(mService, times(1)).resizeStack(eq(task2.getStack().mStackId),
                eq(bounds), anyBoolean(), anyBoolean(), anyBoolean(), anyInt());

        // In the case of no animation, the stack and task bounds should be set immediately.
        if (!ANIMATE) {
            assertEquals(task2.getStack().getOverrideBounds(), bounds);
            assertEquals(task2.getOverrideBounds(), bounds);
        } else {
            assertEquals(task2.getOverrideBounds(), new Rect());
        }
    }

    @Test
    public void testStartActivityPreconditions() throws Exception {
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

    /**
     * Excercises how the {@link ActivityStarter} reacts to various preconditions. The caller
     * provides a bitmask of all the set conditions (such as {@link #PRECONDITION_NO_CALLER_APP})
     * and the launch flags specified in the intent. The method constructs a call to
     * {@link ActivityStarter#execute} based on these preconditions and ensures the result matches
     * the expected. It is important to note that the method also checks side effects of the start,
     * such as ensuring {@link ActivityOptions#abort()} is called in the relevant scenarios.
     * @param preconditions A bitmask representing the preconditions for the launch
     * @param launchFlags The launch flags to be provided by the launch {@link Intent}.
     * @param expectedResult The expected result from the launch.
     */
    private void verifyStartActivityPreconditions(int preconditions, int launchFlags,
            int expectedResult) {
        final ActivityManagerService service = createActivityManagerService();
        final IPackageManager packageManager = mock(IPackageManager.class);
        final ActivityStartController controller = mock(ActivityStartController.class);

        final ActivityStarter starter = new ActivityStarter(controller, service,
                service.mStackSupervisor, mock(ActivityStartInterceptor.class));
        final IApplicationThread caller = mock(IApplicationThread.class);

        // If no caller app, return {@code null} {@link ProcessRecord}.
        final ProcessRecord record = containsConditions(preconditions, PRECONDITION_NO_CALLER_APP)
                ? null : new ProcessRecord(null, mock(BatteryStatsImpl.class),
                mock(ApplicationInfo.class), null, 0);

        doReturn(record).when(service).getRecordForAppLocked(anyObject());

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
            intent.setComponent(source.realActivity);
        }

        if (containsConditions(preconditions, PRECONDITION_DISALLOW_APP_SWITCHING)) {
            doReturn(false).when(service).checkAppSwitchAllowedLocked(anyInt(), anyInt(), anyInt(),
                    anyInt(), any());
        }

        if (containsConditions(preconditions,PRECONDITION_CANNOT_START_ANY_ACTIVITY)) {
            doReturn(false).when(service.mStackSupervisor).checkStartAnyActivityPermission(
                    any(), any(), any(), anyInt(), anyInt(), anyInt(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any());
        }

        try {
            if (containsConditions(preconditions,
                    PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION)) {
                doAnswer((inv) -> {
                    throw new RemoteException();
                }).when(packageManager).activitySupportsIntent(eq(source.realActivity), eq(intent),
                        any());
            } else {
                doReturn(!containsConditions(preconditions, PRECONDITION_NO_VOICE_SESSION_SUPPORT))
                        .when(packageManager).activitySupportsIntent(eq(source.realActivity),
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

    private ActivityStarter prepareStarter(int launchFlags) {
        // always allow test to start activity.
        doReturn(true).when(mService.mStackSupervisor).checkStartAnyActivityPermission(
                any(), any(), any(), anyInt(), anyInt(), anyInt(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any());

        // instrument the stack and task used.
        final ActivityStack stack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final TaskRecord task = new TaskBuilder(mService.mStackSupervisor)
                .setCreateStack(false)
                .build();

        // supervisor needs a focused stack.
        mService.mStackSupervisor.mFocusedStack = stack;

        // use factory that only returns spy task.
        final TaskRecordFactory factory = mock(TaskRecordFactory.class);
        TaskRecord.setTaskRecordFactory(factory);

        // return task when created.
        doReturn(task).when(factory).create(any(), anyInt(), any(), any(), any(), any());

        // direct starter to use spy stack.
        doReturn(stack).when(mService.mStackSupervisor)
                .getLaunchStack(any(), any(), any(), anyBoolean());
        doReturn(stack).when(mService.mStackSupervisor)
                .getLaunchStack(any(), any(), any(), anyBoolean(), anyInt());

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

        // verify that values are passed to the modifier.
        verify(modifier, times(1)).onCalculate(any(), eq(windowLayout), any(), any(), eq(options),
                any(), any());
    }

    /**
     * This test ensures that if the intent is being delivered to a
     */
    @Test
    public void testSplitScreenDeliverToTop() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        final ActivityRecord focusActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .build();

        focusActivity.getStack().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);

        final ActivityRecord reusableActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .build();

        // Create reusable activity after entering split-screen so that it is the top secondary
        // stack.
        reusableActivity.getStack().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);

        // Set focus back to primary.
        mService.mStackSupervisor.setFocusStackUnchecked("testSplitScreenDeliverToTop",
                focusActivity.getStack());

        doReturn(reusableActivity).when(mService.mStackSupervisor).findTaskLocked(any(), anyInt());

        final int result = starter.setReason("testSplitScreenDeliverToTop").execute();

        // Ensure result is delivering intent to top.
        assertEquals(result, START_DELIVERED_TO_TOP);
    }

    /**
     * This test ensures that if the intent is being delivered to a split-screen unfocused task
     * reports it is brought to front instead of delivering to top.
     */
    @Test
    public void testSplitScreenTaskToFront() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        // Create reusable activity here first. Setting the windowing mode of the primary stack
        // will move the existing standard full screen stack to secondary, putting this one on the
        // bottom.
        final ActivityRecord reusableActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .build();

        reusableActivity.getStack().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);

        final ActivityRecord focusActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .build();

        // Enter split-screen. Primary stack should have focus.
        focusActivity.getStack().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);

        doReturn(reusableActivity).when(mService.mStackSupervisor).findTaskLocked(any(), anyInt());

        final int result = starter.setReason("testSplitScreenMoveToFront").execute();

        // Ensure result is moving task to front.
        assertEquals(result, START_TASK_TO_FRONT);
    }

    /**
     * Tests activity is cleaned up properly in a task mode violation.
     */
    @Test
    public void testTaskModeViolation() {
        final ActivityDisplay display = mService.mStackSupervisor.getDefaultDisplay();
        assertNoTasks(display);

        final ActivityStarter starter = prepareStarter(0);

        final LockTaskController lockTaskController = mService.getLockTaskController();
        doReturn(true).when(lockTaskController).isLockTaskModeViolation(any());

        final int result = starter.setReason("testTaskModeViolation").execute();

        assertEquals(START_RETURN_LOCK_TASK_MODE_VIOLATION, result);
        assertNoTasks(display);
    }

    private void assertNoTasks(ActivityDisplay display) {
        for (int i = display.getChildCount() - 1; i >= 0; --i) {
            final ActivityStack stack = display.getChildAt(i);
            assertTrue(stack.getAllTasks().isEmpty());
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
        verify(mActivityMetricsLogger, never()).logActivityStart(any(), any(), any(), anyInt(),
                any(), anyInt(), anyBoolean(), anyInt(), anyInt(), anyBoolean(), anyInt(), any(),
                anyInt(), anyBoolean(), any(), anyBoolean());
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
        verify(mActivityMetricsLogger, times(1)).logActivityStart(any(), any(), any(),
                eq(FAKE_CALLING_UID), eq(FAKE_CALLING_PACKAGE), anyInt(), anyBoolean(),
                eq(FAKE_REAL_CALLING_UID), anyInt(), anyBoolean(), anyInt(),
                eq(ActivityBuilder.getDefaultComponent().getPackageName()), anyInt(), anyBoolean(),
                any(), eq(false));
    }
}
