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
 * limitations under the License.
 */

package com.android.providers.settings;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for the SettingContentProvider.
 *
 * Before you run this test you must add a secondary user.
 */
public class SettingsProviderTest extends BaseSettingsProviderTest {
    private static final String LOG_TAG = "SettingsProviderTest";

    private static final long WAIT_FOR_SETTING_URI_CHANGE_TIMEOUT_MILLIS = 2000; // 2 sec

    private static final String[] NAME_VALUE_COLUMNS = new String[]{
            Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE
    };

    private final Object mLock = new Object();

    @Test
    public void testSetAndGetGlobalViaFrontEndApiForSystemUser() throws Exception {
        performSetAndGetSettingTestViaFrontEndApi(SETTING_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
    }

    @Test
    public void testSetAndGetGlobalViaFrontEndApiForNonSystemUser() throws Exception {
        final int secondaryUserId = getSecondaryUserId();
        if (secondaryUserId == UserHandle.USER_SYSTEM) {
            Log.w(LOG_TAG, "No secondary user. Skipping "
                    + "testSetAndGetGlobalViaFrontEndApiForNonSystemUser");
            return;
        }
        performSetAndGetSettingTestViaFrontEndApi(SETTING_TYPE_GLOBAL, secondaryUserId);
    }

    @Test
    public void testSetAndGetSecureViaFrontEndApiForSystemUser() throws Exception {
        performSetAndGetSettingTestViaFrontEndApi(SETTING_TYPE_SECURE, UserHandle.USER_SYSTEM);
    }

    @Test
    public void testSetAndGetSecureViaFrontEndApiForNonSystemUser() throws Exception {
        final int secondaryUserId = getSecondaryUserId();
        if (secondaryUserId == UserHandle.USER_SYSTEM) {
            Log.w(LOG_TAG, "No secondary user. Skipping "
                    + "testSetAndGetSecureViaFrontEndApiForNonSystemUser");
            return;
        }
        performSetAndGetSettingTestViaFrontEndApi(SETTING_TYPE_SECURE, secondaryUserId);
    }

    @Test
    public void testSetAndGetSystemViaFrontEndApiForSystemUser() throws Exception {
        performSetAndGetSettingTestViaFrontEndApi(SETTING_TYPE_SYSTEM, UserHandle.USER_SYSTEM);
    }

    @Test
    public void testSetAndGetSystemViaFrontEndApiForNonSystemUser() throws Exception {
        final int secondaryUserId = getSecondaryUserId();
        if (secondaryUserId == UserHandle.USER_SYSTEM) {
            Log.w(LOG_TAG, "No secondary user. Skipping "
                    + "testSetAndGetSystemViaFrontEndApiForNonSystemUser");
            return;
        }
        performSetAndGetSettingTestViaFrontEndApi(SETTING_TYPE_SYSTEM, secondaryUserId);
    }

    @Test
    public void testSetAndGetGlobalViaProviderApi() throws Exception {
        performSetAndGetSettingTestViaProviderApi(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testSetAndGetSecureViaProviderApi() throws Exception {
        performSetAndGetSettingTestViaProviderApi(SETTING_TYPE_SECURE);
    }

    @Test
    public void testSetAndGetSystemViaProviderApi() throws Exception {
        performSetAndGetSettingTestViaProviderApi(SETTING_TYPE_SYSTEM);
    }

    @Test
    public void testSelectAllGlobalViaProviderApi() throws Exception {
        setSettingViaProviderApiAndAssertSuccessfulChange(SETTING_TYPE_GLOBAL,
                FAKE_SETTING_NAME, FAKE_SETTING_VALUE, false);
        try {
            queryAllSettingsViaProviderApiSettingAndAssertSettingPresent(SETTING_TYPE_GLOBAL,
                    FAKE_SETTING_NAME);
        } finally {
            deleteStringViaProviderApi(SETTING_TYPE_GLOBAL, FAKE_SETTING_NAME);
        }
    }

    @Test
    public void testSelectAllSecureViaProviderApi() throws Exception {
        setSettingViaProviderApiAndAssertSuccessfulChange(SETTING_TYPE_SECURE,
                FAKE_SETTING_NAME, FAKE_SETTING_VALUE, false);
        try {
            queryAllSettingsViaProviderApiSettingAndAssertSettingPresent(SETTING_TYPE_SECURE,
                    FAKE_SETTING_NAME);
        } finally {
            deleteStringViaProviderApi(SETTING_TYPE_SECURE, FAKE_SETTING_NAME);
        }
    }

    @Test
    public void testSelectAllSystemViaProviderApi() throws Exception {
        setSettingViaProviderApiAndAssertSuccessfulChange(SETTING_TYPE_SYSTEM,
                FAKE_SETTING_NAME, FAKE_SETTING_VALUE, true);
        try {
            queryAllSettingsViaProviderApiSettingAndAssertSettingPresent(SETTING_TYPE_SYSTEM,
                    FAKE_SETTING_NAME);
        } finally {
            deleteStringViaProviderApi(SETTING_TYPE_SYSTEM, FAKE_SETTING_NAME);
        }
    }

    @Test
    public void testQueryUpdateDeleteGlobalViaProviderApi() throws Exception {
        doTestQueryUpdateDeleteGlobalViaProviderApiForType(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testQueryUpdateDeleteSecureViaProviderApi() throws Exception {
        doTestQueryUpdateDeleteGlobalViaProviderApiForType(SETTING_TYPE_SECURE);
    }

    @Test
    public void testQueryUpdateDeleteSystemViaProviderApi() throws Exception {
        doTestQueryUpdateDeleteGlobalViaProviderApiForType(SETTING_TYPE_SYSTEM);
    }

    @Test
    public void testBulkInsertGlobalViaProviderApi() throws Exception {
        toTestBulkInsertViaProviderApiForType(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testBulkInsertSystemViaProviderApi() throws Exception {
        toTestBulkInsertViaProviderApiForType(SETTING_TYPE_SYSTEM);
    }

    @Test
    public void testBulkInsertSecureViaProviderApi() throws Exception {
        toTestBulkInsertViaProviderApiForType(SETTING_TYPE_SECURE);
    }

    @Test
    public void testAppCannotRunsSystemOutOfMemoryWritingSystemSettings() throws Exception {
        int insertedCount = 0;
        try {
            for (; insertedCount < 1200; insertedCount++) {
                Log.w(LOG_TAG, "Adding app specific setting: " + insertedCount);
                insertStringViaProviderApi(SETTING_TYPE_SYSTEM,
                        String.valueOf(insertedCount), FAKE_SETTING_VALUE, false);
            }
            fail("Adding app specific settings must be bound.");
        } catch (Exception e) {
            // expected
        } finally {
            for (; insertedCount >= 0; insertedCount--) {
                Log.w(LOG_TAG, "Removing app specific setting: " + insertedCount);
                deleteStringViaProviderApi(SETTING_TYPE_SYSTEM,
                        String.valueOf(insertedCount));
            }
        }
    }

    @Test
    public void testQueryStringInBracketsGlobalViaProviderApiForType() throws Exception {
        doTestQueryStringInBracketsViaProviderApiForType(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testQueryStringInBracketsSecureViaProviderApiForType() throws Exception {
        doTestQueryStringInBracketsViaProviderApiForType(SETTING_TYPE_SECURE);
    }

    @Test
    public void testQueryStringInBracketsSystemViaProviderApiForType() throws Exception {
        doTestQueryStringInBracketsViaProviderApiForType(SETTING_TYPE_SYSTEM);
    }

    @Test
    public void testQueryStringWithAppendedNameToUriViaProviderApi() throws Exception {
        // Make sure we have a clean slate.
        deleteStringViaProviderApi(SETTING_TYPE_SYSTEM, FAKE_SETTING_NAME);

        try {
            // Insert the setting.
            final Uri uri = insertStringViaProviderApi(SETTING_TYPE_SYSTEM, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE, false);
            Uri expectUri = Uri.withAppendedPath(getBaseUriForType(SETTING_TYPE_SYSTEM),
                    FAKE_SETTING_NAME);
            assertEquals("Did not get expected Uri.", expectUri, uri);

            // Make sure the first setting is there.
            String firstValue = queryStringViaProviderApi(SETTING_TYPE_SYSTEM, FAKE_SETTING_NAME,
                    false, true);
            assertEquals("Setting must be present", FAKE_SETTING_VALUE, firstValue);
        } finally {
            // Clean up.
            deleteStringViaProviderApi(SETTING_TYPE_SYSTEM, FAKE_SETTING_NAME);
        }
    }

    @Test
    public void testResetModePackageDefaultsGlobal() throws Exception {
        testResetModePackageDefaultsCommon(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testResetModePackageDefaultsSecure() throws Exception {
        testResetModePackageDefaultsCommon(SETTING_TYPE_SECURE);
    }

    private void testResetModePackageDefaultsCommon(int type) throws Exception {
        // Make sure we have a clean slate.
        setSettingViaShell(type, FAKE_SETTING_NAME, null, true);
        try {
            // Set a value but don't make it the default
            setSettingViaShell(type, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE, false);

            // Reset the changes made by the "shell/root" package
            resetToDefaultsViaShell(type, "com.android.shell");
            resetToDefaultsViaShell(type, "root");

            // Make sure the old APIs don't set defaults
            assertNull(getSetting(type, FAKE_SETTING_NAME));

            // Set a value and make it the default
            setSettingViaShell(type, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE, true);
            // Change the setting from the default
            setSettingViaShell(type, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE_2, false);

            // Reset the changes made by this package
            resetToDefaultsViaShell(type, "com.android.shell");
            resetToDefaultsViaShell(type, "root");

            // Make sure the old APIs don't set defaults
            assertEquals(FAKE_SETTING_VALUE, getSetting(type, FAKE_SETTING_NAME));
        } finally {
            // Make sure we have a clean slate.
            setSettingViaShell(type, FAKE_SETTING_NAME, null, true);
        }
    }

    @Test
    public void testResetModePackageDefaultsWithTokensGlobal() throws Exception {
        testResetModePackageDefaultsWithTokensCommon(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testResetModePackageDefaultsWithTokensSecure() throws Exception {
        testResetModePackageDefaultsWithTokensCommon(SETTING_TYPE_SECURE);
    }

    private void testResetModePackageDefaultsWithTokensCommon(int type) throws Exception {
        // Make sure we have a clean slate.
        setSettingViaShell(type, FAKE_SETTING_NAME, null, true);
        setSettingViaShell(type, FAKE_SETTING_NAME_1, null, true);
        try {
            // Set a default value
            setSettingViaShell(type, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE, true);
            // Change the default and associate a token
            setSettingViaShell(type, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE_2, "TOKEN1", false);

            // Set a default value
            setSettingViaShell(type, FAKE_SETTING_NAME_1,
                    FAKE_SETTING_VALUE, "TOKEN2", true);
            // Change the default and associate a token
            setSettingViaShell(type, FAKE_SETTING_NAME_1,
                    FAKE_SETTING_VALUE_2, "TOKEN2", false);

            // Reset settings associated with TOKEN1
            resetToDefaultsViaShell(type, "com.android.shell", "TOKEN1");
            resetToDefaultsViaShell(type, "root", "TOKEN1");

            // Make sure TOKEN1 settings are reset
            assertEquals(FAKE_SETTING_VALUE, getSetting(type,
                    FAKE_SETTING_NAME));

            // Make sure TOKEN2 settings are not reset
            assertEquals(FAKE_SETTING_VALUE_2, getSetting(type,
                    FAKE_SETTING_NAME_1));

            // Reset settings associated with TOKEN2
            resetToDefaultsViaShell(type, "com.android.shell", "TOKEN2");
            resetToDefaultsViaShell(type, "root", "TOKEN2");

            // Make sure TOKEN2 settings are reset
            assertEquals(FAKE_SETTING_VALUE, getSetting(type,
                    FAKE_SETTING_NAME_1));
        } finally {
            // Make sure we have a clean slate.
            setSettingViaShell(type, FAKE_SETTING_NAME, null, true);
            setSettingViaShell(type, FAKE_SETTING_NAME_1, null, true);
        }
    }

    @Test
    public void testResetModeUntrustedDefaultsGlobal() throws Exception {
        testResetModeUntrustedDefaultsCommon(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testResetModeUntrustedDefaultsSecure() throws Exception {
        testResetModeUntrustedDefaultsCommon(SETTING_TYPE_SECURE);
    }

    private void testResetModeUntrustedDefaultsCommon(int type) throws Exception {
        // Make sure we have a clean slate.
        putSetting(type, FAKE_SETTING_NAME, null);
        setSettingViaShell(type, FAKE_SETTING_NAME_1, null, true);
        try {
            // Set a default setting as a trusted component
            putSetting(type, FAKE_SETTING_NAME, FAKE_SETTING_VALUE);
            // Change the setting as a trusted component
            putSetting(type, FAKE_SETTING_NAME, FAKE_SETTING_VALUE_2);

            // Set a default setting as an untrusted component
            setSettingViaShell(type, FAKE_SETTING_NAME_1,
                    FAKE_SETTING_VALUE, true);
            // Change the setting as an untrusted component
            setSettingViaShell(type, FAKE_SETTING_NAME_1,
                    FAKE_SETTING_VALUE_2, false);

            // Reset the untrusted changes to defaults
            resetSettingsViaShell(type,
                    Settings.RESET_MODE_UNTRUSTED_DEFAULTS);

            // Check whether only the untrusted changes set to defaults
            assertEquals(FAKE_SETTING_VALUE_2, getSetting(type, FAKE_SETTING_NAME));
            assertEquals(FAKE_SETTING_VALUE, getSetting(type, FAKE_SETTING_NAME_1));
        } finally {
            // Make sure we have a clean slate.
            putSetting(type, FAKE_SETTING_NAME, null);
            setSettingViaShell(type, FAKE_SETTING_NAME_1, null, true);
        }
    }

    @Test
    public void testResetModeUntrustedClearGlobal() throws Exception {
        testResetModeUntrustedClearCommon(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testResetModeUntrustedClearSecure() throws Exception {
        testResetModeUntrustedClearCommon(SETTING_TYPE_SECURE);
    }

    private void testResetModeUntrustedClearCommon(int type) throws Exception {
        // Make sure we have a clean slate.
        putSetting(type, FAKE_SETTING_NAME, null);
        setSettingViaShell(type, FAKE_SETTING_NAME_1, null, true);
        try {
            // Set a default setting as a trusted component
            putSetting(type, FAKE_SETTING_NAME, FAKE_SETTING_VALUE);
            // Change the setting as a trusted component
            putSetting(type, FAKE_SETTING_NAME, FAKE_SETTING_VALUE_2);

            // Set a default setting as an untrusted component
            setSettingViaShell(type, FAKE_SETTING_NAME_1,
                    FAKE_SETTING_VALUE, true);
            // Change the setting as an untrusted component
            setSettingViaShell(type, FAKE_SETTING_NAME_1,
                    FAKE_SETTING_VALUE_2, false);

            // Clear the untrusted changes
            resetSettingsViaShell(type,
                    Settings.RESET_MODE_UNTRUSTED_CHANGES);

            // Check whether only the untrusted changes set to defaults
            assertEquals(FAKE_SETTING_VALUE_2, getSetting(type, FAKE_SETTING_NAME));
            assertNull(getSetting(type, FAKE_SETTING_NAME_1));
        } finally {
            // Make sure we have a clean slate.
            putSetting(type, FAKE_SETTING_NAME, null);
            setSettingViaShell(type, FAKE_SETTING_NAME_1, null, true);
        }
    }

    @Test
    public void testResetModeTrustedDefaultsGlobal() throws Exception {
        testResetModeTrustedDefaultsCommon(SETTING_TYPE_GLOBAL);
    }

    @Test
    public void testResetModeTrustedDefaultsSecure() throws Exception {
        testResetModeTrustedDefaultsCommon(SETTING_TYPE_SECURE);
    }

    private void testResetModeTrustedDefaultsCommon(int type) throws Exception {
        // Make sure we have a clean slate.
        putSetting(type, FAKE_SETTING_NAME, null);
        setSettingViaShell(type, FAKE_SETTING_NAME_1, null, true);
        try {
            // Set a default setting as a trusted component
            putSetting(type, FAKE_SETTING_NAME, FAKE_SETTING_VALUE);
            // Change the setting as a trusted component
            setSettingViaShell(type, FAKE_SETTING_NAME, FAKE_SETTING_VALUE_2, false);

            // Set a default setting as an untrusted component
            setSettingViaShell(type, FAKE_SETTING_NAME_1,
                    FAKE_SETTING_VALUE, true);
            // Change the setting as an untrusted component
            setSettingViaShell(type, FAKE_SETTING_NAME_1,
                    FAKE_SETTING_VALUE_2, false);

            // Reset to trusted defaults
            resetSettingsViaShell(type,
                    Settings.RESET_MODE_TRUSTED_DEFAULTS);

            // Check whether snapped to trusted defaults
            assertEquals(FAKE_SETTING_VALUE, getSetting(type, FAKE_SETTING_NAME));
            assertNull(getSetting(type, FAKE_SETTING_NAME_1));
        } finally {
            // Make sure we have a clean slate.
            putSetting(type, FAKE_SETTING_NAME, null);
            setSettingViaShell(type, FAKE_SETTING_NAME_1, null, true);
        }
    }

    // To prevent FRP bypasses, the SECURE_FRP_MODE setting should not be reset when all other
    // settings are reset.  But it should still be possible to explicitly set its value.
    @Test
    public void testSecureFrpModeSettingCannotBeReset() throws Exception {
        final String name = Settings.Secure.SECURE_FRP_MODE;
        final String origValue = getSetting(SETTING_TYPE_GLOBAL, name);
        setSettingViaShell(SETTING_TYPE_GLOBAL, name, "1", false);
        try {
            assertEquals("1", getSetting(SETTING_TYPE_GLOBAL, name));
            for (int type : new int[] { SETTING_TYPE_GLOBAL, SETTING_TYPE_SECURE }) {
                resetSettingsViaShell(type, Settings.RESET_MODE_UNTRUSTED_DEFAULTS);
                resetSettingsViaShell(type, Settings.RESET_MODE_UNTRUSTED_CHANGES);
                resetSettingsViaShell(type, Settings.RESET_MODE_TRUSTED_DEFAULTS);
            }
            // The value should still be "1".  It should not have been reset to null.
            assertEquals("1", getSetting(SETTING_TYPE_GLOBAL, name));
            // It should still be possible to explicitly set the value to "0".
            setSettingViaShell(SETTING_TYPE_GLOBAL, name, "0", false);
            assertEquals("0", getSetting(SETTING_TYPE_GLOBAL, name));
        } finally {
            setSettingViaShell(SETTING_TYPE_GLOBAL, name, origValue, false);
            assertEquals(origValue, getSetting(SETTING_TYPE_GLOBAL, name));
        }
    }

    private void doTestQueryStringInBracketsViaProviderApiForType(int type) {
        // Make sure we have a clean slate.
        deleteStringViaProviderApi(type, FAKE_SETTING_NAME);

        try {
            // Insert the setting.
            final Uri uri = insertStringViaProviderApi(type, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE, false);
            Uri expectUri = Uri.withAppendedPath(getBaseUriForType(type), FAKE_SETTING_NAME);
            assertEquals("Did not get expected Uri.", expectUri, uri);

            // Make sure the first setting is there.
            String firstValue = queryStringViaProviderApi(type, FAKE_SETTING_NAME, true, false);
            assertEquals("Setting must be present", FAKE_SETTING_VALUE, firstValue);
        } finally {
            // Clean up.
            deleteStringViaProviderApi(type, FAKE_SETTING_NAME);
        }
    }

    private void toTestBulkInsertViaProviderApiForType(int type) {
        // Make sure we have a clean slate.
        deleteStringViaProviderApi(type, FAKE_SETTING_NAME);
        deleteStringViaProviderApi(type, FAKE_SETTING_NAME_1);
        deleteStringViaProviderApi(type, FAKE_SETTING_NAME_2);

        try {
            Uri uri = getBaseUriForType(type);
            ContentValues[] allValues = new ContentValues[3];

            // Insert the first setting.
            ContentValues firstValues = new ContentValues();
            firstValues.put(Settings.NameValueTable.NAME, FAKE_SETTING_NAME);
            firstValues.put(Settings.NameValueTable.VALUE, FAKE_SETTING_VALUE);
            allValues[0] = firstValues;

            // Insert the second setting.
            ContentValues secondValues = new ContentValues();
            secondValues.put(Settings.NameValueTable.NAME, FAKE_SETTING_NAME_1);
            secondValues.put(Settings.NameValueTable.VALUE, FAKE_SETTING_VALUE_1);
            allValues[1] = secondValues;

            // Insert the third setting. (null)
            ContentValues thirdValues = new ContentValues();
            thirdValues.put(Settings.NameValueTable.NAME, FAKE_SETTING_NAME_2);
            thirdValues.put(Settings.NameValueTable.VALUE, FAKE_SETTING_VALUE_2);
            allValues[2] = thirdValues;

            // Verify insertion count.
            final int insertCount = getContext().getContentResolver().bulkInsert(uri, allValues);
            assertSame("Couldn't insert both values", 3, insertCount);

            // Make sure the first setting is there.
            String firstValue = queryStringViaProviderApi(type, FAKE_SETTING_NAME);
            assertEquals("First setting must be present", FAKE_SETTING_VALUE, firstValue);

            // Make sure the second setting is there.
            String secondValue = queryStringViaProviderApi(type, FAKE_SETTING_NAME_1);
            assertEquals("Second setting must be present", FAKE_SETTING_VALUE_1, secondValue);

            // Make sure the third setting is there.
            String thirdValue = queryStringViaProviderApi(type, FAKE_SETTING_NAME_2);
            assertEquals("Third setting must be present", FAKE_SETTING_VALUE_2, thirdValue);
        } finally {
            // Clean up.
            deleteStringViaProviderApi(type, FAKE_SETTING_NAME);
            deleteStringViaProviderApi(type, FAKE_SETTING_NAME_1);
            deleteStringViaProviderApi(type, FAKE_SETTING_NAME_2);
        }
    }

    private void doTestQueryUpdateDeleteGlobalViaProviderApiForType(int type) throws Exception {
        // Make sure it is not there.
        deleteStringViaProviderApi(type, FAKE_SETTING_NAME);

        // Now selection should return nothing.
        String value = queryStringViaProviderApi(type, FAKE_SETTING_NAME);
        assertNull("Setting should not be present.", value);

        // Insert the setting.
        Uri uri = insertStringViaProviderApi(type,
                FAKE_SETTING_NAME, FAKE_SETTING_VALUE, false);
        Uri expectUri = Uri.withAppendedPath(getBaseUriForType(type), FAKE_SETTING_NAME);
        assertEquals("Did not get expected Uri.", expectUri, uri);

        // Now selection should return the setting.
        value = queryStringViaProviderApi(type, FAKE_SETTING_NAME);
        assertEquals("Setting should be present.", FAKE_SETTING_VALUE, value);

        // Update the setting.
        final int changeCount = updateStringViaProviderApiSetting(type,
                FAKE_SETTING_NAME, FAKE_SETTING_VALUE_1);
        assertEquals("Did not get expected change count.", 1, changeCount);

        // Now selection should return the new setting.
        value = queryStringViaProviderApi(type, FAKE_SETTING_NAME);
        assertEquals("Setting should be present.", FAKE_SETTING_VALUE_1, value);

        // Delete the setting.
        final int deletedCount = deleteStringViaProviderApi(type,
                FAKE_SETTING_NAME);
        assertEquals("Did not get expected deleted count", 1, deletedCount);

        // Now selection should return nothing.
        value = queryStringViaProviderApi(type, FAKE_SETTING_NAME);
        assertNull("Setting should not be present.", value);
    }

    private void performSetAndGetSettingTestViaFrontEndApi(int type, int userId)
            throws Exception {
        try {
            // Change the setting and assert a successful change.
            setSettingViaFrontEndApiAndAssertSuccessfulChange(type, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE, userId);
        } finally {
            // Remove the setting.
            setStringViaFrontEndApiSetting(type, FAKE_SETTING_NAME, null, userId);
        }
    }

    private void performSetAndGetSettingTestViaProviderApi(int type)
            throws Exception {
        try {
            // Change the setting and assert a successful change.
            setSettingViaProviderApiAndAssertSuccessfulChange(type, FAKE_SETTING_NAME,
                    FAKE_SETTING_VALUE, true);
        } finally {
            // Remove the setting.
            setSettingViaProviderApiAndAssertSuccessfulChange(type, FAKE_SETTING_NAME, null,
                    true);
        }
    }

    private void setSettingViaFrontEndApiAndAssertSuccessfulChange(final int type,
            final String name, final String value, final int userId) throws Exception {
        setSettingAndAssertSuccessfulChange(() -> {
            setStringViaFrontEndApiSetting(type, name, value, userId);
        }, type, name, value, userId);
    }

    private void setSettingViaProviderApiAndAssertSuccessfulChange(final int type,
            final String name, final String value, final boolean withTableRowUri)
            throws Exception {
        setSettingAndAssertSuccessfulChange(() -> {
            insertStringViaProviderApi(type, name, value, withTableRowUri);
        }, type, name, value, UserHandle.USER_SYSTEM);
    }

    private void setSettingAndAssertSuccessfulChange(Runnable setCommand, final int type,
            final String name, final String value, final int userId) throws Exception {
        ContentResolver contentResolver = getContext().getContentResolver();

        final Uri settingUri = getBaseUriForType(type).buildUpon().appendPath(name).build();

        final AtomicBoolean success = new AtomicBoolean();

        ContentObserver contentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            public void onChange(boolean selfChange, Uri changeUri, int changeId) {
                Log.i(LOG_TAG, "onChange(" + selfChange + ", " + changeUri + ", " + changeId + ")");
                assertEquals("Wrong change Uri", changeUri, settingUri);
                assertEquals("Wrong user id", userId, changeId);
                String changeValue = getStringViaFrontEndApiSetting(type, name, userId);
                assertEquals("Wrong setting value", value, changeValue);

                success.set(true);

                synchronized (mLock) {
                    mLock.notifyAll();
                }
            }
        };

        contentResolver.registerContentObserver(settingUri, false, contentObserver, userId);

        try {
            setCommand.run();

            final long startTimeMillis = SystemClock.uptimeMillis();
            synchronized (mLock) {
                while (true) {
                    if (success.get()) {
                        return;
                    }
                    final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                    if (elapsedTimeMillis >= WAIT_FOR_SETTING_URI_CHANGE_TIMEOUT_MILLIS) {
                        fail("Could not change setting for "
                                + WAIT_FOR_SETTING_URI_CHANGE_TIMEOUT_MILLIS + " ms");
                    }
                    final long remainingTimeMillis = WAIT_FOR_SETTING_URI_CHANGE_TIMEOUT_MILLIS
                            - elapsedTimeMillis;
                    try {
                        mLock.wait(remainingTimeMillis);
                    } catch (InterruptedException ie) {
                        /* ignore */
                    }
                }
            }
        } finally {
            contentResolver.unregisterContentObserver(contentObserver);
        }
    }

    private void queryAllSettingsViaProviderApiSettingAndAssertSettingPresent(int type,
            String name) {
        Uri uri = getBaseUriForType(type);

        Cursor cursor = getContext().getContentResolver().query(uri, NAME_VALUE_COLUMNS,
                null, null, null);

        if (cursor == null || !cursor.moveToFirst()) {
            fail("Nothing selected");
        }

        try {
            final int nameColumnIdx = cursor.getColumnIndex(Settings.NameValueTable.NAME);

            while (cursor.moveToNext()) {
                String currentName = cursor.getString(nameColumnIdx);
                if (name.equals(currentName)) {
                    return;
                }
            }

            fail("Not found setting: " + name);
        } finally {
            cursor.close();
        }
    }
}
