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

import static android.content.pm.UserInfo.FLAG_DEMO;
import static android.content.pm.UserInfo.FLAG_DISABLED;
import static android.content.pm.UserInfo.FLAG_EPHEMERAL;
import static android.content.pm.UserInfo.FLAG_FULL;
import static android.content.pm.UserInfo.FLAG_GUEST;
import static android.content.pm.UserInfo.FLAG_INITIALIZED;
import static android.content.pm.UserInfo.FLAG_MAIN;
import static android.content.pm.UserInfo.FLAG_MANAGED_PROFILE;
import static android.content.pm.UserInfo.FLAG_PROFILE;
import static android.content.pm.UserInfo.FLAG_RESTRICTED;
import static android.content.pm.UserInfo.FLAG_SYSTEM;
import static android.os.UserManager.USER_TYPE_FULL_DEMO;
import static android.os.UserManager.USER_TYPE_FULL_GUEST;
import static android.os.UserManager.USER_TYPE_FULL_RESTRICTED;
import static android.os.UserManager.USER_TYPE_FULL_SECONDARY;
import static android.os.UserManager.USER_TYPE_FULL_SYSTEM;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;
import static android.os.UserManager.USER_TYPE_SYSTEM_HEADLESS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.UserIdInt;
import android.app.PropertyInvalidatedCache;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.os.Looper;
import android.os.Parcel;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
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
@Presubmit
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
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();

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

        assertUserInfoEquals(data.info, read.info, /* parcelCopy= */ false);
    }

    /** Tests that device policy restrictions are written/read properly. */
    @Test
    public void testWriteReadDevicePolicyUserRestrictions() throws Exception {
        final String globalRestriction = UserManager.DISALLOW_FACTORY_RESET;
        final String localRestriction = UserManager.DISALLOW_CONFIG_DATE_TIME;

        UserData data = new UserData();
        data.info = createUser(100, FLAG_FULL, "A type");

        mUserManagerService.putUserInfo(data.info);

        // Set a global and user restriction so they get written out to the user file.
        setUserRestrictions(data.info.id, globalRestriction, localRestriction, true);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        mUserManagerService.writeUserLP(data, out);
        byte[] bytes = baos.toByteArray();

        // Clear the restrictions to see if they are properly read in from the user file.
        setUserRestrictions(data.info.id, globalRestriction, localRestriction, false);

        mUserManagerService.readUserLP(data.info.id, new ByteArrayInputStream(bytes));
        assertTrue(mUserManagerService.hasUserRestrictionOnAnyUser(globalRestriction));
        assertTrue(mUserManagerService.hasUserRestrictionOnAnyUser(localRestriction));
    }

    /** Sets a global and local restriction and verifies they were set properly **/
    private void setUserRestrictions(int id, String global, String local, boolean enabled) {
        mUserManagerService.setUserRestrictionInner(UserHandle.USER_ALL, global, enabled);
        assertEquals(mUserManagerService.hasUserRestrictionOnAnyUser(global), enabled);

        mUserManagerService.setUserRestrictionInner(id, local, enabled);
        assertEquals(mUserManagerService.hasUserRestrictionOnAnyUser(local), enabled);
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

        assertUserInfoEquals(info, read, /* parcelCopy= */ true);
    }

    @Test
    public void testCopyConstructor() throws Exception {
        UserInfo info = createUser();

        UserInfo copy = new UserInfo(info);

        assertUserInfoEquals(info, copy, /* parcelCopy= */ false);
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

    /** Test UMS.isUserOfType(). */
    @Test
    public void testIsUserOfType() throws Exception {
        assertTrue("System user was of invalid type",
                mUserManagerService.isUserOfType(UserHandle.USER_SYSTEM, USER_TYPE_SYSTEM_HEADLESS)
                || mUserManagerService.isUserOfType(UserHandle.USER_SYSTEM, USER_TYPE_FULL_SYSTEM));

        final int testId = 100;
        final String typeName = "A type";
        UserInfo userInfo = createUser(testId, 0, typeName);
        mUserManagerService.putUserInfo(userInfo);
        assertTrue(mUserManagerService.isUserOfType(testId, typeName));
    }

    /** Test UserInfo.supportsSwitchTo() for partial user. */
    @Test
    public void testSupportSwitchTo_partial() throws Exception {
        UserInfo userInfo = createUser(100, FLAG_FULL, null);
        userInfo.partial = true;
        assertFalse("Switching to a partial user should be disabled",
                userInfo.supportsSwitchTo());
    }

    /** Test UserInfo.supportsSwitchTo() for disabled user. */
    @Test
    public void testSupportSwitchTo_disabled() throws Exception {
        UserInfo userInfo = createUser(100, FLAG_DISABLED, null);
        assertFalse("Switching to a DISABLED user should be disabled",
                userInfo.supportsSwitchTo());
    }

    /** Test UserInfo.supportsSwitchTo() for precreated users. */
    @Test
    public void testSupportSwitchTo_preCreated() throws Exception {
        UserInfo userInfo = createUser(100, FLAG_FULL, null);
        userInfo.preCreated = true;
        assertFalse("Switching to a precreated user should be disabled",
                userInfo.supportsSwitchTo());

        userInfo.preCreated = false;
        assertTrue("Switching to a full, real user should be allowed", userInfo.supportsSwitchTo());
    }

    /** Test UserInfo.supportsSwitchTo() for profiles. */
    @Test
    public void testSupportSwitchTo_profile() throws Exception {
        UserInfo userInfo = createUser(100, FLAG_PROFILE, null);
        assertFalse("Switching to a profiles should be disabled", userInfo.supportsSwitchTo());
    }

    /** Test UserInfo.canHaveProfile for main user */
    @Test
    public void testCanHaveProfile() throws Exception {
        UserInfo userInfo = createUser(100, FLAG_MAIN, null);
        assertTrue("Main users can have profile", userInfo.canHaveProfile());
    }

    /** Tests upgradeIfNecessaryLP (but without locking) for upgrading from version 8 to 9+. */
    @Test
    public void testUpgradeIfNecessaryLP_9() {
        final int versionToTest = 9;
        // do not trigger a user type upgrade
        final int userTypeVersion = UserTypeFactory.getUserTypeVersion();

        mUserManagerService.putUserInfo(createUser(100, FLAG_MANAGED_PROFILE, null));
        mUserManagerService.putUserInfo(createUser(101,
                FLAG_GUEST | FLAG_EPHEMERAL | FLAG_FULL, null));
        mUserManagerService.putUserInfo(createUser(102, FLAG_RESTRICTED | FLAG_FULL, null));
        mUserManagerService.putUserInfo(createUser(103, FLAG_FULL, null));
        mUserManagerService.putUserInfo(createUser(104, FLAG_SYSTEM, null));
        mUserManagerService.putUserInfo(createUser(105, FLAG_SYSTEM | FLAG_FULL, null));
        mUserManagerService.putUserInfo(createUser(106, FLAG_DEMO | FLAG_FULL, null));

        mUserManagerService.upgradeIfNecessaryLP(versionToTest - 1, userTypeVersion);

        assertTrue(mUserManagerService.isUserOfType(100, USER_TYPE_PROFILE_MANAGED));
        assertTrue((mUserManagerService.getUserInfo(100).flags & FLAG_PROFILE) != 0);

        assertTrue(mUserManagerService.isUserOfType(101, USER_TYPE_FULL_GUEST));

        assertTrue(mUserManagerService.isUserOfType(102, USER_TYPE_FULL_RESTRICTED));
        assertTrue((mUserManagerService.getUserInfo(102).flags & FLAG_PROFILE) == 0);

        assertTrue(mUserManagerService.isUserOfType(103, USER_TYPE_FULL_SECONDARY));
        assertTrue((mUserManagerService.getUserInfo(103).flags & FLAG_PROFILE) == 0);

        assertTrue(mUserManagerService.isUserOfType(104, USER_TYPE_SYSTEM_HEADLESS));

        assertTrue(mUserManagerService.isUserOfType(105, USER_TYPE_FULL_SYSTEM));

        assertTrue(mUserManagerService.isUserOfType(106, USER_TYPE_FULL_DEMO));
    }

    /** Creates a UserInfo with the given flags and userType. */
    private UserInfo createUser(@UserIdInt int userId, @UserInfoFlag int flags, String userType) {
        return new UserInfo(userId, "A Name", "A path", flags, userType);
    }

    private UserInfo createUser() {
        UserInfo user = new UserInfo(/*id*/ 21, "A Name", "A path", /*flags*/ 0x0ff0ff, "A type");
        user.serialNumber = 5;
        user.creationTime = 4L << 32;
        user.lastLoggedInTime = 5L << 32;
        user.lastLoggedInFingerprint = "afingerprint";
        user.profileGroupId = 45;
        user.restrictedProfileParentId = 4;
        user.profileBadge = 2;
        user.partial = true;
        user.guestToRemove = true;
        user.preCreated = true;
        user.convertedFromPreCreated = true;
        return user;
    }

    private void assertUserInfoEquals(UserInfo one, UserInfo two, boolean parcelCopy) {
        assertEquals("Id not preserved", one.id, two.id);
        assertEquals("Name not preserved", one.name, two.name);
        assertEquals("Icon path not preserved", one.iconPath, two.iconPath);
        assertEquals("Flags not preserved", one.flags, two.flags);
        assertEquals("UserType not preserved", one.userType, two.userType);
        assertEquals("profile group not preserved", one.profileGroupId,
                two.profileGroupId);
        assertEquals("restricted profile parent not preserved", one.restrictedProfileParentId,
                two.restrictedProfileParentId);
        assertEquals("profile badge not preserved", one.profileBadge, two.profileBadge);
        assertEquals("partial not preserved", one.partial, two.partial);
        assertEquals("guestToRemove not preserved", one.guestToRemove, two.guestToRemove);
        assertEquals("preCreated not preserved", one.preCreated, two.preCreated);
        if (parcelCopy) {
            assertFalse("convertedFromPreCreated should not be set", two.convertedFromPreCreated);
        } else {
            assertEquals("convertedFromPreCreated not preserved", one.convertedFromPreCreated,
                    two.convertedFromPreCreated);
        }
    }

    /** Tests upgrading profile types */
    @Test
    public void testUpgradeProfileType_updateTypeAndFlags() {
        final int userId = 42;
        final String newUserTypeName = "new.user.type";
        final String oldUserTypeName = USER_TYPE_PROFILE_MANAGED;

        UserTypeDetails.Builder oldUserTypeBuilder = new UserTypeDetails.Builder()
                .setName(oldUserTypeName)
                .setBaseType(FLAG_PROFILE)
                .setDefaultUserInfoPropertyFlags(FLAG_MANAGED_PROFILE)
                .setMaxAllowedPerParent(32)
                .setIconBadge(401)
                .setBadgeColors(402, 403, 404)
                .setBadgeLabels(23, 24, 25);
        UserTypeDetails oldUserType = oldUserTypeBuilder.createUserTypeDetails();

        UserInfo userInfo = createUser(userId,
                oldUserType.getDefaultUserInfoFlags() | FLAG_INITIALIZED, oldUserTypeName);
        mUserManagerService.putUserInfo(userInfo);

        UserTypeDetails.Builder newUserTypeBuilder = new UserTypeDetails.Builder()
                .setName(newUserTypeName)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(32)
                .setIconBadge(401)
                .setBadgeColors(402, 403, 404)
                .setBadgeLabels(23, 24, 25);
        UserTypeDetails newUserType = newUserTypeBuilder.createUserTypeDetails();

        mUserManagerService.upgradeProfileToTypeLU(userInfo, newUserType);

        assertTrue(mUserManagerService.isUserOfType(userId, newUserTypeName));
        assertTrue((mUserManagerService.getUserInfo(userId).flags & FLAG_PROFILE) != 0);
        assertTrue((mUserManagerService.getUserInfo(userId).flags & FLAG_MANAGED_PROFILE) == 0);
        assertTrue((mUserManagerService.getUserInfo(userId).flags & FLAG_INITIALIZED) != 0);
    }

    @Test
    public void testUpgradeProfileType_updateRestrictions() {
        final int userId = 42;
        final String newUserTypeName = "new.user.type";
        final String oldUserTypeName = USER_TYPE_PROFILE_MANAGED;

        UserTypeDetails.Builder oldUserTypeBuilder = new UserTypeDetails.Builder()
                .setName(oldUserTypeName)
                .setBaseType(FLAG_PROFILE)
                .setDefaultUserInfoPropertyFlags(FLAG_MANAGED_PROFILE)
                .setMaxAllowedPerParent(32)
                .setIconBadge(401)
                .setBadgeColors(402, 403, 404)
                .setBadgeLabels(23, 24, 25);
        UserTypeDetails oldUserType = oldUserTypeBuilder.createUserTypeDetails();

        UserInfo userInfo = createUser(userId, oldUserType.getDefaultUserInfoFlags(),
                oldUserTypeName);
        mUserManagerService.putUserInfo(userInfo);
        mUserManagerService.setUserRestriction(UserManager.DISALLOW_CAMERA, true, userId);
        mUserManagerService.setUserRestriction(UserManager.DISALLOW_PRINTING, true, userId);

        UserTypeDetails.Builder newUserTypeBuilder = new UserTypeDetails.Builder()
                .setName(newUserTypeName)
                .setBaseType(FLAG_PROFILE)
                .setMaxAllowedPerParent(32)
                .setIconBadge(401)
                .setBadgeColors(402, 403, 404)
                .setBadgeLabels(23, 24, 25)
                .setDefaultRestrictions(
                        UserManagerServiceUserTypeTest.makeRestrictionsBundle(
                                UserManager.DISALLOW_WALLPAPER));
        UserTypeDetails newUserType = newUserTypeBuilder.createUserTypeDetails();

        mUserManagerService.upgradeProfileToTypeLU(userInfo, newUserType);

        assertTrue(mUserManagerService.getUserRestrictions(userId).getBoolean(
                UserManager.DISALLOW_PRINTING));
        assertTrue(mUserManagerService.getUserRestrictions(userId).getBoolean(
                UserManager.DISALLOW_CAMERA));
        assertTrue(mUserManagerService.getUserRestrictions(userId).getBoolean(
                UserManager.DISALLOW_WALLPAPER));
    }
}
