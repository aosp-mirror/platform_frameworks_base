/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.AudioService;
import android.net.ConnectivityManager;
import android.os.Environment;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;
import android.util.Xml;
import com.android.internal.util.XmlUtils;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

/**
 * Database helper class for {@link SettingsProvider}.
 * Mostly just has a bit {@link #onCreate} to initialize the database.
 */
class DatabaseHelper extends SQLiteOpenHelper {
    /**
     * Path to file containing default bookmarks, relative to ANDROID_ROOT.
     */
    private static final String DEFAULT_BOOKMARKS_PATH = "etc/bookmarks.xml";

    private static final String TAG = "SettingsProvider";
    private static final String DATABASE_NAME = "settings.db";
    private static final int DATABASE_VERSION = 30;
    
    private Context mContext;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }

    private void createSecureTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE secure (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT UNIQUE ON CONFLICT REPLACE," +
                "value TEXT" +
                ");");
        db.execSQL("CREATE INDEX secureIndex1 ON secure (name);");
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE system (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT UNIQUE ON CONFLICT REPLACE," +
                    "value TEXT" +
                    ");");
        db.execSQL("CREATE INDEX systemIndex1 ON system (name);");

        createSecureTable(db);

        db.execSQL("CREATE TABLE gservices (" +
                   "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                   "name TEXT UNIQUE ON CONFLICT REPLACE," +
                   "value TEXT" +
                   ");");
        db.execSQL("CREATE INDEX gservicesIndex1 ON gservices (name);");

        db.execSQL("CREATE TABLE bluetooth_devices (" +
                    "_id INTEGER PRIMARY KEY," +
                    "name TEXT," +
                    "addr TEXT," +
                    "channel INTEGER," +
                    "type INTEGER" +
                    ");");

        db.execSQL("CREATE TABLE bookmarks (" +
                    "_id INTEGER PRIMARY KEY," +
                    "title TEXT," +
                    "folder TEXT," +
                    "intent TEXT," +
                    "shortcut INTEGER," +
                    "ordering INTEGER" +
                    ");");

        db.execSQL("CREATE INDEX bookmarksIndex1 ON bookmarks (folder);");
        db.execSQL("CREATE INDEX bookmarksIndex2 ON bookmarks (shortcut);");

        // Populate bookmarks table with initial bookmarks
        loadBookmarks(db);

        // Load initial volume levels into DB
        loadVolumeLevels(db);

        // Load inital settings values
        loadSettings(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
        Log.w(TAG, "Upgrading settings database from version " + oldVersion + " to "
                + currentVersion);
        
        int upgradeVersion = oldVersion;

        // Pattern for upgrade blocks:
        //
        //    if (upgradeVersion == [the DATABASE_VERSION you set] - 1) {
        //        .. your upgrade logic..
        //        upgradeVersion = [the DATABASE_VERSION you set]
        //    }
        
        if (upgradeVersion == 20) {
            /*
             * Version 21 is part of the volume control refresh. There is no
             * longer a UI-visible for setting notification vibrate on/off (in
             * our design), but the functionality still exists. Force the
             * notification vibrate to on.
             */
            loadVibrateSetting(db, true);
            if (Config.LOGD) Log.d(TAG, "Reset system vibrate setting");

            upgradeVersion = 21;
        }
        
        if (upgradeVersion < 22) {
            upgradeVersion = 22;
            // Upgrade the lock gesture storage location and format
            upgradeLockPatternLocation(db);
        }

        if (upgradeVersion < 23) {
            db.execSQL("UPDATE favorites SET iconResource=0 WHERE iconType=0");
            upgradeVersion = 23;
        }

        if (upgradeVersion == 23) {
            db.beginTransaction();
            try {
                db.execSQL("ALTER TABLE favorites ADD spanX INTEGER");
                db.execSQL("ALTER TABLE favorites ADD spanY INTEGER");
                // Shortcuts, applications, folders
                db.execSQL("UPDATE favorites SET spanX=1, spanY=1 WHERE itemType<=0");
                // Photo frames, clocks
                db.execSQL("UPDATE favorites SET spanX=2, spanY=2 WHERE itemType=1000 or itemType=1002");
                // Search boxes
                db.execSQL("UPDATE favorites SET spanX=4, spanY=1 WHERE itemType=1001");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 24;
        }
        
        if (upgradeVersion == 24) {
            db.beginTransaction();
            try {
                // The value of the constants for preferring wifi or preferring mobile have been
                // swapped, so reload the default.
                db.execSQL("DELETE FROM system WHERE name='network_preference'");
                db.execSQL("INSERT INTO system ('name', 'value') values ('network_preference', '" +
                        ConnectivityManager.DEFAULT_NETWORK_PREFERENCE + "')");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 25;
        }

        if (upgradeVersion == 25) {
            db.beginTransaction();
            try {
                db.execSQL("ALTER TABLE favorites ADD uri TEXT");
                db.execSQL("ALTER TABLE favorites ADD displayMode INTEGER");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 26;
        }
        
        if (upgradeVersion == 26) {
            // This introduces the new secure settings table.
            db.beginTransaction();
            try {
                createSecureTable(db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 27;
        }
        
        if (upgradeVersion == 27) {
            // Copy settings values from 'system' to 'secure' and delete them from 'system'
            SQLiteStatement insertStmt = null;
            SQLiteStatement deleteStmt = null;
            
            db.beginTransaction();
            try {
                insertStmt =
                    db.compileStatement("INSERT INTO secure (name,value) SELECT name,value FROM "
                        + "system WHERE name=?");
                deleteStmt = db.compileStatement("DELETE FROM system WHERE name=?");

                String[] settingsToMove = {
                    Settings.Secure.ADB_ENABLED,
                    Settings.Secure.ANDROID_ID,
                    Settings.Secure.BLUETOOTH_ON,
                    Settings.Secure.DATA_ROAMING,
                    Settings.Secure.DEVICE_PROVISIONED,
                    Settings.Secure.HTTP_PROXY,
                    Settings.Secure.INSTALL_NON_MARKET_APPS,
                    Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                    Settings.Secure.LOGGING_ID,
                    Settings.Secure.NETWORK_PREFERENCE,
                    Settings.Secure.PARENTAL_CONTROL_ENABLED,
                    Settings.Secure.PARENTAL_CONTROL_LAST_UPDATE,
                    Settings.Secure.PARENTAL_CONTROL_REDIRECT_URL,
                    Settings.Secure.SETTINGS_CLASSNAME,
                    Settings.Secure.USB_MASS_STORAGE_ENABLED,
                    Settings.Secure.USE_GOOGLE_MAIL,
                    Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                    Settings.Secure.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,
                    Settings.Secure.WIFI_NUM_OPEN_NETWORKS_KEPT,
                    Settings.Secure.WIFI_ON,
                    Settings.Secure.WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE,
                    Settings.Secure.WIFI_WATCHDOG_AP_COUNT,
                    Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS,
                    Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED,
                    Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS,
                    Settings.Secure.WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT,
                    Settings.Secure.WIFI_WATCHDOG_MAX_AP_CHECKS,
                    Settings.Secure.WIFI_WATCHDOG_ON,
                    Settings.Secure.WIFI_WATCHDOG_PING_COUNT,
                    Settings.Secure.WIFI_WATCHDOG_PING_DELAY_MS,
                    Settings.Secure.WIFI_WATCHDOG_PING_TIMEOUT_MS,
                };
                
                for (String setting : settingsToMove) {
                    insertStmt.bindString(1, setting);
                    insertStmt.execute();
                    
                    deleteStmt.bindString(1, setting);
                    deleteStmt.execute();
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (insertStmt != null) {
                    insertStmt.close();
                }
                if (deleteStmt != null) {
                    deleteStmt.close();
                }
            }
            upgradeVersion = 28;
        }
        
        if (upgradeVersion == 28 || upgradeVersion == 29) {
            // Note: The upgrade to 28 was flawed since it didn't delete the old
            // setting first before inserting. Combining 28 and 29 with the
            // fixed version.

            // This upgrade adds the STREAM_NOTIFICATION type to the list of
            // types affected by ringer modes (silent, vibrate, etc.)
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "'");
                int newValue = (1 << AudioManager.STREAM_RING)
                        | (1 << AudioManager.STREAM_NOTIFICATION)
                        | (1 << AudioManager.STREAM_SYSTEM);
                db.execSQL("INSERT INTO system ('name', 'value') values ('"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "', '"
                        + String.valueOf(newValue) + "')");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            
            upgradeVersion = 30;
        }

        if (upgradeVersion != currentVersion) {
            Log.w(TAG, "Got stuck trying to upgrade from version " + upgradeVersion
                    + ", must wipe the settings provider");
            db.execSQL("DROP TABLE IF EXISTS system");
            db.execSQL("DROP INDEX IF EXISTS systemIndex1");
            db.execSQL("DROP TABLE IF EXISTS secure");
            db.execSQL("DROP INDEX IF EXISTS secureIndex1");
            db.execSQL("DROP TABLE IF EXISTS gservices");
            db.execSQL("DROP INDEX IF EXISTS gservicesIndex1");
            db.execSQL("DROP TABLE IF EXISTS bluetooth_devices");
            db.execSQL("DROP TABLE IF EXISTS bookmarks");
            db.execSQL("DROP INDEX IF EXISTS bookmarksIndex1");
            db.execSQL("DROP INDEX IF EXISTS bookmarksIndex2");
            db.execSQL("DROP TABLE IF EXISTS favorites");
            onCreate(db);
        }
    }

    private void upgradeLockPatternLocation(SQLiteDatabase db) {
        Cursor c = db.query("system", new String[] {"_id", "value"}, "name='lock_pattern'", 
                null, null, null, null);
        if (c.getCount() > 0) {
            c.moveToFirst();
            String lockPattern = c.getString(1);
            if (!TextUtils.isEmpty(lockPattern)) {
                // Convert lock pattern
                try {
                    LockPatternUtils lpu = new LockPatternUtils(mContext.getContentResolver());
                    List<LockPatternView.Cell> cellPattern = 
                            LockPatternUtils.stringToPattern(lockPattern);
                    lpu.saveLockPattern(cellPattern);
                } catch (IllegalArgumentException e) {
                    // Don't want corrupted lock pattern to hang the reboot process
                }
            }
            c.close();
            db.delete("system", "name='lock_pattern'", null);
        } else {
            c.close();
        }
    }

    /**
     * Loads the default set of bookmarked shortcuts from an xml file.
     *
     * @param db The database to write the values into
     * @param startingIndex The zero-based position at which bookmarks in this file should begin
     * @param subPath The relative path from ANDROID_ROOT to the file to read
     * @param quiet If true, do no complain if the file is missing
     */
    private int loadBookmarks(SQLiteDatabase db, int startingIndex, String subPath,
            boolean quiet) {
        FileReader bookmarksReader;

        // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
        final File favFile = new File(Environment.getRootDirectory(), subPath);
        try {
            bookmarksReader = new FileReader(favFile);
        } catch (FileNotFoundException e) {
            if (!quiet) {
                Log.e(TAG, "Couldn't find or open bookmarks file " + favFile);
            }
            return 0;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        ContentValues values = new ContentValues();

        PackageManager packageManager = mContext.getPackageManager();
        ActivityInfo info;
        int i = startingIndex;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(bookmarksReader);

            XmlUtils.beginDocument(parser, "bookmarks");

            while (true) {
                XmlUtils.nextElement(parser);

                String name = parser.getName();
                if (!"bookmark".equals(name)) {
                    break;
                }

                String pkg = parser.getAttributeValue(null, "package");
                String cls = parser.getAttributeValue(null, "class");
                String shortcutStr = parser.getAttributeValue(null, "shortcut");
                int shortcutValue = (int) shortcutStr.charAt(0);
                if (TextUtils.isEmpty(shortcutStr)) {
                    Log.w(TAG, "Unable to get shortcut for: " + pkg + "/" + cls);
                }
                try {
                    ComponentName cn = new ComponentName(pkg, cls);
                    info = packageManager.getActivityInfo(cn, 0);
                    intent.setComponent(cn);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    values.put(Settings.Bookmarks.INTENT, intent.toURI());
                    values.put(Settings.Bookmarks.TITLE,
                            info.loadLabel(packageManager).toString());
                    values.put(Settings.Bookmarks.SHORTCUT, shortcutValue);
                    db.insert("bookmarks", null, values);
                    i++;
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "Unable to add bookmark: " + pkg + "/" + cls, e);
                }
            }
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Got execption parsing bookmarks.", e);
        } catch (IOException e) {
            Log.w(TAG, "Got execption parsing bookmarks.", e);
        }

        return i;
    }

    /**
     * Loads the default set of bookmark packages.
     *
     * @param db The database to write the values into
     */
    private void loadBookmarks(SQLiteDatabase db) {
        loadBookmarks(db, 0, DEFAULT_BOOKMARKS_PATH, false);
    }

    /**
     * Loads the default volume levels. It is actually inserting the index of
     * the volume array for each of the volume controls.
     *
     * @param db the database to insert the volume levels into
     */
    private void loadVolumeLevels(SQLiteDatabase db) {
        SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                + " VALUES(?,?);");

        loadSetting(stmt, Settings.System.VOLUME_MUSIC,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_MUSIC]);
        loadSetting(stmt, Settings.System.VOLUME_RING,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_RING]);
        loadSetting(stmt, Settings.System.VOLUME_SYSTEM,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_SYSTEM]);
        loadSetting(
                stmt,
                Settings.System.VOLUME_VOICE,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_VOICE_CALL]);
        loadSetting(stmt, Settings.System.VOLUME_ALARM,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_ALARM]);
        loadSetting(
                stmt,
                Settings.System.VOLUME_NOTIFICATION,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_NOTIFICATION]);
        loadSetting(stmt, Settings.System.MODE_RINGER,
                AudioManager.RINGER_MODE_NORMAL);

        loadVibrateSetting(db, false);
        
        // By default, only the ring/notification and system streams are affected
        loadSetting(stmt, Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                (1 << AudioManager.STREAM_RING) | (1 << AudioManager.STREAM_NOTIFICATION) |
                (1 << AudioManager.STREAM_SYSTEM));
        
        loadSetting(stmt, Settings.System.MUTE_STREAMS_AFFECTED,
                ((1 << AudioManager.STREAM_MUSIC) |
                 (1 << AudioManager.STREAM_RING) |
                 (1 << AudioManager.STREAM_NOTIFICATION) |
                 (1 << AudioManager.STREAM_SYSTEM)));

        stmt.close();
    }

    private void loadVibrateSetting(SQLiteDatabase db, boolean deleteOld) {
        if (deleteOld) {
            db.execSQL("DELETE FROM system WHERE name='" + Settings.System.VIBRATE_ON + "'");
        }
        
        SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                + " VALUES(?,?);");

        // Vibrate off by default for ringer, on for notification
        int vibrate = 0;
        vibrate = AudioService.getValueForVibrateSetting(vibrate,
                AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_ON);
        vibrate = AudioService.getValueForVibrateSetting(vibrate,
                AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_OFF);
        loadSetting(stmt, Settings.System.VIBRATE_ON, vibrate);
    }

    private void loadSettings(SQLiteDatabase db) {
        loadSystemSettings(db);
        loadSecureSettings(db);   
    }
    
    private void loadSystemSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                + " VALUES(?,?);");
        
        loadSetting(stmt, Settings.System.DIM_SCREEN, 1);
        loadSetting(stmt, Settings.System.STAY_ON_WHILE_PLUGGED_IN, 
                "1".equals(SystemProperties.get("ro.kernel.qemu")) ? 1 : 0);
        loadSetting(stmt, Settings.System.SCREEN_OFF_TIMEOUT, 60000);
        // Allow airplane mode to turn off cell radio
        loadSetting(stmt, Settings.System.AIRPLANE_MODE_RADIOS, 
                Settings.System.RADIO_CELL + ","
                + Settings.System.RADIO_BLUETOOTH + "," + Settings.System.RADIO_WIFI);
        
        loadSetting(stmt, Settings.System.AIRPLANE_MODE_ON, 0);
        
        loadSetting(stmt, Settings.System.AUTO_TIME, 1); // Sync time to NITZ
        
        // Set default brightness to 40%
        loadSetting(stmt, Settings.System.SCREEN_BRIGHTNESS, 
                (int) (android.os.Power.BRIGHTNESS_ON * 0.4f));
        
        // Enable normal window animations (menus, toasts); disable
        // activity transition animations.
        loadSetting(stmt, Settings.System.WINDOW_ANIMATION_SCALE, "1");
        loadSetting(stmt, Settings.System.TRANSITION_ANIMATION_SCALE, "1");

        // Default date format based on build
        loadSetting(stmt, Settings.System.DATE_FORMAT,
                SystemProperties.get("ro.com.android.dateformat", 
                        "MM-dd-yyyy"));
        stmt.close();
    }
    
    private void loadSecureSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                + " VALUES(?,?);");
        
        // Bluetooth off
        loadSetting(stmt, Settings.Secure.BLUETOOTH_ON, 0);
        
        // Data roaming default, based on build
        loadSetting(stmt, Settings.Secure.DATA_ROAMING, 
                "true".equalsIgnoreCase(
                        SystemProperties.get("ro.com.android.dataroaming", 
                                "false")) ? 1 : 0);        
        
        // Don't allow non-market apps to be installed
        loadSetting(stmt, Settings.Secure.INSTALL_NON_MARKET_APPS, 0);
        
        // Set the default location providers to network based (cell-id)
        loadSetting(stmt, Settings.Secure.LOCATION_PROVIDERS_ALLOWED, 
                LocationManager.NETWORK_PROVIDER);
        
        loadSetting(stmt, Settings.Secure.NETWORK_PREFERENCE,
            ConnectivityManager.DEFAULT_NETWORK_PREFERENCE);

        // USB mass storage on by default
        loadSetting(stmt, Settings.Secure.USB_MASS_STORAGE_ENABLED, 1);
        
        // WIFI on, notify about available networks
        loadSetting(stmt, Settings.Secure.WIFI_ON, 0);
        loadSetting(stmt, Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1);
        
        // Don't do this.  The SystemServer will initialize ADB_ENABLED from a
        // persistent system property instead.
        //loadSetting(stmt, Settings.Secure.ADB_ENABLED, 0);
        
        stmt.close();
    }

    private void loadSetting(SQLiteStatement stmt, String key, Object value) {
        stmt.bindString(1, key);
        stmt.bindString(2, value.toString());
        stmt.execute();
    }
}
