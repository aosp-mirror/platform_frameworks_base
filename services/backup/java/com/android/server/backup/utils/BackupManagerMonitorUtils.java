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

package com.android.server.backup.utils;

import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME;

import static com.android.server.backup.RefactoredBackupManagerService.DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.app.backup.BackupManagerMonitor;
import android.app.backup.IBackupManagerMonitor;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Utility methods to communicate with BackupManagerMonitor.
 */
public class BackupManagerMonitorUtils {
    /**
     * Notifies monitor about the event.
     *
     * Calls {@link IBackupManagerMonitor#onEvent(Bundle)} with a bundle representing current event.
     *
     * @param monitor - implementation of {@link IBackupManagerMonitor} to notify.
     * @param id - event id.
     * @param pkg - package event is related to.
     * @param category - event category.
     * @param extras - additional event data.
     * @return <code>monitor</code> if call succeeded and <code>null</code> otherwise.
     */
    public static IBackupManagerMonitor monitorEvent(IBackupManagerMonitor monitor, int id,
            PackageInfo pkg, int category, Bundle extras) {
        if (monitor != null) {
            try {
                Bundle bundle = new Bundle();
                bundle.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID, id);
                bundle.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY, category);
                if (pkg != null) {
                    bundle.putString(EXTRA_LOG_EVENT_PACKAGE_NAME,
                            pkg.packageName);
                    bundle.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_VERSION,
                            pkg.versionCode);
                }
                if (extras != null) {
                    bundle.putAll(extras);
                }
                monitor.onEvent(bundle);
                return monitor;
            } catch (RemoteException e) {
                if (DEBUG) {
                    Slog.w(TAG, "backup manager monitor went away");
                }
            }
        }
        return null;
    }

    /**
     * Adds given key-value pair in the bundle and returns the bundle. If bundle was null it will
     * be created.
     *
     * @param extras - bundle where to add key-value to, if null a new bundle will be created.
     * @param key - key.
     * @param value - value.
     * @return extras if it was not null and new bundle otherwise.
     */
    public static Bundle putMonitoringExtra(Bundle extras, String key, String value) {
        if (extras == null) {
            extras = new Bundle();
        }
        extras.putString(key, value);
        return extras;
    }

    /**
     * Adds given key-value pair in the bundle and returns the bundle. If bundle was null it will
     * be created.
     *
     * @param extras - bundle where to add key-value to, if null a new bundle will be created.
     * @param key - key.
     * @param value - value.
     * @return extras if it was not null and new bundle otherwise.
     */
    public static Bundle putMonitoringExtra(Bundle extras, String key, long value) {
        if (extras == null) {
            extras = new Bundle();
        }
        extras.putLong(key, value);
        return extras;
    }

    /**
     * Adds given key-value pair in the bundle and returns the bundle. If bundle was null it will
     * be created.
     *
     * @param extras - bundle where to add key-value to, if null a new bundle will be created.
     * @param key - key.
     * @param value - value.
     * @return extras if it was not null and new bundle otherwise.
     */
    public static Bundle putMonitoringExtra(Bundle extras, String key, boolean value) {
        if (extras == null) {
            extras = new Bundle();
        }
        extras.putBoolean(key, value);
        return extras;
    }
}
