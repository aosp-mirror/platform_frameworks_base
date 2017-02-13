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

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_REMOTE_INPUT;
import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

@RunWith(SettingLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class RestrictedLockUtilsTest {

    @Mock
    private Context mContext;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private UserManager mUserManager;

    private static final int mUserId = 194;
    private static final ComponentName mAdmin1 = new ComponentName("admin1", "admin1class");
    private static final ComponentName mAdmin2 = new ComponentName("admin2", "admin2class");

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE))
                .thenReturn(mDevicePolicyManager);
        when(mContext.getSystemService(Context.USER_SERVICE))
                .thenReturn(mUserManager);
    }

    @Test
    public void checkIfKeyguardFeaturesDisabled_noEnforcedAdminForManagedProfile() {
        setUpManagedProfile(mUserId);
        setUpActiveAdmins(mUserId, new ComponentName[] {mAdmin1, mAdmin2});

        final EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mUserId);

        assertThat(enforcedAdmin).isEqualTo(null);
    }

    @Test
    public void checkIfKeyguardFeaturesDisabled_oneEnforcedAdminForManagedProfile() {
        setUpManagedProfile(mUserId);
        setUpActiveAdmins(mUserId, new ComponentName[] {mAdmin1, mAdmin2});

        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin1, mUserId))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT);

        final EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_FINGERPRINT, mUserId);

        assertThat(enforcedAdmin).isEqualTo(new EnforcedAdmin(mAdmin1, mUserId));
    }

    @Test
    public void checkIfKeyguardFeaturesDisabled_multipleEnforcedAdminForManagedProfile() {
        setUpManagedProfile(mUserId);
        setUpActiveAdmins(mUserId, new ComponentName[] {mAdmin1, mAdmin2});

        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin1, mUserId))
                .thenReturn(KEYGUARD_DISABLE_REMOTE_INPUT);
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(mAdmin2, mUserId))
                .thenReturn(KEYGUARD_DISABLE_REMOTE_INPUT);

        final EnforcedAdmin enforcedAdmin = RestrictedLockUtils.checkIfKeyguardFeaturesDisabled(
                mContext, KEYGUARD_DISABLE_REMOTE_INPUT, mUserId);

        assertThat(enforcedAdmin).isEqualTo(EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN);
    }

    private UserInfo setUpManagedProfile(int userId) {
        final UserInfo userInfo = new UserInfo(userId, "myuser", UserInfo.FLAG_MANAGED_PROFILE);
        when(mUserManager.getUserInfo(userId)).thenReturn(userInfo);
        return userInfo;
    }

    private void setUpActiveAdmins(int userId, ComponentName[] activeAdmins) {
        when(mDevicePolicyManager.getActiveAdminsAsUser(userId))
                .thenReturn(Arrays.asList(activeAdmins));
    }
}
