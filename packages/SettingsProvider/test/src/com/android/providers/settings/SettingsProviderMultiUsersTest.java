/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.settings;

import static android.provider.Settings.Secure.ACCESSIBILITY_ENABLED;
import static android.provider.Settings.Secure.SYNC_PARENT_SOUNDS;
import static android.provider.Settings.System.RINGTONE;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.PackageManager;
import android.support.test.uiautomator.UiDevice;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bedstead.enterprise.annotations.EnsureHasNoWorkProfile;
import com.android.bedstead.enterprise.annotations.EnsureHasWorkProfile;
import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.EnsureHasSecondaryUser;
import com.android.bedstead.harrier.annotations.RequireFeature;
import com.android.bedstead.harrier.annotations.RequireRunOnInitialUser;
import com.android.bedstead.harrier.annotations.RequireRunOnPrimaryUser;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.nene.users.UserType;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(BedsteadJUnit4.class)
@RequireRunOnPrimaryUser
public class SettingsProviderMultiUsersTest {

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final String SETTINGS = "some_random_setting";

    private static final String GET_SHELL_COMMAND = "settings get --user ";
    private static final String SET_SHELL_COMMAND = "settings put --user ";
    private static final String DELETE_SHELL_COMMAND = "settings delete --user ";

    private static final String SPACE_GLOBAL = "global";
    private static final String SPACE_SYSTEM = "system";
    private static final String SPACE_SECURE = "secure";

    private static final String CLONE_TO_MANAGED_PROFILE_SETTING = ACCESSIBILITY_ENABLED;
    private static final String CLONE_FROM_PARENT_SETTINGS = RINGTONE;
    private static final String SYNC_FROM_PARENT_SETTINGS = SYNC_PARENT_SOUNDS;

    private UiDevice mUiDevice;
    private UserReference mPrimaryUser;

    @Before
    public void setUp() throws Exception {
        mPrimaryUser = sDeviceState.initialUser();

        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Test
    @RequireFeature(PackageManager.FEATURE_MANAGED_USERS)
    @EnsureHasWorkProfile
    public void testSettings_workProfile() throws Exception {
        UserReference profile = sDeviceState.workProfile();

        // Settings.Global settings are shared between different users
        assertSettingsShared(SPACE_GLOBAL, mPrimaryUser.id(), profile.id());
        // Settings.System and Settings.Secure settings can be different on different users
        assertSettingsDifferent(SPACE_SYSTEM, mPrimaryUser.id(), profile.id());
        assertSettingsDifferent(SPACE_SECURE, mPrimaryUser.id(), profile.id());
    }

    @Test
    @RequireFeature(PackageManager.FEATURE_MANAGED_USERS)
    @RequireRunOnInitialUser
    @EnsureHasSecondaryUser
    public void testSettings_secondaryUser() throws Exception {
        UserReference secondaryUser = sDeviceState.secondaryUser();

        // Settings.Global settings are shared between different users
        assertSettingsShared(SPACE_GLOBAL, mPrimaryUser.id(), secondaryUser.id());
        // Settings.System and Settings.Secure settings can be different on different users
        assertSettingsDifferent(SPACE_SYSTEM, mPrimaryUser.id(), secondaryUser.id());
        assertSettingsDifferent(SPACE_SECURE, mPrimaryUser.id(), secondaryUser.id());
    }

    private void assertSettingsDifferent(String type, int userId1, int userId2) throws Exception {
        // reset settings
        setSetting(type, SETTINGS, "noValue", userId2);
        waitForIdle();

        // set the value with user 1
        setSetting(type, SETTINGS, "value1", userId1);
        waitForIdle();

        // check no value with user 2
        String value = getSetting(type, SETTINGS, userId2);
        assertThat(value).startsWith("noValue");

        // set the value with user 2
        setSetting(type, SETTINGS, "value2", userId2);
        waitForIdle();

        // check the value with user 1 is not changed
        value = getSetting(type, SETTINGS, userId1);
        assertThat(value).startsWith("value1");
    }

    private void assertSettingsShared(String type, int userId1, int userId2) throws Exception {
        // reset settings
        setSetting(type, SETTINGS, "noValue", userId2);
        waitForIdle();

        // set the value with user 1
        setSetting(type, SETTINGS, "value1", userId1);
        waitForIdle();

        // check no value with user 2
        String value = getSetting(type, SETTINGS, userId2);
        assertThat(value).startsWith("value1");

        // set the value with user 2
        setSetting(type, SETTINGS, "value2", userId2);
        waitForIdle();

        // check the value with user 1 is not changed
        value = getSetting(type, SETTINGS, userId1);
        assertThat(value).startsWith("value2");
    }

    @Test
    @RequireFeature(PackageManager.FEATURE_MANAGED_USERS)
    @EnsureHasNoWorkProfile
    public void testSettings_profile_cloneToManagedProfile() throws Exception {
        assertSettingsCloned(SPACE_SECURE, CLONE_TO_MANAGED_PROFILE_SETTING, false);
    }

    @Test
    @RequireFeature(PackageManager.FEATURE_MANAGED_USERS)
    @EnsureHasNoWorkProfile
    public void testSettings_profile_cloneFromParent() throws Exception {
        assertSettingsCloned(SPACE_SYSTEM, CLONE_FROM_PARENT_SETTINGS, true);
    }

    private void assertSettingsCloned(String type, String name, boolean isSyncSettings)
            throws Exception {
        boolean resetSyncValue = false;
        if (isSyncSettings) {
            // set to sync settings from parent
            final String oldSyncValue =
                    getSetting(SPACE_SECURE, SYNC_FROM_PARENT_SETTINGS, mPrimaryUser.id());
            resetSyncValue = oldSyncValue.startsWith("0");
            if (resetSyncValue) {
                setSetting(SPACE_SECURE, SYNC_FROM_PARENT_SETTINGS, "1", mPrimaryUser.id());
                waitForIdle();
            }
        }

        final String oldValue = getSetting(type, name, mPrimaryUser.id());

        try (UserReference myProfile = TestApis.users().createUser()
                .parent(mPrimaryUser)
                .type(TestApis.users().supportedType(UserType.MANAGED_PROFILE_TYPE_NAME))
                .createAndStart()) {
            String value = getSetting(type, name, myProfile.id());
            assertThat(value).isEqualTo(oldValue);

            String newValue;
            if (isSyncSettings) {
                newValue = generateNewValue(oldValue);
            } else {
                newValue = oldValue.startsWith("0") ? "1" : "0";
            }

            setSetting(type, name, newValue, mPrimaryUser.id());
            waitForIdle();

            value = getSetting(type, name, myProfile.id());
            assertThat(value).startsWith(newValue);
        } finally {
            // reset settings
            setSetting(type, name, oldValue, mPrimaryUser.id());
            if (resetSyncValue) {
                setSetting(SPACE_SECURE, SYNC_FROM_PARENT_SETTINGS, "0", mPrimaryUser.id());
            }
        }
    }

    private String generateNewValue(String oldValue) {
        String newValue = oldValue.replace("\n", "");
        if (newValue.endsWith("0")) {
            final int size = newValue.length();
            newValue = newValue.substring(0, size - 1) + "1";
        } else {
            final int size = newValue.length();
            newValue = newValue.substring(0, size - 1) + "0";
        }
        return newValue;
    }

    @Test
    @RequireRunOnInitialUser
    @EnsureHasSecondaryUser
    public void testSettings_stopAndRestartSecondaryUser() throws Exception {
        UserReference secondaryUser = sDeviceState.secondaryUser();

        assertSettingsDifferent(SPACE_SECURE, mPrimaryUser.id(), secondaryUser.id());

        secondaryUser.stop();

        assertSettingsDifferent(SPACE_SECURE, mPrimaryUser.id(), secondaryUser.id());

        secondaryUser.start();

        assertSettingsDifferent(SPACE_SECURE, mPrimaryUser.id(), secondaryUser.id());
    }

    private void waitForIdle() {
        final UiDevice uiDevice = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        uiDevice.waitForIdle();
    }

    private String getSetting(String type, String name, int userId) throws Exception {
        return executeShellCmd(GET_SHELL_COMMAND + userId + " " +  type + " " + name);
    }

    private void setSetting(String type, String name, String setting, int userId)
            throws Exception {
        setSetting(name, setting, userId + " " + type);
    }

    private void setSetting(String name, String setting, String type) throws Exception {
        if (setting == null || setting.equals("null")) {
            executeShellCmd(DELETE_SHELL_COMMAND + type + " " + name);
        } else {
            setting = setting.replace("\n", "");
            executeShellCmd(SET_SHELL_COMMAND + type + " " + name + " " + setting);
        }
    }

    private String executeShellCmd(String command) throws IOException {
        return mUiDevice.executeShellCommand(command);
    }
}
