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

package com.android.internal.backup;

import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

/** {@hide} */
interface IBackupTransport {
    /**
     * Ask the transport for the name under which it should be registered.  This will
     * typically be its host service's component name, but need not be.
     */
    String name();

	/**
	 * Ask the transport for an Intent that can be used to launch any internal
	 * configuration Activity that it wishes to present.  For example, the transport
	 * may offer a UI for allowing the user to supply login credentials for the
	 * transport's off-device backend.
	 *
	 * If the transport does not supply any user-facing configuration UI, it should
	 * return null from this method.
	 *
	 * @return An Intent that can be passed to Context.startActivity() in order to
	 *         launch the transport's configuration UI.  This method will return null
	 *         if the transport does not offer any user-facing configuration UI.
	 */
	Intent configurationIntent();

	/**
	 * On demand, supply a one-line string that can be shown to the user that
	 * describes the current backend destination.  For example, a transport that
	 * can potentially associate backup data with arbitrary user accounts should
	 * include the name of the currently-active account here.
	 *
	 * @return A string describing the destination to which the transport is currently
	 *         sending data.  This method should not return null.
	 */
	String currentDestinationString();

    /**
     * Ask the transport for an Intent that can be used to launch a more detailed
     * secondary data management activity.  For example, the configuration intent might
     * be one for allowing the user to select which account they wish to associate
     * their backups with, and the management intent might be one which presents a
     * UI for managing the data on the backend.
     *
     * <p>In the Settings UI, the configuration intent will typically be invoked
     * when the user taps on the preferences item labeled with the current
     * destination string, and the management intent will be placed in an overflow
     * menu labelled with the management label string.
     *
     * <p>If the transport does not supply any user-facing data management
     * UI, then it should return {@code null} from this method.
     *
     * @return An intent that can be passed to Context.startActivity() in order to
     *         launch the transport's data-management UI.  This method will return
     *         {@code null} if the transport does not offer any user-facing data
     *         management UI.
     */
    Intent dataManagementIntent();

    /**
     * On demand, supply a short {@link CharSequence} that can be shown to the user as the label on
     * an overflow menu item used to invoke the data management UI.
     *
     * @return A {@link CharSequence} to be used as the label for the transport's data management
     *         affordance.  If the transport supplies a data management intent, this
     *         method must not return {@code null}.
     */
    CharSequence dataManagementIntentLabel();

    /**
     * Ask the transport where, on local device storage, to keep backup state blobs.
     * This is per-transport so that mock transports used for testing can coexist with
     * "live" backup services without interfering with the live bookkeeping.  The
     * returned string should be a name that is expected to be unambiguous among all
     * available backup transports; the name of the class implementing the transport
     * is a good choice.  This MUST be constant.
     *
     * @return A unique name, suitable for use as a file or directory name, that the
     *         Backup Manager could use to disambiguate state files associated with
     *         different backup transports.
     */
    String transportDirName();

    /**
     * Verify that this is a suitable time for a backup pass.  This should return zero
     * if a backup is reasonable right now, some positive value otherwise.  This method
     * will be called outside of the {@link #startSession}/{@link #endSession} pair.
     *
     * <p>If this is not a suitable time for a backup, the transport should return a
     * backoff delay, in milliseconds, after which the Backup Manager should try again.
     *
     * @return Zero if this is a suitable time for a backup pass, or a positive time delay
     *   in milliseconds to suggest deferring the backup pass for a while.
     */
    long requestBackupTime();

    /**
     * Initialize the server side storage for this device, erasing all stored data.
     * The transport may send the request immediately, or may buffer it.  After
     * this is called, {@link #finishBackup} must be called to ensure the request
     * is sent and received successfully.
     *
     * @return One of {@link BackupConstants#TRANSPORT_OK} (OK so far) or
     *   {@link BackupConstants#TRANSPORT_ERROR} (on network error or other failure).
     */
    int initializeDevice();

    /**
     * Send one application's data to the backup destination.  The transport may send
     * the data immediately, or may buffer it.  After this is called, {@link #finishBackup}
     * must be called to ensure the data is sent and recorded successfully.
     *
     * @param packageInfo The identity of the application whose data is being backed up.
     *   This specifically includes the signature list for the package.
     * @param inFd Descriptor of file with data that resulted from invoking the application's
     *   BackupService.doBackup() method.  This may be a pipe rather than a file on
     *   persistent media, so it may not be seekable.
     * @param flags Some of {@link BackupTransport#FLAG_USER_INITIATED}.
     * @return one of {@link BackupConstants#TRANSPORT_OK} (OK so far),
     *  {@link BackupConstants#TRANSPORT_ERROR} (on network error or other failure), or
     *  {@link BackupConstants#TRANSPORT_NOT_INITIALIZED} (if the backend dataset has
     *  become lost due to inactive expiry or some other reason and needs re-initializing)
     */
    int performBackup(in PackageInfo packageInfo, in ParcelFileDescriptor inFd, int flags);

    /**
     * Erase the give application's data from the backup destination.  This clears
     * out the given package's data from the current backup set, making it as though
     * the app had never yet been backed up.  After this is called, {@link finishBackup}
     * must be called to ensure that the operation is recorded successfully.
     *
     * @return the same error codes as {@link #performBackup}.
     */
    int clearBackupData(in PackageInfo packageInfo);

    /**
     * Finish sending application data to the backup destination.  This must be
     * called after {@link #performBackup} or {@link clearBackupData} to ensure that
     * all data is sent.  Only when this method returns true can a backup be assumed
     * to have succeeded.
     *
     * @return the same error codes as {@link #performBackup}.
     */
    int finishBackup();

    /**
     * Get the set of all backups currently available over this transport.
     *
     * @return Descriptions of the set of restore images available for this device,
     *   or null if an error occurred (the attempt should be rescheduled).
     **/
    RestoreSet[] getAvailableRestoreSets();

    /**
     * Get the identifying token of the backup set currently being stored from
     * this device.  This is used in the case of applications wishing to restore
     * their last-known-good data.
     *
     * @return A token that can be passed to {@link #startRestore}, or 0 if there
     *   is no backup set available corresponding to the current device state.
     */
    long getCurrentRestoreSet();

    /**
     * Start restoring application data from backup.  After calling this function,
     * alternate calls to {@link #nextRestorePackage} and {@link #nextRestoreData}
     * to walk through the actual application data.
     *
     * @param token A backup token as returned by {@link #getAvailableRestoreSets}
     *   or {@link #getCurrentRestoreSet}.
     * @param packages List of applications to restore (if data is available).
     *   Application data will be restored in the order given.
     * @return One of {@link BackupConstants#TRANSPORT_OK} (OK so far, call
     *   {@link #nextRestorePackage}) or {@link BackupConstants#TRANSPORT_ERROR}
     *   (an error occurred, the restore should be aborted and rescheduled).
     */
    int startRestore(long token, in PackageInfo[] packages);

    /**
     * Get the package name of the next application with data in the backup store, plus
     * a description of the structure of the restored archive: either TYPE_KEY_VALUE for
     * an original-API key/value dataset, or TYPE_FULL_STREAM for a tarball-type archive stream.
     *
     * <p>If the package name in the returned RestoreDescription object is the singleton
     * {@link RestoreDescription#NO_MORE_PACKAGES}, it indicates that no further data is available
     * in the current restore session: all packages described in startRestore() have been
     * processed.
     *
     * <p>If this method returns {@code null}, it means that a transport-level error has
     * occurred and the entire restore operation should be abandoned.
     *
     * @return A RestoreDescription object containing the name of one of the packages
     *   supplied to {@link #startRestore} plus an indicator of the data type of that
     *   restore data; or {@link RestoreDescription#NO_MORE_PACKAGES} to indicate that
     *   no more packages can be restored in this session; or {@code null} to indicate
     *   a transport-level error.
     */
    RestoreDescription nextRestorePackage();

    /**
     * Get the data for the application returned by {@link #nextRestorePackage}.
     * @param data An open, writable file into which the backup data should be stored.
     * @return the same error codes as {@link #startRestore}.
     */
    int getRestoreData(in ParcelFileDescriptor outFd);

    /**
     * End a restore session (aborting any in-process data transfer as necessary),
     * freeing any resources and connections used during the restore process.
     */
    void finishRestore();

    // full backup stuff

    long requestFullBackupTime();
    int performFullBackup(in PackageInfo targetPackage, in ParcelFileDescriptor socket, int flags);
    int checkFullBackupSize(long size);
    int sendBackupData(int numBytes);
    void cancelFullBackup();

    /**
     * Ask the transport whether this app is eligible for backup.
     *
     * @param targetPackage The identity of the application.
     * @param isFullBackup If set, transport should check if app is eligible for full data backup,
     *   otherwise to check if eligible for key-value backup.
     * @return Whether this app is eligible for backup.
     */
    boolean isAppEligibleForBackup(in PackageInfo targetPackage, boolean isFullBackup);

    /**
     * Ask the transport about current quota for backup size of the package.
     *
     * @param packageName ID of package to provide the quota.
     * @param isFullBackup If set, transport should return limit for full data backup, otherwise
     *                     for key-value backup.
     * @return Current limit on full data backup size in bytes.
     */
    long getBackupQuota(String packageName, boolean isFullBackup);

    // full restore stuff

    /**
     * Ask the transport to provide data for the "current" package being restored.  This
     * is the package that was just reported by {@link #nextRestorePackage()} as having
     * {@link RestoreDescription#TYPE_FULL_STREAM} data.
     *
     * The transport writes some data to the socket supplied to this call, and returns
     * the number of bytes written.  The system will then read that many bytes and
     * stream them to the application's agent for restore, then will call this method again
     * to receive the next chunk of the archive.  This sequence will be repeated until the
     * transport returns zero indicating that all of the package's data has been delivered
     * (or returns a negative value indicating some sort of hard error condition at the
     * transport level).
     *
     * <p>After this method returns zero, the system will then call
     * {@link #getNextFullRestorePackage()} to begin the restore process for the next
     * application, and the sequence begins again.
     *
     * <p>The transport should always close this socket when returning from this method.
     * Do not cache this socket across multiple calls or you may leak file descriptors.
     *
     * @param socket The file descriptor that the transport will use for delivering the
     *    streamed archive.  The transport must close this socket in all cases when returning
     *    from this method.
     * @return 0 when no more data for the current package is available.  A positive value
     *    indicates the presence of that many bytes to be delivered to the app.  Any negative
     *    return value is treated as equivalent to {@link BackupTransport#TRANSPORT_ERROR},
     *    indicating a fatal error condition that precludes further restore operations
     *    on the current dataset.
     */
    int getNextFullRestoreDataChunk(in ParcelFileDescriptor socket);

    /**
     * If the OS encounters an error while processing {@link RestoreDescription#TYPE_FULL_STREAM}
     * data for restore, it will invoke this method to tell the transport that it should
     * abandon the data download for the current package.  The OS will then either call
     * {@link #nextRestorePackage()} again to move on to restoring the next package in the
     * set being iterated over, or will call {@link #finishRestore()} to shut down the restore
     * operation.
     *
     * @return {@link #TRANSPORT_OK} if the transport was successful in shutting down the
     *    current stream cleanly, or {@link #TRANSPORT_ERROR} to indicate a serious
     *    transport-level failure.  If the transport reports an error here, the entire restore
     *    operation will immediately be finished with no further attempts to restore app data.
     */
    int abortFullRestore();

    /**
     * Returns flags with additional information about the transport, which is accessible to the
     * {@link android.app.backup.BackupAgent}. This allows the agent to decide what to backup or
     * restore based on properties of the transport.
     *
     * <p>For supported flags see {@link android.app.backup.BackupAgent}.
     */
    int getTransportFlags();
}
