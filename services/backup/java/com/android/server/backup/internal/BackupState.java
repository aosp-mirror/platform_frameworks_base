package com.android.server.backup.internal;

/**
 * Current state of the backup.
 */
enum BackupState {
    INITIAL,
    RUNNING_QUEUE,
    FINAL
}
