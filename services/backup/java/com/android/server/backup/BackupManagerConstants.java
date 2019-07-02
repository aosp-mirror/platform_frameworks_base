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
 * limitations under the License
 */

package com.android.server.backup;

import static com.android.server.backup.BackupManagerService.DEBUG_SCHEDULING;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.KeyValueListParser;
import android.util.KeyValueSettingObserver;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Class to access backup manager constants.
 *
 * <p>The backup manager constants are encoded as a key value list separated by commas and stored as
 * a Settings.Secure.
 */
public class BackupManagerConstants extends KeyValueSettingObserver {
    private static final String TAG = "BackupManagerConstants";
    private static final String SETTING = Settings.Secure.BACKUP_MANAGER_CONSTANTS;

    // Key names stored in the secure settings value.
    @VisibleForTesting
    public static final String KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS =
            "key_value_backup_interval_milliseconds";

    @VisibleForTesting
    public static final String KEY_VALUE_BACKUP_FUZZ_MILLISECONDS =
            "key_value_backup_fuzz_milliseconds";

    @VisibleForTesting
    public static final String KEY_VALUE_BACKUP_REQUIRE_CHARGING =
            "key_value_backup_require_charging";

    @VisibleForTesting
    public static final String KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE =
            "key_value_backup_required_network_type";

    @VisibleForTesting
    public static final String FULL_BACKUP_INTERVAL_MILLISECONDS =
            "full_backup_interval_milliseconds";

    @VisibleForTesting
    public static final String FULL_BACKUP_REQUIRE_CHARGING = "full_backup_require_charging";

    @VisibleForTesting
    public static final String FULL_BACKUP_REQUIRED_NETWORK_TYPE =
            "full_backup_required_network_type";

    @VisibleForTesting
    public static final String BACKUP_FINISHED_NOTIFICATION_RECEIVERS =
            "backup_finished_notification_receivers";

    // Hard coded default values.
    @VisibleForTesting
    public static final long DEFAULT_KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS =
            4 * AlarmManager.INTERVAL_HOUR;

    @VisibleForTesting
    public static final long DEFAULT_KEY_VALUE_BACKUP_FUZZ_MILLISECONDS = 10 * 60 * 1000;

    @VisibleForTesting public static final boolean DEFAULT_KEY_VALUE_BACKUP_REQUIRE_CHARGING = true;
    @VisibleForTesting public static final int DEFAULT_KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE = 1;

    @VisibleForTesting
    public static final long DEFAULT_FULL_BACKUP_INTERVAL_MILLISECONDS =
            24 * AlarmManager.INTERVAL_HOUR;

    @VisibleForTesting public static final boolean DEFAULT_FULL_BACKUP_REQUIRE_CHARGING = true;
    @VisibleForTesting public static final int DEFAULT_FULL_BACKUP_REQUIRED_NETWORK_TYPE = 2;

    @VisibleForTesting
    public static final String DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS = "";

    // Backup manager constants.
    private long mKeyValueBackupIntervalMilliseconds;
    private long mKeyValueBackupFuzzMilliseconds;
    private boolean mKeyValueBackupRequireCharging;
    private int mKeyValueBackupRequiredNetworkType;
    private long mFullBackupIntervalMilliseconds;
    private boolean mFullBackupRequireCharging;
    private int mFullBackupRequiredNetworkType;
    private String[] mBackupFinishedNotificationReceivers;

    public BackupManagerConstants(Handler handler, ContentResolver resolver) {
        super(handler, resolver, Settings.Secure.getUriFor(SETTING));
    }

    public String getSettingValue(ContentResolver resolver) {
        return Settings.Secure.getString(resolver, SETTING);
    }

    public synchronized void update(KeyValueListParser parser) {
        mKeyValueBackupIntervalMilliseconds =
                parser.getLong(
                        KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS,
                        DEFAULT_KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS);
        mKeyValueBackupFuzzMilliseconds =
                parser.getLong(
                        KEY_VALUE_BACKUP_FUZZ_MILLISECONDS,
                        DEFAULT_KEY_VALUE_BACKUP_FUZZ_MILLISECONDS);
        mKeyValueBackupRequireCharging =
                parser.getBoolean(
                        KEY_VALUE_BACKUP_REQUIRE_CHARGING,
                        DEFAULT_KEY_VALUE_BACKUP_REQUIRE_CHARGING);
        mKeyValueBackupRequiredNetworkType =
                parser.getInt(
                        KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE,
                        DEFAULT_KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE);
        mFullBackupIntervalMilliseconds =
                parser.getLong(
                        FULL_BACKUP_INTERVAL_MILLISECONDS,
                        DEFAULT_FULL_BACKUP_INTERVAL_MILLISECONDS);
        mFullBackupRequireCharging =
                parser.getBoolean(
                        FULL_BACKUP_REQUIRE_CHARGING, DEFAULT_FULL_BACKUP_REQUIRE_CHARGING);
        mFullBackupRequiredNetworkType =
                parser.getInt(
                        FULL_BACKUP_REQUIRED_NETWORK_TYPE,
                        DEFAULT_FULL_BACKUP_REQUIRED_NETWORK_TYPE);
        String backupFinishedNotificationReceivers =
                parser.getString(
                        BACKUP_FINISHED_NOTIFICATION_RECEIVERS,
                        DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        if (backupFinishedNotificationReceivers.isEmpty()) {
            mBackupFinishedNotificationReceivers = new String[] {};
        } else {
            mBackupFinishedNotificationReceivers = backupFinishedNotificationReceivers.split(":");
        }
    }

    // The following are access methods for the individual parameters.
    // To be sure to retrieve values from the same set of settings,
    // group the calls of these methods in a block syncrhonized on
    // a reference of this object.
    public synchronized long getKeyValueBackupIntervalMilliseconds() {
        if (DEBUG_SCHEDULING) {
            Slog.v(
                    TAG,
                    "getKeyValueBackupIntervalMilliseconds(...) returns "
                            + mKeyValueBackupIntervalMilliseconds);
        }
        return mKeyValueBackupIntervalMilliseconds;
    }

    public synchronized long getKeyValueBackupFuzzMilliseconds() {
        if (DEBUG_SCHEDULING) {
            Slog.v(
                    TAG,
                    "getKeyValueBackupFuzzMilliseconds(...) returns "
                            + mKeyValueBackupFuzzMilliseconds);
        }
        return mKeyValueBackupFuzzMilliseconds;
    }

    public synchronized boolean getKeyValueBackupRequireCharging() {
        if (DEBUG_SCHEDULING) {
            Slog.v(
                    TAG,
                    "getKeyValueBackupRequireCharging(...) returns "
                            + mKeyValueBackupRequireCharging);
        }
        return mKeyValueBackupRequireCharging;
    }

    public synchronized int getKeyValueBackupRequiredNetworkType() {
        if (DEBUG_SCHEDULING) {
            Slog.v(
                    TAG,
                    "getKeyValueBackupRequiredNetworkType(...) returns "
                            + mKeyValueBackupRequiredNetworkType);
        }
        return mKeyValueBackupRequiredNetworkType;
    }

    public synchronized long getFullBackupIntervalMilliseconds() {
        if (DEBUG_SCHEDULING) {
            Slog.v(
                    TAG,
                    "getFullBackupIntervalMilliseconds(...) returns "
                            + mFullBackupIntervalMilliseconds);
        }
        return mFullBackupIntervalMilliseconds;
    }

    public synchronized boolean getFullBackupRequireCharging() {
        if (DEBUG_SCHEDULING) {
            Slog.v(TAG, "getFullBackupRequireCharging(...) returns " + mFullBackupRequireCharging);
        }
        return mFullBackupRequireCharging;
    }

    public synchronized int getFullBackupRequiredNetworkType() {
        if (DEBUG_SCHEDULING) {
            Slog.v(
                    TAG,
                    "getFullBackupRequiredNetworkType(...) returns "
                            + mFullBackupRequiredNetworkType);
        }
        return mFullBackupRequiredNetworkType;
    }

    /** Returns an array of package names that should be notified whenever a backup finishes. */
    public synchronized String[] getBackupFinishedNotificationReceivers() {
        if (DEBUG_SCHEDULING) {
            Slog.v(
                    TAG,
                    "getBackupFinishedNotificationReceivers(...) returns "
                            + TextUtils.join(", ", mBackupFinishedNotificationReceivers));
        }
        return mBackupFinishedNotificationReceivers;
    }
}
