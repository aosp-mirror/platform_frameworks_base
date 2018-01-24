package com.android.server.backup.internal;

/**
 * Current state of the backup.
 */
enum BackupState {
    INITIAL,
    BACKUP_PM,
    RUNNING_QUEUE,
    FINAL
}
