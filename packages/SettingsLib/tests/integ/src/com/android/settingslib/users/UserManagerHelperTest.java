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

package com.android.settingslib.users;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class UserManagerHelperTest {
    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private UserManagerHelper.OnUsersUpdateListener mTestListener;

    private UserManagerHelper mHelper;
    private UserInfo mCurrentProcessUser;
    private UserInfo mSystemUser;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(mActivityManager);
        when(mContext.getResources())
                .thenReturn(InstrumentationRegistry.getTargetContext().getResources());
        mHelper = new UserManagerHelper(mContext);

        mCurrentProcessUser = createUserInfoForId(UserHandle.myUserId());
        mSystemUser = createUserInfoForId(UserHandle.USER_SYSTEM);
        when(mUserManager.getUserInfo(UserHandle.myUserId())).thenReturn(mCurrentProcessUser);
    }

    @Test
    public void userIsSystemUser() {
        UserInfo testInfo = new UserInfo();

        testInfo.id = UserHandle.USER_SYSTEM;
        assertThat(mHelper.userIsSystemUser(testInfo)).isTrue();

        testInfo.id = UserHandle.USER_SYSTEM + 2; // Make it different than system id.
        assertThat(mHelper.userIsSystemUser(testInfo)).isFalse();
    }

    @Test
    public void getAllUsersExcludesSystemUser() {
        UserInfo otherUser1 = createUserInfoForId(10);
        UserInfo otherUser2 = createUserInfoForId(11);
        UserInfo otherUser3 = createUserInfoForId(12);

        List<UserInfo> testUsers = new ArrayList<>();
        testUsers.add(otherUser1);
        testUsers.add(otherUser2);
        testUsers.add(mSystemUser);
        testUsers.add(otherUser3);

        when(mUserManager.getUsers(true)).thenReturn(testUsers);

        // Should return 3 users that don't have SYSTEM USER id.
        assertThat(mHelper.getAllUsersExcludesSystemUser()).hasSize(3);
        assertThat(mHelper.getAllUsersExcludesSystemUser())
                .containsExactly(otherUser1, otherUser2, otherUser3);
    }

    @Test
    public void getAllUsersExceptUser() {
        UserInfo user1 = createUserInfoForId(10);
        UserInfo user2 = createUserInfoForId(10);
        UserInfo user3 = createUserInfoForId(12);

        List<UserInfo> testUsers = new ArrayList<>();
        testUsers.add(user1);
        testUsers.add(user2);
        testUsers.add(user3);

        when(mUserManager.getUsers(true)).thenReturn(new ArrayList<>(testUsers));

        // Should return all 3 users.
        assertThat(mHelper.getAllUsersExceptUser(9).size()).isEqualTo(3);

        // Should return only user 12.
        assertThat(mHelper.getAllUsersExceptUser(10).size()).isEqualTo(1);
        assertThat(mHelper.getAllUsersExceptUser(10)).contains(user3);

        when(mUserManager.getUsers(true)).thenReturn(new ArrayList<>(testUsers));

        // Should drop user 12.
        assertThat(mHelper.getAllUsersExceptUser(12).size()).isEqualTo(2);
        assertThat(mHelper.getAllUsersExceptUser(12)).contains(user1);
        assertThat(mHelper.getAllUsersExceptUser(12)).contains(user2);
    }

    @Test
    public void getAllUsers() {
        int currentUser = UserHandle.myUserId();

        UserInfo otherUser1 = createUserInfoForId(currentUser + 1);
        UserInfo otherUser2 = createUserInfoForId(currentUser - 1);
        UserInfo otherUser3 = createUserInfoForId(currentUser + 2);

        List<UserInfo> testUsers = new ArrayList<>();
        testUsers.add(otherUser1);
        testUsers.add(otherUser2);
        testUsers.add(mCurrentProcessUser);
        testUsers.add(otherUser3);

        when(mUserManager.getUsers(true)).thenReturn(testUsers);

        assertThat(mHelper.getAllUsers().size()).isEqualTo(4);
        assertThat(mHelper.getAllUsers())
                .containsExactly(mCurrentProcessUser, otherUser1, otherUser2, otherUser3);
    }

    @Test
    public void userCanBeRemoved() {
        UserInfo testInfo = new UserInfo();

        // System user cannot be removed.
        testInfo.id = UserHandle.USER_SYSTEM;
        assertThat(mHelper.userCanBeRemoved(testInfo)).isFalse();

        testInfo.id = UserHandle.USER_SYSTEM + 2; // Make it different than system id.
        assertThat(mHelper.userCanBeRemoved(testInfo)).isTrue();
    }

    @Test
    public void currentProcessCanAddUsers() {
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_ADD_USER)).thenReturn(false);
        assertThat(mHelper.currentProcessCanAddUsers()).isTrue();

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_ADD_USER)).thenReturn(true);
        assertThat(mHelper.currentProcessCanAddUsers()).isFalse();
    }

    @Test
    public void currentProcessCanRemoveUsers() {
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_REMOVE_USER)).thenReturn(false);
        assertThat(mHelper.currentProcessCanRemoveUsers()).isTrue();

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_REMOVE_USER)).thenReturn(true);
        assertThat(mHelper.currentProcessCanRemoveUsers()).isFalse();
    }

    @Test
    public void currentProcessCanSwitchUsers() {
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_USER_SWITCH)).thenReturn(false);
        assertThat(mHelper.currentProcessCanSwitchUsers()).isTrue();

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_USER_SWITCH)).thenReturn(true);
        assertThat(mHelper.currentProcessCanSwitchUsers()).isFalse();
    }

    @Test
    public void currentProcessRunningAsGuestCannotModifyAccounts() {
        assertThat(mHelper.currentProcessCanModifyAccounts()).isTrue();

        when(mUserManager.isGuestUser()).thenReturn(true);
        assertThat(mHelper.currentProcessCanModifyAccounts()).isFalse();
    }

    @Test
    public void currentProcessRunningAsDemoUserCannotModifyAccounts() {
        assertThat(mHelper.currentProcessCanModifyAccounts()).isTrue();

        when(mUserManager.isDemoUser()).thenReturn(true);
        assertThat(mHelper.currentProcessCanModifyAccounts()).isFalse();
    }

    @Test
    public void currentProcessWithDisallowModifyAccountsRestrictionCannotModifyAccounts() {
        assertThat(mHelper.currentProcessCanModifyAccounts()).isTrue();

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS))
                .thenReturn(true);
        assertThat(mHelper.currentProcessCanModifyAccounts()).isFalse();
    }

    @Test
    public void createNewUser() {
        // Verify createUser on UserManager gets called.
        mHelper.createNewUser("Test User");
        verify(mUserManager).createUser("Test User", 0);

        when(mUserManager.createUser("Test User", 0)).thenReturn(null);
        assertThat(mHelper.createNewUser("Test User")).isNull();

        UserInfo newUser = new UserInfo();
        newUser.name = "Test User";
        when(mUserManager.createUser("Test User", 0)).thenReturn(newUser);
        assertThat(mHelper.createNewUser("Test User")).isEqualTo(newUser);
    }

    @Test
    public void removeUser() {
        // Cannot remove system user.
        assertThat(mHelper.removeUser(mSystemUser)).isFalse();

        // Removing non-current, non-system user, simply calls removeUser.
        UserInfo userToRemove = createUserInfoForId(mCurrentProcessUser.id + 2);

        mHelper.removeUser(userToRemove);
        verify(mUserManager).removeUser(mCurrentProcessUser.id + 2);
    }

    @Test
    public void startNewGuestSession() {
        mHelper.startNewGuestSession("Test Guest");
        verify(mUserManager).createGuest(mContext, "Test Guest");

        UserInfo guestInfo = new UserInfo(21, "Test Guest", UserInfo.FLAG_GUEST);
        when(mUserManager.createGuest(mContext, "Test Guest")).thenReturn(guestInfo);
        mHelper.startNewGuestSession("Test Guest");
        verify(mActivityManager).switchUser(21);
    }

    @Test
    public void getUserIcon() {
        mHelper.getUserIcon(mCurrentProcessUser);
        verify(mUserManager).getUserIcon(mCurrentProcessUser.id);
    }

    @Test
    public void scaleUserIcon() {
        Bitmap fakeIcon = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Drawable scaledIcon = mHelper.scaleUserIcon(fakeIcon, 300);
        assertThat(scaledIcon.getIntrinsicWidth()).isEqualTo(300);
        assertThat(scaledIcon.getIntrinsicHeight()).isEqualTo(300);
    }

    @Test
    public void setUserName() {
        UserInfo testInfo = createUserInfoForId(mCurrentProcessUser.id + 3);
        mHelper.setUserName(testInfo, "New Test Name");
        verify(mUserManager).setUserName(mCurrentProcessUser.id + 3, "New Test Name");
    }

    @Test
    public void registerUserChangeReceiver() {
        mHelper.registerOnUsersUpdateListener(mTestListener);

        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<UserHandle> handleCaptor = ArgumentCaptor.forClass(UserHandle.class);
        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);
        ArgumentCaptor<String> permissionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Handler> handlerCaptor = ArgumentCaptor.forClass(Handler.class);

        verify(mContext).registerReceiverAsUser(
                receiverCaptor.capture(),
                handleCaptor.capture(),
                filterCaptor.capture(),
                permissionCaptor.capture(),
                handlerCaptor.capture());

        // Verify we're listening to Intents from ALL users.
        assertThat(handleCaptor.getValue()).isEqualTo(UserHandle.ALL);

        // Verify the presence of each intent in the filter.
        // Verify the exact number of filters. Every time a new intent is added, this test should
        // get updated.
        assertThat(filterCaptor.getValue().countActions()).isEqualTo(6);
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_REMOVED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_ADDED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_INFO_CHANGED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_SWITCHED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_STOPPED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_UNLOCKED)).isTrue();


        // Verify that calling the receiver calls the listener.
        receiverCaptor.getValue().onReceive(mContext, new Intent());
        verify(mTestListener).onUsersUpdate();

        assertThat(permissionCaptor.getValue()).isNull();
        assertThat(handlerCaptor.getValue()).isNull();


        // Unregister the receiver.
        mHelper.unregisterOnUsersUpdateListener();
        verify(mContext).unregisterReceiver(receiverCaptor.getValue());
    }

    private UserInfo createUserInfoForId(int id) {
        UserInfo userInfo = new UserInfo();
        userInfo.id = id;
        return userInfo;
    }
}
