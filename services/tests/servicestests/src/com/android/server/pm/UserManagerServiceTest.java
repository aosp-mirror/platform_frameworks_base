/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.os.UserManager.DISALLOW_USER_SWITCH;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityManager;
import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Looper;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Postsubmit;
import android.util.AtomicFile;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Test {@link UserManagerService} functionality. */
@Postsubmit
@RunWith(AndroidJUnit4.class)
public class UserManagerServiceTest {
    private static String[] STRING_ARRAY = new String[] {"<tag", "<![CDATA["};
    private File restrictionsFile;
    private int tempUserId = UserHandle.USER_NULL;
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private UserManagerService mUserManagerService;

    @Before
    public void setup() throws Exception {
        // Currently UserManagerService cannot be instantiated twice inside a VM without a cleanup
        // TODO: Remove once UMS supports proper dependency injection
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();

        LocalServices.removeServiceForTest(UserManagerInternal.class);
        mUserManagerService = new UserManagerService(InstrumentationRegistry.getContext());
        // Put the current user to mUsers. UMS can't find userlist.xml, and fallbackToSingleUserLP.
        mUserManagerService.putUserInfo(
                new UserInfo(ActivityManager.getCurrentUser(), "Current User", 0));
        restrictionsFile = new File(mContext.getCacheDir(), "restrictions.xml");
        restrictionsFile.delete();
    }

    @After
    public void teardown() throws Exception {
        restrictionsFile.delete();
        if (tempUserId != UserHandle.USER_NULL) {
            UserManager.get(mContext).removeUser(tempUserId);
        }
    }

    @Test
    public void testWriteReadApplicationRestrictions() throws IOException {
        AtomicFile atomicFile = new AtomicFile(restrictionsFile);
        Bundle bundle = createBundle();
        UserManagerService.writeApplicationRestrictionsLAr(bundle, atomicFile);
        assertThat(atomicFile.getBaseFile().exists()).isTrue();
        String s = FileUtils.readTextFile(restrictionsFile, 10000, "");
        System.out.println("restrictionsFile: " + s);
        bundle = UserManagerService.readApplicationRestrictionsLAr(atomicFile);
        System.out.println("readApplicationRestrictionsLocked bundle: " + bundle);
        assertBundle(bundle);
    }

    @Test
    public void testAddUserWithAccount() {
        UserManager um = UserManager.get(mContext);
        UserInfo user = um.createUser("Test User", 0);
        assertThat(user).isNotNull();
        tempUserId = user.id;
        String accountName = "Test Account";
        um.setUserAccount(tempUserId, accountName);
        assertThat(um.getUserAccount(tempUserId)).isEqualTo(accountName);
    }

    @Test
    public void testUserSystemPackageWhitelist() throws Exception {
        String cmd = "cmd user report-system-user-package-whitelist-problems --critical-only";
        final String result = runShellCommand(cmd);
        assertThat(result).isEmpty();
    }

    private Bundle createBundle() {
        Bundle result = new Bundle();
        // Tests for 6 allowed types: Integer, Boolean, String, String[], Bundle and Parcelable[]
        result.putBoolean("boolean_0", false);
        result.putBoolean("boolean_1", true);
        result.putInt("integer", 100);
        result.putString("empty", "");
        result.putString("string", "text");
        result.putStringArray("string[]", STRING_ARRAY);

        Bundle bundle = new Bundle();
        bundle.putString("bundle_string", "bundle_string");
        bundle.putInt("bundle_int", 1);
        result.putBundle("bundle", bundle);

        Bundle[] bundleArray = new Bundle[2];
        bundleArray[0] = new Bundle();
        bundleArray[0].putString("bundle_array_string", "bundle_array_string");
        bundleArray[0].putBundle("bundle_array_bundle", bundle);
        bundleArray[1] = new Bundle();
        bundleArray[1].putString("bundle_array_string2", "bundle_array_string2");
        result.putParcelableArray("bundle_array", bundleArray);
        return result;
    }

    private void assertBundle(Bundle bundle) {
        assertThat(bundle.getBoolean("boolean_0")).isFalse();
        assertThat(bundle.getBoolean("boolean_1")).isTrue();
        assertThat(bundle.getInt("integer")).isEqualTo(100);
        assertThat(bundle.getString("empty")).isEqualTo("");
        assertThat(bundle.getString("string")).isEqualTo("text");
        assertThat(Arrays.asList(bundle.getStringArray("string[]")))
                .isEqualTo(Arrays.asList(STRING_ARRAY));
        Parcelable[] bundle_array = bundle.getParcelableArray("bundle_array");
        assertThat(bundle_array.length).isEqualTo(2);
        Bundle bundle1 = (Bundle) bundle_array[0];
        assertThat(bundle1.getString("bundle_array_string"))
                .isEqualTo("bundle_array_string");
        assertThat(bundle1.getBundle("bundle_array_bundle")).isNotNull();
        Bundle bundle2 = (Bundle) bundle_array[1];
        assertThat(bundle2.getString("bundle_array_string2"))
                .isEqualTo("bundle_array_string2");
        Bundle childBundle = bundle.getBundle("bundle");
        assertThat(childBundle.getString("bundle_string"))
                .isEqualTo("bundle_string");
        assertThat(childBundle.getInt("bundle_int")).isEqualTo(1);
    }

    @Test
    public void assertHasUserRestriction() throws Exception {
        int userId = ActivityManager.getCurrentUser();

        mUserManagerService.setUserRestriction(DISALLOW_USER_SWITCH, true, userId);
        assertThat(mUserManagerService.hasUserRestriction(DISALLOW_USER_SWITCH, userId)).isTrue();

        mUserManagerService.setUserRestriction(DISALLOW_USER_SWITCH, false, userId);
        assertThat(mUserManagerService.hasUserRestriction(DISALLOW_USER_SWITCH, userId)).isFalse();
    }

    @Test
    public void testHasUserRestriction_NonExistentUserReturnsFalse() {
        int nonExistentUserId = UserHandle.USER_NULL;
        assertThat(mUserManagerService.hasUserRestriction(DISALLOW_USER_SWITCH, nonExistentUserId))
                .isFalse();
    }

    @Test
    public void testSetUserRestrictionWithIncorrectID() throws Exception {
        int incorrectId = 1;
        while (mUserManagerService.userExists(incorrectId)) {
            incorrectId++;
        }
        assertThat(mUserManagerService.hasUserRestriction(DISALLOW_USER_SWITCH, incorrectId))
                .isFalse();
        mUserManagerService.setUserRestriction(DISALLOW_USER_SWITCH, true, incorrectId);
        assertThat(mUserManagerService.hasUserRestriction(DISALLOW_USER_SWITCH, incorrectId))
                .isFalse();
    }

    private static String runShellCommand(String cmd) throws Exception {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand(cmd);
    }
}
