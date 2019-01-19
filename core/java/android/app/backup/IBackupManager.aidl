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

import android.app.backup.IBackupObserver;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.os.ParcelFileDescriptor;
import android.content.Intent;
import android.content.ComponentName;

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
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the caller has made changes to its data.
     */
    void dataChangedForUser(int userId, String packageName);

    /**
     * {@link android.app.backup.IBackupManager.dataChangedForUser} for the calling user id.
     */
    void dataChanged(String packageName);

    /**
     * Erase all backed-up data for the given package from the given storage
     * destination.
     *
     * Any application can invoke this method for its own package, but
     * only callers who hold the android.permission.BACKUP permission
     * may invoke it for arbitrary packages.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which backup data should be erased.
     */
    void clearBackupDataForUser(int userId, String transportName, String packageName);

    /**
     * {@link android.app.backup.IBackupManager.clearBackupDataForUser} for the calling user id.
     */
    void clearBackupData(String transportName, String packageName);

    /**
     * Run an initialize operation on the given transports.  This will wipe all data from
     * the backing data store and establish a clean starting point for all backup
     * operations.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the given transports should be initialized.
     */
    void initializeTransportsForUser(int userId, in String[] transportNames,
        IBackupObserver observer);

    /**
     * Notifies the Backup Manager Service that an agent has become available.  This
     * method is only invoked by the Activity Manager.
     *
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which an agent has become available.
     */
    void agentConnectedForUser(int userId, String packageName, IBinder agent);

    /**
     * {@link android.app.backup.IBackupManager.agentConnected} for the calling user id.
     */
    void agentConnected(String packageName, IBinder agent);

    /**
     * Notify the Backup Manager Service that an agent has unexpectedly gone away.
     * This method is only invoked by the Activity Manager.
     *
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which an agent has unexpectedly gone away.
     */
    void agentDisconnectedForUser(int userId, String packageName);

    /**
     * {@link android.app.backup.IBackupManager.agentDisconnected} for the calling user id.
     */
    void agentDisconnected(String packageName);

    /**
     * Notify the Backup Manager Service that an application being installed will
     * need a data-restore pass.  This method is only invoked by the Package Manager.
     *
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the application will need a data-restore pass.
     */
    void restoreAtInstallForUser(int userId, String packageName, int token);

    /**
     * {@link android.app.backup.IBackupManager.restoreAtInstallForUser} for the calling user id.
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
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which backup service should be enabled/disabled.
     */
    void setBackupEnabledForUser(int userId, boolean isEnabled);

    /**
     * {@link android.app.backup.IBackupManager.setBackupEnabledForUser} for the calling user id.
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
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which automatic restore should be enabled/disabled.
     * @param doAutoRestore When true, enables the automatic app-data restore facility.  When
     *   false, this facility will be disabled.
     */
    void setAutoRestoreForUser(int userId, boolean doAutoRestore);

    /**
     * {@link android.app.backup.IBackupManager.setAutoRestoreForUser} for the calling user id.
     */
    void setAutoRestore(boolean doAutoRestore);

    /**
     * Report whether the backup mechanism is currently enabled.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the backup service status should be reported.
     */
    boolean isBackupEnabledForUser(int userId);

    /**
     * {@link android.app.backup.IBackupManager.isBackupEnabledForUser} for the calling user id.
     */
    boolean isBackupEnabled();

    /**
     * Set the device's backup password.  Returns {@code true} if the password was set
     * successfully, {@code false} otherwise.  Typically a failure means that an incorrect
     * current password was supplied.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     */
    boolean setBackupPassword(in String currentPw, in String newPw);

    /**
     * Reports whether a backup password is currently set.  If not, then a null or empty
     * "current password" argument should be passed to setBackupPassword().
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     */
    boolean hasBackupPassword();

    /**
     * Schedule an immediate backup attempt for all pending updates.  This is
     * primarily intended for transports to use when they detect a suitable
     * opportunity for doing a backup pass.  If there are no pending updates to
     * be sent, no action will be taken.  Even if some updates are pending, the
     * transport will still be asked to confirm via the usual requestBackupTime()
     * method.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which an immediate backup should be scheduled.
     */
    void backupNowForUser(int userId);

    /**
     * {@link android.app.backup.IBackupManager.backupNowForUser} for the calling user id.
     */
    void backupNow();

    /**
     * Write a backup of the given package to the supplied file descriptor.
     * The fd may be a socket or other non-seekable destination.  If no package names
     * are supplied, then every application on the device will be backed up to the output.
     * Currently only used by the 'adb backup' command.
     *
     * <p>This method is <i>synchronous</i> -- it does not return until the backup has
     * completed.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If the {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which backup should be performed.
     * @param fd The file descriptor to which a 'tar' file stream is to be written.
     * @param includeApks If <code>true</code>, the resulting tar stream will include the
     *     application .apk files themselves as well as their data.
     * @param includeObbs If <code>true</code>, the resulting tar stream will include any
     *     application expansion (OBB) files themselves belonging to each application.
     * @param includeShared If <code>true</code>, the resulting tar stream will include
     *     the contents of the device's shared storage (SD card or equivalent).
     * @param allApps If <code>true</code>, the resulting tar stream will include all
     *     installed applications' data, not just those named in the <code>packageNames</code>
     *     parameter.
     * @param allIncludesSystem If {@code true}, then {@code allApps} will be interpreted
     *     as including packages pre-installed as part of the system. If {@code false},
     *     then setting {@code allApps} to {@code true} will mean only that all 3rd-party
     *     applications will be included in the dataset.
     * @param doKeyValue If {@code true}, also packages supporting key-value backup will be backed
     *     up. If {@code false}, key-value packages will be skipped.
     * @param packageNames The package names of the apps whose data (and optionally .apk files)
     *     are to be backed up.  The <code>allApps</code> parameter supersedes this.
     */
    void adbBackup(int userId, in ParcelFileDescriptor fd, boolean includeApks, boolean includeObbs,
            boolean includeShared, boolean doWidgets, boolean allApps, boolean allIncludesSystem,
            boolean doCompress, boolean doKeyValue, in String[] packageNames);

    /**
     * Perform a full-dataset backup of the given applications via the currently active
     * transport.
     *
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the full-dataset backup should be performed.
     * @param packageNames The package names of the apps whose data are to be backed up.
     */
    void fullTransportBackupForUser(int userId, in String[] packageNames);

    /**
     * Restore device content from the data stream passed through the given socket.  The
     * data stream must be in the format emitted by adbBackup().
     * Currently only used by the 'adb restore' command.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If the {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL.
     *
     * @param userId User id for which restore should be performed.
     */
    void adbRestore(int userId, in ParcelFileDescriptor fd);

    /**
     * Confirm that the requested full backup/restore operation can proceed.  The system will
     * not actually perform the operation described to fullBackup() / fullRestore() unless the
     * UI calls back into the Backup Manager to confirm, passing the correct token.  At
     * the same time, the UI supplies a callback Binder for progress notifications during
     * the operation.
     *
     * <p>The password passed by the confirming entity must match the saved backup or
     * full-device encryption password in order to perform a backup.  If a password is
     * supplied for restore, it must match the password used when creating the full
     * backup dataset being used for restore.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the requested backup/restore operation can proceed.
     */
    void acknowledgeFullBackupOrRestoreForUser(int userId, int token, boolean allow,
            in String curPassword, in String encryptionPassword,
            IFullBackupRestoreObserver observer);

    /**
     * {@link android.app.backup.IBackupManager.acknowledgeFullBackupOrRestoreForUser} for the
     * calling user id.
     */
    void acknowledgeFullBackupOrRestore(int token, boolean allow,
            in String curPassword, in String encryptionPassword,
            IFullBackupRestoreObserver observer);

    /**
     * Update the attributes of the transport identified by {@code transportComponent}. If the
     * specified transport has not been bound at least once (for registration), this call will be
     * ignored. Only the host process of the transport can change its description, otherwise a
     * {@link SecurityException} will be thrown.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the attributes of the transport should be updated.
     * @param transportComponent The identity of the transport being described.
     * @param name A {@link String} with the new name for the transport. This is NOT for
     *     identification. MUST NOT be {@code null}.
     * @param configurationIntent An {@link Intent} that can be passed to
     *     {@link Context#startActivity} in order to launch the transport's configuration UI. It may
     *     be {@code null} if the transport does not offer any user-facing configuration UI.
     * @param currentDestinationString A {@link String} describing the destination to which the
     *     transport is currently sending data. MUST NOT be {@code null}.
     * @param dataManagementIntent An {@link Intent} that can be passed to
     *     {@link Context#startActivity} in order to launch the transport's data-management UI. It
     *     may be {@code null} if the transport does not offer any user-facing data
     *     management UI.
     * @param dataManagementLabel A {@link String} to be used as the label for the transport's data
     *     management affordance. This MUST be {@code null} when dataManagementIntent is
     *     {@code null} and MUST NOT be {@code null} when dataManagementIntent is not {@code null}.
     * @throws SecurityException If the UID of the calling process differs from the package UID of
     *     {@code transportComponent} or if the caller does NOT have BACKUP permission.
     */
    void updateTransportAttributesForUser(int userId, in ComponentName transportComponent,
            in String name,
            in Intent configurationIntent, in String currentDestinationString,
            in Intent dataManagementIntent, in String dataManagementLabel);

    /**
     * Identify the currently selected transport.  Callers must hold the
     * android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the currently selected transport should be identified.
     */
    String getCurrentTransportForUser(int userId);

    /**
     * {@link android.app.backup.IBackupManager.getCurrentTransportForUser} for the calling user id.
     */
    String getCurrentTransport();

     /**
      * Returns the {@link ComponentName} of the host service of the selected transport or {@code
      * null} if no transport selected or if the transport selected is not registered.  Callers must
      * hold the android.permission.BACKUP permission to use this method.
      * If {@code userId} is different from the calling user id, then the caller must hold the
      * android.permission.INTERACT_ACROSS_USERS_FULL permission.
      *
      * @param userId User id for which the currently selected transport should be identified.
      */
    ComponentName getCurrentTransportComponentForUser(int userId);

    /**
     * Request a list of all available backup transports' names.  Callers must
     * hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which all available backup transports' names should be listed.
     */
    String[] listAllTransportsForUser(int userId);

    /**
     * {@link android.app.backup.IBackupManager.listAllTransportsForUser} for the calling user id.
     */
    String[] listAllTransports();

    /**
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which all available backup transports should be listed.
     */
    ComponentName[] listAllTransportComponentsForUser(int userId);

    /**
     * Retrieve the list of whitelisted transport components.  Callers do </i>not</i> need
     * any special permission.
     *
     * @return The names of all whitelisted transport components defined by the system.
     */
    String[] getTransportWhitelist();

    /**
     * Specify the current backup transport.  Callers must hold the
     * android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the transport should be selected.
     * @param transport The name of the transport to select.  This should be one
     * of {@link BackupManager.TRANSPORT_GOOGLE} or {@link BackupManager.TRANSPORT_ADB}.
     * @return The name of the previously selected transport.  If the given transport
     *   name is not one of the currently available transports, no change is made to
     *   the current transport setting and the method returns null.
     */
    String selectBackupTransportForUser(int userId, String transport);

    /**
     * {@link android.app.backup.IBackupManager.selectBackupTransportForUser} for the calling user
     * id.
     */
    String selectBackupTransport(String transport);

    /**
     * Specify the current backup transport and get notified when the transport is ready to be used.
     * This method is async because BackupManager might need to bind to the specified transport
     * which is in a separate process.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the transport should be selected.
     * @param transport ComponentName of the service hosting the transport. This is different from
     *                  the transport's name that is returned by {@link BackupTransport#name()}.
     * @param listener A listener object to get a callback on the transport being selected.
     */
    void selectBackupTransportAsyncForUser(int userId, in ComponentName transport,
        ISelectBackupTransportCallback listener);

    /**
     * Get the configuration Intent, if any, from the given transport.  Callers must
     * hold the android.permission.BACKUP permission in order to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the configuration Intent should be reported.
     * @param transport The name of the transport to query.
     * @return An Intent to use with Activity#startActivity() to bring up the configuration
     *   UI supplied by the transport.  If the transport has no configuration UI, it should
     *   return {@code null} here.
     */
    Intent getConfigurationIntentForUser(int userId, String transport);

    /**
     * {@link android.app.backup.IBackupManager.getConfigurationIntentForUser} for the calling user
     * id.
     */
    Intent getConfigurationIntent(String transport);

    /**
     * Get the destination string supplied by the given transport.  Callers must
     * hold the android.permission.BACKUP permission in order to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the transport destination string should be reported.
     * @param transport The name of the transport to query.
     * @return A string describing the current backup destination.  This string is used
     *   verbatim by the Settings UI as the summary text of the "configure..." item.
     */
    String getDestinationStringForUser(int userId, String transport);

    /**
     * {@link android.app.backup.IBackupManager.getDestinationStringForUser} for the calling user
     * id.
     */
    String getDestinationString(String transport);

    /**
     * Get the manage-data UI intent, if any, from the given transport.  Callers must
     * hold the android.permission.BACKUP permission in order to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the manage-data UI intent should be reported.
     */
    Intent getDataManagementIntentForUser(int userId, String transport);

    /**
     * {@link android.app.backup.IBackupManager.getDataManagementIntentForUser} for the calling user
     * id.
     */
    Intent getDataManagementIntent(String transport);

    /**
     * Get the manage-data menu label, if any, from the given transport.  Callers must
     * hold the android.permission.BACKUP permission in order to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the manage-data menu label should be reported.
     */
    String getDataManagementLabelForUser(int userId, String transport);

    /**
     * {@link android.app.backup.IBackupManager.getDataManagementLabelForUser} for the calling user
     * id.
     */
    String getDataManagementLabel(String transport);

    /**
     * Begin a restore session.  Either or both of packageName and transportID
     * may be null.  If packageName is non-null, then only the given package will be
     * considered for restore.  If transportID is null, then the restore will use
     * the current active transport.
     * <p>
     * This method requires the android.permission.BACKUP permission <i>except</i>
     * when transportID is null and packageName is the name of the caller's own
     * package.  In that case, the restore session returned is suitable for supporting
     * the BackupManager.requestRestore() functionality via RestoreSession.restorePackage()
     * without requiring the app to hold any special permission.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which a restore session should be begun.
     * @param packageName The name of the single package for which a restore will
     *        be requested.  May be null, in which case all packages in the restore
     *        set can be restored.
     * @param transportID The name of the transport to use for the restore operation.
     *        May be null, in which case the current active transport is used.
     * @return An interface to the restore session, or null on error.
     */
    IRestoreSession beginRestoreSessionForUser(int userId, String packageName, String transportID);

    /**
     * Notify the backup manager that a BackupAgent has completed the operation
     * corresponding to the given token and user id.
     *
     * @param userId User id for which the operation has been completed.
     * @param token The transaction token passed to the BackupAgent method being
     *        invoked.
     * @param result In the case of a full backup measure operation, the estimated
     *        total file size that would result from the operation. Unused in all other
     *        cases.
     */
    void opCompleteForUser(int userId, int token, long result);

    /**
     * Notify the backup manager that a BackupAgent has completed the operation
     * corresponding to the given token.
     *
     * @param token The transaction token passed to the BackupAgent method being
     *        invoked.
     * @param result In the case of a full backup measure operation, the estimated
     *        total file size that would result from the operation. Unused in all other
     *        cases.
     */
    void opComplete(int token, long result);

    /**
     * Make the device's backup and restore machinery (in)active.  When it is inactive,
     * the device will not perform any backup operations, nor will it deliver data for
     * restore, although clients can still safely call BackupManager methods.
     *
     * @param whichUser User handle of the defined user whose backup active state
     *     is to be adjusted.
     * @param makeActive {@code true} when backup services are to be made active;
     *     {@code false} otherwise.
     */
    void setBackupServiceActive(int whichUser, boolean makeActive);

    /**
     * Queries the activity status of backup service as set by {@link #setBackupServiceActive}.
     * @param whichUser User handle of the defined user whose backup active state
     *     is being queried.
     */
    boolean isBackupServiceActive(int whichUser);

    /**
     * Ask the framework which dataset, if any, the given package's data would be
     * restored from if we were to install it right now.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which this operation should be performed.
     * @param packageName The name of the package whose most-suitable dataset we
     *     wish to look up
     * @return The dataset token from which a restore should be attempted, or zero if
     *     no suitable data is available.
     */
    long getAvailableRestoreTokenForUser(int userId, String packageName);

    /**
     * Ask the framework whether this app is eligible for backup.
     *
     * <p>If you are calling this method multiple times, you should instead use
     * {@link #filterAppsEligibleForBackup(String[])} to save resources.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which this operation should be performed.
     * @param packageName The name of the package.
     * @return Whether this app is eligible for backup.
     */
    boolean isAppEligibleForBackupForUser(int userId, String packageName);

    /**
     * Filter the packages that are eligible for backup and return the result.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which the filter should be performed.
     * @param packages The list of packages to filter.
     * @return The packages eligible for backup.
     */
    String[] filterAppsEligibleForBackupForUser(int userId, in String[] packages);

    /**
     * Request an immediate backup, providing an observer to which results of the backup operation
     * will be published. The Android backup system will decide for each package whether it will
     * be full app data backup or key/value-pair-based backup.
     *
     * <p>If this method returns zero (meaning success), the OS will attempt to backup all provided
     * packages using the remote transport.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which an immediate backup should be requested.

     * @param observer The {@link BackupObserver} to receive callbacks during the backup
     * operation.
     *
     * @param monitor the {@link BackupManagerMonitor} to receive callbacks about important events
     * during the backup operation.
     *
     * @param flags {@link BackupManager#FLAG_NON_INCREMENTAL_BACKUP}.
     *
     * @return Zero on success; nonzero on error.
     */
    int requestBackupForUser(int userId, in String[] packages, IBackupObserver observer,
        IBackupManagerMonitor monitor, int flags);

    /**
     * {@link android.app.backup.IBackupManager.requestBackupForUser} for the calling user id.
     */
    int requestBackup(in String[] packages, IBackupObserver observer, IBackupManagerMonitor monitor,
        int flags);

    /**
     * Cancel all running backups. After this call returns, no currently running backups will
     * interact with the selected transport.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     * If {@code userId} is different from the calling user id, then the caller must hold the
     * android.permission.INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId User id for which backups should be cancelled.
     */
    void cancelBackupsForUser(int userId);

    /**
     * {@link android.app.backup.IBackupManager.cancelBackups} for the calling user id.
     */
    void cancelBackups();
}
