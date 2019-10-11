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
 * limitations under the License.
 */

package com.android.settingslib;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TetherUtilTest {

    private Context mContext;

    @Mock
    private ConnectivityManager mConnectivityManager;
    @Mock
    private UserManager mUserManager;

    @Before
    public void setUp() {
        mContext = spy(RuntimeEnvironment.application);

        MockitoAnnotations.initMocks(this);
        doReturn(mConnectivityManager)
                .when(mContext).getSystemService(Context.CONNECTIVITY_SERVICE);
        doReturn(mUserManager)
                .when(mContext).getSystemService(Context.USER_SERVICE);
    }

    @Test
    public void isEntitlementCheckRequired_noConfigManager_returnTrue() {
        doReturn(null).when(mContext).getSystemService(Context.CARRIER_CONFIG_SERVICE);

        assertThat(TetherUtil.isEntitlementCheckRequired(mContext)).isTrue();
    }

    @Test
    public void isTetherAvailable_supported_configDisallowed_hasUserRestriction_returnTrue() {
        setupIsTetherAvailable(true, true, true);

        assertThat(TetherUtil.isTetherAvailable(mContext)).isFalse();
    }

    @Test
    public void isTetherAvailable_notSupported_configDisallowed_hasUserRestriction_returnTrue() {
        setupIsTetherAvailable(false, true, true);

        assertThat(TetherUtil.isTetherAvailable(mContext)).isFalse();
    }

    @Test
    public void isTetherAvailable_supported_configAllowed_hasUserRestriction_returnTrue() {
        setupIsTetherAvailable(true, false, true);

        assertThat(TetherUtil.isTetherAvailable(mContext)).isFalse();
    }

    @Test
    public void isTetherAvailable_notSupported_configAllowed_hasUserRestriction_returnFalse() {
        setupIsTetherAvailable(false, false, true);

        assertThat(TetherUtil.isTetherAvailable(mContext)).isFalse();
    }

    @Test
    public void isTetherAvailable_supported_configDisallowed_noUserRestriction_returnTrue() {
        setupIsTetherAvailable(true, true, false);

        assertThat(TetherUtil.isTetherAvailable(mContext)).isTrue();
    }

    @Test
    public void isTetherAvailable_notSupported_configDisallowed_noUserRestriction_returnTrue() {
        setupIsTetherAvailable(false, true, false);

        assertThat(TetherUtil.isTetherAvailable(mContext)).isTrue();
    }

    @Test
    public void isTetherAvailable_supported_configAllowed_noUserRestriction_returnTrue() {
        setupIsTetherAvailable(true, false, false);

        assertThat(TetherUtil.isTetherAvailable(mContext)).isTrue();
    }

    @Test
    public void isTetherAvailable_notSupported_configAllowed_noUserRestriction_returnFalse() {
        setupIsTetherAvailable(false, false, false);

        assertThat(TetherUtil.isTetherAvailable(mContext)).isFalse();
    }

    private void setupIsTetherAvailable(boolean tetherSupported, boolean configAllowed,
            boolean hasBseUserRestriction) {
        when(mConnectivityManager.isTetheringSupported()).thenReturn(tetherSupported);

        // For RestrictedLockUtils.checkIfRestrictionEnforced
        final int userId = UserHandle.myUserId();
        List<UserManager.EnforcingUser> enforcingUsers = new ArrayList<>();
        if (configAllowed) {
            // Add two enforcing users so that RestrictedLockUtils.checkIfRestrictionEnforced
            // returns non-null
            enforcingUsers.add(new UserManager.EnforcingUser(userId,
                    UserManager.RESTRICTION_SOURCE_DEVICE_OWNER));
            enforcingUsers.add(new UserManager.EnforcingUser(userId,
                    UserManager.RESTRICTION_SOURCE_PROFILE_OWNER));
        }
        when(mUserManager.getUserRestrictionSources(
                UserManager.DISALLOW_CONFIG_TETHERING, UserHandle.of(userId)))
                .thenReturn(enforcingUsers);

        // For RestrictedLockUtils.hasBaseUserRestriction
        when(mUserManager.hasBaseUserRestriction(
                UserManager.DISALLOW_CONFIG_TETHERING, UserHandle.of(userId)))
                .thenReturn(hasBseUserRestriction);
    }
}
