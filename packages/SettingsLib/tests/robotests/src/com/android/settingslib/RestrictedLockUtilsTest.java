/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib;

import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_REMOTE_INPUT;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;
import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class RestrictedLockUtilsTest {

    @Mock
    private Context mContext;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RestrictedLockUtils.Proxy mProxy;

    private final int mUserId = 194;
    private final int mProfileId = 160;
    private final ComponentName mAdmin1 = new ComponentName("admin1", "admin1class");
    private final ComponentName mAdmin2 = new ComponentName("admin2", "admin2class");

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE))
                .thenReturn(mDevicePolicyManager);
        when(mContext.getSystemService(Context.USER_SERVICE))
                .thenReturn(mUserManager);
        when(mContext.getPackageManager())
                .thenReturn(mPackageManager);

        RestrictedLockUtils.sProxy = mProxy;
    }

    @Test
    public void checkIfRestrictionEnforced_deviceOwner() {
        UserManager.EnforcingUser enforcingUser = new UserManager.EnforcingUser(mUserId,
                UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);
        final String userRestriction = UserManager.DISALLOW_UNINSTALL_APPS;
        when(mUserManager.getUserRestrictionSources(userRestriction,
                UserHandle.of(mUserId))).
                thenReturn(Collections.singletonList(enforcingUser));
        setUpDeviceOwner(mAdmin1);

        EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfRestrictionEnforced(mContext,
                userRestriction, mUserId);

        assertThat(enforcedAdmin).isNotNull();
        assertThat(enforcedAdmin.enforcedRestriction).isEqualTo(userRestriction);
        assertThat(enforcedAdmin.component).isEqualTo(mAdmin1);
    }

    @Test
    public void checkIfRestrictionEnforced_profileOwner() {
        UserManager.EnforcingUser enforcingUser = new UserManager.EnforcingUser(mUserId,
                UserManager.RESTRICTION_SOURCE_PROFILE_OWNER);
        final String userRestriction = UserManager.DISALLOW_UNINSTALL_APPS;
        when(mUserManager.getUserRestrictionSources(userRestriction,
                UserHandle.of(mUserId))).
                thenReturn(Collections.singletonList(enforcingUser));
        setUpProfileOwner(mAdmin1, mUserId);

        EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfRestrictionEnforced(mContext,
                userRestriction, mUserId);

        assertThat(enforcedAdmin).isNotNull();
        assertThat(enforcedAdmin.enforcedRestriction).isEqualTo(userRestriction);
        assertThat(enforcedAdmin.component).isEqualTo(mAdmin1);
    }

    @Test
    public void checkIfDevicePolicyServiceDisabled_noEnforceAdminForManagedProfile() {
        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(null);
        final EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfAccountManagementDisabled(
                mContext, "account_type", mUserId);

        assertThat(enforcedAdmin).isEqualTo(null);
    }

    @Test
    public void checkIfDeviceAdminFeatureDisabled_noEnforceAdminForManagedProfile() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN))
                .thenReturn(false);
        final EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfAccountManagementDisabled(
                mContext, "account_type", mUserId);

        assertThat(enforcedAdmin).isEqualTo(null);
    }

    @Test
    public void checkIfKeyguardFeaturesDisabled_noEnforcedAdminForManagedProfile() {
        setUpManagedProfile(mUserId, new ComponentName[] {mAdmin1, mAdmin2});

        final EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mUserId);

        assertThat(enforcedAdmin).isEqualTo(null);
    }

    @Test
    public void checkIfKeyguardFeaturesDisabled_oneEnforcedAdminForManagedProfile() {
        setUpManagedProfile(mUserId, new ComponentName[] {mAdmin1, mAdmin2});

        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin1, mUserId))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT);

        final EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mUserId);

        assertThat(enforcedAdmin).isEqualTo(new EnforcedAdmin(mAdmin1, mUserId));
    }

    @Test
    public void checkIfKeyguardFeaturesDisabled_multipleEnforcedAdminForManagedProfile() {
        setUpManagedProfile(mUserId, new ComponentName[] {mAdmin1, mAdmin2});

        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin1, mUserId))
                .thenReturn(KEYGUARD_DISABLE_REMOTE_INPUT);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin2, mUserId))
                .thenReturn(KEYGUARD_DISABLE_REMOTE_INPUT);

        final EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_REMOTE_INPUT, mUserId);

        assertThat(enforcedAdmin).isEqualTo(EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN);
    }

    @Test
    public void checkIfKeyguardFeaturesAreDisabled_doesMatchAllowedFeature_unifiedManagedProfile() {
        UserInfo userInfo = setUpUser(mUserId, new ComponentName[] {mAdmin1});
        UserInfo profileInfo = setUpManagedProfile(mProfileId, new ComponentName[] {mAdmin2});
        when(mUserManager.getProfiles(mUserId)).thenReturn(Arrays.asList(new UserInfo[] {
                userInfo, profileInfo}));

        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin1, mUserId))
                .thenReturn(KEYGUARD_DISABLE_FEATURES_NONE);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin2, mProfileId))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT);

        // Querying the parent should return the policy, since it affects the parent.
        EnforcedAdmin parent = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mUserId);
        assertThat(parent).isEqualTo(new EnforcedAdmin(mAdmin2, mProfileId));

        // Querying the child should return that too.
        EnforcedAdmin profile = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mProfileId);
        assertThat(profile).isEqualTo(new EnforcedAdmin(mAdmin2, mProfileId));

        // Querying for some unrelated feature should return nothing. Nothing!
        assertThat(RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_REMOTE_INPUT, mUserId)).isNull();
        assertThat(RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_REMOTE_INPUT, mProfileId)).isNull();
    }

    @Test
    public void checkIfKeyguardFeaturesAreDisabled_notMatchOtherFeatures_unifiedManagedProfile() {
        UserInfo userInfo = setUpUser(mUserId, new ComponentName[] {mAdmin1});
        UserInfo profileInfo = setUpManagedProfile(mProfileId, new ComponentName[] {mAdmin2});
        when(mUserManager.getProfiles(mUserId)).thenReturn(Arrays.asList(new UserInfo[] {
                userInfo, profileInfo}));

        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin1, mUserId))
                .thenReturn(KEYGUARD_DISABLE_FEATURES_NONE);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin2, mProfileId))
                .thenReturn(KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        // Querying the parent should not return the policy, because it's not a policy that should
        // affect parents even when the lock screen is unified.
        EnforcedAdmin primary = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS, mUserId);
        assertThat(primary).isNull();

        // Querying the child should still return the policy.
        EnforcedAdmin profile = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS, mProfileId);
        assertThat(profile).isEqualTo(new EnforcedAdmin(mAdmin2, mProfileId));
    }

    @Test
    public void checkIfKeyguardFeaturesAreDisabled_onlyMatchesProfile_separateManagedProfile() {
        UserInfo userInfo = setUpUser(mUserId, new ComponentName[] {mAdmin1});
        UserInfo profileInfo = setUpManagedProfile(mProfileId, new ComponentName[] {mAdmin2});
        when(mUserManager.getProfiles(mUserId)).thenReturn(Arrays.asList(new UserInfo[] {
                userInfo, profileInfo}));

        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin1, mUserId))
                .thenReturn(KEYGUARD_DISABLE_FEATURES_NONE);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin2, mProfileId))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT);

        // Crucially for this test, isSeparateWorkChallengeEnabled => true.
        doReturn(true).when(mProxy).isSeparateProfileChallengeEnabled(any(), eq(mProfileId));

        // Querying the parent should not return the policy, even though it's shared by default,
        // because the parent doesn't share a lock screen with the profile any more.
        EnforcedAdmin parent = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mUserId);
        assertThat(parent).isNull();

        // Querying the child should still return the policy.
        EnforcedAdmin profile = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mProfileId);
        assertThat(profile).isEqualTo(new EnforcedAdmin(mAdmin2, mProfileId));
    }

    /**
     * This test works great. The real world implementation is sketchy though.
     * <p>
     * DevicePolicyManager.getParentProfileInstance(UserInfo) does not do what it looks like it does
     * (which would be to get an instance for the parent of the user that's passed in to it.)
     * <p>
     * Instead it just always returns a parent instance for the current user.
     * <p>
     * Still, the test works.
     */
    @Test
    public void checkIfKeyguardFeaturesAreDisabled_onlyMatchesParent_profileParentPolicy() {
        UserInfo userInfo = setUpUser(mUserId, new ComponentName[] {mAdmin1});
        UserInfo profileInfo = setUpManagedProfile(mProfileId, new ComponentName[] {mAdmin2});
        when(mUserManager.getProfiles(mUserId)).thenReturn(Arrays.asList(new UserInfo[] {
                userInfo, profileInfo}));

        when(mProxy.getParentProfileInstance(any(DevicePolicyManager.class), any())
                .getKeyguardDisabledFeatures(mAdmin2, mProfileId))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT);

        // Parent should get the policy.
        EnforcedAdmin parent = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mUserId);
        assertThat(parent).isEqualTo(new EnforcedAdmin(mAdmin2, mProfileId));

        // Profile should not get the policy.
        EnforcedAdmin profile = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mProfileId);
        assertThat(profile).isNull();
    }

    private UserInfo setUpUser(int userId, ComponentName[] admins) {
        UserInfo userInfo = new UserInfo(userId, "primary", 0);
        when(mUserManager.getUserInfo(userId)).thenReturn(userInfo);
        setUpActiveAdmins(userId, admins);
        return userInfo;
    }

    private UserInfo setUpManagedProfile(int userId, ComponentName[] admins) {
        UserInfo userInfo = new UserInfo(userId, "profile", UserInfo.FLAG_MANAGED_PROFILE);
        when(mUserManager.getUserInfo(userId)).thenReturn(userInfo);
        setUpActiveAdmins(userId, admins);
        return userInfo;
    }

    private void setUpActiveAdmins(int userId, ComponentName[] activeAdmins) {
        when(mDevicePolicyManager.getActiveAdminsAsUser(userId))
                .thenReturn(Arrays.asList(activeAdmins));
    }

    private void setUpDeviceOwner(ComponentName admin) {
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser()).thenReturn(admin);
    }

    private void setUpProfileOwner(ComponentName admin, int userId) {
        when(mDevicePolicyManager.getProfileOwnerAsUser(userId)).thenReturn(admin);
    }
}
