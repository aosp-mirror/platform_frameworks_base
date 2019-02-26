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
import static org.junit.Assert.fail;

import android.content.pm.UserInfo;
import android.os.Looper;
import android.os.UserManagerInternal;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashSet;

/**
 * <p>Run with:<pre>
 * m FrameworksServicesTests &&
 * adb install \
 * -r out/target/product/hammerhead/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 * adb shell am instrument -e class com.android.server.pm.UserManagerServiceIdRecyclingTest \
 * -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class UserManagerServiceIdRecyclingTest {
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
    }

    @Test
    public void testUserCreateRecycleIdsAddAllThenRemove() {
        // Add max possible users
        for (int i = UserManagerService.MIN_USER_ID; i < UserManagerService.MAX_USER_ID; i++) {
            int userId = mUserManagerService.getNextAvailableId();
            assertEquals(i, userId);
            mUserManagerService.putUserInfo(newUserInfo(userId));
        }

        assertNoNextIdAvailable("All ids should be assigned");
        // Now remove RECENTLY_REMOVED_IDS_MAX_SIZE users in the middle
        int startFrom = UserManagerService.MIN_USER_ID + 10000 /* arbitrary number */;
        int lastId = startFrom + UserManagerService.MAX_RECENTLY_REMOVED_IDS_SIZE;
        for (int i = startFrom; i < lastId; i++) {
            removeUser(i);
            assertNoNextIdAvailable("There is not enough recently removed users. "
                    + "Next id should not be available. Failed at u" + i);
        }

        // Now remove first user
        removeUser(UserManagerService.MIN_USER_ID);

        // Released UserIDs should be returned in the FIFO order
        int nextId = mUserManagerService.getNextAvailableId();
        assertEquals(startFrom, nextId);
    }

    @Test
    public void testUserCreateRecycleIdsOverflow() {
        LinkedHashSet<Integer> queue = new LinkedHashSet<>();
        // Make sure we can generate more than 2x ids without issues
        for (int i = 0; i < UserManagerService.MAX_USER_ID * 2; i++) {
            int userId = mUserManagerService.getNextAvailableId();
            assertTrue("Returned id should not be recent. Id=" + userId + ". Recents=" + queue,
                    queue.add(userId));
            if (queue.size() > UserManagerService.MAX_RECENTLY_REMOVED_IDS_SIZE) {
                queue.remove(queue.iterator().next());
            }
            mUserManagerService.putUserInfo(newUserInfo(userId));
            removeUser(userId);
        }
    }

    private void removeUser(int userId) {
        mUserManagerService.removeUserInfo(userId);
        mUserManagerService.addRemovingUserIdLocked(userId);
    }

    private void assertNoNextIdAvailable(String message) {
        try {
            mUserManagerService.getNextAvailableId();
            fail(message);
        } catch (IllegalStateException e) {
            //OK
        }
    }

    private static UserInfo newUserInfo(int userId) {
        return new UserInfo(userId, "User " + userId, 0);
    }
}

