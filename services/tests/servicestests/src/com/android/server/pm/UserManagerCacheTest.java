/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.app.ActivityManager;
import android.app.LocaleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.multiuser.Flags;
import android.os.LocaleList;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Postsubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Test {@link UserManager Cache} functionality.
 *
 * atest com.android.server.pm.UserManagerCacheTest
 */
@Postsubmit
@RunWith(AndroidJUnit4.class)
public final class UserManagerCacheTest {

    private static final LocaleList TEST_LOCALE_LIST = LocaleList.forLanguageTags("pl-PL");
    private static final long SLEEP_TIMEOUT = 5_000;
    private static final int REMOVE_USER_TIMEOUT_SECONDS = 180; // 180 seconds
    private static final String TAG = UserManagerCacheTest.class.getSimpleName();

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private UserManager mUserManager = null;
    private PackageManager mPackageManager;
    private LocaleManager mLocaleManager;
    private ArraySet<Integer> mUsersToRemove;
    private UserRemovalWaiter mUserRemovalWaiter;
    private int mOriginalCurrentUserId;
    private LocaleList mSystemLocales;

    @Before
    public void setUp() throws Exception {
        mOriginalCurrentUserId = ActivityManager.getCurrentUser();
        mUserManager = UserManager.get(mContext);
        mPackageManager = mContext.getPackageManager();
        mLocaleManager = mContext.getSystemService(LocaleManager.class);
        mSystemLocales = mLocaleManager.getSystemLocales();
        mUserRemovalWaiter = new UserRemovalWaiter(mContext, TAG, REMOVE_USER_TIMEOUT_SECONDS);
        mUsersToRemove = new ArraySet<>();
        removeExistingUsers();
    }

    @After
    public void tearDown() throws Exception {
        // Making a copy of mUsersToRemove to avoid ConcurrentModificationException
        mUsersToRemove.stream().toList().forEach(this::removeUser);
        mUserRemovalWaiter.close();
        mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, false,
                mContext.getUser());
        mLocaleManager.setSystemLocales(mSystemLocales);
    }

    private void removeExistingUsers() {
        int currentUser = ActivityManager.getCurrentUser();

        UserHandle communalProfile = mUserManager.getCommunalProfile();
        int communalProfileId = communalProfile != null
                ? communalProfile.getIdentifier() : UserHandle.USER_NULL;

        List<UserInfo> list = mUserManager.getUsers();
        for (UserInfo user : list) {
            // Keep system and current user
            if (user.id != UserHandle.USER_SYSTEM
                    && user.id != currentUser
                    && user.id != communalProfileId
                    && !user.isMain()) {
                removeUser(user.id);
            }
        }
    }

    @MediumTest
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CACHE_USER_INFO_READ_ONLY)
    public void testUserInfoAfterLocaleChange() throws Exception {
        UserInfo userInfo = mUserManager.createGuest(mContext);
        mUsersToRemove.add(userInfo.id);
        assertThat(userInfo).isNotNull();

        UserInfo guestUserInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(guestUserInfo).isNotNull();
        assertThat(guestUserInfo.name).isNotEqualTo("Gość");

        UserInfo ownerUserInfo = mUserManager.getUserInfo(mOriginalCurrentUserId);
        assertThat(ownerUserInfo).isNotNull();
        assertThat(ownerUserInfo.name).isNotEqualTo("Właściciel");

        mLocaleManager.setSystemLocales(TEST_LOCALE_LIST);
        SystemClock.sleep(SLEEP_TIMEOUT);
        UserInfo guestUserInfoPl = mUserManager.getUserInfo(userInfo.id);
        UserInfo ownerUserInfoPl = mUserManager.getUserInfo(mOriginalCurrentUserId);

        assertThat(guestUserInfoPl).isNotNull();
        assertThat(guestUserInfoPl.name).isEqualTo("Gość");

        assertThat(ownerUserInfoPl).isNotNull();
        assertThat(ownerUserInfoPl.name).isEqualTo("Właściciel");
    }


    @MediumTest
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CACHE_USER_INFO_READ_ONLY)
    public void testGetUserInfo10kSpam() throws Exception {
        UserInfo cachedUserInfo = mUserManager.getUserInfo(mOriginalCurrentUserId);
        for (int i = 0; i < 10000; i++) {
            // Control how often cache is calling the API
            UserInfo ownerUserInfo = mUserManager.getUserInfo(mOriginalCurrentUserId);
            assertThat(ownerUserInfo).isNotNull();
            // If indeed it was chached then objects should stay the same. We use == to compare
            // object addresses to make sure UserInfo is not new copy of the same UserInfo.
            assertThat(cachedUserInfo.toFullString()).isEqualTo(ownerUserInfo.toFullString());
        }
    }


    @MediumTest
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CACHE_USER_INFO_READ_ONLY)
    public void testSetUserAdmin() throws Exception {
        UserInfo userInfo = mUserManager.createUser("SecondaryUser",
                UserManager.USER_TYPE_FULL_SECONDARY, /*flags=*/ 0);
        mUsersToRemove.add(userInfo.id);
        // cache user
        UserInfo cachedUserInfo = mUserManager.getUserInfo(userInfo.id);

        assertThat(userInfo.isAdmin()).isFalse();
        assertThat(cachedUserInfo.isAdmin()).isFalse();

        // invalidate cache
        mUserManager.setUserAdmin(userInfo.id);

        // updated UserInfo should be returned
        cachedUserInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(cachedUserInfo.isAdmin()).isTrue();
    }


    @MediumTest
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CACHE_USER_INFO_READ_ONLY)
    public void testRevokeUserAdmin() throws Exception {
        UserInfo userInfo = mUserManager.createUser("Admin",
                UserManager.USER_TYPE_FULL_SECONDARY, /*flags=*/ UserInfo.FLAG_ADMIN);
        mUsersToRemove.add(userInfo.id);
        // cache user
        UserInfo cachedUserInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(userInfo.isAdmin()).isTrue();
        assertThat(cachedUserInfo.isAdmin()).isTrue();

        // invalidate cache
        mUserManager.revokeUserAdmin(userInfo.id);

        // updated UserInfo should be returned
        cachedUserInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(cachedUserInfo.isAdmin()).isFalse();
    }

    @MediumTest
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CACHE_USER_INFO_READ_ONLY)
    public void testRevokeUserAdminFromNonAdmin() throws Exception {
        UserInfo userInfo = mUserManager.createUser("NonAdmin",
                UserManager.USER_TYPE_FULL_SECONDARY, /*flags=*/ 0);
        mUsersToRemove.add(userInfo.id);
        // cache user
        UserInfo cachedUserInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(userInfo.isAdmin()).isFalse();
        assertThat(cachedUserInfo.isAdmin()).isFalse();

        // invalidate cache
        mUserManager.revokeUserAdmin(userInfo.id);

        // updated UserInfo should be returned
        cachedUserInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(cachedUserInfo.isAdmin()).isFalse();
    }


    @MediumTest
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_CACHE_USER_INFO_READ_ONLY)
    public void testSetUserName_withContextUserId() throws Exception {
        assumeManagedUsersSupported();
        final String newName = "Managed_user 1";
        final int mainUserId = mUserManager.getMainUser().getIdentifier();

        // cache main user
        UserInfo mainUserInfo =  mUserManager.getUserInfo(mainUserId);

        assertThat(mainUserInfo).isNotNull();

        // invalidate cache
        UserInfo userInfo =  mUserManager.createProfileForUser("Managed 1",
                UserManager.USER_TYPE_PROFILE_MANAGED,  0, mainUserId, null);
        mUsersToRemove.add(userInfo.id);
        // cache user
        UserInfo cachedUserInfo = mUserManager.getUserInfo(userInfo.id);
        // updated cache for main user
        mainUserInfo =  mUserManager.getUserInfo(mainUserId);

        assertThat(userInfo).isNotNull();
        assertThat(cachedUserInfo).isNotNull();
        assertThat(mainUserInfo).isNotNull();
        // profileGroupId are the same after adding profile to user.
        assertThat(mainUserInfo.profileGroupId).isEqualTo(cachedUserInfo.profileGroupId);

        UserManager um = (UserManager) mContext.createPackageContextAsUser(
                        "android", 0, userInfo.getUserHandle())
                .getSystemService(Context.USER_SERVICE);
        // invalidate cache
        um.setUserName(newName);

        // updated UserInfo should be returned
        cachedUserInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(cachedUserInfo.name).isEqualTo(newName);

        // get user name from getUserName using context.getUserId
        assertThat(um.getUserName()).isEqualTo(newName);
    }

    private void assumeManagedUsersSupported() {
        // In Automotive, if headless system user is enabled, a managed user cannot be created
        // under a primary user.
        assumeTrue("device doesn't support managed users",
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
                        && (!isAutomotive() || !UserManager.isHeadlessSystemUserMode()));
    }

    private void removeUser(int userId) {
        mUserManager.removeUser(userId);
        mUserRemovalWaiter.waitFor(userId);
        mUsersToRemove.remove(userId);
    }

    private boolean isAutomotive() {
        return mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }
}
