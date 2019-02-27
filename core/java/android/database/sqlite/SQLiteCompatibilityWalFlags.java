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

import android.annotation.TestApi;
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
@TestApi
public class SQLiteCompatibilityWalFlags {

    private static final String TAG = "SQLiteCompatibilityWalFlags";

    private static volatile boolean sInitialized;
    private static volatile boolean sLegacyCompatibilityWalEnabled;
    private static volatile String sWALSyncMode;
    private static volatile long sTruncateSize = -1;
    // This flag is used to avoid recursive initialization due to circular dependency on Settings
    private static volatile boolean sCallingGlobalSettings;

    private SQLiteCompatibilityWalFlags() {
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public static boolean isLegacyCompatibilityWalEnabled() {
        initIfNeeded();
        return sLegacyCompatibilityWalEnabled;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public static String getWALSyncMode() {
        initIfNeeded();
        // The configurable WAL sync mode should only ever be used if the legacy compatibility
        // WAL is enabled. It should *not* have any effect if app developers explicitly turn on
        // WAL for their database using setWriteAheadLoggingEnabled. Throwing an exception here
        // adds an extra layer of checking that we never use it in the wrong place.
        if (!sLegacyCompatibilityWalEnabled) {
            throw new IllegalStateException("isLegacyCompatibilityWalEnabled() == false");
        }

        return sWALSyncMode;
    }

    /**
     * Override {@link com.android.internal.R.integer#db_wal_truncate_size}.
     *
     * @return the value set in the global setting, or -1 if a value is not set.
     *
     * @hide
     */
    @VisibleForTesting
    public static long getTruncateSize() {
        initIfNeeded();
        return sTruncateSize;
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
        sLegacyCompatibilityWalEnabled = parser.getBoolean(
                "legacy_compatibility_wal_enabled", false);
        sWALSyncMode = parser.getString("wal_syncmode", SQLiteGlobal.getWALSyncMode());
        sTruncateSize = parser.getInt("truncate_size", -1);
        Log.i(TAG, "Read compatibility WAL flags: legacy_compatibility_wal_enabled="
                + sLegacyCompatibilityWalEnabled + ", wal_syncmode=" + sWALSyncMode);
        sInitialized = true;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    @TestApi
    public static void reset() {
        sInitialized = false;
        sLegacyCompatibilityWalEnabled = false;
        sWALSyncMode = null;
    }
}
