/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.pm;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.OP_INTERACT_ACROSS_PROFILES;
import static android.content.Intent.FLAG_RECEIVER_FOREGROUND;
import static android.content.Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND;
import static android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY;
import static android.content.pm.CrossProfileApps.ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.AppOpsManager.Mode;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.util.FunctionalUtils.ThrowingSupplier;
import com.android.server.LocalServices;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.testing.shadows.ShadowApplicationPackageManager;
import com.android.server.testing.shadows.ShadowUserManager;
import com.android.server.wm.ActivityTaskManagerInternal;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unit tests for {@link CrossProfileAppsServiceImpl}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
@Config(shadows = {ShadowUserManager.class, ShadowApplicationPackageManager.class})
public class CrossProfileAppsServiceImplRoboTest {
    private static final int CALLING_UID = 1111;
    private static final int CALLING_PID = 1000;
    private static final String CROSS_PROFILE_APP_PACKAGE_NAME =
            "com.android.server.pm.crossprofileappsserviceimplrobotest.crossprofileapp";
    private static final int PERSONAL_PROFILE_USER_ID = 0;
    private static final int PERSONAL_PROFILE_UID = 2222;
    private static final int WORK_PROFILE_USER_ID = 10;
    private static final int WORK_PROFILE_UID = 3333;
    private static final int OTHER_PROFILE_WITHOUT_CROSS_PROFILE_APP_USER_ID = 20;

    private final ContextWrapper mContext = ApplicationProvider.getApplicationContext();
    private final UserManager mUserManager = mContext.getSystemService(UserManager.class);
    private final AppOpsManager mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
    private final PackageManager mPackageManager = mContext.getPackageManager();
    private final TestInjector mInjector = new TestInjector();
    private final CrossProfileAppsServiceImpl mCrossProfileAppsServiceImpl =
            new CrossProfileAppsServiceImpl(mContext, mInjector);
    private final Map<UserHandle, Set<Intent>> mSentUserBroadcasts = new HashMap<>();
    private final Map<Integer, List<ApplicationInfo>> installedApplications = new HashMap<>();

    @Mock private PackageManagerInternal mPackageManagerInternal;
    @Mock private IPackageManager mIPackageManager;
    @Mock private DevicePolicyManagerInternal mDevicePolicyManagerInternal;

    @Before
    public void initializeMocks() throws Exception {
        MockitoAnnotations.initMocks(this);
        initializeInstalledApplicationsMock();
        mockCrossProfileAppInstalledAndEnabledOnEachProfile();
        mockCrossProfileAppRequestsInteractAcrossProfiles();
        mockCrossProfileAppRegistersBroadcastReceiver();
        mockCrossProfileAppWhitelisted();
    }

    private void initializeInstalledApplicationsMock() {
        when(mPackageManagerInternal.getInstalledApplications(anyInt(), anyInt(), eq(CALLING_UID)))
                .thenAnswer(invocation -> installedApplications.get(invocation.getArgument(1)));
    }

    private void mockCrossProfileAppInstalledAndEnabledOnEachProfile() {
        // They are enabled by default, so we simply have to ensure that a package info with an
        // application info is returned.
        final PackageInfo packageInfo = buildTestPackageInfo();
        mockCrossProfileAppInstalledOnProfile(
                packageInfo, PERSONAL_PROFILE_USER_ID, PERSONAL_PROFILE_UID);
        mockCrossProfileAppInstalledOnProfile(packageInfo, WORK_PROFILE_USER_ID, WORK_PROFILE_UID);
    }

    private void mockCrossProfileAppInstalledOnProfile(
            PackageInfo packageInfo, @UserIdInt int userId, int uid) {
        when(mPackageManagerInternal.getPackageInfo(
                        eq(CROSS_PROFILE_APP_PACKAGE_NAME),
                        /* flags= */ anyInt(),
                        /* filterCallingUid= */ anyInt(),
                        eq(userId)))
                .thenReturn(packageInfo);
        when(mPackageManagerInternal.getPackage(uid))
                .thenReturn(((ParsedPackage) PackageImpl.forTesting(CROSS_PROFILE_APP_PACKAGE_NAME)
                        .hideAsParsed()).hideAsFinal());
        installedApplications.putIfAbsent(userId, new ArrayList<>());
        installedApplications.get(userId).add(packageInfo.applicationInfo);
    }

    private PackageInfo buildTestPackageInfo() {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = CROSS_PROFILE_APP_PACKAGE_NAME;
        return packageInfo;
    }

    private void mockCrossProfileAppRequestsInteractAcrossProfiles() throws Exception {
        final String permissionName = Manifest.permission.INTERACT_ACROSS_PROFILES;
        when(mIPackageManager.getAppOpPermissionPackages(permissionName))
                .thenReturn(new String[] {CROSS_PROFILE_APP_PACKAGE_NAME});
    }

    private void mockCrossProfileAppRegistersBroadcastReceiver() {
        final ShadowApplicationPackageManager shadowApplicationPackageManager =
                Shadow.extract(mPackageManager);
        final Intent baseIntent =
                new Intent(ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED)
                        .setPackage(CROSS_PROFILE_APP_PACKAGE_NAME);
        final Intent manifestIntent =
                new Intent(baseIntent)
                        .setFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                                | Intent.FLAG_RECEIVER_FOREGROUND);
        final Intent registeredIntent =
                new Intent(baseIntent).setFlags(FLAG_RECEIVER_REGISTERED_ONLY);
        final List<ResolveInfo> resolveInfos = Lists.newArrayList(buildTestResolveInfo());
        shadowApplicationPackageManager.setResolveInfosForIntent(manifestIntent, resolveInfos);
        shadowApplicationPackageManager.setResolveInfosForIntent(registeredIntent, resolveInfos);
    }

    private ResolveInfo buildTestResolveInfo() {
        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = CROSS_PROFILE_APP_PACKAGE_NAME;
        resolveInfo.activityInfo.name = CROSS_PROFILE_APP_PACKAGE_NAME + ".Receiver";
        return resolveInfo;
    }

    private void mockCrossProfileAppWhitelisted() {
        when(mDevicePolicyManagerInternal.getAllCrossProfilePackages())
                .thenReturn(Lists.newArrayList(CROSS_PROFILE_APP_PACKAGE_NAME));
    }

    @Before
    public void setUpCrossProfileAppUidsAndPackageNames() {
        ShadowApplicationPackageManager.setPackageUidAsUser(
                CROSS_PROFILE_APP_PACKAGE_NAME, PERSONAL_PROFILE_UID, PERSONAL_PROFILE_USER_ID);
        ShadowApplicationPackageManager.setPackageUidAsUser(
                CROSS_PROFILE_APP_PACKAGE_NAME, WORK_PROFILE_UID, WORK_PROFILE_USER_ID);
    }

    @Before
    public void grantPermissions() {
        grantPermissions(
                Manifest.permission.MANAGE_APP_OPS_MODES,
                Manifest.permission.UPDATE_APP_OPS_STATS,
                Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES,
                Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    @Before
    public void setUpProfiles() {
        final ShadowUserManager shadowUserManager = Shadow.extract(mUserManager);
        shadowUserManager.addProfileIds(
                PERSONAL_PROFILE_USER_ID,
                WORK_PROFILE_USER_ID,
                OTHER_PROFILE_WITHOUT_CROSS_PROFILE_APP_USER_ID);
    }

    @Before
    public void setInteractAcrossProfilesAppOpDefault() {
        // It seems to be necessary to provide the shadow with the default already specified in
        // AppOpsManager.
        final int defaultMode = AppOpsManager.opToDefaultMode(OP_INTERACT_ACROSS_PROFILES);
        explicitlySetInteractAcrossProfilesAppOp(PERSONAL_PROFILE_UID, defaultMode);
        explicitlySetInteractAcrossProfilesAppOp(WORK_PROFILE_UID, defaultMode);
    }

    @Test
    public void setInteractAcrossProfilesAppOp_noPermissions_throwsSecurityException() {
        denyPermissions(
                Manifest.permission.MANAGE_APP_OPS_MODES,
                Manifest.permission.UPDATE_APP_OPS_STATS,
                Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES,
                Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        try {
            mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                    CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
            fail();
        } catch (SecurityException expected) {}
    }

    @Test
    public void setInteractAcrossProfilesAppOp_missingInteractAcrossUsersAndFull_throwsSecurityException() {
        denyPermissions(
                Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        grantPermissions(Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES);
        try {
            mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                    CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
            fail();
        } catch (SecurityException expected) {}
    }

    @Test
    public void setInteractAcrossProfilesAppOp_setsAppOp() {
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(getCrossProfileAppOp()).isEqualTo(MODE_ALLOWED);
    }

    @Test
    public void setInteractAcrossProfilesAppOp_configureInteractAcrossProfilesPermissionWithoutAppOpsPermissions_setsAppOp() {
        denyPermissions(
                Manifest.permission.MANAGE_APP_OPS_MODES,
                Manifest.permission.UPDATE_APP_OPS_STATS);
        grantPermissions(
                Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES,
                Manifest.permission.INTERACT_ACROSS_USERS);

        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);

        assertThat(getCrossProfileAppOp()).isEqualTo(MODE_ALLOWED);
    }

    @Test
    public void setInteractAcrossProfilesAppOp_appOpsPermissionsWithoutConfigureInteractAcrossProfilesPermission_setsAppOp() {
        denyPermissions(Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES);
        grantPermissions(
                Manifest.permission.MANAGE_APP_OPS_MODES,
                Manifest.permission.UPDATE_APP_OPS_STATS,
                Manifest.permission.INTERACT_ACROSS_USERS);

        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);

        assertThat(getCrossProfileAppOp()).isEqualTo(MODE_ALLOWED);
    }

    @Test
    public void setInteractAcrossProfilesAppOp_setsAppOpWithUsersAndWithoutFull() {
        denyPermissions(Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        grantPermissions(Manifest.permission.INTERACT_ACROSS_USERS);
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(getCrossProfileAppOp()).isEqualTo(MODE_ALLOWED);
    }

    @Test
    public void setInteractAcrossProfilesAppOp_setsAppOpWithFullAndWithoutUsers() {
        denyPermissions(Manifest.permission.INTERACT_ACROSS_USERS);
        grantPermissions(Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(getCrossProfileAppOp()).isEqualTo(MODE_ALLOWED);
    }

    @Test
    public void setInteractAcrossProfilesAppOp_setsAppOpOnOtherProfile() {
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(getCrossProfileAppOp(WORK_PROFILE_UID)).isEqualTo(MODE_ALLOWED);
    }

    @Test
    public void setInteractAcrossProfilesAppOp_sendsBroadcast() {
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(receivedCanInteractAcrossProfilesChangedBroadcast()).isTrue();
    }

    @Test
    public void setInteractAcrossProfilesAppOp_sendsBroadcastToOtherProfile() {
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(receivedCanInteractAcrossProfilesChangedBroadcast(WORK_PROFILE_USER_ID))
                .isTrue();
    }

    @Test
    public void setInteractAcrossProfilesAppOp_doesNotSendBroadcastToProfileWithoutPackage() {
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(receivedCanInteractAcrossProfilesChangedBroadcast(
                        OTHER_PROFILE_WITHOUT_CROSS_PROFILE_APP_USER_ID))
                .isFalse();
    }

    @Test
    public void setInteractAcrossProfilesAppOp_toSameAsCurrent_doesNotSendBroadcast() {
        explicitlySetInteractAcrossProfilesAppOp(MODE_ALLOWED);
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(receivedCanInteractAcrossProfilesChangedBroadcast()).isFalse();
    }

    @Test
    public void setInteractAcrossProfilesAppOp_toAllowed_whenNotAbleToRequest_doesNotSet() {
        mockCrossProfileAppNotWhitelisted();
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(getCrossProfileAppOp()).isNotEqualTo(MODE_ALLOWED);
    }

    @Test
    public void setInteractAcrossProfilesAppOp_toAllowed_whenNotAbleToRequest_doesNotSendBroadcast() {
        mockCrossProfileAppNotWhitelisted();
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(receivedCanInteractAcrossProfilesChangedBroadcast()).isFalse();
    }

    @Test
    public void setInteractAcrossProfilesAppOp_withoutCrossProfileAttribute_manifestReceiversDoNotGetBroadcast() {
        declareCrossProfileAttributeOnCrossProfileApp(false);
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(receivedManifestCanInteractAcrossProfilesChangedBroadcast()).isFalse();
    }

    @Test
    public void setInteractAcrossProfilesAppOp_withCrossProfileAttribute_manifestReceiversGetBroadcast() {
        declareCrossProfileAttributeOnCrossProfileApp(true);
        mCrossProfileAppsServiceImpl.setInteractAcrossProfilesAppOp(
                CROSS_PROFILE_APP_PACKAGE_NAME, MODE_ALLOWED);
        assertThat(receivedManifestCanInteractAcrossProfilesChangedBroadcast()).isTrue();
    }

    @Test
    public void canConfigureInteractAcrossProfiles_packageNotInstalledInProfile_returnsFalse() {
        mockUninstallCrossProfileAppFromWorkProfile();
        assertThat(mCrossProfileAppsServiceImpl
                .canConfigureInteractAcrossProfiles(CROSS_PROFILE_APP_PACKAGE_NAME))
                .isFalse();
    }

    private void mockUninstallCrossProfileAppFromWorkProfile() {
        when(mPackageManagerInternal.getPackageInfo(
                        eq(CROSS_PROFILE_APP_PACKAGE_NAME),
                        /* flags= */ anyInt(),
                        /* filterCallingUid= */ anyInt(),
                        eq(WORK_PROFILE_USER_ID)))
                .thenReturn(null);
        when(mPackageManagerInternal.getPackage(WORK_PROFILE_UID)).thenReturn(null);
    }

    @Test
    public void canConfigureInteractAcrossProfiles_packageDoesNotRequestInteractAcrossProfiles_returnsFalse()
            throws Exception {
        mockCrossProfileAppDoesNotRequestInteractAcrossProfiles();
        assertThat(mCrossProfileAppsServiceImpl
                .canConfigureInteractAcrossProfiles(CROSS_PROFILE_APP_PACKAGE_NAME))
                .isFalse();
    }

    private void mockCrossProfileAppDoesNotRequestInteractAcrossProfiles() throws Exception {
        final String permissionName = Manifest.permission.INTERACT_ACROSS_PROFILES;
        when(mIPackageManager.getAppOpPermissionPackages(permissionName))
                .thenReturn(new String[] {});
    }

    @Test
    public void canConfigureInteractAcrossProfiles_packageNotWhitelisted_returnsFalse() {
        mockCrossProfileAppNotWhitelisted();
        assertThat(mCrossProfileAppsServiceImpl
                .canConfigureInteractAcrossProfiles(CROSS_PROFILE_APP_PACKAGE_NAME))
                .isFalse();
    }

    @Test
    public void canConfigureInteractAcrossProfiles_returnsTrue() {
        assertThat(mCrossProfileAppsServiceImpl
                .canConfigureInteractAcrossProfiles(CROSS_PROFILE_APP_PACKAGE_NAME))
                .isTrue();
    }

    @Test
    public void canUserAttemptToConfigureInteractAcrossProfiles_packageNotInstalledInProfile_returnsTrue() {
        mockUninstallCrossProfileAppFromWorkProfile();
        assertThat(mCrossProfileAppsServiceImpl
                .canUserAttemptToConfigureInteractAcrossProfiles(CROSS_PROFILE_APP_PACKAGE_NAME))
                .isTrue();
    }

    @Test
    public void canUserAttemptToConfigureInteractAcrossProfiles_packageDoesNotRequestInteractAcrossProfiles_returnsFalse()
            throws Exception {
        mockCrossProfileAppDoesNotRequestInteractAcrossProfiles();
        assertThat(mCrossProfileAppsServiceImpl
                .canUserAttemptToConfigureInteractAcrossProfiles(CROSS_PROFILE_APP_PACKAGE_NAME))
                .isFalse();
    }

    @Test
    public void canUserAttemptToConfigureInteractAcrossProfiles_packageNotWhitelisted_returnsTrue() {
        mockCrossProfileAppNotWhitelisted();
        assertThat(mCrossProfileAppsServiceImpl
                .canUserAttemptToConfigureInteractAcrossProfiles(CROSS_PROFILE_APP_PACKAGE_NAME))
                .isTrue();
    }

    @Test
    public void canUserAttemptToConfigureInteractAcrossProfiles_returnsTrue() {
        assertThat(mCrossProfileAppsServiceImpl
                .canUserAttemptToConfigureInteractAcrossProfiles(CROSS_PROFILE_APP_PACKAGE_NAME))
                .isTrue();
    }

    @Test
    public void clearInteractAcrossProfilesAppOps() {
        explicitlySetInteractAcrossProfilesAppOp(MODE_ALLOWED);
        mCrossProfileAppsServiceImpl.clearInteractAcrossProfilesAppOps();
        assertThat(getCrossProfileAppOp()).isEqualTo(MODE_DEFAULT);
    }

    private void explicitlySetInteractAcrossProfilesAppOp(@Mode int mode) {
        explicitlySetInteractAcrossProfilesAppOp(PERSONAL_PROFILE_UID, mode);
    }

    private void explicitlySetInteractAcrossProfilesAppOp(int uid, @Mode int mode) {
        shadowOf(mAppOpsManager).setMode(
                OP_INTERACT_ACROSS_PROFILES, uid, CROSS_PROFILE_APP_PACKAGE_NAME, mode);
    }

    private void grantPermissions(String... permissions) {
        shadowOf(mContext).grantPermissions(Process.myPid(), CALLING_UID, permissions);
    }

    private void denyPermissions(String... permissions) {
        shadowOf(mContext).denyPermissions(Process.myPid(), CALLING_UID, permissions);
    }

    private @Mode int getCrossProfileAppOp() {
        return getCrossProfileAppOp(PERSONAL_PROFILE_UID);
    }

    private @Mode int getCrossProfileAppOp(int uid) {
        return mAppOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.permissionToOp(Manifest.permission.INTERACT_ACROSS_PROFILES),
                uid,
                CROSS_PROFILE_APP_PACKAGE_NAME);
    }

    private boolean receivedCanInteractAcrossProfilesChangedBroadcast() {
        return receivedCanInteractAcrossProfilesChangedBroadcast(PERSONAL_PROFILE_USER_ID);
    }

    private boolean receivedCanInteractAcrossProfilesChangedBroadcast(@UserIdInt int userId) {
        final UserHandle userHandle = UserHandle.of(userId);
        if (!mSentUserBroadcasts.containsKey(userHandle)) {
            return false;
        }
        return mSentUserBroadcasts.get(userHandle)
                .stream()
                .anyMatch(this::isBroadcastCanInteractAcrossProfilesChanged);
    }

    private boolean isBroadcastCanInteractAcrossProfilesChanged(Intent intent) {
        return intent.getAction().equals(ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED)
                && CROSS_PROFILE_APP_PACKAGE_NAME.equals(intent.getPackage());
    }

    private void mockCrossProfileAndroidPackage(AndroidPackage androidPackage) {
        when(mPackageManagerInternal.getPackage(CROSS_PROFILE_APP_PACKAGE_NAME))
                .thenReturn(androidPackage);
        when(mPackageManagerInternal.getPackage(PERSONAL_PROFILE_UID)).thenReturn(androidPackage);
        when(mPackageManagerInternal.getPackage(WORK_PROFILE_UID)).thenReturn(androidPackage);
    }

    private void mockCrossProfileAppNotWhitelisted() {
        when(mDevicePolicyManagerInternal.getAllCrossProfilePackages())
                .thenReturn(new ArrayList<>());
    }

    private boolean receivedManifestCanInteractAcrossProfilesChangedBroadcast() {
        final UserHandle userHandle = UserHandle.of(PERSONAL_PROFILE_USER_ID);
        if (!mSentUserBroadcasts.containsKey(userHandle)) {
            return false;
        }
        return mSentUserBroadcasts.get(userHandle)
                .stream()
                .anyMatch(this::isBroadcastManifestCanInteractAcrossProfilesChanged);
    }

    private boolean isBroadcastManifestCanInteractAcrossProfilesChanged(Intent intent) {
        return isBroadcastCanInteractAcrossProfilesChanged(intent)
                && (intent.getFlags() & FLAG_RECEIVER_REGISTERED_ONLY) == 0
                && (intent.getFlags() & FLAG_RECEIVER_INCLUDE_BACKGROUND) != 0
                && (intent.getFlags() & FLAG_RECEIVER_FOREGROUND) != 0
                && intent.getComponent() != null
                && intent.getComponent().getPackageName().equals(CROSS_PROFILE_APP_PACKAGE_NAME);
    }

    private void declareCrossProfileAttributeOnCrossProfileApp(boolean value) {
        mockCrossProfileAndroidPackage(
                ((ParsedPackage) PackageImpl.forTesting(CROSS_PROFILE_APP_PACKAGE_NAME)
                        .setCrossProfile(value)
                        .hideAsParsed()).hideAsFinal());
    }

    private class TestInjector implements CrossProfileAppsServiceImpl.Injector {

        @Override
        public int getCallingUid() {
            return CALLING_UID;
        }

        @Override
        public int getCallingPid() {
            return CALLING_PID;
        }

        @Override
        public @UserIdInt int getCallingUserId() {
            return PERSONAL_PROFILE_USER_ID;
        }

        @Override
        public UserHandle getCallingUserHandle() {
            return UserHandle.of(getCallingUserId());
        }

        @Override
        public long clearCallingIdentity() {
            return 0;
        }

        @Override
        public void restoreCallingIdentity(long token) {}

        @Override
        public void withCleanCallingIdentity(ThrowingRunnable action) {
            action.run();
        }

        @Override
        public <T> T withCleanCallingIdentity(ThrowingSupplier<T> action) {
            return action.get();
        }

        @Override
        public UserManager getUserManager() {
            return mUserManager;
        }

        @Override
        public PackageManagerInternal getPackageManagerInternal() {
            return mPackageManagerInternal;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public AppOpsManager getAppOpsManager() {
            return mAppOpsManager;
        }

        @Override
        public ActivityManagerInternal getActivityManagerInternal() {
            return LocalServices.getService(ActivityManagerInternal.class);
        }

        @Override
        public ActivityTaskManagerInternal getActivityTaskManagerInternal() {
            return LocalServices.getService(ActivityTaskManagerInternal.class);
        }

        @Override
        public IPackageManager getIPackageManager() {
            return mIPackageManager;
        }

        @Override
        public DevicePolicyManagerInternal getDevicePolicyManagerInternal() {
            return mDevicePolicyManagerInternal;
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            // Robolectric's shadows do not currently support sendBroadcastAsUser.
            final Set<Intent> broadcasts =
                    mSentUserBroadcasts.containsKey(user)
                            ? mSentUserBroadcasts.get(user)
                            : new HashSet<>();
            broadcasts.add(intent);
            mSentUserBroadcasts.put(user, broadcasts);
            mContext.sendBroadcastAsUser(intent, user);
        }

        @Override
        public int checkComponentPermission(
                String permission, int uid, int owningUid, boolean exported) {
            // ActivityManager#checkComponentPermission calls through to
            // AppGlobals.getPackageManager()#checkUidPermission, which calls through to
            // ShadowActivityThread with Robolectric. This method is currently not supported there.
            return mContext.checkPermission(permission, Process.myPid(), uid);
        }
    }
}
