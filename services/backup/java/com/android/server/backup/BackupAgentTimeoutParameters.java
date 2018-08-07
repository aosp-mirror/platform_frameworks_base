/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.ContentResolver;
import android.os.Handler;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.KeyValueSettingObserver;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Configure backup and restore agent timeouts.
 *
 * <p>These timeout parameters are stored in Settings.Global to be configurable flags with P/H. They
 * are represented as a comma-delimited key value list.
 */
public class BackupAgentTimeoutParameters extends KeyValueSettingObserver {
    private static final String TAG = "BackupAgentTimeout";

    @VisibleForTesting
    public static final String SETTING = Settings.Global.BACKUP_AGENT_TIMEOUT_PARAMETERS;

    @VisibleForTesting
    public static final String SETTING_KV_BACKUP_AGENT_TIMEOUT_MILLIS =
            "kv_backup_agent_timeout_millis";

    @VisibleForTesting
    public static final String SETTING_FULL_BACKUP_AGENT_TIMEOUT_MILLIS =
            "full_backup_agent_timeout_millis";

    @VisibleForTesting
    public static final String SETTING_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS =
            "shared_backup_agent_timeout_millis";

    @VisibleForTesting
    public static final String SETTING_RESTORE_AGENT_TIMEOUT_MILLIS =
            "restore_agent_timeout_millis";

    @VisibleForTesting
    public static final String SETTING_RESTORE_AGENT_FINISHED_TIMEOUT_MILLIS =
            "restore_agent_finished_timeout_millis";

    // Default values
    @VisibleForTesting public static final long DEFAULT_KV_BACKUP_AGENT_TIMEOUT_MILLIS = 30 * 1000;

    @VisibleForTesting
    public static final long DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS = 5 * 60 * 1000;

    @VisibleForTesting
    public static final long DEFAULT_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS = 30 * 60 * 1000;

    @VisibleForTesting public static final long DEFAULT_RESTORE_AGENT_TIMEOUT_MILLIS = 60 * 1000;

    @VisibleForTesting
    public static final long DEFAULT_RESTORE_AGENT_FINISHED_TIMEOUT_MILLIS = 30 * 1000;

    @GuardedBy("mLock")
    private long mKvBackupAgentTimeoutMillis;

    @GuardedBy("mLock")
    private long mFullBackupAgentTimeoutMillis;

    @GuardedBy("mLock")
    private long mSharedBackupAgentTimeoutMillis;

    @GuardedBy("mLock")
    private long mRestoreAgentTimeoutMillis;

    @GuardedBy("mLock")
    private long mRestoreAgentFinishedTimeoutMillis;

    private final Object mLock = new Object();

    public BackupAgentTimeoutParameters(Handler handler, ContentResolver resolver) {
        super(handler, resolver, Settings.Global.getUriFor(SETTING));
    }

    public String getSettingValue(ContentResolver resolver) {
        return Settings.Global.getString(resolver, SETTING);
    }

    public void update(KeyValueListParser parser) {
        synchronized (mLock) {
            mKvBackupAgentTimeoutMillis =
                    parser.getLong(
                            SETTING_KV_BACKUP_AGENT_TIMEOUT_MILLIS,
                            DEFAULT_KV_BACKUP_AGENT_TIMEOUT_MILLIS);
            mFullBackupAgentTimeoutMillis =
                    parser.getLong(
                            SETTING_FULL_BACKUP_AGENT_TIMEOUT_MILLIS,
                            DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS);
            mSharedBackupAgentTimeoutMillis =
                    parser.getLong(
                            SETTING_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS,
                            DEFAULT_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS);
            mRestoreAgentTimeoutMillis =
                    parser.getLong(
                            SETTING_RESTORE_AGENT_TIMEOUT_MILLIS,
                            DEFAULT_RESTORE_AGENT_TIMEOUT_MILLIS);
            mRestoreAgentFinishedTimeoutMillis =
                    parser.getLong(
                            SETTING_RESTORE_AGENT_FINISHED_TIMEOUT_MILLIS,
                            DEFAULT_RESTORE_AGENT_FINISHED_TIMEOUT_MILLIS);
        }
    }

    public long getKvBackupAgentTimeoutMillis() {
        synchronized (mLock) {
            if (BackupManagerService.DEBUG_SCHEDULING) {
                Slog.v(TAG, "getKvBackupAgentTimeoutMillis(): " + mKvBackupAgentTimeoutMillis);
            }
            return mKvBackupAgentTimeoutMillis;
        }
    }

    public long getFullBackupAgentTimeoutMillis() {
        synchronized (mLock) {
            if (BackupManagerService.DEBUG_SCHEDULING) {
                Slog.v(TAG, "getFullBackupAgentTimeoutMillis(): " + mFullBackupAgentTimeoutMillis);
            }
            return mFullBackupAgentTimeoutMillis;
        }
    }

    public long getSharedBackupAgentTimeoutMillis() {
        synchronized (mLock) {
            if (BackupManagerService.DEBUG_SCHEDULING) {
                Slog.v(
                        TAG,
                        "getSharedBackupAgentTimeoutMillis(): " + mSharedBackupAgentTimeoutMillis);
            }
            return mSharedBackupAgentTimeoutMillis;
        }
    }

    public long getRestoreAgentTimeoutMillis() {
        synchronized (mLock) {
            if (BackupManagerService.DEBUG_SCHEDULING) {
                Slog.v(TAG, "getRestoreAgentTimeoutMillis(): " + mRestoreAgentTimeoutMillis);
            }
            return mRestoreAgentTimeoutMillis;
        }
    }

    public long getRestoreAgentFinishedTimeoutMillis() {
        synchronized (mLock) {
            if (BackupManagerService.DEBUG_SCHEDULING) {
                Slog.v(
                        TAG,
                        "getRestoreAgentFinishedTimeoutMillis(): "
                                + mRestoreAgentFinishedTimeoutMillis);
            }
            return mRestoreAgentFinishedTimeoutMillis;
        }
    }
}
