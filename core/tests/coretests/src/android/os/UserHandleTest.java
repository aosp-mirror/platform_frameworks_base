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

package android.os;

import static android.os.UserHandle.ERR_GID;
import static android.os.UserHandle.getAppId;
import static android.os.UserHandle.getCacheAppGid;
import static android.os.UserHandle.getSharedAppGid;
import static android.os.UserHandle.getUid;
import static android.os.UserHandle.getUserId;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UserHandleTest {
    // NOTE: keep logic in sync with system/core/libcutils/tests/multiuser_test.cpp

    @Test
    public void testMerge() throws Exception {
        EXPECT_EQ(0, multiuser_get_uid(0, 0));
        EXPECT_EQ(1000, multiuser_get_uid(0, 1000));
        EXPECT_EQ(10000, multiuser_get_uid(0, 10000));
        EXPECT_EQ(50000, multiuser_get_uid(0, 50000));
        EXPECT_EQ(1000000, multiuser_get_uid(10, 0));
        EXPECT_EQ(1001000, multiuser_get_uid(10, 1000));
        EXPECT_EQ(1010000, multiuser_get_uid(10, 10000));
        EXPECT_EQ(1050000, multiuser_get_uid(10, 50000));
    }

    @Test
    public void testSplitUser() throws Exception {
        EXPECT_EQ(0, multiuser_get_user_id(0));
        EXPECT_EQ(0, multiuser_get_user_id(1000));
        EXPECT_EQ(0, multiuser_get_user_id(10000));
        EXPECT_EQ(0, multiuser_get_user_id(50000));
        EXPECT_EQ(10, multiuser_get_user_id(1000000));
        EXPECT_EQ(10, multiuser_get_user_id(1001000));
        EXPECT_EQ(10, multiuser_get_user_id(1010000));
        EXPECT_EQ(10, multiuser_get_user_id(1050000));
    }

    @Test
    public void testSplitApp() throws Exception {
        EXPECT_EQ(0, multiuser_get_app_id(0));
        EXPECT_EQ(1000, multiuser_get_app_id(1000));
        EXPECT_EQ(10000, multiuser_get_app_id(10000));
        EXPECT_EQ(50000, multiuser_get_app_id(50000));
        EXPECT_EQ(0, multiuser_get_app_id(1000000));
        EXPECT_EQ(1000, multiuser_get_app_id(1001000));
        EXPECT_EQ(10000, multiuser_get_app_id(1010000));
        EXPECT_EQ(50000, multiuser_get_app_id(1050000));
    }

    @Test
    public void testCache() throws Exception {
        EXPECT_EQ(ERR_GID, multiuser_get_cache_gid(0, 0));
        EXPECT_EQ(ERR_GID, multiuser_get_cache_gid(0, 1000));
        EXPECT_EQ(20000, multiuser_get_cache_gid(0, 10000));
        EXPECT_EQ(ERR_GID, multiuser_get_cache_gid(0, 50000));
        EXPECT_EQ(ERR_GID, multiuser_get_cache_gid(10, 0));
        EXPECT_EQ(ERR_GID, multiuser_get_cache_gid(10, 1000));
        EXPECT_EQ(1020000, multiuser_get_cache_gid(10, 10000));
        EXPECT_EQ(ERR_GID, multiuser_get_cache_gid(10, 50000));
    }

    @Test
    public void testShared() throws Exception {
        EXPECT_EQ(0, multiuser_get_shared_gid(0, 0));
        EXPECT_EQ(1000, multiuser_get_shared_gid(0, 1000));
        EXPECT_EQ(50000, multiuser_get_shared_gid(0, 10000));
        EXPECT_EQ(ERR_GID, multiuser_get_shared_gid(0, 50000));
        EXPECT_EQ(0, multiuser_get_shared_gid(10, 0));
        EXPECT_EQ(1000, multiuser_get_shared_gid(10, 1000));
        EXPECT_EQ(50000, multiuser_get_shared_gid(10, 10000));
        EXPECT_EQ(ERR_GID, multiuser_get_shared_gid(10, 50000));
    }

    private static void EXPECT_EQ(int expected, int actual) {
        assertEquals(expected, actual);
    }

    private static int multiuser_get_uid(int userId, int appId) {
        return getUid(userId, appId);
    }

    private static int multiuser_get_cache_gid(int userId, int appId) {
        return getCacheAppGid(userId, appId);
    }

    private static int multiuser_get_shared_gid(int userId, int appId) {
        return getSharedAppGid(userId, appId);
    }

    private static int multiuser_get_user_id(int uid) {
        return getUserId(uid);
    }

    private static int multiuser_get_app_id(int uid) {
        return getAppId(uid);
    }
}
