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

import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.app.StatusBarManager.DISABLE2_MASK;
import static android.app.StatusBarManager.DISABLE2_NONE;
import static android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE;
import static android.app.StatusBarManager.DISABLE_HOME;
import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ALERTS;
import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NONE;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_ALWAYS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_NEVER;
import static android.os.Process.SYSTEM_UID;
import static android.telecom.TelecomManager.EMERGENCY_DIALER_COMPONENT;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.isNull;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.LockTaskController.STATUS_BAR_MASK_LOCKED;
import static com.android.server.wm.LockTaskController.STATUS_BAR_MASK_PINNED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.IDevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.testing.DexmakerShareClassLoaderRule;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

/**
 * Unit tests for {@link LockTaskController}.
 *
 * Build/Install/Run:
 *  atest WmTests:LockTaskControllerTest
 */
@SmallTest
@Presubmit
public class LockTaskControllerTest {
    private static final String TEST_PACKAGE_NAME = "com.test.package";
    private static final String TEST_PACKAGE_NAME_2 = "com.test.package2";
    private static final String TEST_CLASS_NAME = ".TestClass";
    private static final int TEST_USER_ID = 123;
    private static final int TEST_UID = 10467;

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Mock private ActivityStackSupervisor mSupervisor;
    @Mock private RootWindowContainer mRootWindowContainer;
    @Mock private IDevicePolicyManager mDevicePolicyManager;
    @Mock private IStatusBarService mStatusBarService;
    @Mock private WindowManagerService mWindowManager;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private StatusBarManagerInternal mStatusBarManagerInternal;
    @Mock private TelecomManager mTelecomManager;
    @Mock private RecentTasks mRecentTasks;

    private LockTaskController mLockTaskController;
    private Context mContext;
    private String mPackageName;
    private String mLockToAppSetting;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = getInstrumentation().getTargetContext();
        mPackageName = mContext.getPackageName();
        mLockToAppSetting = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.LOCK_TO_APP_EXIT_LOCKED);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mSupervisor.mRecentTasks = mRecentTasks;
        mSupervisor.mRootWindowContainer = mRootWindowContainer;

        mLockTaskController = new LockTaskController(mContext, mSupervisor,
                new ImmediatelyExecuteHandler());
        mLockTaskController.setWindowManager(mWindowManager);
        mLockTaskController.mStatusBarService = mStatusBarService;
        mLockTaskController.mDevicePolicyManager = mDevicePolicyManager;
        mLockTaskController.mTelecomManager = mTelecomManager;
        mLockTaskController.mLockPatternUtils = mLockPatternUtils;

        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarManagerInternal);
    }

    @After
    public void tearDown() throws Exception {
        mLockTaskController.setWindowManager(null);
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.LOCK_TO_APP_EXIT_LOCKED, mLockToAppSetting);
    }

    @Test
    public void testPreconditions() {
        // GIVEN nothing has happened

        // THEN current lock task mode should be NONE
        assertEquals(LOCK_TASK_MODE_NONE, mLockTaskController.getLockTaskModeState());
    }

    @Test
    public void testStartLockTaskMode_once() throws Exception {
        // GIVEN a task record with allowlisted auth
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);

        // WHEN calling setLockTaskMode for LOCKED mode without resuming
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // THEN the lock task mode state should be LOCKED
        assertEquals(LOCK_TASK_MODE_LOCKED, mLockTaskController.getLockTaskModeState());
        // THEN the task should be locked
        assertTrue(mLockTaskController.isTaskLocked(tr));

        // THEN lock task mode should be started
        verifyLockTaskStarted(STATUS_BAR_MASK_LOCKED, DISABLE2_MASK);
    }

    @Test
    public void testStartLockTaskMode_twice() throws Exception {
        // GIVEN two task records with allowlisted auth
        Task tr1 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        Task tr2 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);

        // WHEN calling setLockTaskMode for LOCKED mode on both tasks
        mLockTaskController.startLockTaskMode(tr1, false, TEST_UID);
        mLockTaskController.startLockTaskMode(tr2, false, TEST_UID);

        // THEN the lock task mode state should be LOCKED
        assertEquals(LOCK_TASK_MODE_LOCKED, mLockTaskController.getLockTaskModeState());
        // THEN neither of the tasks should be able to move to back of stack
        assertTrue(mLockTaskController.isTaskLocked(tr1));
        assertTrue(mLockTaskController.isTaskLocked(tr2));

        // THEN lock task mode should be started
        verifyLockTaskStarted(STATUS_BAR_MASK_LOCKED, DISABLE2_MASK);
    }

    @Test
    public void testStartLockTaskMode_pinningRequest() {
        // GIVEN a task record that is not allowlisted, i.e. with pinned auth
        Task tr = getTask(Task.LOCK_TASK_AUTH_PINNABLE);

        // WHEN calling startLockTaskMode
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // THEN a pinning request should be shown
        verify(mStatusBarManagerInternal).showScreenPinningRequest(anyInt());
    }

    @Test
    public void testStartLockTaskMode_pinnedBySystem() throws Exception {
        // GIVEN a task record with pinned auth
        Task tr = getTask(Task.LOCK_TASK_AUTH_PINNABLE);

        // WHEN the system calls startLockTaskMode
        mLockTaskController.startLockTaskMode(tr, true, SYSTEM_UID);

        // THEN the lock task mode state should be PINNED
        assertEquals(LOCK_TASK_MODE_PINNED, mLockTaskController.getLockTaskModeState());
        // THEN the task should be locked
        assertTrue(mLockTaskController.isTaskLocked(tr));

        // THEN lock task mode should be started
        verifyLockTaskStarted(STATUS_BAR_MASK_PINNED, DISABLE2_NONE);
        // THEN screen pinning toast should be shown
        verify(mStatusBarService).showPinningEnterExitToast(eq(true /* entering */));
    }

    @Test
    public void testLockTaskViolation() {
        // GIVEN one task record with allowlisted auth that is in lock task mode
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // THEN it's not a lock task violation to try and launch this task without clearing
        assertFalse(mLockTaskController.isLockTaskModeViolation(tr, false));

        // THEN it's a lock task violation to launch another task that is not allowlisted
        assertTrue(mLockTaskController.isLockTaskModeViolation(getTask(
                Task.LOCK_TASK_AUTH_PINNABLE)));
        // THEN it's a lock task violation to launch another task that is disallowed from lock task
        assertTrue(mLockTaskController.isLockTaskModeViolation(getTask(
                Task.LOCK_TASK_AUTH_DONT_LOCK)));

        // THEN it's no a lock task violation to launch another task that is allowlisted
        assertFalse(mLockTaskController.isLockTaskModeViolation(getTask(
                Task.LOCK_TASK_AUTH_ALLOWLISTED)));
        assertFalse(mLockTaskController.isLockTaskModeViolation(getTask(
                Task.LOCK_TASK_AUTH_LAUNCHABLE)));
        // THEN it's not a lock task violation to launch another task that is priv launchable
        assertFalse(mLockTaskController.isLockTaskModeViolation(getTask(
                Task.LOCK_TASK_AUTH_LAUNCHABLE_PRIV)));
    }

    @Test
    public void testLockTaskViolation_emergencyCall() {
        // GIVEN one task record with allowlisted auth that is in lock task mode
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // GIVEN tasks necessary for emergency calling
        Task keypad = getTask(new Intent().setComponent(EMERGENCY_DIALER_COMPONENT),
                Task.LOCK_TASK_AUTH_PINNABLE);
        Task callAction = getTask(new Intent(Intent.ACTION_CALL_EMERGENCY),
                Task.LOCK_TASK_AUTH_PINNABLE);
        Task dialer = getTask("com.example.dialer", Task.LOCK_TASK_AUTH_PINNABLE);
        when(mTelecomManager.getSystemDialerPackage())
                .thenReturn(dialer.intent.getComponent().getPackageName());

        // GIVEN keyguard is allowed for lock task mode
        mLockTaskController.updateLockTaskFeatures(TEST_USER_ID, LOCK_TASK_FEATURE_KEYGUARD);

        // THEN the above tasks should all be allowed
        assertFalse(mLockTaskController.isLockTaskModeViolation(keypad));
        assertFalse(mLockTaskController.isLockTaskModeViolation(callAction));
        assertFalse(mLockTaskController.isLockTaskModeViolation(dialer));

        // GIVEN keyguard is disallowed for lock task mode (default)
        mLockTaskController.updateLockTaskFeatures(TEST_USER_ID, LOCK_TASK_FEATURE_NONE);

        // THEN the above tasks should all be blocked
        assertTrue(mLockTaskController.isLockTaskModeViolation(keypad));
        assertTrue(mLockTaskController.isLockTaskModeViolation(callAction));
        assertTrue(mLockTaskController.isLockTaskModeViolation(dialer));
    }

    @Test
    public void testStopLockTaskMode() throws Exception {
        // GIVEN one task record with allowlisted auth that is in lock task mode
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // WHEN the same caller calls stopLockTaskMode
        mLockTaskController.stopLockTaskMode(tr, false, TEST_UID);

        // THEN the lock task mode should be NONE
        assertEquals(LOCK_TASK_MODE_NONE, mLockTaskController.getLockTaskModeState());
        // THEN the task should no longer be locked
        assertFalse(mLockTaskController.isTaskLocked(tr));
        // THEN lock task mode should have been finished
        verifyLockTaskStopped(times(1));
    }

    @Test(expected = SecurityException.class)
    public void testStopLockTaskMode_differentCaller() {
        // GIVEN one task record with allowlisted auth that is in lock task mode
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // WHEN a different caller calls stopLockTaskMode
        mLockTaskController.stopLockTaskMode(tr, false, TEST_UID + 1);

        // THEN security exception should be thrown, because different caller tried to unlock
    }

    @Test
    public void testStopLockTaskMode_systemCaller() {
        // GIVEN one task record with allowlisted auth that is in lock task mode
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // WHEN system calls stopLockTaskMode
        mLockTaskController.stopLockTaskMode(tr, true, SYSTEM_UID);

        // THEN lock task mode should still be active
        assertEquals(LOCK_TASK_MODE_LOCKED, mLockTaskController.getLockTaskModeState());
    }

    @Test
    public void testStopLockTaskMode_twoTasks() throws Exception {
        // GIVEN two task records with allowlisted auth that is in lock task mode
        Task tr1 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        Task tr2 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr1, false, TEST_UID);
        mLockTaskController.startLockTaskMode(tr2, false, TEST_UID);

        // WHEN calling stopLockTaskMode
        mLockTaskController.stopLockTaskMode(tr2, false, TEST_UID);

        // THEN the lock task mode should still be active
        assertEquals(LOCK_TASK_MODE_LOCKED, mLockTaskController.getLockTaskModeState());
        // THEN the first task should still be locked
        assertTrue(mLockTaskController.isTaskLocked(tr1));
        // THEN the top task should no longer be locked
        assertFalse(mLockTaskController.isTaskLocked(tr2));
        // THEN lock task mode should not have been finished
        verifyLockTaskStopped(never());
    }

    @Test
    public void testStopLockTaskMode_rootTask() throws Exception {
        // GIVEN two task records with allowlisted auth that is in lock task mode
        Task tr1 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        Task tr2 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr1, false, TEST_UID);
        mLockTaskController.startLockTaskMode(tr2, false, TEST_UID);

        // WHEN calling stopLockTaskMode on the root task
        mLockTaskController.stopLockTaskMode(tr1, false, TEST_UID);

        // THEN the lock task mode should be inactive
        assertEquals(LOCK_TASK_MODE_NONE, mLockTaskController.getLockTaskModeState());
        // THEN the first task should no longer be locked
        assertFalse(mLockTaskController.isTaskLocked(tr1));
        // THEN the top task should no longer be locked
        assertFalse(mLockTaskController.isTaskLocked(tr2));
        // THEN lock task mode should be finished
        verifyLockTaskStopped(times(1));
    }

    @Test
    public void testStopLockTaskMode_pinned() throws Exception {
        // GIVEN one task records that is in pinned mode
        Task tr = getTask(Task.LOCK_TASK_AUTH_PINNABLE);
        mLockTaskController.startLockTaskMode(tr, true, SYSTEM_UID);
        // GIVEN that the keyguard is required to show after unlocking
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_TO_APP_EXIT_LOCKED, 1);

        // reset invocation counter
        reset(mStatusBarService);

        // WHEN calling stopLockTask
        mLockTaskController.stopLockTaskMode(null, true, SYSTEM_UID);

        // THEN the lock task mode should no longer be active
        assertEquals(LOCK_TASK_MODE_NONE, mLockTaskController.getLockTaskModeState());
        // THEN the task should no longer be locked
        assertFalse(mLockTaskController.isTaskLocked(tr));
        // THEN lock task mode should have been finished
        verifyLockTaskStopped(times(1));
        // THEN the keyguard should be shown
        verify(mLockPatternUtils).requireCredentialEntry(eq(UserHandle.USER_ALL));
        // THEN screen pinning toast should be shown
        verify(mStatusBarService).showPinningEnterExitToast(eq(false /* entering */));
    }

    @Test
    public void testClearLockedTasks() throws Exception {
        // GIVEN two task records with allowlisted auth that is in lock task mode
        Task tr1 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        Task tr2 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr1, false, TEST_UID);
        mLockTaskController.startLockTaskMode(tr2, false, TEST_UID);

        // WHEN calling clearLockedTasks on the root task
        mLockTaskController.clearLockedTasks("testClearLockedTasks");

        // THEN the lock task mode should be inactive
        assertEquals(LOCK_TASK_MODE_NONE, mLockTaskController.getLockTaskModeState());
        // THEN the first task should no longer be locked
        assertFalse(mLockTaskController.isTaskLocked(tr1));
        // THEN the top task should no longer be locked
        assertFalse(mLockTaskController.isTaskLocked(tr2));
        // THEN lock task mode should be finished
        verifyLockTaskStopped(times(1));
    }

    @Test
    public void testClearLockedTasks_noLockSetting_noPassword_deviceIsUnlocked() throws Exception {
        // GIVEN There is no setting set for LOCK_TO_APP_EXIT_LOCKED
        Settings.Secure.clearProviderForTest();

        // AND no password is set
        when(mLockPatternUtils.getKeyguardStoredPasswordQuality(anyInt()))
                .thenReturn(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);

        // AND there is a task record
        Task tr1 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr1, true, TEST_UID);

        // WHEN calling clearLockedTasks on the root task
        mLockTaskController.clearLockedTasks("testClearLockedTasks");

        // THEN the device should not be locked
        verify(mWindowManager, never()).lockNow(any());
    }

    @Test
    public void testClearLockedTasks_noLockSetting_password_deviceIsLocked() throws Exception {
        // GIVEN There is no setting set for LOCK_TO_APP_EXIT_LOCKED
        Settings.Secure.clearProviderForTest();

        // AND a password is set
        when(mLockPatternUtils.isSecure(anyInt()))
                .thenReturn(true);

        // AND there is a task record
        Task tr1 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr1, true, TEST_UID);

        // WHEN calling clearLockedTasks on the root task
        mLockTaskController.clearLockedTasks("testClearLockedTasks");

        // THEN the device should be locked
        verify(mWindowManager, times(1)).lockNow(any());
    }

    @Test
    public void testClearLockedTasks_lockSettingTrue_deviceIsLocked() throws Exception {
        // GIVEN LOCK_TO_APP_EXIT_LOCKED is set to 1
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_TO_APP_EXIT_LOCKED, 1, mContext.getUserId());

        // AND there is a task record
        Task tr1 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr1, true, TEST_UID);

        // WHEN calling clearLockedTasks on the root task
        mLockTaskController.clearLockedTasks("testClearLockedTasks");

        // THEN the device should be locked
        verify(mWindowManager, times(1)).lockNow(any());
    }

    @Test
    public void testClearLockedTasks_lockSettingFalse_doesNotRequirePassword() throws Exception {
        // GIVEN LOCK_TO_APP_EXIT_LOCKED is set to 1
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_TO_APP_EXIT_LOCKED, 0, mContext.getUserId());

        // AND there is a task record
        Task tr1 = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr1, true, TEST_UID);

        // WHEN calling clearLockedTasks on the root task
        mLockTaskController.clearLockedTasks("testClearLockedTasks");

        // THEN the device should be unlocked
        verify(mWindowManager, never()).lockNow(any());
    }

    @Test
    public void testUpdateLockTaskPackages() {
        String[] allowlist1 = {TEST_PACKAGE_NAME, TEST_PACKAGE_NAME_2};
        String[] allowlist2 = {TEST_PACKAGE_NAME};

        // No package is allowlisted initially
        for (String pkg : allowlist1) {
            assertFalse("Package shouldn't be allowlisted: " + pkg,
                    mLockTaskController.isPackageAllowlisted(TEST_USER_ID, pkg));
            assertFalse("Package shouldn't be allowlisted for user 0: " + pkg,
                    mLockTaskController.isPackageAllowlisted(0, pkg));
        }

        // Apply allowlist
        mLockTaskController.updateLockTaskPackages(TEST_USER_ID, allowlist1);

        // Assert the allowlist is applied to the correct user
        for (String pkg : allowlist1) {
            assertTrue("Package should be allowlisted: " + pkg,
                    mLockTaskController.isPackageAllowlisted(TEST_USER_ID, pkg));
            assertFalse("Package shouldn't be allowlisted for user 0: " + pkg,
                    mLockTaskController.isPackageAllowlisted(0, pkg));
        }

        // Update allowlist
        mLockTaskController.updateLockTaskPackages(TEST_USER_ID, allowlist2);

        // Assert the new allowlist is applied
        assertTrue("Package should remain allowlisted: " + TEST_PACKAGE_NAME,
                mLockTaskController.isPackageAllowlisted(TEST_USER_ID, TEST_PACKAGE_NAME));
        assertFalse("Package should no longer be allowlisted: " + TEST_PACKAGE_NAME_2,
                mLockTaskController.isPackageAllowlisted(TEST_USER_ID, TEST_PACKAGE_NAME_2));
    }

    @Test
    public void testUpdateLockTaskPackages_taskRemoved() throws Exception {
        // GIVEN two tasks which are allowlisted initially
        Task tr1 = getTaskForUpdate(TEST_PACKAGE_NAME, true);
        Task tr2 = getTaskForUpdate(TEST_PACKAGE_NAME_2, false);
        String[] allowlist = {TEST_PACKAGE_NAME, TEST_PACKAGE_NAME_2};
        mLockTaskController.updateLockTaskPackages(TEST_USER_ID, allowlist);

        // GIVEN the tasks are launched into LockTask mode
        mLockTaskController.startLockTaskMode(tr1, false, TEST_UID);
        mLockTaskController.startLockTaskMode(tr2, false, TEST_UID);
        assertEquals(LOCK_TASK_MODE_LOCKED, mLockTaskController.getLockTaskModeState());
        assertTrue(mLockTaskController.isTaskLocked(tr1));
        assertTrue(mLockTaskController.isTaskLocked(tr2));
        verifyLockTaskStarted(STATUS_BAR_MASK_LOCKED, DISABLE2_MASK);

        // WHEN removing one package from allowlist
        allowlist = new String[] {TEST_PACKAGE_NAME};
        mLockTaskController.updateLockTaskPackages(TEST_USER_ID, allowlist);

        // THEN the task running that package should be stopped
        verify(tr2).performClearTaskLocked();
        assertFalse(mLockTaskController.isTaskLocked(tr2));
        // THEN the other task should remain locked
        assertEquals(LOCK_TASK_MODE_LOCKED, mLockTaskController.getLockTaskModeState());
        assertTrue(mLockTaskController.isTaskLocked(tr1));
        verifyLockTaskStarted(STATUS_BAR_MASK_LOCKED, DISABLE2_MASK);

        // WHEN removing the last package from allowlist
        allowlist = new String[] {};
        mLockTaskController.updateLockTaskPackages(TEST_USER_ID, allowlist);

        // THEN the last task should be cleared, and the system should quit LockTask mode
        verify(tr1).performClearTaskLocked();
        assertFalse(mLockTaskController.isTaskLocked(tr1));
        assertEquals(LOCK_TASK_MODE_NONE, mLockTaskController.getLockTaskModeState());
        verifyLockTaskStopped(times(1));
    }

    @Test
    public void testUpdateLockTaskFeatures() throws Exception {
        // GIVEN a locked task
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // THEN lock task mode should be started with default status bar masks
        verifyLockTaskStarted(STATUS_BAR_MASK_LOCKED, DISABLE2_MASK);

        // reset invocation counter
        reset(mStatusBarService);

        // WHEN home button is enabled for lock task mode
        mLockTaskController.updateLockTaskFeatures(TEST_USER_ID, LOCK_TASK_FEATURE_HOME);

        // THEN status bar should be updated to reflect this change
        int expectedFlags = STATUS_BAR_MASK_LOCKED
                & ~DISABLE_HOME;
        int expectedFlags2 = DISABLE2_MASK;
        verify(mStatusBarService).disable(eq(expectedFlags), any(IBinder.class),
                eq(mPackageName));
        verify(mStatusBarService).disable2(eq(expectedFlags2), any(IBinder.class),
                eq(mPackageName));

        // reset invocation counter
        reset(mStatusBarService);

        // WHEN notifications are enabled for lock task mode
        mLockTaskController.updateLockTaskFeatures(TEST_USER_ID, LOCK_TASK_FEATURE_NOTIFICATIONS);

        // THEN status bar should be updated to reflect this change
        expectedFlags = STATUS_BAR_MASK_LOCKED
                & ~DISABLE_NOTIFICATION_ICONS
                & ~DISABLE_NOTIFICATION_ALERTS;
        expectedFlags2 = DISABLE2_MASK
                & ~DISABLE2_NOTIFICATION_SHADE;
        verify(mStatusBarService).disable(eq(expectedFlags), any(IBinder.class),
                eq(mPackageName));
        verify(mStatusBarService).disable2(eq(expectedFlags2), any(IBinder.class),
                eq(mPackageName));
    }

    @Test
    public void testUpdateLockTaskFeatures_differentUser() throws Exception {
        // GIVEN a locked task
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // THEN lock task mode should be started with default status bar masks
        verifyLockTaskStarted(STATUS_BAR_MASK_LOCKED, DISABLE2_MASK);

        // reset invocation counter
        reset(mStatusBarService);

        // WHEN home button is enabled for lock task mode for another user
        mLockTaskController.updateLockTaskFeatures(TEST_USER_ID + 1, LOCK_TASK_FEATURE_HOME);

        // THEN status bar shouldn't change
        verify(mStatusBarService, never()).disable(anyInt(), any(IBinder.class),
                eq(mPackageName));
        verify(mStatusBarService, never()).disable2(anyInt(), any(IBinder.class),
                eq(mPackageName));
    }

    @Test
    public void testUpdateLockTaskFeatures_keyguard() {
        // GIVEN a locked task
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // THEN keyguard should be disabled
        verify(mWindowManager).disableKeyguard(any(IBinder.class), anyString(), eq(TEST_USER_ID));

        // WHEN keyguard is enabled for lock task mode
        mLockTaskController.updateLockTaskFeatures(TEST_USER_ID, LOCK_TASK_FEATURE_KEYGUARD);

        // THEN keyguard should be enabled
        verify(mWindowManager).reenableKeyguard(any(IBinder.class), eq(TEST_USER_ID));

        // WHEN keyguard is disabled again for lock task mode
        mLockTaskController.updateLockTaskFeatures(TEST_USER_ID, LOCK_TASK_FEATURE_NONE);

        // THEN keyguard should be disabled
        verify(mWindowManager, times(2)).disableKeyguard(any(IBinder.class), anyString(),
                eq(TEST_USER_ID));
    }

    @Test
    public void testGetStatusBarDisableFlags() {
        // Note that we don't enumerate all StatusBarManager flags, but only choose a subset to test

        // WHEN nothing is enabled
        Pair<Integer, Integer> flags = mLockTaskController.getStatusBarDisableFlags(
                LOCK_TASK_FEATURE_NONE);
        // THEN unsupported feature flags should still be untouched
        assertTrue((~STATUS_BAR_MASK_LOCKED & flags.first) == 0);
        // THEN everything else should be disabled
        assertTrue((StatusBarManager.DISABLE_CLOCK & flags.first) != 0);
        assertTrue((StatusBarManager.DISABLE2_QUICK_SETTINGS & flags.second) != 0);

        // WHEN only home button is enabled
        flags = mLockTaskController.getStatusBarDisableFlags(
                LOCK_TASK_FEATURE_HOME);
        // THEN unsupported feature flags should still be untouched
        assertTrue((~STATUS_BAR_MASK_LOCKED & flags.first) == 0);
        // THEN home button should indeed be enabled
        assertTrue((StatusBarManager.DISABLE_HOME & flags.first) == 0);
        // THEN other feature flags should remain disabled
        assertTrue((StatusBarManager.DISABLE2_NOTIFICATION_SHADE & flags.second) != 0);

        // WHEN only global actions menu and notifications are enabled
        flags = mLockTaskController.getStatusBarDisableFlags(
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                        | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS);
        // THEN unsupported feature flags should still be untouched
        assertTrue((~STATUS_BAR_MASK_LOCKED & flags.first) == 0);
        // THEN notifications should be enabled
        assertTrue((StatusBarManager.DISABLE_NOTIFICATION_ICONS & flags.first) == 0);
        assertTrue((StatusBarManager.DISABLE_NOTIFICATION_ALERTS & flags.first) == 0);
        assertTrue((StatusBarManager.DISABLE2_NOTIFICATION_SHADE & flags.second) == 0);
        // THEN global actions should be enabled
        assertTrue((StatusBarManager.DISABLE2_GLOBAL_ACTIONS & flags.second) == 0);
        // THEN quick settings should still be disabled
        assertTrue((StatusBarManager.DISABLE2_QUICK_SETTINGS & flags.second) != 0);
    }

    @Test
    public void testIsActivityAllowed() {
        // WHEN lock task mode is not enabled
        assertTrue(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_DEFAULT));

        // Start lock task mode
        Task tr = getTask(Task.LOCK_TASK_AUTH_ALLOWLISTED);
        mLockTaskController.startLockTaskMode(tr, false, TEST_UID);

        // WHEN LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK is not enabled
        assertTrue(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_DEFAULT));

        // Enable LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK feature
        mLockTaskController.updateLockTaskFeatures(
                TEST_USER_ID, LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK);

        // package with LOCK_TASK_LAUNCH_MODE_ALWAYS should always be allowed
        assertTrue(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_ALWAYS));

        // unallowlisted package should not be allowed
        assertFalse(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_DEFAULT));

        // update the allowlist
        String[] allowlist = new String[] { TEST_PACKAGE_NAME };
        mLockTaskController.updateLockTaskPackages(TEST_USER_ID, allowlist);

        // allowlisted package should be allowed
        assertTrue(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_DEFAULT));

        // package with LOCK_TASK_LAUNCH_MODE_NEVER should never be allowed
        assertFalse(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_NEVER));
    }

    private Task getTask(int lockTaskAuth) {
        return getTask(TEST_PACKAGE_NAME, lockTaskAuth);
    }

    private Task getTask(String pkg, int lockTaskAuth) {
        final Intent intent = new Intent()
                .setComponent(ComponentName.createRelative(pkg, TEST_CLASS_NAME));
        return getTask(intent, lockTaskAuth);
    }

    private Task getTask(Intent intent, int lockTaskAuth) {
        Task tr = mock(Task.class);
        tr.mLockTaskAuth = lockTaskAuth;
        tr.intent = intent;
        tr.mUserId = TEST_USER_ID;
        return tr;
    }

    /**
     * @param isAppAware {@code true} if the app has marked if_allowlisted in its manifest
     */
    private Task getTaskForUpdate(String pkg, boolean isAppAware) {
        final int authIfAllowlisted = isAppAware
                ? Task.LOCK_TASK_AUTH_LAUNCHABLE
                : Task.LOCK_TASK_AUTH_ALLOWLISTED;
        Task tr = getTask(pkg, authIfAllowlisted);
        doAnswer((invocation) -> {
            boolean isAllowlisted =
                    mLockTaskController.isPackageAllowlisted(TEST_USER_ID, pkg);
            tr.mLockTaskAuth = isAllowlisted ? authIfAllowlisted : Task.LOCK_TASK_AUTH_PINNABLE;
            return null;
        }).when(tr).setLockTaskAuth();
        return tr;
    }

    private void verifyLockTaskStarted(int statusBarMask, int statusBarMask2) throws Exception {
        // THEN the keyguard should have been disabled
        verify(mWindowManager).disableKeyguard(any(IBinder.class), anyString(), eq(TEST_USER_ID));
        // THEN the status bar should have been disabled
        verify(mStatusBarService).disable(eq(statusBarMask), any(IBinder.class),
                eq(mPackageName));
        verify(mStatusBarService).disable2(eq(statusBarMask2), any(IBinder.class),
                eq(mPackageName));
        // THEN recents should have been notified
        verify(mRecentTasks).onLockTaskModeStateChanged(anyInt(), eq(TEST_USER_ID));
        // THEN the DO/PO should be informed about the operation
        verify(mDevicePolicyManager).notifyLockTaskModeChanged(eq(true), eq(TEST_PACKAGE_NAME),
                eq(TEST_USER_ID));
    }

    private void verifyLockTaskStopped(VerificationMode mode) throws Exception {
        // THEN the keyguard should have been disabled
        verify(mWindowManager, mode).reenableKeyguard(any(IBinder.class), eq(TEST_USER_ID));
        // THEN the status bar should have been disabled
        verify(mStatusBarService, mode).disable(eq(StatusBarManager.DISABLE_NONE),
                any(IBinder.class), eq(mPackageName));
        verify(mStatusBarService, mode).disable2(eq(StatusBarManager.DISABLE2_NONE),
                any(IBinder.class), eq(mPackageName));
        // THEN the DO/PO should be informed about the operation
        verify(mDevicePolicyManager, mode).notifyLockTaskModeChanged(eq(false), isNull(),
                eq(TEST_USER_ID));
    }

    /**
     * Special handler implementation that executes any message / runnable posted immediately on the
     * thread that it's posted on rather than enqueuing them on its looper.
     */
    private static class ImmediatelyExecuteHandler extends Handler {
        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            if (msg.getCallback() != null) {
                msg.getCallback().run();
            }
            return true;
        }
    }
}
