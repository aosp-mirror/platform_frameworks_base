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
 * limitations under the License.
 */

package com.android.settingslib.users;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;

import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.settingslib.testutils.shadow.ShadowActivityManager;
import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.util.ArrayList;
import java.util.List;

@RunWith(SettingsLibRobolectricTestRunner.class)
@Config(shadows = { ShadowActivityManager.class, UserManagerHelperRoboTest.ShadowUserHandle.class})
public class UserManagerHelperRoboTest {
    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;

    private UserManagerHelper mHelper;

    @Before
    public void setUpMocksAndUserManagerHelper() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(
                RuntimeEnvironment.application.getSystemService(ActivityManager.class));
        mHelper = new UserManagerHelper(mContext);
    }

    @After
    public void tearDown() {
        ShadowActivityManager.getShadow().reset();
    }

    @Test
    public void getForegroundUserId() {
        ShadowActivityManager.setCurrentUser(15);
        assertThat(mHelper.getForegroundUserId()).isEqualTo(15);
    }

    @Test
    public void getForegroundUserInfo() {
        ShadowActivityManager.setCurrentUser(17);
        when(mUserManager.getUserInfo(ShadowActivityManager.getCurrentUser()))
                .thenReturn(createUserInfoForId(ShadowActivityManager.getCurrentUser()));
        assertThat(mHelper.getForegroundUserInfo().id).isEqualTo(17);
    }

    @Test
    public void getCurrentProcessUserId() {
        ShadowUserHandle.setUid(11);
        assertThat(mHelper.getCurrentProcessUserId()).isEqualTo(11);
    }

    @Test
    public void getCurrentProcessUserInfo() {
        ShadowUserHandle.setUid(12);
        when(mUserManager.getUserInfo(UserHandle.myUserId()))
                .thenReturn(createUserInfoForId(UserHandle.myUserId()));
        assertThat(mHelper.getCurrentProcessUserInfo().id).isEqualTo(12);
    }

    @Test
    public void getAllUsersExcludesCurrentProcessUser() {
        ShadowUserHandle.setUid(12);
        UserInfo currentProcessUser = createUserInfoForId(12);

        UserInfo otherUser1 = createUserInfoForId(13);
        UserInfo otherUser2 = createUserInfoForId(11);
        UserInfo otherUser3 = createUserInfoForId(14);

        List<UserInfo> testUsers = new ArrayList<>();
        testUsers.add(otherUser1);
        testUsers.add(otherUser2);
        testUsers.add(currentProcessUser);
        testUsers.add(otherUser3);

        when(mUserManager.getUsers(true)).thenReturn(testUsers);

        // Should return 3 users that don't have currentProcessUser id.
        assertThat(mHelper.getAllUsersExcludesCurrentProcessUser()).hasSize(3);
        assertThat(mHelper.getAllUsersExcludesCurrentProcessUser())
                .containsExactly(otherUser1, otherUser2, otherUser3);
    }

    @Test
    public void getAllUsersExcludesForegroundUser() {
        ShadowActivityManager.setCurrentUser(17);
        UserInfo foregroundUser = createUserInfoForId(17);

        UserInfo otherUser1 = createUserInfoForId(11);
        UserInfo otherUser2 = createUserInfoForId(18);
        UserInfo otherUser3 = createUserInfoForId(16);

        List<UserInfo> testUsers = new ArrayList<>();
        testUsers.add(otherUser1);
        testUsers.add(otherUser2);
        testUsers.add(foregroundUser);
        testUsers.add(otherUser3);

        when(mUserManager.getUsers(true)).thenReturn(testUsers);

        // Should return 3 users that don't have foregroundUser id.
        assertThat(mHelper.getAllUsersExcludesForegroundUser()).hasSize(3);
        assertThat(mHelper.getAllUsersExcludesForegroundUser())
                .containsExactly(otherUser1, otherUser2, otherUser3);
    }

    @Test
    public void userIsForegroundUser() {
        ShadowActivityManager.setCurrentUser(10);
        assertThat(mHelper.userIsForegroundUser(createUserInfoForId(10))).isTrue();
        assertThat(mHelper.userIsForegroundUser(createUserInfoForId(11))).isFalse();

        ShadowActivityManager.setCurrentUser(11);
        assertThat(mHelper.userIsForegroundUser(createUserInfoForId(11))).isTrue();
    }

    @Test
    public void userIsRunningCurrentProcess() {
        ShadowUserHandle.setUid(10);
        assertThat(mHelper.userIsRunningCurrentProcess(createUserInfoForId(10))).isTrue();
        assertThat(mHelper.userIsRunningCurrentProcess(createUserInfoForId(11))).isFalse();

        ShadowUserHandle.setUid(11);
        assertThat(mHelper.userIsRunningCurrentProcess(createUserInfoForId(11))).isTrue();
    }

    @Test
    public void removingCurrentProcessUserSwitchesToSystemUser() {
        // Set currentProcess user to be user 10.
        ShadowUserHandle.setUid(10);

        // Removing a currentProcess user, calls "switch" to system user
        mHelper.removeUser(createUserInfoForId(10));
        assertThat(ShadowActivityManager.getShadow().getSwitchUserCalled()).isTrue();
        assertThat(ShadowActivityManager.getShadow().getUserSwitchedTo()).isEqualTo(0);

        verify(mUserManager).removeUser(10);
    }

    @Test
    public void switchToUser() {
        ShadowActivityManager.setCurrentUser(20);

        // Switching to foreground user doesn't do anything.
        mHelper.switchToUser(createUserInfoForId(20));
        assertThat(ShadowActivityManager.getShadow().getSwitchUserCalled()).isFalse();

        // Switching to Guest calls createGuest.
        UserInfo guestInfo = new UserInfo(21, "Test Guest", UserInfo.FLAG_GUEST);
        mHelper.switchToUser(guestInfo);
        verify(mUserManager).createGuest(mContext, "Test Guest");

        // Switching to non-current, non-guest user, simply calls switchUser.
        UserInfo userToSwitchTo = new UserInfo(22, "Test User", 0);
        mHelper.switchToUser(userToSwitchTo);
        assertThat(ShadowActivityManager.getShadow().getSwitchUserCalled()).isTrue();
        assertThat(ShadowActivityManager.getShadow().getUserSwitchedTo()).isEqualTo(22);
    }

    private UserInfo createUserInfoForId(int id) {
        UserInfo userInfo = new UserInfo();
        userInfo.id = id;
        return userInfo;
    }

    @Implements(UserHandle.class)
    public static class ShadowUserHandle {
        private static int sUid = 0; // SYSTEM by default

        public static void setUid(int uid) {
            sUid = uid;
        }

        @Implementation
        public static int myUserId() {
            return sUid;
        }

        @Resetter
        public static void reset() {
            sUid = 0;
        }
    }
}
