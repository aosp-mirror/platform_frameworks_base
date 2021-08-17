/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.provider;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertThat;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;

import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit test for SettingsProvider. */
public class SettingsProviderTest extends AndroidTestCase {

    @MediumTest
    public void testNameValueCache() {
        ContentResolver r = getContext().getContentResolver();
        Settings.Secure.putString(r, "test_service", "Value");
        assertEquals("Value", Settings.Secure.getString(r, "test_service"));

        // Make sure the value can be overwritten.
        Settings.Secure.putString(r, "test_service", "New");
        assertEquals("New", Settings.Secure.getString(r, "test_service"));

        // Also that delete works.
        assertEquals(1, r.delete(Settings.Secure.getUriFor("test_service"), null, null));
        assertEquals(null, Settings.Secure.getString(r, "test_service"));

        // Apps should not be able to use System settings.
        try {
            Settings.System.putString(r, "test_setting", "Value");
            fail("IllegalArgumentException expected");
        } catch (java.lang.IllegalArgumentException e) {
            // expected
        }
    }

    @MediumTest
    public void testRowNameContentUriForSecure() {
        final String testKey = "testRowNameContentUriForSecure";
        final String testValue = "testValue";
        final String secondTestValue = "testValueNew";

        try {
            testRowNameContentUri(Settings.Secure.CONTENT_URI, Settings.Secure.NAME,
                    Settings.Secure.VALUE, testKey, testValue, secondTestValue);
        } finally {
            // clean up
            Settings.Secure.putString(getContext().getContentResolver(), testKey, null);
        }
    }

    @MediumTest
    public void testRowNameContentUriForSystem() {
        final String testKey = Settings.System.VIBRATE_ON;
        assertTrue("Settings.System.PUBLIC_SETTINGS cannot be empty.  We need to use one of it"
                + " for testing.  Only settings key in this collection will be accepted by the"
                + " framework.", Settings.System.PUBLIC_SETTINGS.contains(testKey));
        final String testValue = "0";
        final String secondTestValue = "1";
        final String oldValue =
                Settings.System.getString(getContext().getContentResolver(), testKey);

        try {
            testRowNameContentUri(Settings.System.CONTENT_URI, Settings.System.NAME,
                    Settings.System.VALUE, testKey, testValue, secondTestValue);
        } finally {
            // restore old value
            if (oldValue != null) {
                Settings.System.putString(getContext().getContentResolver(), testKey, oldValue);
            }
        }
    }

    private void testRowNameContentUri(Uri table, String nameField, String valueField,
            String testKey, String testValue, String secondTestValue) {
        ContentResolver r = getContext().getContentResolver();

        ContentValues v = new ContentValues();
        v.put(nameField, testKey);
        v.put(valueField, testValue);

        r.insert(table, v);
        Uri uri = Uri.parse(table.toString() + "/" + testKey);

        // Query with a specific URI and no WHERE clause succeeds.
        Cursor c = r.query(uri, null, null, null, null);
        try {
            assertTrue(c.moveToNext());
            assertEquals(testKey, c.getString(c.getColumnIndex(nameField)));
            assertEquals(testValue, c.getString(c.getColumnIndex(valueField)));
            assertFalse(c.moveToNext());
        } finally {
            c.close();
        }

        // Query with a specific URI and a WHERE clause fails.
        try {
            r.query(uri, null, "1", null, null);
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            // expected
        }

        // Query with a tablewide URI and a WHERE clause succeeds.
        c = r.query(table, null, "name='" + testKey + "'", null, null);
        try {
            assertTrue(c.moveToNext());
            assertEquals(testKey, c.getString(c.getColumnIndex(nameField)));
            assertEquals(testValue, c.getString(c.getColumnIndex(valueField)));
            assertFalse(c.moveToNext());
        } finally {
            c.close();
        }

        v = new ContentValues();
        // NAME is still needed, although the uri should be specific enough. Why?
        v.put(nameField, testKey);
        v.put(valueField, secondTestValue);
        assertEquals(1, r.update(uri, v, null, null));

        c = r.query(uri, null, null, null, null);
        try {
            assertTrue(c.moveToNext());
            assertEquals(testKey, c.getString(c.getColumnIndex(nameField)));
            assertEquals(secondTestValue, c.getString(c.getColumnIndex(valueField)));
            assertFalse(c.moveToNext());
        } finally {
            c.close();
        }
    }

    @MediumTest
    public void testSettingsChangeForOtherUser() {
        UserManager um = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        ContentResolver r = getContext().getContentResolver();

        // Make sure there's an owner
        assertTrue(findUser(um, UserHandle.USER_SYSTEM));

        // create a new user to use for testing
        UserInfo otherUser = um.createUser("TestUser1", UserInfo.FLAG_GUEST);
        assertTrue(otherUser != null);
        try {
            assertNotSame("Current calling user id should not be the new guest user",
                    otherUser.id, UserHandle.getCallingUserId());

            final String testKey = "testSettingsChangeForOtherUser";
            final String testValue1 = "value1";
            final String testValue2 = "value2";
            Settings.Secure.putString(r, testKey, testValue1);
            Settings.Secure.putStringForUser(r, testKey, testValue2, otherUser.id);

            assertEquals(testValue1, Settings.Secure.getString(r, testKey));
            assertEquals(testValue2, Settings.Secure.getStringForUser(r, testKey, otherUser.id));

            assertNotSame("Current calling user id should not be the new guest user",
                    otherUser.id, UserHandle.getCallingUserId());
        } finally {
            // Tidy up
            um.removeUser(otherUser.id);
        }
    }

    @MediumTest
    @Suppress  // Settings.Bookmarks uses a query format that's not supported now.
    public void testRowNumberContentUri() {
        ContentResolver r = getContext().getContentResolver();

        // The bookmarks table (and everything else) uses standard row number content URIs.
        Uri uri = Settings.Bookmarks.add(r, new Intent("TEST"),
                "Test Title", "Test Folder", '*', 123);

        assertTrue(ContentUris.parseId(uri) > 0);

        assertEquals("TEST", Settings.Bookmarks.getIntentForShortcut(r, '*').getAction());

        ContentValues v = new ContentValues();
        v.put(Settings.Bookmarks.INTENT, "#Intent;action=TOAST;end");
        assertEquals(1, r.update(uri, v, null, null));

        assertEquals("TOAST", Settings.Bookmarks.getIntentForShortcut(r, '*').getAction());

        assertEquals(1, r.delete(uri, null, null));

        assertEquals(null, Settings.Bookmarks.getIntentForShortcut(r, '*'));
    }

    private boolean findUser(UserManager um, int userHandle) {
        for (UserInfo user : um.getUsers()) {
            if (user.id == userHandle) {
                return true;
            }
        }
        return false;
    }

    @MediumTest
    public void testPerUserSettings() {
        UserManager um = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        ContentResolver r = getContext().getContentResolver();

        // Make sure there's an owner
        assertTrue(findUser(um, UserHandle.USER_SYSTEM));

        // create a new user to use for testing
        UserInfo user = um.createUser("TestUser1", UserInfo.FLAG_GUEST);
        assertTrue(user != null);

        try {
            // Write some settings for that user as well as the current user
            final String TEST_KEY = "test_setting";
            final int SELF_VALUE = 40;
            final int OTHER_VALUE = 27;

            Settings.Secure.putInt(r, TEST_KEY, SELF_VALUE);
            Settings.Secure.putIntForUser(r, TEST_KEY, OTHER_VALUE, user.id);

            // Verify that they read back as intended
            int myValue = Settings.Secure.getInt(r, TEST_KEY, 0);
            int otherValue = Settings.Secure.getIntForUser(r, TEST_KEY, 0, user.id);
            assertTrue("Running as user " + UserHandle.myUserId()
                    + " and reading/writing as user " + user.id
                    + ", expected to read " + SELF_VALUE + " but got " + myValue,
                    myValue == SELF_VALUE);
            assertTrue("Running as user " + UserHandle.myUserId()
                    + " and reading/writing as user " + user.id
                    + ", expected to read " + OTHER_VALUE + " but got " + otherValue,
                    otherValue == OTHER_VALUE);
        } finally {
            // Tidy up
            um.removeUser(user.id);
        }
    }

     @SmallTest
     public void testSettings() {
        assertCanBeHandled(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_ADD_ACCOUNT));
        assertCanBeHandled(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_APN_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getContext().getPackageName())));
        assertCanBeHandled(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_DATE_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_LOCALE_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_MEMORY_CARD_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_PRIVACY_SETTINGS));
        //TODO: seems no one is using this anymore.
//        assertCanBeHandled(new Intent(Settings.ACTION_QUICK_LAUNCH_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_SEARCH_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_SOUND_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_SYNC_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_SYSTEM_UPDATE_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_USER_DICTIONARY_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_WIFI_IP_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_WIFI_SETTINGS));
        assertCanBeHandled(new Intent(Settings.ACTION_WIRELESS_SETTINGS));

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            assertCanBeHandled(new Intent(Settings.ACTION_ENTERPRISE_PRIVACY_SETTINGS));
        }
    }

    private void assertCanBeHandled(final Intent intent) {
        PackageManager packageManager = mContext.getPackageManager();
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent, 0);
        assertNotNull(resolveInfoList);
        // one or more activity can handle this intent.
        assertTrue(resolveInfoList.size() > 0);
    }

    @SmallTest
    public void testValidSsaid() {
        ContentResolver r = getContext().getContentResolver();

        // Verify ssaid
        String ssaid = Settings.Secure.getString(r, Settings.Secure.ANDROID_ID);
        assertTrue(ssaid != null);
        assertTrue(ssaid.length() == 16);

        String ssaid2 = Settings.Secure.getString(r, Settings.Secure.ANDROID_ID);
        assertTrue(ssaid2 != null);
        assertTrue(ssaid2.length() == 16);

        assertTrue(ssaid.equals(ssaid2));
    }

    @MediumTest
    public void testCall_putAndGetConfig() {
        ContentResolver r = getContext().getContentResolver();
        String name = "key1";
        String value = "value1";
        String newValue = "value2";
        Bundle args = new Bundle();
        args.putString(Settings.NameValueTable.VALUE, value);

        try {
            // value is empty
            Bundle results =
                    r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_GET_CONFIG, name, null);
            assertNull(results.get(Settings.NameValueTable.VALUE));

            // save value
            results = r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_PUT_CONFIG, name, args);
            assertNull(results);

            // value is no longer empty
            results = r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_GET_CONFIG, name, null);
            assertEquals(value, results.get(Settings.NameValueTable.VALUE));

            // save new value
            args.putString(Settings.NameValueTable.VALUE, newValue);
            r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_PUT_CONFIG, name, args);

            // new value is returned
            results = r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_GET_CONFIG, name, null);
            assertEquals(newValue, results.get(Settings.NameValueTable.VALUE));
        } finally {
            // clean up
            r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, name, null);
        }
    }

    @MediumTest
    public void testCall_deleteConfig() {
        ContentResolver r = getContext().getContentResolver();
        String name = "key1";
        String value = "value1";
        Bundle args = new Bundle();
        args.putString(Settings.NameValueTable.VALUE, value);

        try {
            // save value
            r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_PUT_CONFIG, name, args);

            // get value
            Bundle results =
                    r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_GET_CONFIG, name, null);
            assertEquals(value, results.get(Settings.NameValueTable.VALUE));

            // delete value
            results = r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, name,
                    null);

            // value is empty now
            results = r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_GET_CONFIG, name, null);
            assertNull(results.get(Settings.NameValueTable.VALUE));
        } finally {
            // clean up
            r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, name, null);
        }
    }

    @MediumTest
    public void testCall_listConfig() {
        ContentResolver r = getContext().getContentResolver();
        String prefix = "foo";
        String newPrefix = "bar";
        String name = prefix + "/" + "key1";
        String newName = newPrefix + "/" + "key1";
        String value = "value1";
        String newValue = "value2";
        Bundle args = new Bundle();
        args.putString(Settings.NameValueTable.VALUE, value);

        try {
            // save both values
            r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_PUT_CONFIG, name, args);
            args.putString(Settings.NameValueTable.VALUE, newValue);
            r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_PUT_CONFIG, newName, args);

            // list all values
            Bundle result = r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_LIST_CONFIG,
                    null, null);
            Map<String, String> keyValueMap =
                    (HashMap) result.getSerializable(Settings.NameValueTable.VALUE);
            assertThat(keyValueMap.size(), greaterThanOrEqualTo(2));
            assertEquals(value, keyValueMap.get(name));
            assertEquals(newValue, keyValueMap.get(newName));

            // list values for prefix
            args.putString(Settings.CALL_METHOD_PREFIX_KEY, prefix);
            result = r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_LIST_CONFIG, null, args);
            keyValueMap = (HashMap) result.getSerializable(Settings.NameValueTable.VALUE);
            assertThat(keyValueMap, aMapWithSize(1));
            assertEquals(value, keyValueMap.get(name));
        } finally {
            // clean up
            r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, name, null);
            r.call(DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, newName, null);
        }
    }
}
