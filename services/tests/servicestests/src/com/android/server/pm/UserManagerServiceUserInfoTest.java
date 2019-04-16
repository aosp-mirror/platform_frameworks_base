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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.UserInfo;
import android.os.Looper;
import android.os.Parcel;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerService.UserData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * <p>Run with:<pre>
 * runtest -c com.android.server.pm.UserManagerServiceUserInfoTest frameworks-services
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class UserManagerServiceUserInfoTest {
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

    @Test
    public void testWriteReadUserInfo() throws Exception {
        UserData data = new UserData();
        data.info = createUser();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        mUserManagerService.writeUserLP(data, out);
        byte[] bytes = baos.toByteArray();

        UserData read = mUserManagerService.readUserLP(
                data.info.id, new ByteArrayInputStream(bytes));

        assertUserInfoEquals(data.info, read.info);
    }

    @Test
    public void testParcelUnparcelUserInfo() throws Exception {
        UserInfo info = createUser();

        Parcel out = Parcel.obtain();
        info.writeToParcel(out, 0);
        byte[] data = out.marshall();
        out.recycle();

        Parcel in = Parcel.obtain();
        in.unmarshall(data, 0, data.length);
        in.setDataPosition(0);
        UserInfo read = UserInfo.CREATOR.createFromParcel(in);
        in.recycle();

        assertUserInfoEquals(info, read);
    }

    @Test
    public void testGetUserName() throws Exception {
        assertFalse("System user name shouldn't be set",
                mUserManagerService.isUserNameSet(UserHandle.USER_SYSTEM));
        UserInfo userInfo = mUserManagerService.getUserInfo(UserHandle.USER_SYSTEM);
        assertFalse("A system provided name should be returned for primary user",
                TextUtils.isEmpty(userInfo.name));

        userInfo = createUser();
        userInfo.partial = false;
        final int TEST_ID = 100;
        userInfo.id = TEST_ID;
        mUserManagerService.putUserInfo(userInfo);
        assertTrue("Test user name must be set", mUserManagerService.isUserNameSet(TEST_ID));
        assertEquals("A Name", mUserManagerService.getUserInfo(TEST_ID).name);
    }

    private UserInfo createUser() {
        UserInfo user = new UserInfo(/*id*/ 21, "A Name", "A path", /*flags*/ 0x0ff0ff);
        user.serialNumber = 5;
        user.creationTime = 4L << 32;
        user.lastLoggedInTime = 5L << 32;
        user.lastLoggedInFingerprint = "afingerprint";
        user.profileGroupId = 45;
        user.restrictedProfileParentId = 4;
        user.profileBadge = 2;
        user.partial = true;
        user.guestToRemove = true;
        return user;
    }

    private void assertUserInfoEquals(UserInfo one, UserInfo two) {
        assertEquals("Id not preserved", one.id, two.id);
        assertEquals("Name not preserved", one.name, two.name);
        assertEquals("Icon path not preserved", one.iconPath, two.iconPath);
        assertEquals("Flags not preserved", one.flags, two.flags);
        assertEquals("profile group not preserved", one.profileGroupId,
                two.profileGroupId);
        assertEquals("restricted profile parent not preseved", one.restrictedProfileParentId,
                two.restrictedProfileParentId);
        assertEquals("profile badge not preseved", one.profileBadge, two.profileBadge);
        assertEquals("partial not preseved", one.partial, two.partial);
        assertEquals("guestToRemove not preseved", one.guestToRemove, two.guestToRemove);
    }
}
