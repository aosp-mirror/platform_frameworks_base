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

import java.util.ArrayList;
import java.util.List;

/** Test {@link UserManager} functionality. */
public class UserManagerTest extends AndroidTestCase {

    UserManager mUserManager = null;
    Object mUserLock = new Object();

    @Override
    public void setUp() throws Exception {
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
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
    }

    private void removeExistingUsers() {
        List<UserInfo> list = mUserManager.getUsers();
        boolean found = false;
        for (UserInfo user : list) {
            if (user.id != UserHandle.USER_OWNER) {
                removeUser(user.id);
            }
        }
    }

    public void testHasPrimary() throws Exception {
        assertTrue(findUser(0));
    }

    public void testAddUser() throws Exception {
        UserInfo userInfo = mUserManager.createUser("Guest 1", UserInfo.FLAG_GUEST);
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
                assertFalse("New user should have DISALLOW_CONFIG_WIFI =false by default",
                        restrictions.getBoolean(UserManager.DISALLOW_CONFIG_WIFI));
            }
        }
        assertTrue(found);
        removeUser(userInfo.id);
    }

    public void testAdd2Users() throws Exception {
        UserInfo user1 = mUserManager.createUser("Guest 1", UserInfo.FLAG_GUEST);
        UserInfo user2 = mUserManager.createUser("User 2", UserInfo.FLAG_ADMIN);

        assertTrue(user1 != null);
        assertTrue(user2 != null);

        assertTrue(findUser(0));
        assertTrue(findUser(user1.id));
        assertTrue(findUser(user2.id));
        removeUser(user1.id);
        removeUser(user2.id);
    }

    public void testRemoveUser() throws Exception {
        UserInfo userInfo = mUserManager.createUser("Guest 1", UserInfo.FLAG_GUEST);
        removeUser(userInfo.id);

        assertFalse(findUser(userInfo.id));
    }

    public void testAddGuest() throws Exception {
        UserInfo userInfo1 = mUserManager.createUser("Guest 1", UserInfo.FLAG_GUEST);
        UserInfo userInfo2 = mUserManager.createUser("Guest 2", UserInfo.FLAG_GUEST);
        assertNotNull(userInfo1);
        assertNull(userInfo2);

        // Cleanup
        removeUser(userInfo1.id);
    }

    // Make sure only one managed profile can be created
    public void testAddManagedProfile() throws Exception {
        UserInfo userInfo1 = mUserManager.createProfileForUser("Managed 1",
                UserInfo.FLAG_MANAGED_PROFILE, UserHandle.USER_OWNER);
        UserInfo userInfo2 = mUserManager.createProfileForUser("Managed 2",
                UserInfo.FLAG_MANAGED_PROFILE, UserHandle.USER_OWNER);
        assertNotNull(userInfo1);
        assertNull(userInfo2);

        // Cleanup
        removeUser(userInfo1.id);
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
        UserInfo user1 = mUserManager.createUser("User 1", UserInfo.FLAG_RESTRICTED);
        int serialNumber1 = user1.serialNumber;
        assertEquals(serialNumber1, mUserManager.getUserSerialNumber(user1.id));
        assertEquals(user1.id, mUserManager.getUserHandle(serialNumber1));
        removeUser(user1.id);
        UserInfo user2 = mUserManager.createUser("User 2", UserInfo.FLAG_RESTRICTED);
        int serialNumber2 = user2.serialNumber;
        assertFalse(serialNumber1 == serialNumber2);
        assertEquals(serialNumber2, mUserManager.getUserSerialNumber(user2.id));
        assertEquals(user2.id, mUserManager.getUserHandle(serialNumber2));
        removeUser(user2.id);
    }

    public void testMaxUsers() {
        int N = UserManager.getMaxSupportedUsers();
        int count = mUserManager.getUsers().size();
        List<UserInfo> created = new ArrayList<UserInfo>();
        // Create as many users as permitted and make sure creation passes
        while (count < N) {
            UserInfo ui = mUserManager.createUser("User " + count, 0);
            assertNotNull(ui);
            created.add(ui);
            count++;
        }
        // Try to create one more user and make sure it fails
        UserInfo extra = null;
        assertNull(extra = mUserManager.createUser("One more", 0));
        if (extra != null) {
            removeUser(extra.id);
        }
        while (!created.isEmpty()) {
            UserInfo user = created.remove(0);
            removeUser(user.id);
        }
    }

    public void testRestrictions() {
        List<UserInfo> users = mUserManager.getUsers();
        if (users.size() > 1) {
            Bundle restrictions = new Bundle();
            restrictions.putBoolean(UserManager.DISALLOW_INSTALL_APPS, true);
            restrictions.putBoolean(UserManager.DISALLOW_CONFIG_WIFI, false);
            mUserManager.setUserRestrictions(restrictions, new UserHandle(users.get(1).id));
            Bundle stored = mUserManager.getUserRestrictions(new UserHandle(users.get(1).id));
            assertEquals(stored.getBoolean(UserManager.DISALLOW_CONFIG_WIFI), false);
            assertEquals(stored.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS), false);
            assertEquals(stored.getBoolean(UserManager.DISALLOW_INSTALL_APPS), true);
        }
    }

    private void removeUser(int userId) {
        synchronized (mUserLock) {
            mUserManager.removeUser(userId);
            while (mUserManager.getUserInfo(userId) != null) {
                try {
                    mUserLock.wait(1000);
                } catch (InterruptedException ie) {
                }
            }
        }
    }

}
