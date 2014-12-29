/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Tests for application restrictions persisting via profile owner:
 *   make -j FrameworksServicesTests
 *   runtest --path frameworks/base/services/tests/servicestests/ \
 *       src/com/android/server/devicepolicy/ApplicationRestrictionsTest.java
 */
public class ApplicationRestrictionsTest extends AndroidTestCase {

    static DevicePolicyManager sDpm;
    static ComponentName sAdminReceiver;
    private static final String RESTRICTED_APP = "com.example.restrictedApp";
    static boolean sAddBack = false;

    public static class AdminReceiver extends DeviceAdminReceiver {

        @Override
        public void onDisabled(Context context, Intent intent) {
            if (sAddBack) {
                sDpm.setActiveAdmin(sAdminReceiver, false);
                sAddBack = false;
            }

            super.onDisabled(context, intent);
        }
    }

    @Override
    public void setUp() {
        final Context context = getContext();
        sAdminReceiver = new ComponentName(mContext.getPackageName(),
                AdminReceiver.class.getName());
        sDpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        Settings.Secure.putInt(context.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0);
        sDpm.setProfileOwner(sAdminReceiver, "Test", UserHandle.myUserId());
        Settings.Secure.putInt(context.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 1);
        // Remove the admin if already registered. It's async, so add it back
        // when the admin gets a broadcast. Otherwise add it back right away.
        if (sDpm.isAdminActive(sAdminReceiver)) {
            sAddBack = true;
            sDpm.removeActiveAdmin(sAdminReceiver);
        } else {
            sDpm.setActiveAdmin(sAdminReceiver, false);
        }
    }

    @Override
    public void tearDown() {
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0);
        sDpm.removeActiveAdmin(sAdminReceiver);
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 1);
    }

    public void testSettingRestrictions() {
        Bundle restrictions = new Bundle();
        restrictions.putString("KEY_STRING", "Foo");
        assertNotNull(sDpm.getApplicationRestrictions(sAdminReceiver, RESTRICTED_APP));
        sDpm.setApplicationRestrictions(sAdminReceiver, RESTRICTED_APP, restrictions);
        Bundle returned = sDpm.getApplicationRestrictions(sAdminReceiver, RESTRICTED_APP);
        assertNotNull(returned);
        assertEquals(returned.size(), 1);
        assertEquals(returned.get("KEY_STRING"), "Foo");
        sDpm.setApplicationRestrictions(sAdminReceiver, RESTRICTED_APP, new Bundle());
        returned = sDpm.getApplicationRestrictions(sAdminReceiver, RESTRICTED_APP);
        assertEquals(returned.size(), 0);
    }

    public void testRestrictionTypes() {
        Bundle restrictions = new Bundle();
        restrictions.putString("KEY_STRING", "Foo");
        restrictions.putInt("KEY_INT", 7);
        restrictions.putBoolean("KEY_BOOLEAN", true);
        restrictions.putBoolean("KEY_BOOLEAN_2", false);
        restrictions.putString("KEY_STRING_2", "Bar");
        restrictions.putStringArray("KEY_STR_ARRAY", new String[] { "Foo", "Bar" });
        sDpm.setApplicationRestrictions(sAdminReceiver, RESTRICTED_APP, restrictions);
        Bundle returned = sDpm.getApplicationRestrictions(sAdminReceiver, RESTRICTED_APP);
        assertTrue(returned.getBoolean("KEY_BOOLEAN"));
        assertFalse(returned.getBoolean("KEY_BOOLEAN_2"));
        assertFalse(returned.getBoolean("KEY_BOOLEAN_3"));
        assertEquals(returned.getInt("KEY_INT"), 7);
        assertTrue(returned.get("KEY_BOOLEAN") instanceof Boolean);
        assertTrue(returned.get("KEY_INT") instanceof Integer);
        assertEquals(returned.get("KEY_STRING"), "Foo");
        assertEquals(returned.get("KEY_STRING_2"), "Bar");
        assertTrue(returned.getStringArray("KEY_STR_ARRAY") instanceof String[]);
        sDpm.setApplicationRestrictions(sAdminReceiver, RESTRICTED_APP, new Bundle());
    }

    public void testTextEscaping() {
        String fancyText = "<This contains XML/> <JSON> "
                + "{ \"One\": { \"OneOne\": \"11\", \"OneTwo\": \"12\" }, \"Two\": \"2\" } <JSON/>";
        Bundle restrictions = new Bundle();
        restrictions.putString("KEY_FANCY_TEXT", fancyText);
        sDpm.setApplicationRestrictions(sAdminReceiver, RESTRICTED_APP, restrictions);
        Bundle returned = sDpm.getApplicationRestrictions(sAdminReceiver, RESTRICTED_APP);
        assertEquals(returned.getString("KEY_FANCY_TEXT"), fancyText);
    }
}
