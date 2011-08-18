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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.List;

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

        // Try all the same things in the System table
        Settings.System.putString(r, "test_setting", "Value");
        assertEquals("Value", Settings.System.getString(r, "test_setting"));

        Settings.System.putString(r, "test_setting", "New");
        assertEquals("New", Settings.System.getString(r, "test_setting"));

        assertEquals(1, r.delete(Settings.System.getUriFor("test_setting"), null, null));
        assertEquals(null, Settings.System.getString(r, "test_setting"));
    }

    @MediumTest
    public void testRowNameContentUri() {
        ContentResolver r = getContext().getContentResolver();

        assertEquals("content://settings/system/test_setting",
                Settings.System.getUriFor("test_setting").toString());
        assertEquals("content://settings/secure/test_service",
                Settings.Secure.getUriFor("test_service").toString());

        // These tables use the row name (not ID) as their content URI.
        Uri tables[] = { Settings.System.CONTENT_URI, Settings.Secure.CONTENT_URI };
        for (Uri table : tables) {
            ContentValues v = new ContentValues();
            v.put(Settings.System.NAME, "test_key");
            v.put(Settings.System.VALUE, "Test");
            Uri uri = r.insert(table, v);
            assertEquals(table.toString() + "/test_key", uri.toString());

            // Query with a specific URI and no WHERE clause succeeds.
            Cursor c = r.query(uri, null, null, null, null);
            try {
                assertTrue(c.moveToNext());
                assertEquals("test_key", c.getString(c.getColumnIndex(Settings.System.NAME)));
                assertEquals("Test", c.getString(c.getColumnIndex(Settings.System.VALUE)));
                assertFalse(c.moveToNext());
            } finally {
                c.close();
            }

            // Query with a specific URI and a WHERE clause fails.
            try {
                r.query(uri, null, "1", null, null);
                fail("UnsupportedOperationException expected");
            } catch (UnsupportedOperationException e) {
                if (!e.toString().contains("WHERE clause")) throw e;
            }

            // Query with a tablewide URI and a WHERE clause succeeds.
            c = r.query(table, null, "name='test_key'", null, null);
            try {
                assertTrue(c.moveToNext());
                assertEquals("test_key", c.getString(c.getColumnIndex(Settings.System.NAME)));
                assertEquals("Test", c.getString(c.getColumnIndex(Settings.System.VALUE)));
                assertFalse(c.moveToNext());
            } finally {
                c.close();
            }

            v = new ContentValues();
            v.put(Settings.System.VALUE, "Toast");
            assertEquals(1, r.update(uri, v, null, null));

            c = r.query(uri, null, null, null, null);
            try {
                assertTrue(c.moveToNext());
                assertEquals("test_key", c.getString(c.getColumnIndex(Settings.System.NAME)));
                assertEquals("Toast", c.getString(c.getColumnIndex(Settings.System.VALUE)));
                assertFalse(c.moveToNext());
            } finally {
                c.close();
            }

            assertEquals(1, r.delete(uri, null, null));
        }

        assertEquals(null, Settings.System.getString(r, "test_key"));
        assertEquals(null, Settings.Secure.getString(r, "test_key"));
    }

    @MediumTest
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

    @MediumTest
    public void testParseProviderList() {
        ContentResolver r = getContext().getContentResolver();

        // Make sure we get out what we put in.
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                "test1,test2,test3");
        assertEquals(Settings.Secure.getString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED),
                "test1,test2,test3");

        // Test adding a value
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                "");
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "+test1");
        assertEquals("test1",
                Settings.Secure.getString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED));

        // Test adding a second value
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "+test2");
        assertEquals("test1,test2",
                Settings.Secure.getString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED));

        // Test adding a third value
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "+test3");
        assertEquals("test1,test2,test3",
                Settings.Secure.getString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED));

        // Test deleting the first value in a 3 item list
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "-test1");
        assertEquals("test2,test3",
                Settings.Secure.getString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED));

        // Test deleting the middle value in a 3 item list
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                "test1,test2,test3");
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "-test2");
        assertEquals("test1,test3",
                Settings.Secure.getString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED));

        // Test deleting the last value in a 3 item list
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                "test1,test2,test3");
        Settings.Secure.putString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "-test3");
        assertEquals("test1,test2",
                Settings.Secure.getString(r, Settings.Secure.LOCATION_PROVIDERS_ALLOWED));
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
        assertCanBeHandled(new Intent(Settings.ACTION_QUICK_LAUNCH_SETTINGS));
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
    }

    private void assertCanBeHandled(final Intent intent) {
        PackageManager packageManager = mContext.getPackageManager();
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent, 0);
        assertNotNull(resolveInfoList);
        // one or more activity can handle this intent.
        assertTrue(resolveInfoList.size() > 0);
    }
}
