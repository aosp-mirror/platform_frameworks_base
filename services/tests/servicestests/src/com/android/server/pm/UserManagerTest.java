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

import com.android.server.pm.UserManager;

import android.content.pm.UserInfo;
import android.os.Debug;
import android.os.Environment;
import android.test.AndroidTestCase;

import java.util.List;

/** Test {@link UserManager} functionality. */
public class UserManagerTest extends AndroidTestCase {

    UserManager mUserManager = null;

    @Override
    public void setUp() throws Exception {
        mUserManager = new UserManager(Environment.getExternalStorageDirectory(),
                Environment.getExternalStorageDirectory());
    }

    @Override
    public void tearDown() throws Exception {
        List<UserInfo> users = mUserManager.getUsers();
        // Remove all except the primary user
        for (UserInfo user : users) {
            if (!user.isPrimary()) {
                mUserManager.removeUser(user.id);
            }
        }
    }

    public void testHasPrimary() throws Exception {
        assertTrue(findUser(0));
    }

    public void testAddUser() throws Exception {
        final UserManager details = mUserManager;

        UserInfo userInfo = details.createUser("Guest 1", UserInfo.FLAG_GUEST);
        assertTrue(userInfo != null);

        List<UserInfo> list = details.getUsers();
        boolean found = false;
        for (UserInfo user : list) {
            if (user.id == userInfo.id && user.name.equals("Guest 1")
                    && user.isGuest()
                    && !user.isAdmin()
                    && !user.isPrimary()) {
                found = true;
            }
        }
        assertTrue(found);
    }

    public void testAdd2Users() throws Exception {
        final UserManager details = mUserManager;

        UserInfo user1 = details.createUser("Guest 1", UserInfo.FLAG_GUEST);
        UserInfo user2 = details.createUser("User 2", UserInfo.FLAG_ADMIN);

        assertTrue(user1 != null);
        assertTrue(user2 != null);

        assertTrue(findUser(0));
        assertTrue(findUser(user1.id));
        assertTrue(findUser(user2.id));
    }

    public void testRemoveUser() throws Exception {
        final UserManager details = mUserManager;

        UserInfo userInfo = details.createUser("Guest 1", UserInfo.FLAG_GUEST);

        details.removeUser(userInfo.id);

        assertFalse(findUser(userInfo.id));
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
}
