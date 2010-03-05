/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.app.backup;

import android.app.backup.IRestoreSession;

/**
 * Direct interface to the Backup Manager Service that applications invoke on.  The only
 * operation currently needed is a simple notification that the app has made changes to
 * data it wishes to back up, so the system should run a backup pass.
 *
 * Apps will use the {@link android.app.backup.BackupManager} class rather than going through
 * this Binder interface directly.
 * 
 * {@hide}
 */
interface IBackupManager {
    /**
     * Tell the system service that the caller has made changes to its
     * data, and therefore needs to undergo an incremental backup pass.
     *
     * Any application can invoke this method for its own package, but
     * only callers who hold the android.permission.BACKUP permission
     * may invoke it for arbitrary packages.
     */
    void dataChanged(String packageName);

    /**
     * Erase all backed-up data for the given package from the storage
     * destination.
     *
     * Any application can invoke this method for its own package, but
     * only callers who hold the android.permission.BACKUP permission
     * may invoke it for arbitrary packages.
     */
    void clearBackupData(String packageName);

    /**
     * Notifies the Backup Manager Service that an agent has become available.  This
     * method is only invoked by the Activity Manager.
     */
    void agentConnected(String packageName, IBinder agent);

    /**
     * Notify the Backup Manager Service that an agent has unexpectedly gone away.
     * This method is only invoked by the Activity Manager.
     */
    void agentDisconnected(String packageName);

    /**
     * Notify the Backup Manager Service that an application being installed will
     * need a data-restore pass.  This method is only invoked by the Package Manager.
     */
    void restoreAtInstall(String packageName, int token);

    /**
     * Enable/disable the backup service entirely.  When disabled, no backup
     * or restore operations will take place.  Data-changed notifications will
     * still be observed and collected, however, so that changes made while the
     * mechanism was disabled will still be backed up properly if it is enabled
     * at some point in the future.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     */
    void setBackupEnabled(boolean isEnabled);

    /**
     * Enable/disable automatic restore of application data at install time.  When
     * enabled, installation of any package will involve the Backup Manager.  If data
     * exists for the newly-installed package, either from the device's current [enabled]
     * backup dataset or from the restore set used in the last wholesale restore operation,
     * that data will be supplied to the new package's restore agent before the package
     * is made generally available for launch.
     *
     * <p>Callers must hold  the android.permission.BACKUP permission to use this method.
     *
     * @param doAutoRestore When true, enables the automatic app-data restore facility.  When
     *   false, this facility will be disabled.
     */
    void setAutoRestore(boolean doAutoRestore);

    /**
     * Indicate that any necessary one-time provisioning has occurred.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     */
    void setBackupProvisioned(boolean isProvisioned);

    /**
     * Report whether the backup mechanism is currently enabled.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     */
    boolean isBackupEnabled();

    /**
     * Schedule an immediate backup attempt for all pending updates.  This is
     * primarily intended for transports to use when they detect a suitable
     * opportunity for doing a backup pass.  If there are no pending updates to
     * be sent, no action will be taken.  Even if some updates are pending, the
     * transport will still be asked to confirm via the usual requestBackupTime()
     * method.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     */
    void backupNow();

    /**
     * Identify the currently selected transport.  Callers must hold the
     * android.permission.BACKUP permission to use this method.
     */
    String getCurrentTransport();

    /**
     * Request a list of all available backup transports' names.  Callers must
     * hold the android.permission.BACKUP permission to use this method.
     */
    String[] listAllTransports();

    /**
     * Specify the current backup transport.  Callers must hold the
     * android.permission.BACKUP permission to use this method.
     *
     * @param transport The name of the transport to select.  This should be one
     * of {@link BackupManager.TRANSPORT_GOOGLE} or {@link BackupManager.TRANSPORT_ADB}.
     * @return The name of the previously selected transport.  If the given transport
     *   name is not one of the currently available transports, no change is made to
     *   the current transport setting and the method returns null.
     */
    String selectBackupTransport(String transport);

    /**
     * Begin a restore session with the given transport (which may differ from the
     * currently-active backup transport).
     *
     * @param transport The name of the transport to use for the restore operation.
     * @return An interface to the restore session, or null on error.
     */
    IRestoreSession beginRestoreSession(String transportID);

    /**
     * Notify the backup manager that a BackupAgent has completed the operation
     * corresponding to the given token.
     *
     * @param token The transaction token passed to a BackupAgent's doBackup() or
     *        doRestore() method.
     * {@hide}
     */
    void opComplete(int token);
}
