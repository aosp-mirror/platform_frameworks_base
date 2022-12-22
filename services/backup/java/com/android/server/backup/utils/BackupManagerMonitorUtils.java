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

import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_AGENT_LOGGING_RESULTS;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_AGENT_LOGGING_RESULTS;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.IBackupAgent;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.BackupRestoreEventLogger.DataTypeResult;
import android.app.backup.IBackupManagerMonitor;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility methods to communicate with BackupManagerMonitor.
 */
public class BackupManagerMonitorUtils {
    /**
     * Timeout for how long we wait before we give up on getting logs from a {@link IBackupAgent}.
     * We expect this to be very fast since the agent immediately returns whatever logs have been
     * accumulated. The timeout adds a bit more security and ensures we don't hang the B&R waiting
     * for non-essential logs.
     */
    private static final int AGENT_LOGGER_RESULTS_TIMEOUT_MILLIS = 500;

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
    @Nullable
    public static IBackupManagerMonitor monitorEvent(
            @Nullable IBackupManagerMonitor monitor,
            int id,
            PackageInfo pkg,
            int category,
            Bundle extras) {
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
                    bundle.putLong(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION,
                            pkg.getLongVersionCode());
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
     * Extracts logging results from the provided {@code agent} and notifies the {@code monitor}
     * about them.
     *
     * <p>Note that this method does two separate binder calls (one to the agent and one to the
     * monitor).
     *
     * @param monitor - implementation of {@link IBackupManagerMonitor} to notify.
     * @param pkg - package the {@code agent} belongs to.
     * @param agent - the {@link IBackupAgent} to retrieve logs from.
     * @return {@code null} if the monitor is null. {@code monitor} if we fail to retrieve the logs
     *     from the {@code agent}. Otherwise, the result of {@link
     *     #monitorEvent(IBackupManagerMonitor, int, PackageInfo, int, Bundle)}.
     */
    public static IBackupManagerMonitor monitorAgentLoggingResults(
            @Nullable IBackupManagerMonitor monitor, PackageInfo pkg, IBackupAgent agent) {
        if (monitor == null) {
            return null;
        }

        try {
            AndroidFuture<List<DataTypeResult>> resultsFuture =
                    new AndroidFuture<>();
            agent.getLoggerResults(resultsFuture);
            return sendAgentLoggingResults(monitor, pkg,
                    resultsFuture.get(AGENT_LOGGER_RESULTS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            Slog.w(TAG, "Timeout while waiting to retrieve logging results from agent", e);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to retrieve logging results from agent", e);
        }
        return monitor;
    }

    public static IBackupManagerMonitor sendAgentLoggingResults(
            @NonNull IBackupManagerMonitor monitor, PackageInfo pkg, List<DataTypeResult> results) {
        Bundle loggerResultsBundle = new Bundle();
        loggerResultsBundle.putParcelableList(
                EXTRA_LOG_AGENT_LOGGING_RESULTS, results);
        return monitorEvent(
                monitor,
                LOG_EVENT_ID_AGENT_LOGGING_RESULTS,
                pkg,
                LOG_EVENT_CATEGORY_AGENT,
                loggerResultsBundle);
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
