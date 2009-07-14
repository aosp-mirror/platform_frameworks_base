/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.webkit;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.ContentObserver;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.util.HashSet;

/**
 *  Donut-specific hack to keep Gears permissions in sync with the
 *  system location setting.
 */
class GearsPermissionsManager {
    // The application context.
    Context mContext;
    // The path to gears.so.
    private String mGearsPath;

    // The Gears permissions database directory.
    private final static String GEARS_DATABASE_DIR = "gears";
    // The Gears permissions database file name.
    private final static String GEARS_DATABASE_FILE = "permissions.db";
    // The Gears location permissions table.
    private final static String GEARS_LOCATION_ACCESS_TABLE_NAME =
        "LocationAccess";
    // The Gears storage access permissions table.
    private final static String GEARS_STORAGE_ACCESS_TABLE_NAME = "Access";
    // The Gears permissions db schema version table.
    private final static String GEARS_SCHEMA_VERSION_TABLE_NAME =
        "VersionInfo";
    // The Gears permission value that denotes "allow access to location".
    private static final int GEARS_ALLOW_LOCATION_ACCESS = 1;
    // The shared pref name.
    private static final String LAST_KNOWN_LOCATION_SETTING =
        "lastKnownLocationSystemSetting";
    // The Browser package name.
    private static final String BROWSER_PACKAGE_NAME = "com.android.browser";
    // The Secure Settings observer that will be notified when the system
    // location setting changes.
    private SecureSettingsObserver mSettingsObserver;
    // The Google URLs whitelisted for Gears location access.
    private static HashSet<String> sGearsWhiteList;

    static {
        sGearsWhiteList = new HashSet<String>();
        // NOTE: DO NOT ADD A "/" AT THE END!
        sGearsWhiteList.add("http://www.google.com");
        sGearsWhiteList.add("http://www.google.co.uk");
    }

    private static final String LOGTAG = "webcore";
    static final boolean DEBUG = false;
    static final boolean LOGV_ENABLED = DEBUG;

    GearsPermissionsManager(Context context, String gearsPath) {
        mContext = context;
        mGearsPath = gearsPath;
    }

    public void doCheckAndStartObserver() {
     // Are we running in the browser?
        if (!BROWSER_PACKAGE_NAME.equals(mContext.getPackageName())) {
            return;
        }
        // Do the check.
        checkGearsPermissions();
        // Install the observer.
        mSettingsObserver = new SecureSettingsObserver();
        mSettingsObserver.observe();
    }

    private void checkGearsPermissions() {
        // Get the current system settings.
        int setting = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.USE_LOCATION_FOR_SERVICES, -1);
        // Check if we need to set the Gears permissions.
        if (setting != -1 && locationSystemSettingChanged(setting)) {
            setGearsPermissionForGoogleDomains(setting);
        }
    }

    private boolean locationSystemSettingChanged(int newSetting) {
        SharedPreferences prefs =
            PreferenceManager.getDefaultSharedPreferences(mContext);
        int oldSetting = 0;
        oldSetting = prefs.getInt(LAST_KNOWN_LOCATION_SETTING, oldSetting);
        if (oldSetting == newSetting) {
            return false;
        }
        Editor ed = prefs.edit();
        ed.putInt(LAST_KNOWN_LOCATION_SETTING, newSetting);
        ed.commit();
        return true;
    }

    private void setGearsPermissionForGoogleDomains(int systemPermission) {
        // Transform the system permission into a boolean flag. When this
        // flag is true, it means the origins in gGearsWhiteList are added
        // to the Gears location permission table with permission 1 (allowed).
        // When the flag is false, the origins in gGearsWhiteList are removed
        // from the Gears location permission table. Next time the user
        // navigates to one of these origins, she will see the normal Gears
        // permission prompt.
        boolean addToGearsLocationTable = (systemPermission == 1 ? true : false);
        // Build the path to the Gears library.

        File file = new File(mGearsPath).getParentFile();
        if (file == null) {
            return;
        }
        // Build the Gears database file name.
        file = new File(file.getAbsolutePath() + File.separator
                + GEARS_DATABASE_DIR + File.separator + GEARS_DATABASE_FILE);
        // Remember whether or not we need to create the LocationAccess table.
        boolean needToCreateTables = !file.exists();
        // If the database file does not yet exist and the system location
        // setting says that the Gears origins need to be removed from the
        // location permission table, it means that we don't actually need
        // to do anything at all.
        if (needToCreateTables && !addToGearsLocationTable) {
            return;
        }
        // Try opening the Gears database.
        SQLiteDatabase permissions;
        try {
            permissions = SQLiteDatabase.openOrCreateDatabase(file, null);
        } catch (SQLiteException e) {
            if (LOGV_ENABLED) {
                Log.v(LOGTAG, "Could not open Gears permission DB: "
                        + e.getMessage());
            }
            // Just bail out.
            return;
        }
        // We now have a database open. Begin a transaction.
        permissions.beginTransaction();
        try {
            if (needToCreateTables) {
                // Create the tables. Note that this creates the
                // Gears tables for the permissions DB schema version 2.
                // The Gears schema upgrade process will take care of the rest.
                // First, the storage access table.
                SQLiteStatement statement = permissions.compileStatement(
                        "CREATE TABLE IF NOT EXISTS "
                        + GEARS_STORAGE_ACCESS_TABLE_NAME
                        + " (Name TEXT UNIQUE, Value)");
                statement.execute();
                // Next the location access table.
                statement = permissions.compileStatement(
                        "CREATE TABLE IF NOT EXISTS "
                        + GEARS_LOCATION_ACCESS_TABLE_NAME
                        + " (Name TEXT UNIQUE, Value)");
                statement.execute();
                // Finally, the schema version table.
                statement = permissions.compileStatement(
                        "CREATE TABLE IF NOT EXISTS "
                        + GEARS_SCHEMA_VERSION_TABLE_NAME
                        + " (Name TEXT UNIQUE, Value)");
                statement.execute();
                // Set the schema version to 2.
                ContentValues schema = new ContentValues();
                schema.put("Name", "Version");
                schema.put("Value", 2);
                permissions.insert(GEARS_SCHEMA_VERSION_TABLE_NAME, null,
                        schema);
            }

            if (addToGearsLocationTable) {
                ContentValues permissionValues = new ContentValues();

                for (String url : sGearsWhiteList) {
                    permissionValues.put("Name", url);
                    permissionValues.put("Value", GEARS_ALLOW_LOCATION_ACCESS);
                    permissions.replace(GEARS_LOCATION_ACCESS_TABLE_NAME, null,
                            permissionValues);
                    permissionValues.clear();
                }
            } else {
                for (String url : sGearsWhiteList) {
                    permissions.delete(GEARS_LOCATION_ACCESS_TABLE_NAME, "Name=?",
                            new String[] { url });
                }
            }
            // Commit the transaction.
            permissions.setTransactionSuccessful();
        } catch (SQLiteException e) {
            if (LOGV_ENABLED) {
                Log.v(LOGTAG, "Could not set the Gears permissions: "
                        + e.getMessage());
            }
        } finally {
            permissions.endTransaction();
            permissions.close();
        }
    }

    class SecureSettingsObserver extends ContentObserver {
        SecureSettingsObserver() {
            super(new Handler());
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.USE_LOCATION_FOR_SERVICES), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            checkGearsPermissions();
        }
    }
}
