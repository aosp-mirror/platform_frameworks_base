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

import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;

import android.app.ActivityManagerInternal;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.testing.DexmakerShareClassLoaderRule;

import androidx.test.filters.SmallTest;

import com.android.internal.app.BlockedAppActivity;
import com.android.internal.app.HarmfulAppWarningActivity;
import com.android.internal.app.SuspendedAppActivity;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.pm.PackageManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ActivityStartInterceptorTest}.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStartInterceptorTest
 */
@SmallTest
@Presubmit
public class ActivityStartInterceptorTest {
    private static final int TEST_USER_ID = 1;
    private static final int TEST_REAL_CALLING_UID = 2;
    private static final int TEST_REAL_CALLING_PID = 3;
    private static final String TEST_CALLING_PACKAGE = "com.test.caller";
    private static final int TEST_START_FLAGS = 4;
    private static final Intent ADMIN_SUPPORT_INTENT =
            new Intent("com.test.ADMIN_SUPPORT");
    private static final Intent CONFIRM_CREDENTIALS_INTENT =
            new Intent("com.test.CONFIRM_CREDENTIALS");
    private static final UserInfo PARENT_USER_INFO = new UserInfo(0 /* userId */, "parent",
            0 /* flags */);
    private static final String TEST_PACKAGE_NAME = "com.test.package";

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Mock
    private Context mContext;
    @Mock
    private ActivityManagerService mAm;
    @Mock
    private ActivityTaskManagerService mService;
    @Mock
    private RootWindowContainer mRootWindowContainer;
    @Mock
    private ActivityTaskSupervisor mSupervisor;
    @Mock
    private DevicePolicyManagerInternal mDevicePolicyManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private UserManager mUserManager;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private PackageManagerService mPackageManager;
    @Mock
    private ActivityManagerInternal mAmInternal;
    @Mock
    private LockTaskController mLockTaskController;

    private ActivityStartInterceptor mInterceptor;
    private ActivityInfo mAInfo = new ActivityInfo();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mService.mAmInternal = mAmInternal;
        mInterceptor = new ActivityStartInterceptor(
                mService, mSupervisor, mRootWindowContainer, mContext);
        mInterceptor.setStates(TEST_USER_ID, TEST_REAL_CALLING_PID, TEST_REAL_CALLING_UID,
                TEST_START_FLAGS, TEST_CALLING_PACKAGE, null);

        // Mock ActivityManagerInternal
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmInternal);

        // Mock DevicePolicyManagerInternal
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class,
                mDevicePolicyManager);
        when(mDevicePolicyManager.createShowAdminSupportIntent(TEST_USER_ID, true))
                .thenReturn(ADMIN_SUPPORT_INTENT);
        when(mService.getPackageManagerInternalLocked()).thenReturn(mPackageManagerInternal);

        // Mock UserManager
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mUserManager.getProfileParent(TEST_USER_ID)).thenReturn(PARENT_USER_INFO);

        // Mock KeyguardManager
        when(mContext.getSystemService(Context.KEYGUARD_SERVICE)).thenReturn(mKeyguardManager);
        when(mKeyguardManager.createConfirmDeviceCredentialIntent(
                nullable(CharSequence.class), nullable(CharSequence.class), eq(TEST_USER_ID),
                eq(true))).thenReturn(CONFIRM_CREDENTIALS_INTENT);

        // Mock PackageManager
        when(mService.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getHarmfulAppWarning(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(null);

        // Mock LockTaskController
        mAInfo.lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_DEFAULT;
        when(mService.getLockTaskController()).thenReturn(mLockTaskController);
        when(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_DEFAULT))
                .thenReturn(true);

        // Initialise activity info
        mAInfo.applicationInfo = new ApplicationInfo();
        mAInfo.packageName = mAInfo.applicationInfo.packageName = TEST_PACKAGE_NAME;
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
    }

    @Test
    public void testSuspendedByAdminPackage() {
        // GIVEN the package we're about to launch is currently suspended
        mAInfo.applicationInfo.flags = FLAG_SUSPENDED;

        when(mPackageManagerInternal.getSuspendingPackage(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(PLATFORM_PACKAGE_NAME);

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));

        // THEN the returned intent is the admin support intent
        assertEquals(ADMIN_SUPPORT_INTENT, mInterceptor.mIntent);
    }

    @Test
    public void testSuspendedPackage() {
        final String suspendingPackage = "com.test.suspending.package";
        final SuspendDialogInfo dialogInfo = suspendPackage(suspendingPackage);
        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));

        // Check intent parameters
        assertEquals(dialogInfo,
                mInterceptor.mIntent.getParcelableExtra(SuspendedAppActivity.EXTRA_DIALOG_INFO));
        assertEquals(suspendingPackage,
                mInterceptor.mIntent.getStringExtra(SuspendedAppActivity.EXTRA_SUSPENDING_PACKAGE));
        assertEquals(TEST_PACKAGE_NAME,
                mInterceptor.mIntent.getStringExtra(SuspendedAppActivity.EXTRA_SUSPENDED_PACKAGE));
        assertEquals(TEST_USER_ID, mInterceptor.mIntent.getIntExtra(Intent.EXTRA_USER_ID, -1000));
    }

    private SuspendDialogInfo suspendPackage(String suspendingPackage) {
        mAInfo.applicationInfo.flags = FLAG_SUSPENDED;
        final SuspendDialogInfo dialogInfo = new SuspendDialogInfo.Builder()
                .setMessage("Test Message")
                .setIcon(0x11110001)
                .build();
        when(mPackageManagerInternal.getSuspendingPackage(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(suspendingPackage);
        when(mPackageManagerInternal.getSuspendedDialogInfo(TEST_PACKAGE_NAME, suspendingPackage,
                TEST_USER_ID)).thenReturn(dialogInfo);
        return dialogInfo;
    }

    @Test
    public void testInterceptLockTaskModeViolationPackage() {
        when(mLockTaskController.isActivityAllowed(
                TEST_USER_ID, TEST_PACKAGE_NAME, LOCK_TASK_LAUNCH_MODE_DEFAULT))
                .thenReturn(false);

        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));

        assertTrue(BlockedAppActivity.createIntent(TEST_USER_ID, TEST_PACKAGE_NAME)
                .filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testInterceptQuietProfile() {
        // GIVEN that the user the activity is starting as is currently in quiet mode
        when(mUserManager.isQuietModeEnabled(eq(UserHandle.of(TEST_USER_ID)))).thenReturn(true);

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));

        // THEN the returned intent is the quiet mode intent
        assertTrue(UnlaunchableAppActivity.createInQuietModeDialogIntent(TEST_USER_ID)
                .filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testInterceptQuietProfileWhenPackageSuspended() {
        suspendPackage("com.test.suspending.package");
        // GIVEN that the user the activity is starting as is currently in quiet mode
        when(mUserManager.isQuietModeEnabled(eq(UserHandle.of(TEST_USER_ID)))).thenReturn(true);

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));

        // THEN the returned intent is the quiet mode intent
        assertTrue(UnlaunchableAppActivity.createInQuietModeDialogIntent(TEST_USER_ID)
                .filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testLockedManagedProfile() {
        // GIVEN that the user the activity is starting as is currently locked
        when(mAmInternal.shouldConfirmCredentials(TEST_USER_ID)).thenReturn(true);

        // THEN calling intercept returns true
        mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null);

        // THEN the returned intent is the quiet mode intent
        assertTrue(CONFIRM_CREDENTIALS_INTENT.filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testHarmfulAppWarning() {
        // GIVEN the package we're about to launch has a harmful app warning set
        when(mPackageManager.getHarmfulAppWarning(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn("This app is bad");

        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));

        // THEN the returned intent is the harmful app warning intent
        assertEquals(HarmfulAppWarningActivity.class.getName(),
                mInterceptor.mIntent.getComponent().getClassName());
    }

    @Test
    public void testNoInterception() {
        // GIVEN that none of the interception conditions are met

        // THEN calling intercept returns false
        assertFalse(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));
    }
}
