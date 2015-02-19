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
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.media.AudioManager;
import android.media.AudioService;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.ImsConfig;
import com.android.internal.content.PackageHelper;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/**
 * Database helper class for {@link SettingsProvider}.
 * Mostly just has a bit {@link #onCreate} to initialize the database.
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "SettingsProvider";
    private static final String DATABASE_NAME = "settings.db";

    // Please, please please. If you update the database version, check to make sure the
    // database gets upgraded properly. At a minimum, please confirm that 'upgradeVersion'
    // is properly propagated through your change.  Not doing so will result in a loss of user
    // settings.
    private static final int DATABASE_VERSION = 118;

    private Context mContext;
    private int mUserHandle;

    private static final HashSet<String> mValidTables = new HashSet<String>();

    private static final String TABLE_SYSTEM = "system";
    private static final String TABLE_SECURE = "secure";
    private static final String TABLE_GLOBAL = "global";

    static {
        mValidTables.add(TABLE_SYSTEM);
        mValidTables.add(TABLE_SECURE);
        mValidTables.add(TABLE_GLOBAL);
        mValidTables.add("bluetooth_devices");
        mValidTables.add("bookmarks");

        // These are old.
        mValidTables.add("favorites");
        mValidTables.add("gservices");
        mValidTables.add("old_favorites");
    }

    static String dbNameForUser(final int userHandle) {
        // The owner gets the unadorned db name;
        if (userHandle == UserHandle.USER_OWNER) {
            return DATABASE_NAME;
        } else {
            // Place the database in the user-specific data tree so that it's
            // cleaned up automatically when the user is deleted.
            File databaseFile = new File(
                    Environment.getUserSystemDirectory(userHandle), DATABASE_NAME);
            return databaseFile.getPath();
        }
    }

    public DatabaseHelper(Context context, int userHandle) {
        super(context, dbNameForUser(userHandle), null, DATABASE_VERSION);
        mContext = context;
        mUserHandle = userHandle;
    }

    public static boolean isValidTable(String name) {
        return mValidTables.contains(name);
    }

    private void createSecureTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE secure (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT UNIQUE ON CONFLICT REPLACE," +
                "value TEXT" +
                ");");
        db.execSQL("CREATE INDEX secureIndex1 ON secure (name);");
    }

    private void createGlobalTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE global (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT UNIQUE ON CONFLICT REPLACE," +
                "value TEXT" +
                ");");
        db.execSQL("CREATE INDEX globalIndex1 ON global (name);");
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

        // Only create the global table for the singleton 'owner' user
        if (mUserHandle == UserHandle.USER_OWNER) {
            createGlobalTable(db);
        }

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
        boolean onlyCore = false;
        try {
            onlyCore = IPackageManager.Stub.asInterface(ServiceManager.getService(
                    "package")).isOnlyCoreApps();
        } catch (RemoteException e) {
        }
        if (!onlyCore) {
            loadBookmarks(db);
        }

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
                db.execSQL(
                    "UPDATE favorites SET spanX=2, spanY=2 WHERE itemType=1000 or itemType=1002");
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
            moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_SECURE, settingsToMove, false);
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

        if (upgradeVersion == 30) {
            /*
             * Upgrade 31 clears the title for all quick launch shortcuts so the
             * activities' titles will be resolved at display time. Also, the
             * folder is changed to '@quicklaunch'.
             */
            db.beginTransaction();
            try {
                db.execSQL("UPDATE bookmarks SET folder = '@quicklaunch'");
                db.execSQL("UPDATE bookmarks SET title = ''");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 31;
        }

        if (upgradeVersion == 31) {
            /*
             * Animations are now managed in preferences, and may be
             * enabled or disabled based on product resources.
             */
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.WINDOW_ANIMATION_SCALE + "'");
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.TRANSITION_ANIMATION_SCALE + "'");
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadDefaultAnimationSettings(stmt);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 32;
        }

        if (upgradeVersion == 32) {
            // The Wi-Fi watchdog SSID list is now seeded with the value of
            // the property ro.com.android.wifi-watchlist
            String wifiWatchList = SystemProperties.get("ro.com.android.wifi-watchlist");
            if (!TextUtils.isEmpty(wifiWatchList)) {
                db.beginTransaction();
                try {
                    db.execSQL("INSERT OR IGNORE INTO secure(name,value) values('" +
                            Settings.Secure.WIFI_WATCHDOG_WATCH_LIST + "','" +
                            wifiWatchList + "');");
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 33;
        }

        if (upgradeVersion == 33) {
            // Set the default zoom controls to: tap-twice to bring up +/-
            db.beginTransaction();
            try {
                db.execSQL("INSERT INTO system(name,value) values('zoom','2');");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 34;
        }

        if (upgradeVersion == 34) {
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadSecure35Settings(stmt);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 35;
        }
            // due to a botched merge from donut to eclair, the initialization of ASSISTED_GPS_ENABLED
            // was accidentally done out of order here.
            // to fix this, ASSISTED_GPS_ENABLED is now initialized while upgrading from 38 to 39,
            // and we intentionally do nothing from 35 to 36 now.
        if (upgradeVersion == 35) {
            upgradeVersion = 36;
        }

        if (upgradeVersion == 36) {
           // This upgrade adds the STREAM_SYSTEM_ENFORCED type to the list of
            // types affected by ringer modes (silent, vibrate, etc.)
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "'");
                int newValue = (1 << AudioManager.STREAM_RING)
                        | (1 << AudioManager.STREAM_NOTIFICATION)
                        | (1 << AudioManager.STREAM_SYSTEM)
                        | (1 << AudioManager.STREAM_SYSTEM_ENFORCED);
                db.execSQL("INSERT INTO system ('name', 'value') values ('"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "', '"
                        + String.valueOf(newValue) + "')");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 37;
        }

        if (upgradeVersion == 37) {
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                        + " VALUES(?,?);");
                loadStringSetting(stmt, Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                        R.string.airplane_mode_toggleable_radios);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 38;
        }

        if (upgradeVersion == 38) {
            db.beginTransaction();
            try {
                String value =
                        mContext.getResources().getBoolean(R.bool.assisted_gps_enabled) ? "1" : "0";
                db.execSQL("INSERT OR IGNORE INTO secure(name,value) values('" +
                        Settings.Global.ASSISTED_GPS_ENABLED + "','" + value + "');");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            upgradeVersion = 39;
        }

        if (upgradeVersion == 39) {
            upgradeAutoBrightness(db);
            upgradeVersion = 40;
        }

        if (upgradeVersion == 40) {
            /*
             * All animations are now turned on by default!
             */
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.WINDOW_ANIMATION_SCALE + "'");
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.TRANSITION_ANIMATION_SCALE + "'");
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadDefaultAnimationSettings(stmt);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 41;
        }

        if (upgradeVersion == 41) {
            /*
             * Initialize newly public haptic feedback setting
             */
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.HAPTIC_FEEDBACK_ENABLED + "'");
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadDefaultHapticSettings(stmt);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 42;
        }

        if (upgradeVersion == 42) {
            /*
             * Initialize new notification pulse setting
             */
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.System.NOTIFICATION_LIGHT_PULSE,
                        R.bool.def_notification_pulse);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 43;
        }

        if (upgradeVersion == 43) {
            /*
             * This upgrade stores bluetooth volume separately from voice volume
             */
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                        + " VALUES(?,?);");
                loadSetting(stmt, Settings.System.VOLUME_BLUETOOTH_SCO,
                        AudioService.getDefaultStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO));
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 44;
        }

        if (upgradeVersion == 44) {
            /*
             * Gservices was moved into vendor/google.
             */
            db.execSQL("DROP TABLE IF EXISTS gservices");
            db.execSQL("DROP INDEX IF EXISTS gservicesIndex1");
            upgradeVersion = 45;
        }

        if (upgradeVersion == 45) {
             /*
              * New settings for MountService
              */
            db.beginTransaction();
            try {
                db.execSQL("INSERT INTO secure(name,value) values('" +
                        Settings.Secure.MOUNT_PLAY_NOTIFICATION_SND + "','1');");
                db.execSQL("INSERT INTO secure(name,value) values('" +
                        Settings.Secure.MOUNT_UMS_AUTOSTART + "','0');");
                db.execSQL("INSERT INTO secure(name,value) values('" +
                        Settings.Secure.MOUNT_UMS_PROMPT + "','1');");
                db.execSQL("INSERT INTO secure(name,value) values('" +
                        Settings.Secure.MOUNT_UMS_NOTIFY_ENABLED + "','1');");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 46;
        }

        if (upgradeVersion == 46) {
            /*
             * The password mode constants have changed; reset back to no
             * password.
             */
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='lockscreen.password_type';");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
           upgradeVersion = 47;
       }


        if (upgradeVersion == 47) {
            /*
             * The password mode constants have changed again; reset back to no
             * password.
             */
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='lockscreen.password_type';");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
           upgradeVersion = 48;
       }

       if (upgradeVersion == 48) {
           /*
            * Default recognition service no longer initialized here,
            * moved to RecognitionManagerService.
            */
           upgradeVersion = 49;
       }

       if (upgradeVersion == 49) {
           /*
            * New settings for new user interface noises.
            */
           db.beginTransaction();
           SQLiteStatement stmt = null;
           try {
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadUISoundEffectsSettings(stmt);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }

           upgradeVersion = 50;
       }

       if (upgradeVersion == 50) {
           /*
            * Install location no longer initiated here.
            */
           upgradeVersion = 51;
       }

       if (upgradeVersion == 51) {
           /* Move the lockscreen related settings to Secure, including some private ones. */
           String[] settingsToMove = {
                   Secure.LOCK_PATTERN_ENABLED,
                   Secure.LOCK_PATTERN_VISIBLE,
                   Secure.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED,
                   "lockscreen.password_type",
                   "lockscreen.lockoutattemptdeadline",
                   "lockscreen.patterneverchosen",
                   "lock_pattern_autolock",
                   "lockscreen.lockedoutpermanently",
                   "lockscreen.password_salt"
           };
           moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_SECURE, settingsToMove, false);
           upgradeVersion = 52;
       }

        if (upgradeVersion == 52) {
            // new vibration/silent mode settings
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.System.VIBRATE_IN_SILENT,
                        R.bool.def_vibrate_in_silent);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }

            upgradeVersion = 53;
        }

        if (upgradeVersion == 53) {
            /*
             * New settings for set install location UI no longer initiated here.
             */
            upgradeVersion = 54;
        }

        if (upgradeVersion == 54) {
            /*
             * Update the screen timeout value if set to never
             */
            db.beginTransaction();
            try {
                upgradeScreenTimeoutFromNever(db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            upgradeVersion = 55;
        }

        if (upgradeVersion == 55) {
            /* Move the install location settings. */
            String[] settingsToMove = {
                    Global.SET_INSTALL_LOCATION,
                    Global.DEFAULT_INSTALL_LOCATION
            };
            moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_SECURE, settingsToMove, false);
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadSetting(stmt, Global.SET_INSTALL_LOCATION, 0);
                loadSetting(stmt, Global.DEFAULT_INSTALL_LOCATION,
                        PackageHelper.APP_INSTALL_AUTO);
                db.setTransactionSuccessful();
             } finally {
                 db.endTransaction();
                 if (stmt != null) stmt.close();
             }
            upgradeVersion = 56;
        }

        if (upgradeVersion == 56) {
            /*
             * Add Bluetooth to list of toggleable radios in airplane mode
             */
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS + "'");
                stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                        + " VALUES(?,?);");
                loadStringSetting(stmt, Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                        R.string.airplane_mode_toggleable_radios);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 57;
        }

        /************* The following are Honeycomb changes ************/

        if (upgradeVersion == 57) {
            /*
             * New settings to:
             *  1. Enable injection of accessibility scripts in WebViews.
             *  2. Define the key bindings for traversing web content in WebViews.
             */
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION,
                        R.bool.def_accessibility_script_injection);
                stmt.close();
                stmt = db.compileStatement("INSERT INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadStringSetting(stmt, Settings.Secure.ACCESSIBILITY_WEB_CONTENT_KEY_BINDINGS,
                        R.string.def_accessibility_web_content_key_bindings);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 58;
        }

        if (upgradeVersion == 58) {
            /* Add default for new Auto Time Zone */
            int autoTimeValue = getIntValueFromSystem(db, Settings.System.AUTO_TIME, 0);
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO system(name,value)" + " VALUES(?,?);");
                loadSetting(stmt, Settings.System.AUTO_TIME_ZONE,
                        autoTimeValue); // Sync timezone to NITZ if auto_time was enabled
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 59;
        }

        if (upgradeVersion == 59) {
            // Persistence for the rotation lock feature.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.System.USER_ROTATION,
                        R.integer.def_user_rotation); // should be zero degrees
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 60;
        }

        if (upgradeVersion == 60) {
            // Don't do this for upgrades from Gingerbread
            // Were only required for intra-Honeycomb upgrades for testing
            // upgradeScreenTimeout(db);
            upgradeVersion = 61;
        }

        if (upgradeVersion == 61) {
            // Don't do this for upgrades from Gingerbread
            // Were only required for intra-Honeycomb upgrades for testing
            // upgradeScreenTimeout(db);
            upgradeVersion = 62;
        }

        // Change the default for screen auto-brightness mode
        if (upgradeVersion == 62) {
            // Don't do this for upgrades from Gingerbread
            // Were only required for intra-Honeycomb upgrades for testing
            // upgradeAutoBrightness(db);
            upgradeVersion = 63;
        }

        if (upgradeVersion == 63) {
            // This upgrade adds the STREAM_MUSIC type to the list of
             // types affected by ringer modes (silent, vibrate, etc.)
             db.beginTransaction();
             try {
                 db.execSQL("DELETE FROM system WHERE name='"
                         + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "'");
                 int newValue = (1 << AudioManager.STREAM_RING)
                         | (1 << AudioManager.STREAM_NOTIFICATION)
                         | (1 << AudioManager.STREAM_SYSTEM)
                         | (1 << AudioManager.STREAM_SYSTEM_ENFORCED)
                         | (1 << AudioManager.STREAM_MUSIC);
                 db.execSQL("INSERT INTO system ('name', 'value') values ('"
                         + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "', '"
                         + String.valueOf(newValue) + "')");
                 db.setTransactionSuccessful();
             } finally {
                 db.endTransaction();
             }
             upgradeVersion = 64;
         }

        if (upgradeVersion == 64) {
            // New setting to configure the long press timeout.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadIntegerSetting(stmt, Settings.Secure.LONG_PRESS_TIMEOUT,
                        R.integer.def_long_press_timeout_millis);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 65;
        }

        /************* The following are Ice Cream Sandwich changes ************/

        if (upgradeVersion == 65) {
            /*
             * Animations are removed from Settings. Turned on by default
             */
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.WINDOW_ANIMATION_SCALE + "'");
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.TRANSITION_ANIMATION_SCALE + "'");
                stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadDefaultAnimationSettings(stmt);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 66;
        }

        if (upgradeVersion == 66) {
            // This upgrade makes sure that MODE_RINGER_STREAMS_AFFECTED is set
            // according to device voice capability
            db.beginTransaction();
            try {
                int ringerModeAffectedStreams = (1 << AudioManager.STREAM_RING) |
                                                (1 << AudioManager.STREAM_NOTIFICATION) |
                                                (1 << AudioManager.STREAM_SYSTEM) |
                                                (1 << AudioManager.STREAM_SYSTEM_ENFORCED);
                if (!mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_voice_capable)) {
                    ringerModeAffectedStreams |= (1 << AudioManager.STREAM_MUSIC);
                }
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "'");
                db.execSQL("INSERT INTO system ('name', 'value') values ('"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "', '"
                        + String.valueOf(ringerModeAffectedStreams) + "')");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 67;
        }

        if (upgradeVersion == 67) {
            // New setting to enable touch exploration.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.Secure.TOUCH_EXPLORATION_ENABLED,
                        R.bool.def_touch_exploration_enabled);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 68;
        }

        if (upgradeVersion == 68) {
            // Enable all system sounds by default
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.NOTIFICATIONS_USE_RING_VOLUME + "'");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 69;
        }

        if (upgradeVersion == 69) {
            // Add RADIO_NFC to AIRPLANE_MODE_RADIO and AIRPLANE_MODE_TOGGLEABLE_RADIOS
            String airplaneRadios = mContext.getResources().getString(
                    R.string.def_airplane_mode_radios);
            String toggleableRadios = mContext.getResources().getString(
                    R.string.airplane_mode_toggleable_radios);
            db.beginTransaction();
            try {
                db.execSQL("UPDATE system SET value='" + airplaneRadios + "' " +
                        "WHERE name='" + Settings.System.AIRPLANE_MODE_RADIOS + "'");
                db.execSQL("UPDATE system SET value='" + toggleableRadios + "' " +
                        "WHERE name='" + Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS + "'");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 70;
        }

        if (upgradeVersion == 70) {
            // Update all built-in bookmarks.  Some of the package names have changed.
            loadBookmarks(db);
            upgradeVersion = 71;
        }

        if (upgradeVersion == 71) {
             // New setting to specify whether to speak passwords in accessibility mode.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD,
                        R.bool.def_accessibility_speak_password);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 72;
        }

        if (upgradeVersion == 72) {
            // update vibration settings
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR REPLACE INTO system(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.System.VIBRATE_IN_SILENT,
                        R.bool.def_vibrate_in_silent);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 73;
        }

        if (upgradeVersion == 73) {
            upgradeVibrateSettingFromNone(db);
            upgradeVersion = 74;
        }

        if (upgradeVersion == 74) {
            // URL from which WebView loads a JavaScript based screen-reader.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO secure(name,value) VALUES(?,?);");
                loadStringSetting(stmt, Settings.Secure.ACCESSIBILITY_SCREEN_READER_URL,
                        R.string.def_accessibility_screen_reader_url);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 75;
        }
        if (upgradeVersion == 75) {
            db.beginTransaction();
            SQLiteStatement stmt = null;
            Cursor c = null;
            try {
                c = db.query(TABLE_SECURE, new String[] {"_id", "value"},
                        "name='lockscreen.disabled'",
                        null, null, null, null);
                // only set default if it has not yet been set
                if (c == null || c.getCount() == 0) {
                    stmt = db.compileStatement("INSERT INTO system(name,value)"
                            + " VALUES(?,?);");
                    loadBooleanSetting(stmt, Settings.System.LOCKSCREEN_DISABLED,
                            R.bool.def_lockscreen_disabled);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (c != null) c.close();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 76;
        }

        /************* The following are Jelly Bean changes ************/

        if (upgradeVersion == 76) {
            // Removed VIBRATE_IN_SILENT setting
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                                + Settings.System.VIBRATE_IN_SILENT + "'");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            upgradeVersion = 77;
        }

        if (upgradeVersion == 77) {
            // Introduce "vibrate when ringing" setting
            loadVibrateWhenRingingSetting(db);

            upgradeVersion = 78;
        }

        if (upgradeVersion == 78) {
            // The JavaScript based screen-reader URL changes in JellyBean.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR REPLACE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadStringSetting(stmt, Settings.Secure.ACCESSIBILITY_SCREEN_READER_URL,
                        R.string.def_accessibility_screen_reader_url);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 79;
        }

        if (upgradeVersion == 79) {
            // Before touch exploration was a global setting controlled by the user
            // via the UI. However, if the enabled accessibility services do not
            // handle touch exploration mode, enabling it makes no sense. Therefore,
            // now the services request touch exploration mode and the user is
            // presented with a dialog to allow that and if she does we store that
            // in the database. As a result of this change a user that has enabled
            // accessibility, touch exploration, and some accessibility services
            // may lose touch exploration state, thus rendering the device useless
            // unless sighted help is provided, since the enabled service(s) are
            // not in the list of services to which the user granted a permission
            // to put the device in touch explore mode. Here we are allowing all
            // enabled accessibility services to toggle touch exploration provided
            // accessibility and touch exploration are enabled and no services can
            // toggle touch exploration. Note that the user has already manually
            // enabled the services and touch exploration which means the she has
            // given consent to have these services work in touch exploration mode.
            final boolean accessibilityEnabled = getIntValueFromTable(db, TABLE_SECURE,
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
            final boolean touchExplorationEnabled = getIntValueFromTable(db, TABLE_SECURE,
                    Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0) == 1;
            if (accessibilityEnabled && touchExplorationEnabled) {
                String enabledServices = getStringValueFromTable(db, TABLE_SECURE,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
                String touchExplorationGrantedServices = getStringValueFromTable(db, TABLE_SECURE,
                        Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES, "");
                if (TextUtils.isEmpty(touchExplorationGrantedServices)
                        && !TextUtils.isEmpty(enabledServices)) {
                    SQLiteStatement stmt = null;
                    try {
                        db.beginTransaction();
                        stmt = db.compileStatement("INSERT OR REPLACE INTO secure(name,value)"
                                + " VALUES(?,?);");
                        loadSetting(stmt,
                                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                                enabledServices);
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                        if (stmt != null) stmt.close();
                    }
                }
            }
            upgradeVersion = 80;
        }

        // vvv Jelly Bean MR1 changes begin here vvv

        if (upgradeVersion == 80) {
            // update screensaver settings
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR REPLACE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.Secure.SCREENSAVER_ENABLED,
                        com.android.internal.R.bool.config_dreamsEnabledByDefault);
                loadBooleanSetting(stmt, Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                        com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault);
                loadBooleanSetting(stmt, Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                        com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault);
                loadStringSetting(stmt, Settings.Secure.SCREENSAVER_COMPONENTS,
                        com.android.internal.R.string.config_dreamsDefaultComponent);
                loadStringSetting(stmt, Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT,
                        com.android.internal.R.string.config_dreamsDefaultComponent);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 81;
        }

        if (upgradeVersion == 81) {
            // Add package verification setting
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR REPLACE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.Global.PACKAGE_VERIFIER_ENABLE,
                        R.bool.def_package_verifier_enable);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 82;
        }

        if (upgradeVersion == 82) {
            // Move to per-user settings dbs
            if (mUserHandle == UserHandle.USER_OWNER) {

                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    // Migrate now-global settings. Note that this happens before
                    // new users can be created.
                    createGlobalTable(db);
                    String[] settingsToMove = hashsetToStringArray(SettingsProvider.sSystemGlobalKeys);
                    moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_GLOBAL, settingsToMove, false);
                    settingsToMove = hashsetToStringArray(SettingsProvider.sSecureGlobalKeys);
                    moveSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, settingsToMove, false);

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 83;
        }

        if (upgradeVersion == 83) {
            // 1. Setting whether screen magnification is enabled.
            // 2. Setting for screen magnification scale.
            // 3. Setting for screen magnification auto update.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT INTO secure(name,value) VALUES(?,?);");
                loadBooleanSetting(stmt,
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                        R.bool.def_accessibility_display_magnification_enabled);
                stmt.close();
                stmt = db.compileStatement("INSERT INTO secure(name,value) VALUES(?,?);");
                loadFractionSetting(stmt, Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                        R.fraction.def_accessibility_display_magnification_scale, 1);
                stmt.close();
                stmt = db.compileStatement("INSERT INTO secure(name,value) VALUES(?,?);");
                loadBooleanSetting(stmt,
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_AUTO_UPDATE,
                        R.bool.def_accessibility_display_magnification_auto_update);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 84;
        }

        if (upgradeVersion == 84) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    // Patch up the slightly-wrong key migration from 82 -> 83 for those
                    // devices that missed it, ignoring if the move is redundant
                    String[] settingsToMove = {
                            Settings.Secure.ADB_ENABLED,
                            Settings.Secure.BLUETOOTH_ON,
                            Settings.Secure.DATA_ROAMING,
                            Settings.Secure.DEVICE_PROVISIONED,
                            Settings.Secure.INSTALL_NON_MARKET_APPS,
                            Settings.Secure.USB_MASS_STORAGE_ENABLED
                    };
                    moveSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, settingsToMove, true);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 85;
        }

        if (upgradeVersion == 85) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    // Fix up the migration, ignoring already-migrated elements, to snap up to
                    // date with new changes to the set of global versus system/secure settings
                    String[] settingsToMove = { Settings.System.STAY_ON_WHILE_PLUGGED_IN };
                    moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_GLOBAL, settingsToMove, true);

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 86;
        }

        if (upgradeVersion == 86) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    String[] settingsToMove = {
                            Settings.Global.PACKAGE_VERIFIER_ENABLE,
                            Settings.Global.PACKAGE_VERIFIER_TIMEOUT,
                            Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE
                    };
                    moveSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, settingsToMove, true);

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 87;
        }

        if (upgradeVersion == 87) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    String[] settingsToMove = {
                            Settings.Global.DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS,
                            Settings.Global.DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS,
                            Settings.Global.GPRS_REGISTER_CHECK_PERIOD_MS
                    };
                    moveSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, settingsToMove, true);

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 88;
        }

        if (upgradeVersion == 88) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    String[] settingsToMove = {
                            Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD,
                            Settings.Global.BATTERY_DISCHARGE_THRESHOLD,
                            Settings.Global.SEND_ACTION_APP_ERROR,
                            Settings.Global.DROPBOX_AGE_SECONDS,
                            Settings.Global.DROPBOX_MAX_FILES,
                            Settings.Global.DROPBOX_QUOTA_KB,
                            Settings.Global.DROPBOX_QUOTA_PERCENT,
                            Settings.Global.DROPBOX_RESERVE_PERCENT,
                            Settings.Global.DROPBOX_TAG_PREFIX,
                            Settings.Global.ERROR_LOGCAT_PREFIX,
                            Settings.Global.SYS_FREE_STORAGE_LOG_INTERVAL,
                            Settings.Global.DISK_FREE_CHANGE_REPORTING_THRESHOLD,
                            Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE,
                            Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES,
                            Settings.Global.SYS_STORAGE_FULL_THRESHOLD_BYTES,
                            Settings.Global.SYNC_MAX_RETRY_DELAY_IN_SECONDS,
                            Settings.Global.CONNECTIVITY_CHANGE_DELAY,
                            Settings.Global.CAPTIVE_PORTAL_DETECTION_ENABLED,
                            Settings.Global.CAPTIVE_PORTAL_SERVER,
                            Settings.Global.NSD_ON,
                            Settings.Global.SET_INSTALL_LOCATION,
                            Settings.Global.DEFAULT_INSTALL_LOCATION,
                            Settings.Global.INET_CONDITION_DEBOUNCE_UP_DELAY,
                            Settings.Global.INET_CONDITION_DEBOUNCE_DOWN_DELAY,
                            Settings.Global.READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT,
                            Settings.Global.HTTP_PROXY,
                            Settings.Global.GLOBAL_HTTP_PROXY_HOST,
                            Settings.Global.GLOBAL_HTTP_PROXY_PORT,
                            Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                            Settings.Global.SET_GLOBAL_HTTP_PROXY,
                            Settings.Global.DEFAULT_DNS_SERVER,
                    };
                    moveSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, settingsToMove, true);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 89;
        }

        if (upgradeVersion == 89) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    String[] prefixesToMove = {
                            Settings.Global.BLUETOOTH_HEADSET_PRIORITY_PREFIX,
                            Settings.Global.BLUETOOTH_A2DP_SINK_PRIORITY_PREFIX,
                            Settings.Global.BLUETOOTH_INPUT_DEVICE_PRIORITY_PREFIX,
                    };

                    movePrefixedSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, prefixesToMove);

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 90;
        }

        if (upgradeVersion == 90) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    String[] systemToGlobal = {
                            Settings.Global.WINDOW_ANIMATION_SCALE,
                            Settings.Global.TRANSITION_ANIMATION_SCALE,
                            Settings.Global.ANIMATOR_DURATION_SCALE,
                            Settings.Global.FANCY_IME_ANIMATIONS,
                            Settings.Global.COMPATIBILITY_MODE,
                            Settings.Global.EMERGENCY_TONE,
                            Settings.Global.CALL_AUTO_RETRY,
                            Settings.Global.DEBUG_APP,
                            Settings.Global.WAIT_FOR_DEBUGGER,
                            Settings.Global.SHOW_PROCESSES,
                            Settings.Global.ALWAYS_FINISH_ACTIVITIES,
                    };
                    String[] secureToGlobal = {
                            Settings.Global.PREFERRED_NETWORK_MODE,
                            Settings.Global.CDMA_SUBSCRIPTION_MODE,
                    };

                    moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_GLOBAL, systemToGlobal, true);
                    moveSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, secureToGlobal, true);

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 91;
        }

        if (upgradeVersion == 91) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    // Move ringer mode from system to global settings
                    String[] settingsToMove = { Settings.Global.MODE_RINGER };
                    moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_GLOBAL, settingsToMove, true);

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 92;
        }

        if (upgradeVersion == 92) {
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                        + " VALUES(?,?);");
                if (mUserHandle == UserHandle.USER_OWNER) {
                    // consider existing primary users to have made it through user setup
                    // if the globally-scoped device-provisioned bit is set
                    // (indicating they already made it through setup as primary)
                    int deviceProvisioned = getIntValueFromTable(db, TABLE_GLOBAL,
                            Settings.Global.DEVICE_PROVISIONED, 0);
                    loadSetting(stmt, Settings.Secure.USER_SETUP_COMPLETE,
                            deviceProvisioned);
                } else {
                    // otherwise use the default
                    loadBooleanSetting(stmt, Settings.Secure.USER_SETUP_COMPLETE,
                            R.bool.def_user_setup_complete);
                }
            } finally {
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 93;
        }

        if (upgradeVersion == 93) {
            // Redo this step, since somehow it didn't work the first time for some users
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    // Migrate now-global settings
                    String[] settingsToMove = hashsetToStringArray(SettingsProvider.sSystemGlobalKeys);
                    moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_GLOBAL, settingsToMove, true);
                    settingsToMove = hashsetToStringArray(SettingsProvider.sSecureGlobalKeys);
                    moveSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, settingsToMove, true);

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 94;
        }

        if (upgradeVersion == 94) {
            // Add wireless charging started sound setting
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR REPLACE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadStringSetting(stmt, Settings.Global.WIRELESS_CHARGING_STARTED_SOUND,
                            R.string.def_wireless_charging_started_sound);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 95;
        }

        if (upgradeVersion == 95) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                try {
                    String[] settingsToMove = { Settings.Global.BUGREPORT_IN_POWER_MENU };
                    moveSettingsToNewTable(db, TABLE_SECURE, TABLE_GLOBAL, settingsToMove, true);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 96;
        }

        if (upgradeVersion == 96) {
            // NOP bump due to a reverted change that some people got on upgrade.
            upgradeVersion = 97;
        }

        if (upgradeVersion == 97) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR REPLACE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadIntegerSetting(stmt, Settings.Global.LOW_BATTERY_SOUND_TIMEOUT,
                            R.integer.def_low_battery_sound_timeout);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 98;
        }

        if (upgradeVersion == 98) {
            // no-op; LOCK_SCREEN_SHOW_NOTIFICATIONS now handled in version 106
            upgradeVersion = 99;
        }

        if (upgradeVersion == 99) {
            // no-op; HEADS_UP_NOTIFICATIONS_ENABLED now handled in version 100
            upgradeVersion = 100;
        }

        if (upgradeVersion == 100) {
            // note: LOCK_SCREEN_SHOW_NOTIFICATIONS now handled in version 106
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR REPLACE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadIntegerSetting(stmt, Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                            R.integer.def_heads_up_enabled);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 101;
        }

        if (upgradeVersion == 101) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR IGNORE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadSetting(stmt, Settings.Global.DEVICE_NAME, getDefaultDeviceName());
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 102;
        }

        if (upgradeVersion == 102) {
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                // The INSTALL_NON_MARKET_APPS setting is becoming per-user rather
                // than device-global.
                if (mUserHandle == UserHandle.USER_OWNER) {
                    // In the owner user, the global table exists so we can migrate the
                    // entry from there to the secure table, preserving its value.
                    String[] globalToSecure = {
                            Settings.Secure.INSTALL_NON_MARKET_APPS
                    };
                    moveSettingsToNewTable(db, TABLE_GLOBAL, TABLE_SECURE, globalToSecure, true);
                } else {
                    // Secondary users' dbs don't have the global table, so institute the
                    // default.
                    stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                            + " VALUES(?,?);");
                    loadBooleanSetting(stmt, Settings.Secure.INSTALL_NON_MARKET_APPS,
                            R.bool.def_install_non_market_apps);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 103;
        }

        if (upgradeVersion == 103) {
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR REPLACE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.Secure.WAKE_GESTURE_ENABLED,
                        R.bool.def_wake_gesture_enabled);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 104;
        }

        if (upgradeVersion < 105) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR IGNORE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadBooleanSetting(stmt, Settings.Global.GUEST_USER_ENABLED,
                            R.bool.def_guest_user_enabled);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 105;
        }

        if (upgradeVersion < 106) {
            // LOCK_SCREEN_SHOW_NOTIFICATIONS is now per-user.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadIntegerSetting(stmt, Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                        R.integer.def_lock_screen_show_notifications);
                if (mUserHandle == UserHandle.USER_OWNER) {
                    final int oldShow = getIntValueFromTable(db,
                            TABLE_GLOBAL, Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, -1);
                    if (oldShow >= 0) {
                        // overwrite the default with whatever you had
                        loadSetting(stmt, Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, oldShow);
                        final SQLiteStatement deleteStmt
                                = db.compileStatement("DELETE FROM global WHERE name=?");
                        deleteStmt.bindString(1, Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS);
                        deleteStmt.execute();
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 106;
        }

        if (upgradeVersion < 107) {
            // Add trusted sound setting
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR REPLACE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadStringSetting(stmt, Settings.Global.TRUSTED_SOUND,
                            R.string.def_trusted_sound);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 107;
        }

        if (upgradeVersion < 108) {
            // Reset the auto-brightness setting to default since the behavior
            // of the feature is now quite different and is being presented to
            // the user in a new way as "adaptive brightness".
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR REPLACE INTO system(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        R.bool.def_screen_brightness_automatic_mode);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 108;
        }

        if (upgradeVersion < 109) {
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                        R.bool.def_lock_screen_allow_private_notifications);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 109;
        }

        if (upgradeVersion < 110) {
            // The SIP_CALL_OPTIONS value SIP_ASK_EACH_TIME is being deprecated.
            // If the SIP_CALL_OPTIONS setting is set to SIP_ASK_EACH_TIME, default to
            // SIP_ADDRESS_ONLY.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("UPDATE system SET value = ? " +
                        "WHERE name = ? AND value = ?;");
                stmt.bindString(1, Settings.System.SIP_ADDRESS_ONLY);
                stmt.bindString(2, Settings.System.SIP_CALL_OPTIONS);
                stmt.bindString(3, Settings.System.SIP_ASK_ME_EACH_TIME);
                stmt.execute();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 110;
        }

        if (upgradeVersion < 111) {
            // reset ringer mode, so it doesn't force zen mode to follow
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR REPLACE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadSetting(stmt, Settings.Global.MODE_RINGER, AudioManager.RINGER_MODE_NORMAL);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 111;
        }

        if (upgradeVersion < 112) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                // When device name was added, we went with Manufacturer + Model, device name should
                // actually be Model only.
                // Update device name to Model if it wasn't modified by user.
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("UPDATE global SET value = ? "
                        + " WHERE name = ? AND value = ?");
                    stmt.bindString(1, getDefaultDeviceName()); // new default device name
                    stmt.bindString(2, Settings.Global.DEVICE_NAME);
                    stmt.bindString(3, getOldDefaultDeviceName()); // old default device name
                    stmt.execute();
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 112;
        }

        if (upgradeVersion < 113) {
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadIntegerSetting(stmt, Settings.Secure.SLEEP_TIMEOUT,
                        R.integer.def_sleep_timeout);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 113;
        }

        // We skipped 114 to handle a merge conflict with the introduction of theater mode.

        if (upgradeVersion < 115) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR IGNORE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadBooleanSetting(stmt, Global.THEATER_MODE_ON,
                            R.bool.def_theater_mode_on);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 115;
        }

        if (upgradeVersion < 116) {
            if (mUserHandle == UserHandle.USER_OWNER) {
                db.beginTransaction();
                SQLiteStatement stmt = null;
                try {
                    stmt = db.compileStatement("INSERT OR IGNORE INTO global(name,value)"
                            + " VALUES(?,?);");
                    loadSetting(stmt, Settings.Global.ENHANCED_4G_MODE_ENABLED, ImsConfig.FeatureValueConstants.ON);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (stmt != null) stmt.close();
                }
            }
            upgradeVersion = 116;
        }

        if (upgradeVersion < 117) {
            db.beginTransaction();
            try {
                String[] systemToSecure = {
                        Settings.Secure.LOCK_TO_APP_EXIT_LOCKED
                };
                moveSettingsToNewTable(db, TABLE_SYSTEM, TABLE_SECURE, systemToSecure, true);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 117;
        }

        if (upgradeVersion < 118) {
            // Reset rotation-lock-for-accessibility on upgrade, since it now hides the display
            // setting.
            db.beginTransaction();
            SQLiteStatement stmt = null;
            try {
                stmt = db.compileStatement("INSERT OR REPLACE INTO system(name,value)"
                        + " VALUES(?,?);");
                loadSetting(stmt, Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, 0);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (stmt != null) stmt.close();
            }
            upgradeVersion = 118;
        }
        // *** Remember to update DATABASE_VERSION above!

        if (upgradeVersion != currentVersion) {
            Log.w(TAG, "Got stuck trying to upgrade from version " + upgradeVersion
                    + ", must wipe the settings provider");
            db.execSQL("DROP TABLE IF EXISTS global");
            db.execSQL("DROP TABLE IF EXISTS globalIndex1");
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

            // Added for diagnosing settings.db wipes after the fact
            String wipeReason = oldVersion + "/" + upgradeVersion + "/" + currentVersion;
            db.execSQL("INSERT INTO secure(name,value) values('" +
                    "wiped_db_reason" + "','" + wipeReason + "');");
        }
    }

    private String[] hashsetToStringArray(HashSet<String> set) {
        String[] array = new String[set.size()];
        return set.toArray(array);
    }

    private void moveSettingsToNewTable(SQLiteDatabase db,
            String sourceTable, String destTable,
            String[] settingsToMove, boolean doIgnore) {
        // Copy settings values from the source table to the dest, and remove from the source
        SQLiteStatement insertStmt = null;
        SQLiteStatement deleteStmt = null;

        db.beginTransaction();
        try {
            insertStmt = db.compileStatement("INSERT "
                    + (doIgnore ? " OR IGNORE " : "")
                    + " INTO " + destTable + " (name,value) SELECT name,value FROM "
                    + sourceTable + " WHERE name=?");
            deleteStmt = db.compileStatement("DELETE FROM " + sourceTable + " WHERE name=?");

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
    }

    /**
     * Move any settings with the given prefixes from the source table to the
     * destination table.
     */
    private void movePrefixedSettingsToNewTable(
            SQLiteDatabase db, String sourceTable, String destTable, String[] prefixesToMove) {
        SQLiteStatement insertStmt = null;
        SQLiteStatement deleteStmt = null;

        db.beginTransaction();
        try {
            insertStmt = db.compileStatement("INSERT INTO " + destTable
                    + " (name,value) SELECT name,value FROM " + sourceTable
                    + " WHERE substr(name,0,?)=?");
            deleteStmt = db.compileStatement(
                    "DELETE FROM " + sourceTable + " WHERE substr(name,0,?)=?");

            for (String prefix : prefixesToMove) {
                insertStmt.bindLong(1, prefix.length() + 1);
                insertStmt.bindString(2, prefix);
                insertStmt.execute();

                deleteStmt.bindLong(1, prefix.length() + 1);
                deleteStmt.bindString(2, prefix);
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
    }

    private void upgradeLockPatternLocation(SQLiteDatabase db) {
        Cursor c = db.query(TABLE_SYSTEM, new String[] {"_id", "value"}, "name='lock_pattern'",
                null, null, null, null);
        if (c.getCount() > 0) {
            c.moveToFirst();
            String lockPattern = c.getString(1);
            if (!TextUtils.isEmpty(lockPattern)) {
                // Convert lock pattern
                try {
                    LockPatternUtils lpu = new LockPatternUtils(mContext);
                    List<LockPatternView.Cell> cellPattern =
                            LockPatternUtils.stringToPattern(lockPattern);
                    lpu.saveLockPattern(cellPattern);
                } catch (IllegalArgumentException e) {
                    // Don't want corrupted lock pattern to hang the reboot process
                }
            }
            c.close();
            db.delete(TABLE_SYSTEM, "name='lock_pattern'", null);
        } else {
            c.close();
        }
    }

    private void upgradeScreenTimeoutFromNever(SQLiteDatabase db) {
        // See if the timeout is -1 (for "Never").
        Cursor c = db.query(TABLE_SYSTEM, new String[] { "_id", "value" }, "name=? AND value=?",
                new String[] { Settings.System.SCREEN_OFF_TIMEOUT, "-1" },
                null, null, null);

        SQLiteStatement stmt = null;
        if (c.getCount() > 0) {
            c.close();
            try {
                stmt = db.compileStatement("INSERT OR REPLACE INTO system(name,value)"
                        + " VALUES(?,?);");

                // Set the timeout to 30 minutes in milliseconds
                loadSetting(stmt, Settings.System.SCREEN_OFF_TIMEOUT,
                        Integer.toString(30 * 60 * 1000));
            } finally {
                if (stmt != null) stmt.close();
            }
        } else {
            c.close();
        }
    }

    private void upgradeVibrateSettingFromNone(SQLiteDatabase db) {
        int vibrateSetting = getIntValueFromSystem(db, Settings.System.VIBRATE_ON, 0);
        // If the ringer vibrate value is invalid, set it to the default
        if ((vibrateSetting & 3) == AudioManager.VIBRATE_SETTING_OFF) {
            vibrateSetting = AudioService.getValueForVibrateSetting(0,
                    AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_ONLY_SILENT);
        }
        // Apply the same setting to the notification vibrate value
        vibrateSetting = AudioService.getValueForVibrateSetting(vibrateSetting,
                AudioManager.VIBRATE_TYPE_NOTIFICATION, vibrateSetting);

        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR REPLACE INTO system(name,value)"
                    + " VALUES(?,?);");
            loadSetting(stmt, Settings.System.VIBRATE_ON, vibrateSetting);
        } finally {
            if (stmt != null)
                stmt.close();
        }
    }

    private void upgradeScreenTimeout(SQLiteDatabase db) {
        // Change screen timeout to current default
        db.beginTransaction();
        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR REPLACE INTO system(name,value)"
                    + " VALUES(?,?);");
            loadIntegerSetting(stmt, Settings.System.SCREEN_OFF_TIMEOUT,
                    R.integer.def_screen_off_timeout);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            if (stmt != null)
                stmt.close();
        }
    }

    private void upgradeAutoBrightness(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            String value =
                    mContext.getResources().getBoolean(
                    R.bool.def_screen_brightness_automatic_mode) ? "1" : "0";
            db.execSQL("INSERT OR REPLACE INTO system(name,value) values('" +
                    Settings.System.SCREEN_BRIGHTNESS_MODE + "','" + value + "');");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Loads the default set of bookmarked shortcuts from an xml file.
     *
     * @param db The database to write the values into
     */
    private void loadBookmarks(SQLiteDatabase db) {
        ContentValues values = new ContentValues();

        PackageManager packageManager = mContext.getPackageManager();
        try {
            XmlResourceParser parser = mContext.getResources().getXml(R.xml.bookmarks);
            XmlUtils.beginDocument(parser, "bookmarks");

            final int depth = parser.getDepth();
            int type;

            while (((type = parser.next()) != XmlPullParser.END_TAG ||
                    parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {

                if (type != XmlPullParser.START_TAG) {
                    continue;
                }

                String name = parser.getName();
                if (!"bookmark".equals(name)) {
                    break;
                }

                String pkg = parser.getAttributeValue(null, "package");
                String cls = parser.getAttributeValue(null, "class");
                String shortcutStr = parser.getAttributeValue(null, "shortcut");
                String category = parser.getAttributeValue(null, "category");

                int shortcutValue = shortcutStr.charAt(0);
                if (TextUtils.isEmpty(shortcutStr)) {
                    Log.w(TAG, "Unable to get shortcut for: " + pkg + "/" + cls);
                    continue;
                }

                final Intent intent;
                final String title;
                if (pkg != null && cls != null) {
                    ActivityInfo info = null;
                    ComponentName cn = new ComponentName(pkg, cls);
                    try {
                        info = packageManager.getActivityInfo(cn, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        String[] packages = packageManager.canonicalToCurrentPackageNames(
                                new String[] { pkg });
                        cn = new ComponentName(packages[0], cls);
                        try {
                            info = packageManager.getActivityInfo(cn, 0);
                        } catch (PackageManager.NameNotFoundException e1) {
                            Log.w(TAG, "Unable to add bookmark: " + pkg + "/" + cls, e);
                            continue;
                        }
                    }

                    intent = new Intent(Intent.ACTION_MAIN, null);
                    intent.addCategory(Intent.CATEGORY_LAUNCHER);
                    intent.setComponent(cn);
                    title = info.loadLabel(packageManager).toString();
                } else if (category != null) {
                    intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category);
                    title = "";
                } else {
                    Log.w(TAG, "Unable to add bookmark for shortcut " + shortcutStr
                            + ": missing package/class or category attributes");
                    continue;
                }

                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                values.put(Settings.Bookmarks.INTENT, intent.toUri(0));
                values.put(Settings.Bookmarks.TITLE, title);
                values.put(Settings.Bookmarks.SHORTCUT, shortcutValue);
                db.delete("bookmarks", "shortcut = ?",
                        new String[] { Integer.toString(shortcutValue) });
                db.insert("bookmarks", null, values);
            }
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Got execption parsing bookmarks.", e);
        } catch (IOException e) {
            Log.w(TAG, "Got execption parsing bookmarks.", e);
        }
    }

    /**
     * Loads the default volume levels. It is actually inserting the index of
     * the volume array for each of the volume controls.
     *
     * @param db the database to insert the volume levels into
     */
    private void loadVolumeLevels(SQLiteDatabase db) {
        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                    + " VALUES(?,?);");

            loadSetting(stmt, Settings.System.VOLUME_MUSIC,
                    AudioService.getDefaultStreamVolume(AudioManager.STREAM_MUSIC));
            loadSetting(stmt, Settings.System.VOLUME_RING,
                    AudioService.getDefaultStreamVolume(AudioManager.STREAM_RING));
            loadSetting(stmt, Settings.System.VOLUME_SYSTEM,
                    AudioService.getDefaultStreamVolume(AudioManager.STREAM_SYSTEM));
            loadSetting(
                    stmt,
                    Settings.System.VOLUME_VOICE,
                    AudioService.getDefaultStreamVolume(AudioManager.STREAM_VOICE_CALL));
            loadSetting(stmt, Settings.System.VOLUME_ALARM,
                    AudioService.getDefaultStreamVolume(AudioManager.STREAM_ALARM));
            loadSetting(
                    stmt,
                    Settings.System.VOLUME_NOTIFICATION,
                    AudioService.getDefaultStreamVolume(AudioManager.STREAM_NOTIFICATION));
            loadSetting(
                    stmt,
                    Settings.System.VOLUME_BLUETOOTH_SCO,
                    AudioService.getDefaultStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO));

            // By default:
            // - ringtones, notification, system and music streams are affected by ringer mode
            // on non voice capable devices (tablets)
            // - ringtones, notification and system streams are affected by ringer mode
            // on voice capable devices (phones)
            int ringerModeAffectedStreams = (1 << AudioManager.STREAM_RING) |
                                            (1 << AudioManager.STREAM_NOTIFICATION) |
                                            (1 << AudioManager.STREAM_SYSTEM) |
                                            (1 << AudioManager.STREAM_SYSTEM_ENFORCED);
            if (!mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_voice_capable)) {
                ringerModeAffectedStreams |= (1 << AudioManager.STREAM_MUSIC);
            }
            loadSetting(stmt, Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                    ringerModeAffectedStreams);

            loadSetting(stmt, Settings.System.MUTE_STREAMS_AFFECTED,
                    ((1 << AudioManager.STREAM_MUSIC) |
                     (1 << AudioManager.STREAM_RING) |
                     (1 << AudioManager.STREAM_NOTIFICATION) |
                     (1 << AudioManager.STREAM_SYSTEM)));
        } finally {
            if (stmt != null) stmt.close();
        }

        loadVibrateWhenRingingSetting(db);
    }

    private void loadVibrateSetting(SQLiteDatabase db, boolean deleteOld) {
        if (deleteOld) {
            db.execSQL("DELETE FROM system WHERE name='" + Settings.System.VIBRATE_ON + "'");
        }

        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                    + " VALUES(?,?);");

            // Vibrate on by default for ringer, on for notification
            int vibrate = 0;
            vibrate = AudioService.getValueForVibrateSetting(vibrate,
                    AudioManager.VIBRATE_TYPE_NOTIFICATION,
                    AudioManager.VIBRATE_SETTING_ONLY_SILENT);
            vibrate |= AudioService.getValueForVibrateSetting(vibrate,
                    AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_ONLY_SILENT);
            loadSetting(stmt, Settings.System.VIBRATE_ON, vibrate);
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void loadVibrateWhenRingingSetting(SQLiteDatabase db) {
        // The default should be off. VIBRATE_SETTING_ONLY_SILENT should also be ignored here.
        // Phone app should separately check whether AudioManager#getRingerMode() returns
        // RINGER_MODE_VIBRATE, with which the device should vibrate anyway.
        int vibrateSetting = getIntValueFromSystem(db, Settings.System.VIBRATE_ON,
                AudioManager.VIBRATE_SETTING_OFF);
        boolean vibrateWhenRinging = ((vibrateSetting & 3) == AudioManager.VIBRATE_SETTING_ON);

        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                    + " VALUES(?,?);");
            loadSetting(stmt, Settings.System.VIBRATE_WHEN_RINGING, vibrateWhenRinging ? 1 : 0);
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void loadSettings(SQLiteDatabase db) {
        loadSystemSettings(db);
        loadSecureSettings(db);
        // The global table only exists for the 'owner' user
        if (mUserHandle == UserHandle.USER_OWNER) {
            loadGlobalSettings(db);
        }
    }

    private void loadSystemSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                    + " VALUES(?,?);");

            loadBooleanSetting(stmt, Settings.System.DIM_SCREEN,
                    R.bool.def_dim_screen);
            loadIntegerSetting(stmt, Settings.System.SCREEN_OFF_TIMEOUT,
                    R.integer.def_screen_off_timeout);

            // Set default cdma DTMF type
            loadSetting(stmt, Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, 0);

            // Set default hearing aid
            loadSetting(stmt, Settings.System.HEARING_AID, 0);

            // Set default tty mode
            loadSetting(stmt, Settings.System.TTY_MODE, 0);

            loadIntegerSetting(stmt, Settings.System.SCREEN_BRIGHTNESS,
                    R.integer.def_screen_brightness);

            loadBooleanSetting(stmt, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    R.bool.def_screen_brightness_automatic_mode);

            loadDefaultAnimationSettings(stmt);

            loadBooleanSetting(stmt, Settings.System.ACCELEROMETER_ROTATION,
                    R.bool.def_accelerometer_rotation);

            loadDefaultHapticSettings(stmt);

            loadBooleanSetting(stmt, Settings.System.NOTIFICATION_LIGHT_PULSE,
                    R.bool.def_notification_pulse);

            loadUISoundEffectsSettings(stmt);

            loadIntegerSetting(stmt, Settings.System.POINTER_SPEED,
                    R.integer.def_pointer_speed);
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void loadUISoundEffectsSettings(SQLiteStatement stmt) {
        loadBooleanSetting(stmt, Settings.System.DTMF_TONE_WHEN_DIALING,
                R.bool.def_dtmf_tones_enabled);
        loadBooleanSetting(stmt, Settings.System.SOUND_EFFECTS_ENABLED,
                R.bool.def_sound_effects_enabled);
        loadBooleanSetting(stmt, Settings.System.HAPTIC_FEEDBACK_ENABLED,
                R.bool.def_haptic_feedback);

        loadIntegerSetting(stmt, Settings.System.LOCKSCREEN_SOUNDS_ENABLED,
            R.integer.def_lockscreen_sounds_enabled);
    }

    private void loadDefaultAnimationSettings(SQLiteStatement stmt) {
        loadFractionSetting(stmt, Settings.System.WINDOW_ANIMATION_SCALE,
                R.fraction.def_window_animation_scale, 1);
        loadFractionSetting(stmt, Settings.System.TRANSITION_ANIMATION_SCALE,
                R.fraction.def_window_transition_scale, 1);
    }

    private void loadDefaultHapticSettings(SQLiteStatement stmt) {
        loadBooleanSetting(stmt, Settings.System.HAPTIC_FEEDBACK_ENABLED,
                R.bool.def_haptic_feedback);
    }

    private void loadSecureSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                    + " VALUES(?,?);");

            loadStringSetting(stmt, Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                    R.string.def_location_providers_allowed);

            String wifiWatchList = SystemProperties.get("ro.com.android.wifi-watchlist");
            if (!TextUtils.isEmpty(wifiWatchList)) {
                loadSetting(stmt, Settings.Secure.WIFI_WATCHDOG_WATCH_LIST, wifiWatchList);
            }

            // Don't do this.  The SystemServer will initialize ADB_ENABLED from a
            // persistent system property instead.
            //loadSetting(stmt, Settings.Secure.ADB_ENABLED, 0);

            // Allow mock locations default, based on build
            loadSetting(stmt, Settings.Secure.ALLOW_MOCK_LOCATION,
                    "1".equals(SystemProperties.get("ro.allow.mock.location")) ? 1 : 0);

            loadSecure35Settings(stmt);

            loadBooleanSetting(stmt, Settings.Secure.MOUNT_PLAY_NOTIFICATION_SND,
                    R.bool.def_mount_play_notification_snd);

            loadBooleanSetting(stmt, Settings.Secure.MOUNT_UMS_AUTOSTART,
                    R.bool.def_mount_ums_autostart);

            loadBooleanSetting(stmt, Settings.Secure.MOUNT_UMS_PROMPT,
                    R.bool.def_mount_ums_prompt);

            loadBooleanSetting(stmt, Settings.Secure.MOUNT_UMS_NOTIFY_ENABLED,
                    R.bool.def_mount_ums_notify_enabled);

            loadBooleanSetting(stmt, Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION,
                    R.bool.def_accessibility_script_injection);

            loadStringSetting(stmt, Settings.Secure.ACCESSIBILITY_WEB_CONTENT_KEY_BINDINGS,
                    R.string.def_accessibility_web_content_key_bindings);

            loadIntegerSetting(stmt, Settings.Secure.LONG_PRESS_TIMEOUT,
                    R.integer.def_long_press_timeout_millis);

            loadBooleanSetting(stmt, Settings.Secure.TOUCH_EXPLORATION_ENABLED,
                    R.bool.def_touch_exploration_enabled);

            loadBooleanSetting(stmt, Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD,
                    R.bool.def_accessibility_speak_password);

            loadStringSetting(stmt, Settings.Secure.ACCESSIBILITY_SCREEN_READER_URL,
                    R.string.def_accessibility_screen_reader_url);

            if (SystemProperties.getBoolean("ro.lockscreen.disable.default", false) == true) {
                loadSetting(stmt, Settings.System.LOCKSCREEN_DISABLED, "1");
            } else {
                loadBooleanSetting(stmt, Settings.System.LOCKSCREEN_DISABLED,
                        R.bool.def_lockscreen_disabled);
            }

            loadBooleanSetting(stmt, Settings.Secure.SCREENSAVER_ENABLED,
                    com.android.internal.R.bool.config_dreamsEnabledByDefault);
            loadBooleanSetting(stmt, Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                    com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault);
            loadBooleanSetting(stmt, Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                    com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault);
            loadStringSetting(stmt, Settings.Secure.SCREENSAVER_COMPONENTS,
                    com.android.internal.R.string.config_dreamsDefaultComponent);
            loadStringSetting(stmt, Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT,
                    com.android.internal.R.string.config_dreamsDefaultComponent);

            loadBooleanSetting(stmt, Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                    R.bool.def_accessibility_display_magnification_enabled);

            loadFractionSetting(stmt, Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                    R.fraction.def_accessibility_display_magnification_scale, 1);

            loadBooleanSetting(stmt,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_AUTO_UPDATE,
                    R.bool.def_accessibility_display_magnification_auto_update);

            loadBooleanSetting(stmt, Settings.Secure.USER_SETUP_COMPLETE,
                    R.bool.def_user_setup_complete);

            loadStringSetting(stmt, Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                        R.string.def_immersive_mode_confirmations);

            loadBooleanSetting(stmt, Settings.Secure.INSTALL_NON_MARKET_APPS,
                    R.bool.def_install_non_market_apps);

            loadBooleanSetting(stmt, Settings.Secure.WAKE_GESTURE_ENABLED,
                    R.bool.def_wake_gesture_enabled);

            loadIntegerSetting(stmt, Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                    R.integer.def_lock_screen_show_notifications);

            loadBooleanSetting(stmt, Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                    R.bool.def_lock_screen_allow_private_notifications);

            loadIntegerSetting(stmt, Settings.Secure.SLEEP_TIMEOUT,
                    R.integer.def_sleep_timeout);
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void loadSecure35Settings(SQLiteStatement stmt) {
        loadBooleanSetting(stmt, Settings.Secure.BACKUP_ENABLED,
                R.bool.def_backup_enabled);

        loadStringSetting(stmt, Settings.Secure.BACKUP_TRANSPORT,
                R.string.def_backup_transport);
    }

    private void loadGlobalSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO global(name,value)"
                    + " VALUES(?,?);");

            // --- Previously in 'system'
            loadBooleanSetting(stmt, Settings.Global.AIRPLANE_MODE_ON,
                    R.bool.def_airplane_mode_on);

            loadBooleanSetting(stmt, Settings.Global.THEATER_MODE_ON,
                    R.bool.def_theater_mode_on);

            loadStringSetting(stmt, Settings.Global.AIRPLANE_MODE_RADIOS,
                    R.string.def_airplane_mode_radios);

            loadStringSetting(stmt, Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                    R.string.airplane_mode_toggleable_radios);

            loadBooleanSetting(stmt, Settings.Global.ASSISTED_GPS_ENABLED,
                    R.bool.assisted_gps_enabled);

            loadBooleanSetting(stmt, Settings.Global.AUTO_TIME,
                    R.bool.def_auto_time); // Sync time to NITZ

            loadBooleanSetting(stmt, Settings.Global.AUTO_TIME_ZONE,
                    R.bool.def_auto_time_zone); // Sync timezone to NITZ

            loadSetting(stmt, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    ("1".equals(SystemProperties.get("ro.kernel.qemu")) ||
                        mContext.getResources().getBoolean(R.bool.def_stay_on_while_plugged_in))
                     ? 1 : 0);

            loadIntegerSetting(stmt, Settings.Global.WIFI_SLEEP_POLICY,
                    R.integer.def_wifi_sleep_policy);

            loadSetting(stmt, Settings.Global.MODE_RINGER,
                    AudioManager.RINGER_MODE_NORMAL);

            // --- Previously in 'secure'
            loadBooleanSetting(stmt, Settings.Global.PACKAGE_VERIFIER_ENABLE,
                    R.bool.def_package_verifier_enable);

            loadBooleanSetting(stmt, Settings.Global.WIFI_ON,
                    R.bool.def_wifi_on);

            loadBooleanSetting(stmt, Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                    R.bool.def_networks_available_notification_on);

            loadBooleanSetting(stmt, Settings.Global.BLUETOOTH_ON,
                    R.bool.def_bluetooth_on);

            // Enable or disable Cell Broadcast SMS
            loadSetting(stmt, Settings.Global.CDMA_CELL_BROADCAST_SMS,
                    RILConstants.CDMA_CELL_BROADCAST_SMS_DISABLED);

            // Data roaming default, based on build
            loadSetting(stmt, Settings.Global.DATA_ROAMING,
                    "true".equalsIgnoreCase(
                            SystemProperties.get("ro.com.android.dataroaming",
                                    "false")) ? 1 : 0);

            loadBooleanSetting(stmt, Settings.Global.DEVICE_PROVISIONED,
                    R.bool.def_device_provisioned);

            final int maxBytes = mContext.getResources().getInteger(
                    R.integer.def_download_manager_max_bytes_over_mobile);
            if (maxBytes > 0) {
                loadSetting(stmt, Settings.Global.DOWNLOAD_MAX_BYTES_OVER_MOBILE,
                        Integer.toString(maxBytes));
            }

            final int recommendedMaxBytes = mContext.getResources().getInteger(
                    R.integer.def_download_manager_recommended_max_bytes_over_mobile);
            if (recommendedMaxBytes > 0) {
                loadSetting(stmt, Settings.Global.DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE,
                        Integer.toString(recommendedMaxBytes));
            }

            // Mobile Data default, based on build
            loadSetting(stmt, Settings.Global.MOBILE_DATA,
                    "true".equalsIgnoreCase(
                            SystemProperties.get("ro.com.android.mobiledata",
                                    "true")) ? 1 : 0);

            loadBooleanSetting(stmt, Settings.Global.NETSTATS_ENABLED,
                    R.bool.def_netstats_enabled);

            loadBooleanSetting(stmt, Settings.Global.USB_MASS_STORAGE_ENABLED,
                    R.bool.def_usb_mass_storage_enabled);

            loadIntegerSetting(stmt, Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT,
                    R.integer.def_max_dhcp_retries);

            loadBooleanSetting(stmt, Settings.Global.WIFI_DISPLAY_ON,
                    R.bool.def_wifi_display_on);

            loadStringSetting(stmt, Settings.Global.LOCK_SOUND,
                    R.string.def_lock_sound);
            loadStringSetting(stmt, Settings.Global.UNLOCK_SOUND,
                    R.string.def_unlock_sound);
            loadStringSetting(stmt, Settings.Global.TRUSTED_SOUND,
                    R.string.def_trusted_sound);
            loadIntegerSetting(stmt, Settings.Global.POWER_SOUNDS_ENABLED,
                    R.integer.def_power_sounds_enabled);
            loadStringSetting(stmt, Settings.Global.LOW_BATTERY_SOUND,
                    R.string.def_low_battery_sound);
            loadIntegerSetting(stmt, Settings.Global.DOCK_SOUNDS_ENABLED,
                    R.integer.def_dock_sounds_enabled);
            loadStringSetting(stmt, Settings.Global.DESK_DOCK_SOUND,
                    R.string.def_desk_dock_sound);
            loadStringSetting(stmt, Settings.Global.DESK_UNDOCK_SOUND,
                    R.string.def_desk_undock_sound);
            loadStringSetting(stmt, Settings.Global.CAR_DOCK_SOUND,
                    R.string.def_car_dock_sound);
            loadStringSetting(stmt, Settings.Global.CAR_UNDOCK_SOUND,
                    R.string.def_car_undock_sound);
            loadStringSetting(stmt, Settings.Global.WIRELESS_CHARGING_STARTED_SOUND,
                    R.string.def_wireless_charging_started_sound);

            loadIntegerSetting(stmt, Settings.Global.DOCK_AUDIO_MEDIA_ENABLED,
                    R.integer.def_dock_audio_media_enabled);

            loadSetting(stmt, Settings.Global.SET_INSTALL_LOCATION, 0);
            loadSetting(stmt, Settings.Global.DEFAULT_INSTALL_LOCATION,
                    PackageHelper.APP_INSTALL_AUTO);

            // Set default cdma emergency tone
            loadSetting(stmt, Settings.Global.EMERGENCY_TONE, 0);

            // Set default cdma call auto retry
            loadSetting(stmt, Settings.Global.CALL_AUTO_RETRY, 0);

            // Set default simplified carrier network settings to 0
            loadSetting(stmt, Settings.Global.HIDE_CARRIER_NETWORK_SETTINGS, 0);

            // Set the preferred network mode to target desired value or Default
            // value defined in RILConstants
            int type;
            type = RILConstants.PREFERRED_NETWORK_MODE;
            loadSetting(stmt, Settings.Global.PREFERRED_NETWORK_MODE, type);

            // Set the preferred cdma subscription source to target desired value or default
            // value defined in CdmaSubscriptionSourceManager
            type = SystemProperties.getInt("ro.telephony.default_cdma_sub",
                        CdmaSubscriptionSourceManager.PREFERRED_CDMA_SUBSCRIPTION);
            loadSetting(stmt, Settings.Global.CDMA_SUBSCRIPTION_MODE, type);

            loadIntegerSetting(stmt, Settings.Global.LOW_BATTERY_SOUND_TIMEOUT,
                    R.integer.def_low_battery_sound_timeout);

            loadIntegerSetting(stmt, Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE,
                    R.integer.def_wifi_scan_always_available);

            loadIntegerSetting(stmt, Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                    R.integer.def_heads_up_enabled);

            loadSetting(stmt, Settings.Global.DEVICE_NAME, getDefaultDeviceName());

            loadBooleanSetting(stmt, Settings.Global.GUEST_USER_ENABLED,
                    R.bool.def_guest_user_enabled);
            loadSetting(stmt, Settings.Global.ENHANCED_4G_MODE_ENABLED, ImsConfig.FeatureValueConstants.ON);
            // --- New global settings start here
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void loadSetting(SQLiteStatement stmt, String key, Object value) {
        stmt.bindString(1, key);
        stmt.bindString(2, value.toString());
        stmt.execute();
    }

    private void loadStringSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key, mContext.getResources().getString(resid));
    }

    private void loadBooleanSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key,
                mContext.getResources().getBoolean(resid) ? "1" : "0");
    }

    private void loadIntegerSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key,
                Integer.toString(mContext.getResources().getInteger(resid)));
    }

    private void loadFractionSetting(SQLiteStatement stmt, String key, int resid, int base) {
        loadSetting(stmt, key,
                Float.toString(mContext.getResources().getFraction(resid, base, base)));
    }

    private int getIntValueFromSystem(SQLiteDatabase db, String name, int defaultValue) {
        return getIntValueFromTable(db, TABLE_SYSTEM, name, defaultValue);
    }

    private int getIntValueFromTable(SQLiteDatabase db, String table, String name,
            int defaultValue) {
        String value = getStringValueFromTable(db, table, name, null);
        return (value != null) ? Integer.parseInt(value) : defaultValue;
    }

    private String getStringValueFromTable(SQLiteDatabase db, String table, String name,
            String defaultValue) {
        Cursor c = null;
        try {
            c = db.query(table, new String[] { Settings.System.VALUE }, "name='" + name + "'",
                    null, null, null, null);
            if (c != null && c.moveToFirst()) {
                String val = c.getString(0);
                return val == null ? defaultValue : val;
            }
        } finally {
            if (c != null) c.close();
        }
        return defaultValue;
    }

    private String getOldDefaultDeviceName() {
        return mContext.getResources().getString(R.string.def_device_name,
                Build.MANUFACTURER, Build.MODEL);
    }

    private String getDefaultDeviceName() {
        return mContext.getResources().getString(R.string.def_device_name_simple, Build.MODEL);
    }
}
