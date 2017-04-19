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
 * limitations under the License
 */

package com.android.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.ApplicationPackageManager;
import android.content.pm.UserInfo;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.IconDrawableFactory;

import com.android.server.LocalServices;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * <p>Run with:<pre>
 * runtest -c com.android.server.pm.UserManagerServiceCreateProfileTest frameworks-services
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class UserManagerServiceCreateProfileTest {
    private UserManagerService mUserManagerService;

    @Before
    public void setup() {
        // Currently UserManagerService cannot be instantiated twice inside a VM without a cleanup
        // TODO: Remove once UMS supports proper dependency injection
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        mUserManagerService = new UserManagerService(InstrumentationRegistry.getContext());

        // The tests assume that the device has one user and its the system user.
        List<UserInfo> users = mUserManagerService.getUsers(/* excludeDying */ false);
        assertEquals("Multiple users so this test can't run.", 1, users.size());
        assertEquals("Only user present isn't the system user.",
                UserHandle.USER_SYSTEM, users.get(0).id);
    }

    @After
    public void tearDown() {
        removeUsers();
    }

    @Test
    public void testGetProfiles() {
        // Pretend we have a secondary user with a profile.
        UserInfo secondaryUser = addUser();
        UserInfo profile = addProfile(secondaryUser);

        // System user should still have no profile so getProfiles should just return 1 user.
        List<UserInfo> users =
                mUserManagerService.getProfiles(UserHandle.USER_SYSTEM, /*excludeDying*/ false);
        assertEquals("Profiles returned where none should exist", 1, users.size());
        assertEquals("Missing system user from profile list of system user",
                UserHandle.USER_SYSTEM, users.get(0).id);

        // Secondary user should have 1 profile, so return that and itself.
        users = mUserManagerService.getProfiles(secondaryUser.id, /*excludeDying*/ false);
        assertEquals("Profiles returned where none should exist", 2, users.size());
        assertTrue("Missing secondary user id", users.get(0).id == secondaryUser.id
                || users.get(1).id == secondaryUser.id);
        assertTrue("Missing profile user id", users.get(0).id == profile.id
                || users.get(1).id == profile.id);
    }

    @Test
    public void testProfileBadge() {
        // First profile for system user should get badge 0
        assertEquals("First profile isn't given badge index 0", 0,
                mUserManagerService.getFreeProfileBadgeLU(UserHandle.USER_SYSTEM));

        // Pretend we have a secondary user.
        UserInfo secondaryUser = addUser();

        // Check first profile badge for secondary user is also 0.
        assertEquals("First profile for secondary user isn't given badge index 0", 0,
                mUserManagerService.getFreeProfileBadgeLU(secondaryUser.id));

        // Shouldn't impact the badge for profile in system user
        assertEquals("First profile isn't given badge index 0 with secondary user", 0,
                mUserManagerService.getFreeProfileBadgeLU(UserHandle.USER_SYSTEM));

        // Pretend a secondary user has a profile.
        addProfile(secondaryUser);

        // Shouldn't have impacted the badge for the system user
        assertEquals("First profile isn't given badge index 0 in secondary user", 0,
                mUserManagerService.getFreeProfileBadgeLU(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testProfileBadgeUnique() {
        List<UserInfo> users = mUserManagerService.getUsers(/* excludeDying */ false);
        UserInfo system = users.get(0);
        // Badges should get allocated 0 -> max
        for (int i = 0; i < UserManagerService.getMaxManagedProfiles(); ++i) {
            int nextBadge = mUserManagerService.getFreeProfileBadgeLU(UserHandle.USER_SYSTEM);
            assertEquals("Wrong badge allocated", i, nextBadge);
            UserInfo profile = addProfile(system);
            profile.profileBadge = nextBadge;
        }
    }

    @Test
    public void testProfileBadgeReuse() {
        // Pretend we have a secondary user with a profile.
        UserInfo secondaryUser = addUser();
        UserInfo profile = addProfile(secondaryUser);
        // Add the profile it to the users being removed.
        mUserManagerService.addRemovingUserIdLocked(profile.id);
        // We should reuse the badge from the profile being removed.
        assertEquals("Badge index not reused while removing a user", 0,
                mUserManagerService.getFreeProfileBadgeLU(secondaryUser.id));

        // Edge case of reuse that only applies if we ever support 3 managed profiles
        // We should prioritise using lower badge indexes
        if (UserManagerService.getMaxManagedProfiles() > 2) {
            UserInfo profileBadgeOne = addProfile(secondaryUser);
            profileBadgeOne.profileBadge = 1;
            // 0 and 2 are free, we should reuse 0 rather than 2.
            assertEquals("Lower index not used", 0,
                    mUserManagerService.getFreeProfileBadgeLU(secondaryUser.id));
        }
    }

    @Test
    public void testNumberOfBadges() {
        assertTrue("Max profiles greater than number of badges",
                UserManagerService.MAX_MANAGED_PROFILES
                <= IconDrawableFactory.CORP_BADGE_COLORS.length);
        assertEquals("Num colors doesn't match number of badge labels",
                IconDrawableFactory.CORP_BADGE_COLORS.length,
                ApplicationPackageManager.CORP_BADGE_LABEL_RES_ID.length);
    }

    @Test
    public void testCanAddMoreManagedProfiles_removeProfile() {
        // if device is low-ram or doesn't support managed profiles for some other reason, just
        // skip the test
        if (!mUserManagerService.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                false /* disallow remove */)) {
            return;
        }

        // GIVEN we've reached the limit of managed profiles possible on the system user
        while (mUserManagerService.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                false /* disallow remove */)) {
            addProfile(mUserManagerService.getPrimaryUser());
        }

        // THEN you should be able to add a new profile if you remove an existing one
        assertTrue("Cannot add a managed profile by removing another one",
                mUserManagerService.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                        true /* allow remove */));
    }

    @Test
    public void testCanAddMoreManagedProfiles_removeDisabledProfile() {
        // if device is low-ram or doesn't support managed profiles for some other reason, just
        // skip the test
        if (!mUserManagerService.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                false /* disallow remove */)) {
            return;
        }

        // GIVEN we've reached the limit of managed profiles possible on the system user
        // GIVEN that the profiles are not enabled yet
        while (mUserManagerService.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                false /* disallow remove */)) {
            addProfile(mUserManagerService.getPrimaryUser(), true /* disabled */);
        }

        // THEN you should be able to add a new profile if you remove an existing one
        assertTrue("Cannot add a managed profile by removing another one",
                mUserManagerService.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                        true /* allow remove */));
    }

    private void removeUsers() {
        List<UserInfo> users = mUserManagerService.getUsers(/* excludeDying */ false);
        for (UserInfo user: users) {
            if (user.id != UserHandle.USER_SYSTEM) {
                mUserManagerService.removeUserInfo(user.id);
            }
        }
    }

    private UserInfo addProfile(UserInfo user) {
        return addProfile(user, false);
    }

    private UserInfo addProfile(UserInfo user, boolean disabled) {
        user.profileGroupId = user.id;
        int flags = UserInfo.FLAG_MANAGED_PROFILE;
        if (disabled) {
            flags |= UserInfo.FLAG_DISABLED;
        }
        UserInfo profile = new UserInfo(
                mUserManagerService.getNextAvailableId(), "profile", flags);
        profile.profileGroupId = user.id;
        mUserManagerService.putUserInfo(profile);
        return profile;
    }

    private UserInfo addUser() {
        UserInfo secondaryUser = new UserInfo(
                mUserManagerService.getNextAvailableId(), "secondary", /* flags */ 0);
        mUserManagerService.putUserInfo(secondaryUser);
        return secondaryUser;
    }
}
