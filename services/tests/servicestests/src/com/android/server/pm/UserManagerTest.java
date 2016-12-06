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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test {@link UserManager} functionality. */
@MediumTest
public class UserManagerTest extends AndroidTestCase {
    private static final int REMOVE_CHECK_INTERVAL = 500;
    private static final int REMOVE_TIMEOUT = 60 * 1000;
    private UserManager mUserManager = null;
    private final Object mUserLock = new Object();
    private List<Integer> usersToRemove;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mUserManager = UserManager.get(getContext());
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                synchronized (mUserLock) {
                    mUserLock.notifyAll();
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

    public void testHasSystemUser() throws Exception {
        assertTrue(findUser(UserHandle.USER_SYSTEM));
    }

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

    public void testAdd2Users() throws Exception {
        UserInfo user1 = createUser("Guest 1", UserInfo.FLAG_GUEST);
        UserInfo user2 = createUser("User 2", UserInfo.FLAG_ADMIN);

        assertTrue(user1 != null);
        assertTrue(user2 != null);

        assertTrue(findUser(0));
        assertTrue(findUser(user1.id));
        assertTrue(findUser(user2.id));
    }

    public void testRemoveUser() throws Exception {
        UserInfo userInfo = createUser("Guest 1", UserInfo.FLAG_GUEST);
        removeUser(userInfo.id);

        assertFalse(findUser(userInfo.id));
    }

    public void testAddGuest() throws Exception {
        UserInfo userInfo1 = createUser("Guest 1", UserInfo.FLAG_GUEST);
        UserInfo userInfo2 = createUser("Guest 2", UserInfo.FLAG_GUEST);
        assertNotNull(userInfo1);
        assertNull(userInfo2);
    }

    // Make sure only one managed profile can be created
    public void testAddManagedProfile() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        UserInfo userInfo1 = createProfileForUser("Managed 1",
                UserInfo.FLAG_MANAGED_PROFILE, primaryUserId);
        UserInfo userInfo2 = createProfileForUser("Managed 2",
                UserInfo.FLAG_MANAGED_PROFILE, primaryUserId);
        assertNotNull(userInfo1);
        assertNull(userInfo2);
        // Verify that current user is not a managed profile
        assertFalse(mUserManager.isManagedProfile());
    }

    public void testGetUserCreationTime() throws Exception {
        final int primaryUserId = mUserManager.getPrimaryUser().id;
        UserInfo profile = createProfileForUser("Managed 1",
                UserInfo.FLAG_MANAGED_PROFILE, primaryUserId);
        assertNotNull(profile);
        assertTrue("creationTime must be set when the profile is created",
                profile.creationTime > 0);
        assertEquals(profile.creationTime, mUserManager.getUserCreationTime(
                new UserHandle(profile.id)));

        long ownerCreationTime = mUserManager.getUserInfo(primaryUserId).creationTime;
        assertEquals(ownerCreationTime, mUserManager.getUserCreationTime(
                new UserHandle(primaryUserId)));

        try {
            int noSuchUserId = 100500;
            mUserManager.getUserCreationTime(new UserHandle(noSuchUserId));
            fail("SecurityException should be thrown for nonexistent user");
        } catch (Exception e) {
            assertTrue("SecurityException should be thrown for nonexistent user, but was: " + e,
                    e instanceof SecurityException);
        }

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

    private void removeUser(int userId) {
        synchronized (mUserLock) {
            mUserManager.removeUser(userId);
            long time = System.currentTimeMillis();
            while (mUserManager.getUserInfo(userId) != null) {
                try {
                    mUserLock.wait(REMOVE_CHECK_INTERVAL);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (System.currentTimeMillis() - time > REMOVE_TIMEOUT) {
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

    private UserInfo createProfileForUser(String name, int flags, int userHandle) {
        UserInfo profile = mUserManager.createProfileForUser(name, flags, userHandle);
        if (profile != null) {
            usersToRemove.add(profile.id);
        }
        return profile;
    }

}
