package com.android.server.backup.utils;

import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME;

import android.app.backup.BackupManagerMonitor;
import android.app.backup.IBackupManagerMonitor;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.backup.RefactoredBackupManagerService;

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
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.w(RefactoredBackupManagerService.TAG, "backup manager monitor went away");
                }
            }
        }
        return null;
    }

    public static Bundle putMonitoringExtra(Bundle extras, String key, String value) {
        if (extras == null) {
            extras = new Bundle();
        }
        extras.putString(key, value);
        return extras;
    }

    private static Bundle putMonitoringExtra(Bundle extras, String key, int value) {
        if (extras == null) {
            extras = new Bundle();
        }
        extras.putInt(key, value);
        return extras;
    }

    public static Bundle putMonitoringExtra(Bundle extras, String key, long value) {
        if (extras == null) {
            extras = new Bundle();
        }
        extras.putLong(key, value);
        return extras;
    }

    public static Bundle putMonitoringExtra(Bundle extras, String key, boolean value) {
        if (extras == null) {
            extras = new Bundle();
        }
        extras.putBoolean(key, value);
        return extras;
    }
}
