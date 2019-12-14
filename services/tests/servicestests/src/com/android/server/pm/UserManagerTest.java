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

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test {@link UserManager} functionality. */
public class UserManagerTest extends AndroidTestCase {
    // Taken from UserManagerService
    private static final long EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L; // 30 years

    private static final int REMOVE_CHECK_INTERVAL_MILLIS = 500; // 0.5 seconds
    private static final int REMOVE_TIMEOUT_MILLIS = 60 * 1000; // 60 seconds
    private static final int SWITCH_USER_TIMEOUT_MILLIS = 40 * 1000; // 40 seconds

    // Packages which are used during tests.
    private static final String[] PACKAGES = new String[] {
            "com.android.egg"
    };

    private final Object mUserRemoveLock = new Object();
    private final Object mUserSwitchLock = new Object();

    private UserManager mUserManager = null;
    private PackageManager mPackageManager;
    private List<Integer> usersToRemove;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mUserManager = UserManager.get(getContext());
        mPackageManager = getContext().getPackageManager();

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
                    case Intent.ACTION_USER_REMOVED:
                        synchronized (mUserRemoveLock) {
                            mUserRemoveLock.notifyAll();
                        }
                        break;
                    case Intent.ACTION_USER_SWITCHED:
                        synchronized (mUserSwitchLock) {
                            mUserSwitchLock.notifyAll();
                        }
                        break;
                }
            }
        }, filter);

        removeExistingUsers();
        usersToRemove = new ArrayList<>();
    }

    @Override
    protected void tearDown() throws Exception {
        for (Integer userId : usersToRemove) {
            removeUser(userId);
        }
        super.tearDown();
    }

    private void removeExistingUsers() {
        List<UserInfo> list = mUserManager.getUsers();
        for (UserInfo user : list) {
            // Keep system and primary user.
            // We do not have to keep primary user, but in split system user mode, we need it
            // until http://b/22976637 is fixed.  Right now in split system user mode, you need to
            // switch to primary user and run tests under primary user.
            if (user.id != UserHandle.USER_SYSTEM && !user.isPrimary()) {
                removeUser(user.id);
            }
        }
    }

    @SmallTest
    public void testHasSystemUser() throws Exception {
        assertTrue(findUser(UserHandle.USER_SYSTEM));
    }

    @MediumTest
    public void testAddUser() throws Exception {
        UserInfo userInfo = createUser("Guest 1", UserInfo.FLAG_GUEST);
        assertTrue(userInfo != null);

        List<UserInfo> list = mUserManager.getUsers();
        boolean found = false;
        for (UserInfo user : list) {
            if (user.id == userInfo.id && user.name.equals("Guest 1")
                    && user.isGuest()
                    && !user.isAdmin()
                    && !user.isPrimary()) {
                found = true;
                Bundle restrictions = mUserManager.getUserRestrictions(user.getUserHandle());
                assertTrue("Guest user should have DISALLOW_CONFIG_WIFI=true by default",
                        restrictions.getBoolean(UserManager.DISALLOW_CONFIG_WIFI));
            }
        }
        assertTrue(found);
    }

    @MediumTest
    public void testAdd2Users() throws Exception {
        UserInfo user1 = createUser("Guest 1", UserInfo.FLAG_GUEST);
        UserInfo user2 = createUser("User 2", UserInfo.FLAG_ADMIN);

        assertTrue(user1 != null);
        assertTrue(user2 != null);

        assertTrue(findUser(0));
        assertTrue(findUser(user1.id));
        assertTrue(findUser(user2.id));
    }

    @MediumTest
    public void testRemoveUser() throws Exception {
        UserInfo userInfo = createUser("Guest 1", UserInfo.FLAG_GUEST);
        removeUser(userInfo.id);

        assertFalse(findUser(userInfo.id));
    }

    @MediumTest
    public void testRemoveUserByHandle() {
        UserInfo userInfo = createUser("Guest 1", UserInfo.FLAG_GUEST);
        final UserHandle user = userInfo.getUserHandle();
        synchronized (mUserRemoveLock) {
            mUserManager.removeUser(user);
            long time = System.currentTimeMillis();
            while (mUserManager.getUserInfo(user.getIdentifier()) != null) {
                try {
                    mUserRemoveLock.wait(REMOVE_CHECK_INTERVAL_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (System.currentTimeMillis() - time > REMOVE_TIMEOUT_MILLIS) {
                    fail("Timeout waiting for removeUser. userId = " + user.getIdentifier());
                }
            }
        }

        assertFalse(findUser(userInfo.id));
    }

    @MediumTest
    public void testRemoveUserByHandle_ThrowsException() {
        synchronized (mUserRemoveLock) {
            try {
                mUserManager.removeUser(null);
                fail("Expected IllegalArgumentException on passing in a null UserHandle.");
            } catch (IllegalArgumentException expected) {
                // Do nothing - exception is expected.
            }
        }
    }

    /** Tests creating a FULL user via specifying userType. */
    @MediumTest
    public void testCreateUserViaTypes() throws Exception {
        createUserWithTypeAndCheckFlags(UserManager.USER_TYPE_FULL_GUEST,
                UserInfo.FLAG_GUEST | UserInfo.FLAG_FULL);

        createUserWithTypeAndCheckFlags(UserManager.USER_TYPE_FULL_DEMO,
                UserInfo.FLAG_DEMO | UserInfo.FLAG_FULL);

        createUserWithTypeAndCheckFlags(UserManager.USER_TYPE_FULL_SECONDARY,
                UserInfo.FLAG_FULL);
    }

    /** Tests creating a FULL user via specifying user flags. */
    @MediumTest
    public void testCreateUserViaFlags() throws Exception {
        createUserWithFlagsAndCheckType(UserInfo.FLAG_GUEST, UserManager.USER_TYPE_FULL_GUEST,
                UserInfo.FLAG_FULL);

        createUserWithFlagsAndCheckType(0, UserManager.USER_TYPE_FULL_SECONDARY,
                UserInfo.FLAG_FULL);

        createUserWithFlagsAndCheckType(UserInfo.FLAG_FULL, UserManager.USER_TYPE_FULL_SECONDARY,
                0);

        createUserWithFlagsAndCheckType(UserInfo.FLAG_DEMO, UserManager.USER_TYPE_FULL_DEMO,
                UserInfo.FLAG_FULL);
    }

    /** Creates a user of the given user type and checks that the result has the requiredFlags. */
    private void createUserWithTypeAndCheckFlags(String userType,
            @UserIdInt int requiredFlags) {
        final UserInfo userInfo = createUser("Name", userType, 0);
        assertEquals("Wrong user type", userType, userInfo.userType);
        assertEquals(
                "Flags " + userInfo.flags + " did not contain expected " + requiredFlags,
                requiredFlags, userInfo.flags & requiredFlags);
        removeUser(userInfo.id);
    }

    /**
     * Creates a user of the given flags and checks that the result is of the expectedUserType type
     * and that it has the expected flags (including both flags and any additionalRequiredFlags).
     */
    private void createUserWithFlagsAndCheckType(@UserIdInt int flags, String expectedUserType,
            @UserIdInt int additionalRequiredFlags) {
        final UserInfo userInfo = createUser("Name", flags);
        assertEquals("Wrong user type", expectedUserType, userInfo.userType);
        additionalRequiredFlags |= flags;
        assertEquals(
                "Flags " + userInfo.flags + " did not contain expected " + additionalRequiredFlags,
                additionalRequiredFlags, userInfo.flags & additionalRequiredFlags);
        removeUser(userInfo.id);
    }


    @MediumTest
    public void testAddGuest() throws Exception {
        UserInfo userInfo1 = createUser("Guest 1", UserInfo.FLAG_GUEST);
        UserInfo userInfo2 = createUser("Guest 2", UserInfo.FLAG_GUEST);
        assertNotNull(userInfo1);
        assertNull(userInfo2);
    }

    @MediumTest
    public void testFindExistingGuest_guestExists() throws Exception {
        UserInfo userInfo1 = createUser("Guest", UserInfo.FLAG_GUEST);
        UserInfo foundGuest = mUserManager.findCurrentGuestUser();
        assertNotNull(foundGuest);
    }

    @SmallTest
    public void testFindExistingGuest_guestDoesNotExist() throws Exception {
        UserInfo foundGuest = mUserManager.findCurrentGuestUser();
        assertNull(foundGuest);
    }

    @MediumTest
    public void testSetUserAdmin() throws Exception {
        UserInfo userInfo = createUser("SecondaryUser", /*flags=*/ 0);
        assertFalse(userInfo.isAdmin());

        mUserManager.setUserAdmin(userInfo.id);

        userInfo = mUserManager.getUserInfo(userInfo.id);
        assertTrue(userInfo.isAdmin());
    }

    @MediumTest
    public void testGetProfileParent() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;

        UserInfo userInfo = createProfileForUser("Profile",
                UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);
        assertNotNull(userInfo);
        assertNull(mUserManager.getProfileParent(primaryUserId));
        UserInfo parentProfileInfo = mUserManager.getProfileParent(userInfo.id);
        assertNotNull(parentProfileInfo);
        assertEquals(parentProfileInfo.id, primaryUserId);
        removeUser(userInfo.id);
        assertNull(mUserManager.getProfileParent(primaryUserId));
    }

    /** Test that UserManager returns the correct badge information for a managed profile. */
    @MediumTest
    public void testProfileTypeInformation() throws Exception {
        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_PROFILE_MANAGED);
        assertNotNull("No " + UserManager.USER_TYPE_PROFILE_MANAGED + " type on device",
                userTypeDetails);
        assertEquals(UserManager.USER_TYPE_PROFILE_MANAGED, userTypeDetails.getName());

        final int primaryUserId = mUserManager.getPrimaryUser().id;
        UserInfo userInfo = createProfileForUser("Managed",
                UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);
        assertNotNull(userInfo);
        final int userId = userInfo.id;
        final UserHandle userHandle = new UserHandle(userId);

        assertEquals(userTypeDetails.hasBadge(),
                mUserManager.hasBadge(userId));
        assertEquals(userTypeDetails.getIconBadge(),
                mUserManager.getUserIconBadgeResId(userId));
        assertEquals(userTypeDetails.getBadgePlain(),
                mUserManager.getUserBadgeResId(userId));
        assertEquals(userTypeDetails.getBadgeNoBackground(),
                mUserManager.getUserBadgeNoBackgroundResId(userId));
        assertEquals(userTypeDetails.isProfile(),
                mUserManager.isProfile(userId));
        assertEquals(userTypeDetails.getName(),
                mUserManager.getUserTypeForUser(userHandle));

        final int badgeIndex = userInfo.profileBadge;
        assertEquals(
                Resources.getSystem().getColor(userTypeDetails.getBadgeColor(badgeIndex), null),
                mUserManager.getUserBadgeColor(userId));
        assertEquals(
                Resources.getSystem().getString(userTypeDetails.getBadgeLabel(badgeIndex), "Test"),
                mUserManager.getBadgedLabelForUser("Test", userHandle));
    }

    // Make sure only one managed profile can be created
    @MediumTest
    public void testAddManagedProfile() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        UserInfo userInfo1 = createProfileForUser("Managed 1",
                UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);
        UserInfo userInfo2 = createProfileForUser("Managed 2",
                UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);

        assertNotNull(userInfo1);
        assertNull(userInfo2);

        assertEquals(userInfo1.userType, UserManager.USER_TYPE_PROFILE_MANAGED);
        int requiredFlags = UserInfo.FLAG_MANAGED_PROFILE | UserInfo.FLAG_PROFILE;
        assertEquals("Wrong flags " + userInfo1.flags, requiredFlags,
                userInfo1.flags & requiredFlags);

        // Verify that current user is not a managed profile
        assertFalse(mUserManager.isManagedProfile());
    }

    // Verify that disallowed packages are not installed in the managed profile.
    @MediumTest
    public void testAddManagedProfile_withDisallowedPackages() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        UserInfo userInfo1 = createProfileForUser("Managed1",
                UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);
        // Verify that the packagesToVerify are installed by default.
        for (String pkg : PACKAGES) {
            assertTrue("Package should be installed in managed profile: " + pkg,
                    isPackageInstalledForUser(pkg, userInfo1.id));
        }
        removeUser(userInfo1.id);

        UserInfo userInfo2 = createProfileForUser("Managed2",
                UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId, PACKAGES);
        // Verify that the packagesToVerify are not installed by default.
        for (String pkg : PACKAGES) {
            assertFalse("Package should not be installed in managed profile when disallowed: "
                    + pkg, isPackageInstalledForUser(pkg, userInfo2.id));
        }
    }

    // Verify that if any packages are disallowed to install during creation of managed profile can
    // still be installed later.
    @MediumTest
    public void testAddManagedProfile_disallowedPackagesInstalledLater() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        UserInfo userInfo = createProfileForUser("Managed",
                UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId, PACKAGES);
        // Verify that the packagesToVerify are not installed by default.
        for (String pkg : PACKAGES) {
            assertFalse("Package should not be installed in managed profile when disallowed: "
                    + pkg, isPackageInstalledForUser(pkg, userInfo.id));
        }

        // Verify that the disallowed packages during profile creation can be installed now.
        for (String pkg : PACKAGES) {
            assertEquals("Package could not be installed: " + pkg,
                    PackageManager.INSTALL_SUCCEEDED,
                    mPackageManager.installExistingPackageAsUser(pkg, userInfo.id));
        }
    }

    // Make sure createUser would fail if we have DISALLOW_ADD_USER.
    @MediumTest
    public void testCreateUser_disallowAddUser() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        final UserHandle primaryUserHandle = new UserHandle(primaryUserId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, true, primaryUserHandle);
        try {
            UserInfo userInfo = createUser("SecondaryUser", /*flags=*/ 0);
            assertNull(userInfo);
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, false,
                    primaryUserHandle);
        }
    }

    // Make sure createProfile would fail if we have DISALLOW_ADD_MANAGED_PROFILE.
    @MediumTest
    public void testCreateProfileForUser_disallowAddManagedProfile() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        final UserHandle primaryUserHandle = new UserHandle(primaryUserId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, true,
                primaryUserHandle);
        try {
            UserInfo userInfo = createProfileForUser("Managed",
                    UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);
            assertNull(userInfo);
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, false,
                    primaryUserHandle);
        }
    }

    // Make sure createProfileEvenWhenDisallowedForUser bypass DISALLOW_ADD_MANAGED_PROFILE.
    @MediumTest
    public void testCreateProfileForUserEvenWhenDisallowed() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        final UserHandle primaryUserHandle = new UserHandle(primaryUserId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, true,
                primaryUserHandle);
        try {
            UserInfo userInfo = createProfileEvenWhenDisallowedForUser("Managed",
                    UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);
            assertNotNull(userInfo);
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, false,
                    primaryUserHandle);
        }
    }

    // createProfile succeeds even if DISALLOW_ADD_USER is set
    @MediumTest
    public void testCreateProfileForUser_disallowAddUser() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        final UserHandle primaryUserHandle = new UserHandle(primaryUserId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, true, primaryUserHandle);
        try {
            UserInfo userInfo = createProfileForUser("Managed",
                    UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);
            assertNotNull(userInfo);
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, false,
                    primaryUserHandle);
        }
    }

    @MediumTest
    public void testAddRestrictedProfile() throws Exception {
        assertFalse("There should be no associated restricted profiles before the test",
                mUserManager.hasRestrictedProfiles());
        UserInfo userInfo = createRestrictedProfile("Profile");
        assertNotNull(userInfo);

        Bundle restrictions = mUserManager.getUserRestrictions(UserHandle.of(userInfo.id));
        assertTrue("Restricted profile should have DISALLOW_MODIFY_ACCOUNTS restriction by default",
                restrictions.getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS));
        assertTrue("Restricted profile should have DISALLOW_SHARE_LOCATION restriction by default",
                restrictions.getBoolean(UserManager.DISALLOW_SHARE_LOCATION));

        int locationMode = Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY,
                userInfo.id);
        assertEquals("Restricted profile should have setting LOCATION_MODE set to "
                + "LOCATION_MODE_OFF by default", locationMode, Settings.Secure.LOCATION_MODE_OFF);

        assertTrue("Newly created profile should be associated with the current user",
                mUserManager.hasRestrictedProfiles());
    }

    @MediumTest
    public void testGetUserCreationTime() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        final long startTime = System.currentTimeMillis();
        UserInfo profile = createProfileForUser("Managed 1",
                UserManager.USER_TYPE_PROFILE_MANAGED, primaryUserId);
        final long endTime = System.currentTimeMillis();
        assertNotNull(profile);
        if (System.currentTimeMillis() > EPOCH_PLUS_30_YEARS) {
            assertTrue("creationTime must be set when the profile is created",
                    profile.creationTime >= startTime && profile.creationTime <= endTime);
        } else {
            assertTrue("creationTime must be 0 if the time is not > EPOCH_PLUS_30_years",
                    profile.creationTime == 0);
        }
        assertEquals(profile.creationTime, mUserManager.getUserCreationTime(
                new UserHandle(profile.id)));

        long ownerCreationTime = mUserManager.getUserInfo(primaryUserId).creationTime;
        assertEquals(ownerCreationTime, mUserManager.getUserCreationTime(
                new UserHandle(primaryUserId)));
    }

    @SmallTest
    public void testGetUserCreationTime_nonExistentUser() throws Exception {
        try {
            int noSuchUserId = 100500;
            mUserManager.getUserCreationTime(new UserHandle(noSuchUserId));
            fail("SecurityException should be thrown for nonexistent user");
        } catch (Exception e) {
            assertTrue("SecurityException should be thrown for nonexistent user, but was: " + e,
                    e instanceof SecurityException);
        }
    }

    @SmallTest
    public void testGetUserCreationTime_otherUser() throws Exception {
        UserInfo user = createUser("User 1", 0);
        try {
            mUserManager.getUserCreationTime(new UserHandle(user.id));
            fail("SecurityException should be thrown for other user");
        } catch (Exception e) {
            assertTrue("SecurityException should be thrown for other user, but was: " + e,
                    e instanceof SecurityException);
        }
    }

    private boolean findUser(int id) {
        List<UserInfo> list = mUserManager.getUsers();

        for (UserInfo user : list) {
            if (user.id == id) {
                return true;
            }
        }
        return false;
    }

    @MediumTest
    public void testSerialNumber() {
        UserInfo user1 = createUser("User 1", 0);
        int serialNumber1 = user1.serialNumber;
        assertEquals(serialNumber1, mUserManager.getUserSerialNumber(user1.id));
        assertEquals(user1.id, mUserManager.getUserHandle(serialNumber1));
        UserInfo user2 = createUser("User 2", 0);
        int serialNumber2 = user2.serialNumber;
        assertFalse(serialNumber1 == serialNumber2);
        assertEquals(serialNumber2, mUserManager.getUserSerialNumber(user2.id));
        assertEquals(user2.id, mUserManager.getUserHandle(serialNumber2));
    }

    @MediumTest
    public void testGetSerialNumbersOfUsers() {
        UserInfo user1 = createUser("User 1", 0);
        UserInfo user2 = createUser("User 2", 0);
        long[] serialNumbersOfUsers = mUserManager.getSerialNumbersOfUsers(false);
        String errMsg = "Array " + Arrays.toString(serialNumbersOfUsers) + " should contain ";
        assertTrue(errMsg + user1.serialNumber,
                ArrayUtils.contains(serialNumbersOfUsers, user1.serialNumber));
        assertTrue(errMsg + user2.serialNumber,
                ArrayUtils.contains(serialNumbersOfUsers, user2.serialNumber));
    }

    @MediumTest
    public void testMaxUsers() {
        int N = UserManager.getMaxSupportedUsers();
        int count = mUserManager.getUsers().size();
        // Create as many users as permitted and make sure creation passes
        while (count < N) {
            UserInfo ui = createUser("User " + count, 0);
            assertNotNull(ui);
            count++;
        }
        // Try to create one more user and make sure it fails
        UserInfo extra = createUser("One more", 0);
        assertNull(extra);
    }

    @MediumTest
    public void testGetUserCount() {
        int count = mUserManager.getUsers().size();
        UserInfo user1 = createUser("User 1", 0);
        assertNotNull(user1);
        UserInfo user2 = createUser("User 2", 0);
        assertNotNull(user2);
        assertEquals(count + 2, mUserManager.getUserCount());
    }

    @MediumTest
    public void testRestrictions() {
        UserInfo testUser = createUser("User 1", 0);

        mUserManager.setUserRestriction(
                UserManager.DISALLOW_INSTALL_APPS, true, new UserHandle(testUser.id));
        mUserManager.setUserRestriction(
                UserManager.DISALLOW_CONFIG_WIFI, false, new UserHandle(testUser.id));

        Bundle stored = mUserManager.getUserRestrictions(new UserHandle(testUser.id));
        // Note this will fail if DO already sets those restrictions.
        assertEquals(stored.getBoolean(UserManager.DISALLOW_CONFIG_WIFI), false);
        assertEquals(stored.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS), false);
        assertEquals(stored.getBoolean(UserManager.DISALLOW_INSTALL_APPS), true);
    }

    @MediumTest
    public void testDefaultRestrictionsApplied() throws Exception {
        final UserInfo userInfo = createUser("Useroid", UserManager.USER_TYPE_FULL_SECONDARY, 0);
        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_FULL_SECONDARY);
        final Bundle expectedRestrictions = userTypeDetails.getDefaultRestrictions();
        // Note this can fail if DO unset those restrictions.
        for (String restriction : expectedRestrictions.keySet()) {
            if (expectedRestrictions.getBoolean(restriction)) {
                assertTrue(
                        mUserManager.hasUserRestriction(restriction, UserHandle.of(userInfo.id)));
            }
        }
    }

    @MediumTest
    public void testSetDefaultGuestRestrictions() {
        final Bundle origGuestRestrictions = mUserManager.getDefaultGuestRestrictions();
        Bundle restrictions = new Bundle();
        restrictions.putBoolean(UserManager.DISALLOW_FUN, true);
        mUserManager.setDefaultGuestRestrictions(restrictions);

        try {
            UserInfo guest = createUser("Guest", UserInfo.FLAG_GUEST);
            assertNotNull(guest);
            assertTrue(mUserManager.hasUserRestriction(UserManager.DISALLOW_FUN,
                    guest.getUserHandle()));
        } finally {
            mUserManager.setDefaultGuestRestrictions(origGuestRestrictions);
        }
    }

    public void testGetUserSwitchability() {
        int userSwitchable = mUserManager.getUserSwitchability();
        assertEquals("Expected users to be switchable", UserManager.SWITCHABILITY_STATUS_OK,
                userSwitchable);
    }

    @LargeTest
    public void testSwitchUser() {
        ActivityManager am = getContext().getSystemService(ActivityManager.class);
        final int startUser = am.getCurrentUser();
        UserInfo user = createUser("User", 0);
        assertNotNull(user);
        // Switch to the user just created.
        switchUser(user.id, null, true);
        // Switch back to the starting user.
        switchUser(startUser, null, true);
    }

    @LargeTest
    public void testSwitchUserByHandle() {
        ActivityManager am = getContext().getSystemService(ActivityManager.class);
        final int startUser = am.getCurrentUser();
        UserInfo user = createUser("User", 0);
        assertNotNull(user);
        // Switch to the user just created.
        switchUser(-1, user.getUserHandle(), false);
        // Switch back to the starting user.
        switchUser(-1, UserHandle.of(startUser), false);
    }

    public void testSwitchUserByHandle_ThrowsException() {
        synchronized (mUserSwitchLock) {
            try {
                ActivityManager am = getContext().getSystemService(ActivityManager.class);
                am.switchUser(null);
                fail("Expected IllegalArgumentException on passing in a null UserHandle.");
            } catch (IllegalArgumentException expected) {
                // Do nothing - exception is expected.
            }
        }
    }

    @MediumTest
    public void testConcurrentUserCreate() throws Exception {
        int userCount = mUserManager.getUserCount();
        int maxSupportedUsers = UserManager.getMaxSupportedUsers();
        int canBeCreatedCount = maxSupportedUsers - userCount;
        // Test exceeding the limit while running in parallel
        int createUsersCount = canBeCreatedCount + 5;
        ExecutorService es = Executors.newCachedThreadPool();
        AtomicInteger created = new AtomicInteger();
        for (int i = 0; i < createUsersCount; i++) {
            final String userName = "testConcUser" + i;
            es.submit(() -> {
                UserInfo user = mUserManager.createUser(userName, 0);
                if (user != null) {
                    created.incrementAndGet();
                    synchronized (mUserRemoveLock) {
                        usersToRemove.add(user.id);
                    }
                }
            });
        }
        es.shutdown();
        es.awaitTermination(20, TimeUnit.SECONDS);
        assertEquals(maxSupportedUsers, mUserManager.getUserCount());
        assertEquals(canBeCreatedCount, created.get());
    }

    @MediumTest
    public void testGetUserHandles_createNewUser_shouldFindNewUser() {
        UserInfo user = createUser("Guest 1", UserManager.USER_TYPE_FULL_GUEST, /*flags*/ 0);

        boolean found = false;
        List<UserHandle> userHandles = mUserManager.getUserHandles(/* excludeDying= */ true);
        for (UserHandle userHandle: userHandles) {
            if (userHandle.getIdentifier() == user.id) {
                found = true;
            }
        }

        assertTrue(found);
    }

    private boolean isPackageInstalledForUser(String packageName, int userId) {
        try {
            return mPackageManager.getPackageInfoAsUser(packageName, 0, userId) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * @param userId value will be used to call switchUser(int) only if ignoreHandle is false.
     * @param user value will be used to call switchUser(UserHandle) only if ignoreHandle is true.
     * @param ignoreHandle if true, switchUser(int) will be called with the provided userId,
     *                     else, switchUser(UserHandle) will be called with the provided user.
     */
    private void switchUser(int userId, UserHandle user, boolean ignoreHandle) {
        synchronized (mUserSwitchLock) {
            ActivityManager am = getContext().getSystemService(ActivityManager.class);
            if (ignoreHandle) {
                am.switchUser(userId);
            } else {
                am.switchUser(user);
            }
            long time = System.currentTimeMillis();
            try {
                mUserSwitchLock.wait(SWITCH_USER_TIMEOUT_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (System.currentTimeMillis() - time > SWITCH_USER_TIMEOUT_MILLIS) {
                fail("Timeout waiting for the user switch to u"
                        + (ignoreHandle ? userId : user.getIdentifier()));
            }
        }
    }

    private void removeUser(int userId) {
        synchronized (mUserRemoveLock) {
            mUserManager.removeUser(userId);
            long time = System.currentTimeMillis();
            while (mUserManager.getUserInfo(userId) != null) {
                try {
                    mUserRemoveLock.wait(REMOVE_CHECK_INTERVAL_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (System.currentTimeMillis() - time > REMOVE_TIMEOUT_MILLIS) {
                    fail("Timeout waiting for removeUser. userId = " + userId);
                }
            }
        }
    }

    private UserInfo createUser(String name, int flags) {
        UserInfo user = mUserManager.createUser(name, flags);
        if (user != null) {
            usersToRemove.add(user.id);
        }
        return user;
    }

    private UserInfo createUser(String name, String userType, int flags) {
        UserInfo user = mUserManager.createUser(name, userType, flags);
        if (user != null) {
            usersToRemove.add(user.id);
        }
        return user;
    }

    private UserInfo createProfileForUser(String name, String userType, int userHandle) {
        return createProfileForUser(name, userType, userHandle, null);
    }

    private UserInfo createProfileForUser(String name, String userType, int userHandle,
            String[] disallowedPackages) {
        UserInfo profile = mUserManager.createProfileForUser(
                name, userType, 0, userHandle, disallowedPackages);
        if (profile != null) {
            usersToRemove.add(profile.id);
        }
        return profile;
    }

    private UserInfo createProfileEvenWhenDisallowedForUser(String name, String userType,
            int userHandle) {
        UserInfo profile = mUserManager.createProfileForUserEvenWhenDisallowed(
                name, userType, 0, userHandle, null);
        if (profile != null) {
            usersToRemove.add(profile.id);
        }
        return profile;
    }

    private UserInfo createRestrictedProfile(String name) {
        UserInfo profile = mUserManager.createRestrictedProfile(name);
        if (profile != null) {
            usersToRemove.add(profile.id);
        }
        return profile;
    }

}
