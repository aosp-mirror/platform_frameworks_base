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

package com.android.providers.settings;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@LargeTest
public class InstallNonMarketAppsDeprecationTest extends BaseSettingsProviderTest {

    private static final String TAG = InstallNonMarketAppsDeprecationTest.class.getSimpleName();
    private static final long USER_RESTRICTION_CHANGE_TIMEOUT = 5000;

    private UserManager mUm;
    private boolean mHasUserRestriction;
    private boolean mSystemSetUserRestriction;
    private List<Integer> mUsersAddedByTest;

    private String waitTillValueChanges(String errorMessage, String oldValue) {
        boolean interrupted = false;
        final long startTime = SystemClock.uptimeMillis();
        String newValue = getSetting(SETTING_TYPE_SECURE, Settings.Secure.INSTALL_NON_MARKET_APPS);
        while (newValue.equals(oldValue) && SystemClock.uptimeMillis() <= (startTime
                + USER_RESTRICTION_CHANGE_TIMEOUT)) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException exc) {
                interrupted = true;
            }
            newValue = getSetting(SETTING_TYPE_SECURE, Settings.Secure.INSTALL_NON_MARKET_APPS);
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        assertFalse(errorMessage, oldValue.equals(newValue));
        return newValue;
    }

    private String getSecureSettingForUserViaShell(int userId) throws IOException {
        StringBuilder sb = new StringBuilder("settings get --user ");
        sb.append(userId + " secure ");
        sb.append(Settings.Secure.INSTALL_NON_MARKET_APPS);
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                        sb.toString()).getFileDescriptor())));
        String line = reader.readLine();
        return line.trim();
    }

    @Before
    public void setUp() {
        mUm = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        mHasUserRestriction = mUm.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        mSystemSetUserRestriction = mUm.getUserRestrictionSource(
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, Process.myUserHandle())
                == UserManager.RESTRICTION_SOURCE_SYSTEM;
        mUsersAddedByTest = new ArrayList<>();
    }

    @Test
    public void testValueDefaults() throws Exception {
        if (mHasUserRestriction) {
            // Default values don't apply when user restriction is set. Pass.
            Log.w(TAG, "User restriction for unknown sources set. Skipping testValueDefaults test");
            return;
        }
        String value = getSetting(SETTING_TYPE_SECURE, Settings.Secure.INSTALL_NON_MARKET_APPS);
        assertEquals("install_non_market_apps should be 1", value, "1");

        setSettingViaShell(SETTING_TYPE_SECURE, Settings.Secure.INSTALL_NON_MARKET_APPS, "0",
                false);
        resetSettingsViaShell(SETTING_TYPE_SECURE, Settings.RESET_MODE_TRUSTED_DEFAULTS);

        value = getSetting(SETTING_TYPE_SECURE, Settings.Secure.INSTALL_NON_MARKET_APPS);
        assertEquals("install_non_market_apps not reset to 1", value, "1");
    }

    @Test
    public void testValueForNewUser() throws Exception {
        UserInfo newUser = mUm.createUser("TEST_USER", 0);
        mUsersAddedByTest.add(newUser.id);
        String value = getSecureSettingForUserViaShell(newUser.id);
        assertEquals("install_non_market_apps should be 1 for a new user", value, "1");
    }

    @Test
    public void testValueRespectsUserRestriction() {
        String value = getSetting(SETTING_TYPE_SECURE, Settings.Secure.INSTALL_NON_MARKET_APPS);
        assertEquals(value, mHasUserRestriction ? "0" : "1");

        if (mHasUserRestriction && !mSystemSetUserRestriction) {
            // User restriction set by device policy. This case should be covered in DO/PO related
            // tests. Pass.
            Log.w(TAG, "User restriction set by PO/DO. Skipping testValueRespectsUserRestriction");
            return;
        }

        mUm.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, !mHasUserRestriction);
        value = waitTillValueChanges(
                "Changing user restriction did not change the value of install_non_market_apps",
                value);
        assertTrue("Invalid value", value.equals("1") || value.equals("0"));

        mUm.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, mHasUserRestriction);
        value = waitTillValueChanges(
                "Changing user restriction did not change the value of install_non_market_apps",
                value);
        assertTrue("Invalid value", value.equals("1") || value.equals("0"));
    }

    @After
    public void tearDown() {
        if (!mHasUserRestriction || mSystemSetUserRestriction) {
            // The test may have modified the user restriction state. Restore it.
            mUm.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                    mHasUserRestriction);
        }
        for (int userId : mUsersAddedByTest) {
            mUm.removeUser(userId);
        }
    }
}
