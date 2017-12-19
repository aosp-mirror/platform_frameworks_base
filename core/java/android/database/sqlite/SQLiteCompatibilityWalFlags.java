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

package android.database.sqlite;

import android.app.ActivityThread;
import android.app.Application;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Helper class for accessing
 * {@link Settings.Global#SQLITE_COMPATIBILITY_WAL_FLAGS global compatibility WAL settings}.
 *
 * <p>The value of {@link Settings.Global#SQLITE_COMPATIBILITY_WAL_FLAGS} is cached on first access
 * for consistent behavior across all connections opened in the process.
 * @hide
 */
public class SQLiteCompatibilityWalFlags {

    private static final String TAG = "SQLiteCompatibilityWalFlags";

    private static volatile boolean sInitialized;
    private static volatile boolean sFlagsSet;
    private static volatile boolean sCompatibilityWalSupported;
    private static volatile String sWALSyncMode;
    // This flag is used to avoid recursive initialization due to circular dependency on Settings
    private static volatile boolean sCallingGlobalSettings;

    /**
     * @hide
     */
    @VisibleForTesting
    public static boolean areFlagsSet() {
        initIfNeeded();
        return sFlagsSet;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public static boolean isCompatibilityWalSupported() {
        initIfNeeded();
        return sCompatibilityWalSupported;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public static String getWALSyncMode() {
        initIfNeeded();
        return sWALSyncMode;
    }

    private static void initIfNeeded() {
        if (sInitialized || sCallingGlobalSettings) {
            return;
        }
        ActivityThread activityThread = ActivityThread.currentActivityThread();
        Application app = activityThread == null ? null : activityThread.getApplication();
        String flags = null;
        if (app == null) {
            Log.w(TAG, "Cannot read global setting "
                    + Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS + " - "
                    + "Application state not available");
        } else {
            try {
                sCallingGlobalSettings = true;
                flags = Settings.Global.getString(app.getContentResolver(),
                        Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS);
            } finally {
                sCallingGlobalSettings = false;
            }
        }

        init(flags);
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public static void init(String flags) {
        if (TextUtils.isEmpty(flags)) {
            sInitialized = true;
            return;
        }
        KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(flags);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Setting has invalid format: " + flags, e);
            sInitialized = true;
            return;
        }
        sCompatibilityWalSupported = parser.getBoolean("compatibility_wal_supported",
                SQLiteGlobal.isCompatibilityWalSupported());
        sWALSyncMode = parser.getString("wal_syncmode", SQLiteGlobal.getWALSyncMode());
        Log.i(TAG, "Read compatibility WAL flags: compatibility_wal_supported="
                + sCompatibilityWalSupported + ", wal_syncmode=" + sWALSyncMode);
        sFlagsSet = true;
        sInitialized = true;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public static void reset() {
        sInitialized = false;
        sFlagsSet = false;
        sCompatibilityWalSupported = false;
        sWALSyncMode = null;
    }
}
