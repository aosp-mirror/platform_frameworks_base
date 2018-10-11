/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.filters.SmallTest;
import android.testing.DexmakerShareClassLoaderRule;

import com.android.internal.app.SuspendedAppActivity;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ActivityStartInterceptorTest}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.ActivityStartInterceptorTest
 */
@SmallTest
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
    private ActivityManagerService mService;
    @Mock
    private ActivityStackSupervisor mSupervisor;
    @Mock
    private DevicePolicyManagerInternal mDevicePolicyManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private UserManager mUserManager;
    @Mock
    private UserController mUserController;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private PackageManagerService mPackageManager;

    private ActivityStartInterceptor mInterceptor;
    private ActivityInfo mAInfo = new ActivityInfo();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInterceptor = new ActivityStartInterceptor(mService, mSupervisor, mContext,
                mUserController);
        mInterceptor.setStates(TEST_USER_ID, TEST_REAL_CALLING_PID, TEST_REAL_CALLING_UID,
                TEST_START_FLAGS, TEST_CALLING_PACKAGE);

        // Mock DevicePolicyManagerInternal
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class,
                mDevicePolicyManager);
        when(mDevicePolicyManager
                        .createShowAdminSupportIntent(TEST_USER_ID, true))
                .thenReturn(ADMIN_SUPPORT_INTENT);
        when(mService.getPackageManagerInternalLocked()).thenReturn(mPackageManagerInternal);

        // Mock UserManager
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mUserManager.getProfileParent(TEST_USER_ID)).thenReturn(PARENT_USER_INFO);

        // Mock KeyguardManager
        when(mContext.getSystemService(Context.KEYGUARD_SERVICE)).thenReturn(mKeyguardManager);
        when(mKeyguardManager.createConfirmDeviceCredentialIntent(
                nullable(CharSequence.class), nullable(CharSequence.class), eq(TEST_USER_ID))).
                thenReturn(CONFIRM_CREDENTIALS_INTENT);

        // Mock PackageManager
        when(mService.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getHarmfulAppWarning(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(null);

        // Initialise activity info
        mAInfo.applicationInfo = new ApplicationInfo();
        mAInfo.packageName = mAInfo.applicationInfo.packageName = TEST_PACKAGE_NAME;
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
        mAInfo.applicationInfo.flags = FLAG_SUSPENDED;
        final String suspendingPackage = "com.test.suspending.package";
        final String dialogMessage = "Test Message";
        when(mPackageManagerInternal.getSuspendingPackage(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(suspendingPackage);
        when(mPackageManagerInternal.getSuspendedDialogMessage(TEST_PACKAGE_NAME, TEST_USER_ID))
                .thenReturn(dialogMessage);
        // THEN calling intercept returns true
        assertTrue(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));

        // Check intent parameters
        assertEquals(dialogMessage,
                mInterceptor.mIntent.getStringExtra(SuspendedAppActivity.EXTRA_DIALOG_MESSAGE));
        assertEquals(suspendingPackage,
                mInterceptor.mIntent.getStringExtra(SuspendedAppActivity.EXTRA_SUSPENDING_PACKAGE));
        assertEquals(TEST_PACKAGE_NAME,
                mInterceptor.mIntent.getStringExtra(SuspendedAppActivity.EXTRA_SUSPENDED_PACKAGE));
        assertEquals(TEST_USER_ID, mInterceptor.mIntent.getIntExtra(Intent.EXTRA_USER_ID, -1000));
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
    public void testWorkChallenge() {
        // GIVEN that the user the activity is starting as is currently locked
        when(mUserController.shouldConfirmCredentials(TEST_USER_ID)).thenReturn(true);

        // THEN calling intercept returns true
        mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null);

        // THEN the returned intent is the quiet mode intent
        assertTrue(CONFIRM_CREDENTIALS_INTENT.filterEquals(mInterceptor.mIntent));
    }

    @Test
    public void testNoInterception() {
        // GIVEN that none of the interception conditions are met

        // THEN calling intercept returns false
        assertFalse(mInterceptor.intercept(null, null, mAInfo, null, null, 0, 0, null));
    }
}
